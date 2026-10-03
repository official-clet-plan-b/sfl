package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteIncidentPort;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteWorkOrderPort;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Corrective actions for missed collections, contamination, missing certificates and receiving evidence,
 * spills and unapproved carriers or destinations - SRS-SFL-S178-05.
 *
 * <h2>Why raising is its own transaction</h2>
 *
 * The commonest way an exception is raised is by <em>refusing</em> something: a handover to an unapproved
 * destination, a closure with the chain open. The refusal throws, which rolls the caller's transaction
 * back - and an exception raised inside it would vanish with it, leaving a blocked handover nobody was
 * told about. So {@link #raise} commits in a transaction of its own, before the caller throws.
 *
 * <p>The S153 work order (spill and contamination) is a third step, again separate, so S153 refusing
 * leaves the exception standing and honestly {@code PENDING_MANUAL}. The S163 incident for a spill is
 * recorded as a request and likewise left pending until someone links the reference.
 */
@Service
public class WasteExceptionService {

    private static final Logger log = LoggerFactory.getLogger(WasteExceptionService.class);

    private final WasteStore store;
    private final WasteSupport support;
    private final WasteWorkOrderPort workOrders;
    private final WasteIncidentPort incidents;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public WasteExceptionService(WasteStore store, WasteSupport support, WasteWorkOrderPort workOrders,
            WasteIncidentPort incidents, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.workOrders = workOrders;
        this.incidents = incidents;
        this.required = new TransactionTemplate(transactions);
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Raises an exception, once per collection and type while it is open, and returns the one that stands. */
    public WasteException raise(String site, UUID collectionId, ExceptionType type, String description,
            String owner, Caller caller) {
        WasteException raised = requiresNew.execute(tx -> doRaise(site, collectionId, type, description, owner,
                caller));
        if ("PENDING_MANUAL".equals(raised.workOrderState())) {
            return attemptWorkOrder(raised.id(), caller);
        }
        return raised;
    }

    private WasteException doRaise(String site, UUID collectionId, ExceptionType type, String description,
            String owner, Caller caller) {
        if (collectionId != null) {
            var existing = store.openException(collectionId, type);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Instant now = support.now();
        boolean workOrder = type == ExceptionType.SPILL || type == ExceptionType.CONTAMINATION;
        WasteException exception = new WasteException(UUID.randomUUID(),
                String.format("WST-X-%06d", store.nextSequence("waste_exception_seq")), site, collectionId, type,
                description, WasteSupport.blankToNull(owner), support.today().plusDays(type.dueInDays()),
                ExceptionStatus.OPEN, "FACILITIES_OWNER", type.needsIncident() ? "PENDING_MANUAL" : "NOT_REQUIRED",
                null, workOrder ? "PENDING_MANUAL" : "NOT_REQUIRED", null, null, null, null, null,
                caller.actor().actorId(), now, now, 0);
        if (!store.insert(exception)) {
            return store.openException(collectionId, type).orElseThrow();
        }
        support.history(site, "EXCEPTION", exception.id(), null, "OPEN", caller.actor().actorId(), description);
        support.audit(caller, AuditAction.WASTE_EXCEPTION_RAISED, "WasteException", exception.id(), site, null,
                exception);
        support.publish(WasteEvents.EXCEPTION_RAISED, "WasteException", exception.id(), site, caller.actor(),
                "exceptionId", exception.id(), "reference", exception.reference(), "type", type, "collectionId",
                collectionId, "escalatedTo", exception.escalatedTo());
        if (type.needsIncident()) {
            WasteIncidentPort.Dispatch dispatch = incidents.request(exception, caller.actor());
            log.info("Incident for {} dispatched via {} (enforced={})", exception.reference(), dispatch.provider(),
                    dispatch.enforced());
        }
        return exception;
    }

    /** A person reports a spill or contamination, or any other exception the system did not detect itself. */
    public WasteException report(String siteCode, UUID collectionId, ExceptionType type, String description,
            String owner, Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode");
        if (type == null) {
            throw new IllegalArgumentException("exceptionType is required");
        }
        WasteSupport.required(description, "description");
        support.require(caller, SflPermission.FACILITIES_WASTE_MANAGE, site, "WasteException", "new");
        if (collectionId != null) {
            WasteCollection collection = support.collection(collectionId);
            if (!collection.siteCode().equals(site)) {
                throw new IllegalArgumentException("That collection is not at site " + site + ".");
            }
        }
        return raise(site, collectionId, type, description.strip(), owner, caller);
    }

    private WasteException attemptWorkOrder(UUID exceptionId, Caller caller) {
        try {
            requiresNew.executeWithoutResult(tx -> {
                WasteException exception = store.exception(exceptionId).orElseThrow();
                UUID roomId = null;
                String location = "SITE " + exception.siteCode();
                if (exception.collectionId() != null) {
                    WasteCollection collection = support.collection(exception.collectionId());
                    var point = store.point(collection.pointId()).orElse(null);
                    if (point != null) {
                        roomId = point.roomId();
                        location = point.name();
                    }
                }
                WasteWorkOrderPort.RaisedWorkOrder order = workOrders.raise(exception, roomId, location,
                        caller.actor().actorId(), caller.actor().correlationId());
                WasteException linked = copy(exception, exception.ownerReference(), exception.status(),
                        exception.incidentState(), exception.incidentReference(), "RAISED", order.workOrderId(),
                        order.workOrderNumber(), exception.resolution(), exception.resolvedBy(),
                        exception.resolvedAt());
                if (!store.update(linked, exception.version())) {
                    throw WasteSupport.conflict();
                }
            });
        } catch (RuntimeException failure) {
            log.warn("S153 work order for waste exception {} could not be raised; it stays PENDING_MANUAL and can"
                    + " be retried", exceptionId, failure);
        }
        return store.exception(exceptionId).orElseThrow();
    }

    public WasteException retryWorkOrder(UUID id, Caller caller) {
        WasteException exception = manageable(id, caller);
        return "PENDING_MANUAL".equals(exception.workOrderState()) ? attemptWorkOrder(id, caller) : exception;
    }

    public WasteStore.Page<WasteException> list(String siteCode, String status, String type, int page, int size,
            Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode");
        support.require(caller, SflPermission.FACILITIES_WASTE_READ, site, "WasteException", "list");
        String statusFilter = enumName(ExceptionStatus.class, status);
        String typeFilter = enumName(ExceptionType.class, type);
        return required.execute(tx -> store.exceptions(site, statusFilter, typeFilter, Math.max(0, page),
                Math.min(Math.max(1, size), 100)));
    }

    public WasteException start(UUID id, Long expectedVersion, Caller caller) {
        return required.execute(tx -> {
            WasteException before = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != ExceptionStatus.OPEN) {
                throw WasteSupport.invalid("Only an open exception can be started.");
            }
            return save(before, copy(before, before.ownerReference(), ExceptionStatus.IN_PROGRESS,
                    before.incidentState(), before.incidentReference(), before.workOrderState(), before.workOrderId(),
                    before.workOrderNumber(), null, null, null), caller, null);
        });
    }

    public WasteException assign(UUID id, String owner, Caller caller) {
        return required.execute(tx -> {
            WasteException before = manageable(id, caller);
            return save(before, copy(before, WasteSupport.required(owner, "owner"), before.status(),
                    before.incidentState(), before.incidentReference(), before.workOrderState(), before.workOrderId(),
                    before.workOrderNumber(), before.resolution(), before.resolvedBy(), before.resolvedAt()), caller,
                    "Assigned to " + owner.strip());
        });
    }

    public WasteException linkIncident(UUID id, String reference, Caller caller) {
        return required.execute(tx -> {
            WasteException before = manageable(id, caller);
            if (before.incidentState().equals("NOT_REQUIRED")) {
                throw new IllegalArgumentException("This exception does not require an incident.");
            }
            return save(before, copy(before, before.ownerReference(), before.status(), "LINKED",
                    WasteSupport.required(reference, "incidentReference"), before.workOrderState(),
                    before.workOrderId(), before.workOrderNumber(), before.resolution(), before.resolvedBy(),
                    before.resolvedAt()), caller, "Incident " + reference.strip() + " linked");
        });
    }

    /** Resolution is a written statement of what was done; a contamination also needs its quantity reconciled. */
    public WasteException resolve(UUID id, String resolution, Long expectedVersion, Caller caller) {
        return required.execute(tx -> {
            WasteException before = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, before.version());
            if (before.status() == ExceptionStatus.RESOLVED) {
                throw WasteSupport.invalid("This exception is already resolved.");
            }
            if (before.exceptionType() == ExceptionType.CONTAMINATION && before.collectionId() != null
                    && !support.collection(before.collectionId()).quantityReconciled()) {
                throw WasteSupport.invalid("Reconcile the collection's quantity before resolving the contamination.");
            }
            return save(before, copy(before, before.ownerReference(), ExceptionStatus.RESOLVED,
                    before.incidentState(), before.incidentReference(), before.workOrderState(), before.workOrderId(),
                    before.workOrderNumber(), WasteSupport.required(resolution, "resolution"),
                    caller.actor().actorId(), support.now()), caller, resolution);
        });
    }

    /** Resolves what the chain itself proves: closing a collection settles its missing-evidence exceptions. */
    void resolveChainExceptions(UUID collectionId, Caller caller) {
        for (WasteException open : store.openExceptionsOf(collectionId)) {
            if (open.exceptionType() == ExceptionType.MISSING_CERTIFICATE
                    || open.exceptionType() == ExceptionType.MISSING_RECEIVING_EVIDENCE
                    || open.exceptionType() == ExceptionType.UNAPPROVED_DESTINATION
                    || open.exceptionType() == ExceptionType.MISSED_COLLECTION) {
                save(open, copy(open, open.ownerReference(), ExceptionStatus.RESOLVED, open.incidentState(),
                        open.incidentReference(), open.workOrderState(), open.workOrderId(), open.workOrderNumber(),
                        "Settled when the collection's chain of custody was completed", caller.actor().actorId(),
                        support.now()), caller, "Chain completed");
            }
        }
    }

    // ---- internals

    private WasteException manageable(UUID id, Caller caller) {
        WasteException exception = store.exception(id)
                .orElseThrow(() -> WasteSupport.notFound("Waste exception", id));
        support.require(caller, SflPermission.FACILITIES_WASTE_MANAGE, exception.siteCode(), "WasteException",
                id.toString());
        return exception;
    }

    private WasteException save(WasteException before, WasteException after, Caller caller, String reason) {
        if (!store.update(after, before.version())) {
            throw WasteSupport.conflict();
        }
        WasteException saved = store.exception(after.id()).orElseThrow();
        support.history(saved.siteCode(), "EXCEPTION", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, AuditAction.WASTE_EXCEPTION_UPDATED, "WasteException", saved.id(), saved.siteCode(),
                before, saved);
        return saved;
    }

    private WasteException copy(WasteException b, String owner, ExceptionStatus status, String incidentState,
            String incidentReference, String workOrderState, UUID workOrderId, String workOrderNumber,
            String resolution, String resolvedBy, Instant resolvedAt) {
        return new WasteException(b.id(), b.reference(), b.siteCode(), b.collectionId(), b.exceptionType(),
                b.description(), owner, b.dueOn(), status, b.escalatedTo(), incidentState, incidentReference,
                workOrderState, workOrderId, workOrderNumber, resolution, resolvedBy, resolvedAt, b.createdBy(),
                b.createdAt(), support.now(), b.version());
    }

    static <E extends Enum<E>> String enumName(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase()).name();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }

    /** For the sweep: the number of days an exception is past due, or zero. */
    static long daysOverdue(WasteException exception, LocalDate today) {
        return exception.overdue(today) ? java.time.temporal.ChronoUnit.DAYS.between(exception.dueOn(), today) : 0;
    }
}

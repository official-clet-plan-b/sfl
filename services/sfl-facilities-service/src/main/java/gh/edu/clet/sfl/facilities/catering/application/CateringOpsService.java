package gh.edu.clet.sfl.facilities.catering.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.catering.application.ports.CateringIncidentPort;
import gh.edu.clet.sfl.facilities.catering.application.ports.FinanceReferencePort;
import gh.edu.clet.sfl.facilities.catering.domain.CateringCheck;
import gh.edu.clet.sfl.facilities.catering.domain.CateringEvidence;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.CheckResult;
import gh.edu.clet.sfl.facilities.catering.domain.CheckType;
import gh.edu.clet.sfl.facilities.catering.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionStatus;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.catering.domain.FinanceState;
import gh.edu.clet.sfl.facilities.catering.domain.HoldType;
import gh.edu.clet.sfl.facilities.catering.domain.ServiceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Variance;
import gh.edu.clet.sfl.facilities.catering.domain.VarianceKind;
import gh.edu.clet.sfl.facilities.catering.domain.VarianceStatus;
import gh.edu.clet.sfl.facilities.maintenance.domain.RetentionClass;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks, exceptions, variances, evidence and reconciliation - SRS-SFL-S172-03 and -05, and the KPIs.
 *
 * <p>Three rules carry it. A temperature check's result is <em>worked out from the reading</em>, so a failing
 * reading cannot be filed as a pass, and a failure raises a food-safety incident on its own. Every variance has
 * an owner and a reason, and is approved by someone other than whoever recorded it. And S172 is not the ledger:
 * reconciliation keeps purchase and invoice references and a finance state, and with no finance integration the
 * state is honest - pending finance, never "matched".
 */
@Service
public class CateringOpsService {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final CateringStore store;
    private final CateringSupport support;
    private final CateringIncidentPort incidents;
    private final FinanceReferencePort finance;
    private final TransactionTemplate inTransaction;

    public CateringOpsService(CateringStore store, CateringSupport support, CateringIncidentPort incidents,
            FinanceReferencePort finance, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.incidents = incidents;
        this.finance = finance;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    // ---- checks

    public record Check(String siteCode, UUID serviceId, UUID supplierId, CheckType checkType, HoldType holdType,
            BigDecimal temperatureC, Boolean passed, String notes, Caller caller) {
    }

    public CateringCheck recordCheck(Check c) {
        return inTransaction.execute(tx -> {
            CateringService service = c.serviceId() == null ? null : support.service(c.serviceId());
            String site = service != null ? service.siteCode() : support.validateSite(c.siteCode());
            support.require(c.caller(), SflPermission.FACILITIES_CATERING_MANAGE, site, "CateringCheck", "new");
            if (c.checkType() == null) {
                throw new IllegalArgumentException("checkType is required");
            }
            UUID supplierId = c.supplierId() != null ? c.supplierId() : service == null ? null : service.supplierId();
            CheckResult result;
            if (c.checkType() == CheckType.TEMPERATURE) {
                if (service == null || c.holdType() == null || c.temperatureC() == null) {
                    throw new IllegalArgumentException("A temperature check needs the service, whether it is hot or cold food, and the reading.");
                }
                result = c.holdType().passes(c.temperatureC()) ? CheckResult.PASS : CheckResult.FAIL;
            } else {
                if (c.passed() == null) {
                    throw new IllegalArgumentException("passed is required for a " + c.checkType() + " check");
                }
                if (c.checkType() == CheckType.SUPPLIER && supplierId == null) {
                    throw new IllegalArgumentException("A supplier check needs the supplier.");
                }
                result = c.passed() ? CheckResult.PASS : CheckResult.FAIL;
            }
            if (supplierId != null) {
                store.supplier(supplierId).orElseThrow(() -> new IllegalArgumentException("Unknown supplier"));
            }
            CateringCheck check = new CateringCheck(UUID.randomUUID(), site, service == null ? null : service.id(), supplierId,
                    c.checkType(), c.holdType(), c.temperatureC(), result, CateringSupport.blankToNull(c.notes()),
                    c.caller().actor().actorId(), support.now());
            store.insert(check);
            UUID subject = service != null ? service.id() : supplierId != null ? supplierId : check.id();
            support.history(site, "CHECK", check.id(), null, check.result().name(), c.caller().actor().actorId(),
                    c.checkType() + (c.temperatureC() == null ? "" : " " + c.temperatureC() + " C"));
            support.audit(c.caller(), AuditAction.CAT_CHECK_RECORDED, "CateringCheck", subject, site, null, check);
            if (result == CheckResult.FAIL && c.checkType() != CheckType.SUPPLIER) {
                raise(site, service == null ? null : service.id(), ExceptionType.FOOD_SAFETY_INCIDENT,
                        "A " + c.checkType() + " check failed" + (c.temperatureC() == null ? "" : " at " + c.temperatureC() + " C")
                                + ".", c.caller().actor().actorId(), c.caller());
            }
            return check;
        });
    }

    // ---- exceptions

    public CateringException raiseException(String siteCode, UUID serviceId, ExceptionType type, String description,
            String owner, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService service = serviceId == null ? null : support.service(serviceId);
            String site = service != null ? service.siteCode() : support.validateSite(siteCode);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, site, "CateringException", "new");
            if (type == null) {
                throw new IllegalArgumentException("exceptionType is required");
            }
            return raise(site, serviceId, type, CateringSupport.required(description, "description"),
                    CateringSupport.required(owner, "owner"), caller);
        });
    }

    private CateringException raise(String site, UUID serviceId, ExceptionType type, String description, String owner,
            Caller caller) {
        Instant now = support.now();
        CateringException exception = new CateringException(UUID.randomUUID(),
                String.format("CAT-X-%06d", store.nextSequence("cat_exception_seq")), site, serviceId, type, description,
                owner, ExceptionStatus.OPEN, type.needsIncident() ? "PENDING_MANUAL" : "NOT_REQUIRED", null, null, null,
                null, caller.actor().actorId(), now, now, 0);
        store.insert(exception);
        support.history(site, "EXCEPTION", exception.id(), null, "OPEN", caller.actor().actorId(), description);
        support.audit(caller, AuditAction.CAT_EXCEPTION_RAISED, "CateringException", exception.id(), site, null, exception);
        support.publish(CateringEvents.EXCEPTION_RAISED, "CateringException", exception.id(), site, caller.actor(),
                "exceptionId", exception.id(), "reference", exception.reference(), "type", type, "serviceId", serviceId);
        if (type.needsIncident()) {
            incidents.request(exception, caller.actor());
        }
        return exception;
    }

    /** A food-safety incident is closed by an approver - it is theirs to accept - the other kinds by the coordinator. */
    public CateringException resolveException(UUID id, String resolution, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringException before = store.exception(id).orElseThrow(() -> CateringSupport.notFound("Exception", id));
            support.require(caller, before.exceptionType() == ExceptionType.FOOD_SAFETY_INCIDENT
                    ? SflPermission.FACILITIES_CATERING_APPROVE : SflPermission.FACILITIES_CATERING_MANAGE, before.siteCode(),
                    "CateringException", id.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (before.status() == ExceptionStatus.RESOLVED) {
                throw CateringSupport.invalid("This exception is already resolved.");
            }
            CateringException after = new CateringException(id, before.reference(), before.siteCode(), before.serviceId(),
                    before.exceptionType(), before.description(), before.ownerReference(), ExceptionStatus.RESOLVED,
                    before.incidentState(), before.incidentReference(), CateringSupport.required(resolution, "resolution"),
                    caller.actor().actorId(), support.now(), before.createdBy(), before.createdAt(), support.now(), before.version());
            return saveException(before, after, caller, resolution);
        });
    }

    public CateringException linkIncident(UUID id, String reference, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringException before = store.exception(id).orElseThrow(() -> CateringSupport.notFound("Exception", id));
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, before.siteCode(), "CateringException", id.toString());
            if ("NOT_REQUIRED".equals(before.incidentState())) {
                throw new IllegalArgumentException("This exception does not need an incident.");
            }
            CateringException after = new CateringException(id, before.reference(), before.siteCode(), before.serviceId(),
                    before.exceptionType(), before.description(), before.ownerReference(), before.status(), "LINKED",
                    CateringSupport.required(reference, "incidentReference"), before.resolution(), before.resolvedBy(),
                    before.resolvedAt(), before.createdBy(), before.createdAt(), support.now(), before.version());
            return saveException(before, after, caller, "Incident " + reference.strip() + " linked");
        });
    }

    public CateringStore.Page<CateringException> exceptions(String siteCode, String status, int page, int size, Caller caller) {
        String site = CateringSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_CATERING_READ, site, "CateringException", "list");
        String statusFilter = CateringServiceService.enumName(ExceptionStatus.class, status);
        return inTransaction.execute(tx -> store.exceptions(site, statusFilter, Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    // ---- variances

    public Variance recordVariance(UUID serviceId, VarianceKind kind, BigDecimal planned, BigDecimal actual, String owner,
            String reason, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService service = support.service(serviceId);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, service.siteCode(), "Variance", "new");
            if (service.status() != ServiceStatus.DELIVERED && service.status() != ServiceStatus.RECONCILED) {
                throw CateringSupport.invalid("A variance can be recorded once the service is delivered, until it is closed.");
            }
            if (kind == null || planned == null || actual == null) {
                throw new IllegalArgumentException("kind, planned and actual are required");
            }
            String why = CateringSupport.required(reason, "reason");
            if (why.length() < 5) {
                throw new IllegalArgumentException("A variance needs a reason.");
            }
            Variance variance = new Variance(UUID.randomUUID(), serviceId, service.siteCode(), kind, planned, actual,
                    actual.subtract(planned), CateringSupport.required(owner, "owner"), why, VarianceStatus.OPEN, null, null,
                    caller.actor().actorId(), support.now());
            store.insert(variance);
            support.history(service.siteCode(), "VARIANCE", variance.id(), null, "OPEN", caller.actor().actorId(), why);
            support.audit(caller, AuditAction.CAT_VARIANCE_RECORDED, "CateringService", serviceId, service.siteCode(), null, variance);
            return variance;
        });
    }

    public Variance approveVariance(UUID varianceId, Caller caller) {
        return inTransaction.execute(tx -> {
            Variance variance = store.variance(varianceId).orElseThrow(() -> CateringSupport.notFound("Variance", varianceId));
            support.require(caller, SflPermission.FACILITIES_CATERING_APPROVE, variance.siteCode(), "Variance", varianceId.toString());
            if (variance.status() == VarianceStatus.APPROVED) {
                throw CateringSupport.invalid("This variance is already approved.");
            }
            if (caller.actor().actorId().equals(variance.createdBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SELF_APPROVAL);
            }
            store.approve(varianceId, caller.actor().actorId(), support.now());
            support.history(variance.siteCode(), "VARIANCE", varianceId, "OPEN", "APPROVED", caller.actor().actorId(), null);
            return store.variance(varianceId).orElseThrow();
        });
    }

    // ---- evidence

    public CateringEvidence submitEvidence(UUID serviceId, EvidenceKind kind, String reference, String fileName,
            String mediaType, long sizeBytes, String contentHash, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService service = support.service(serviceId);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, service.siteCode(), "CateringEvidence", serviceId.toString());
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            if (contentHash == null || !SHA_256.matcher(contentHash.strip()).matches()) {
                throw new IllegalArgumentException("contentHash must be a SHA-256 digest (64 hex characters)");
            }
            CateringEvidence evidence = new CateringEvidence(UUID.randomUUID(), serviceId, service.siteCode(), kind,
                    CateringSupport.required(reference, "reference"), CateringSupport.required(fileName, "fileName"),
                    CateringSupport.required(mediaType, "mediaType"), sizeBytes, contentHash.strip().toLowerCase(),
                    RetentionClass.COMPLIANCE.name(), caller.actor().actorId(), support.now());
            store.insert(evidence);
            support.audit(caller, AuditAction.CAT_EVIDENCE_SUBMITTED, "CateringEvidence", evidence.id(), service.siteCode(), null, evidence);
            return evidence;
        });
    }

    // ---- reconciliation

    public record Reconciliation(CateringService service, FinanceReferencePort.Verification finance, String note) {
    }

    /**
     * Records the purchase and invoice references against a delivered service. With no invoice reference - or while
     * there is no finance system - the service stays DELIVERED and the finance state says PENDING_FINANCE: delivery
     * stays visible, reconciliation stays pending, and nothing claims a match that did not happen.
     */
    public Reconciliation reconcile(UUID serviceId, String purchaseReference, String invoiceReference, Long expectedVersion,
            Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = support.service(serviceId);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, before.siteCode(), "CateringService", serviceId.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != ServiceStatus.DELIVERED) {
                throw CateringSupport.invalid("Only a delivered service can be reconciled.");
            }
            String purchase = CateringSupport.required(purchaseReference, "purchaseReference");
            String invoice = CateringSupport.blankToNull(invoiceReference);
            boolean quantityDiffers = before.deliveredPortions() != null && before.deliveredPortions() != before.plannedPortions();
            if (quantityDiffers && store.variancesOf(serviceId).stream().noneMatch(v -> v.kind() == VarianceKind.QUANTITY)) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID, "Delivered " + before.deliveredPortions()
                        + " portions against " + before.plannedPortions() + " planned: record the variance, with its owner and "
                        + "reason, before reconciling.");
            }
            FinanceReferencePort.Verification verification = finance.verify(purchase, invoice);
            boolean complete = invoice != null;
            CateringService after = new CateringService(before.id(), before.reference(), before.siteCode(), before.venueId(),
                    before.menuId(), before.supplierId(), before.contextType(), before.contextReference(), before.title(),
                    before.serviceDate(), before.startsAt(), before.cancellationCutoff(), before.expectedGuests(),
                    before.plannedPortions(), before.deliveredPortions(),
                    complete ? ServiceStatus.RECONCILED : before.status(), before.requestedBy(), before.approvedBy(),
                    before.approvedAt(), before.capacityExceptionReason(), before.capacityApprovedBy(),
                    before.supplierExceptionReason(), before.supplierApprovedBy(), purchase, invoice,
                    complete ? FinanceState.RECORDED : FinanceState.PENDING_FINANCE, before.deliveredAt(), before.closedAt(),
                    before.cancellationReason(), before.createdBy(), before.createdAt(), support.now(), before.version());
            if (!store.update(after, before.version())) {
                throw CateringSupport.conflict();
            }
            CateringService saved = support.service(serviceId);
            support.history(saved.siteCode(), "SERVICE", serviceId, before.status().name(), saved.status().name(),
                    caller.actor().actorId(), complete ? "Purchase and invoice references recorded"
                            : "Purchase reference recorded; invoice pending");
            support.audit(caller, AuditAction.CAT_RECONCILED, "CateringService", serviceId, saved.siteCode(), before, saved);
            return new Reconciliation(saved, verification, verification.available()
                    ? null : "No finance system is integrated: these references are recorded, not verified.");
        });
    }

    /** Closes with evidence: reconciled, every variance approved, and the delivery note and invoice filed. */
    public CateringService close(UUID serviceId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService before = support.service(serviceId);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, before.siteCode(), "CateringService", serviceId.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != ServiceStatus.RECONCILED) {
                throw CateringSupport.invalid("Only a reconciled service can be closed.");
            }
            List<String> missing = new java.util.ArrayList<>();
            if (store.variancesOf(serviceId).stream().anyMatch(v -> v.status() == VarianceStatus.OPEN)) {
                missing.add("every variance must be approved");
            }
            List<CateringEvidence> evidence = store.evidenceOf(serviceId);
            if (evidence.stream().noneMatch(e -> e.kind() == EvidenceKind.DELIVERY_NOTE)) {
                missing.add("the delivery note must be filed");
            }
            if (evidence.stream().noneMatch(e -> e.kind() == EvidenceKind.INVOICE)) {
                missing.add("the invoice must be filed");
            }
            if (!missing.isEmpty()) {
                throw new FacilitiesException(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID,
                        "The service cannot be closed: " + String.join("; ", missing) + ".");
            }
            CateringService after = new CateringService(before.id(), before.reference(), before.siteCode(), before.venueId(),
                    before.menuId(), before.supplierId(), before.contextType(), before.contextReference(), before.title(),
                    before.serviceDate(), before.startsAt(), before.cancellationCutoff(), before.expectedGuests(),
                    before.plannedPortions(), before.deliveredPortions(), ServiceStatus.CLOSED, before.requestedBy(),
                    before.approvedBy(), before.approvedAt(), before.capacityExceptionReason(), before.capacityApprovedBy(),
                    before.supplierExceptionReason(), before.supplierApprovedBy(), before.purchaseReference(),
                    before.invoiceReference(), before.financeState(), before.deliveredAt(), support.now(),
                    before.cancellationReason(), before.createdBy(), before.createdAt(), support.now(), before.version());
            if (!store.update(after, before.version())) {
                throw CateringSupport.conflict();
            }
            CateringService saved = support.service(serviceId);
            support.history(saved.siteCode(), "SERVICE", serviceId, "RECONCILED", "CLOSED", caller.actor().actorId(), "Closed with evidence");
            support.audit(caller, AuditAction.CAT_SERVICE_UPDATED, "CateringService", serviceId, saved.siteCode(), before, saved);
            support.publish(CateringEvents.SERVICE_CLOSED, "CateringService", serviceId, saved.siteCode(), caller.actor(),
                    "serviceId", serviceId, "reference", saved.reference(), "financeState", saved.financeState());
            return saved;
        });
    }

    /** The reconciliation pack: delivered against planned, purchase references, variances, checks and evidence - references, not a ledger. */
    public Pack pack(UUID serviceId, Caller caller) {
        return inTransaction.execute(tx -> {
            CateringService service = support.service(serviceId);
            support.require(caller, SflPermission.FACILITIES_CATERING_READ, service.siteCode(), "CateringPack", serviceId.toString());
            FinanceReferencePort.Verification verification = finance.verify(service.purchaseReference(), service.invoiceReference());
            return new Pack(service, store.variancesOf(serviceId), store.checksOf(serviceId), store.exceptionsOf(serviceId),
                    store.evidenceOf(serviceId), verification, service.deliveredPortions() == null ? null
                            : service.deliveredPortions() - service.plannedPortions());
        });
    }

    public record Pack(CateringService service, List<Variance> variances, List<CateringCheck> checks,
            List<CateringException> exceptions, List<CateringEvidence> evidence, FinanceReferencePort.Verification finance,
            Integer portionDifference) {
    }

    // ---- dashboard

    public Dashboard dashboard(String siteCode, int periodDays, Caller caller) {
        String site = CateringSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_CATERING_READ, site, "CateringDashboard", site);
        int days = Math.min(Math.max(7, periodDays), 366);
        return inTransaction.execute(tx -> {
            LocalDate today = support.today();
            LocalDate from = today.minusDays(days);
            long[] delivery = store.deliveryCounts(site, from, today);
            long[] checks = store.checkCounts(site, from);
            long[] dietary = store.dietaryCounts(site, from, today.plusDays(30));
            Instant now = support.now();
            List<Instant> open = store.openVarianceDates(site);
            long over14 = open.stream().filter(d -> ChronoUnit.DAYS.between(d, now) > 14).count();
            long over7 = open.stream().filter(d -> ChronoUnit.DAYS.between(d, now) > 7).count() - over14;
            return new Dashboard(site, days, delivery[0], delivery[1], percent(delivery[1], delivery[0]), checks[0], checks[1],
                    percent(checks[1], checks[0]), dietary[0], dietary[1], percent(dietary[1], dietary[0]), open.size(),
                    open.size() - over7 - over14, over7, over14, store.netQuantityVariance(site),
                    store.count("SELECT count(*) FROM facilities.cat_exceptions WHERE site_code = ? AND status = 'OPEN'", site),
                    store.count("SELECT count(*) FROM facilities.cat_services WHERE site_code = ? AND status = 'DELIVERED'"
                            + " AND finance_state <> 'RECORDED'", site));
        });
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    public record Dashboard(String siteCode, int periodDays, long confirmedServices, long deliveredServices,
            BigDecimal deliveredPercent, long servicesDelivered, long servicesWithPassingCheck, BigDecimal checkedPercent,
            long dietaryRequests, long dietaryExceptions, BigDecimal dietaryExceptionPercent, long openVariances,
            long variancesUpTo7Days, long variances8To14Days, long variancesOver14Days, BigDecimal netQuantityVariance,
            long openExceptions, long awaitingFinance) {
    }

    // ---- internals

    private CateringException saveException(CateringException before, CateringException after, Caller caller, String reason) {
        if (!store.update(after, before.version())) {
            throw CateringSupport.conflict();
        }
        CateringException saved = store.exception(after.id()).orElseThrow();
        support.history(saved.siteCode(), "EXCEPTION", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, AuditAction.CAT_EXCEPTION_UPDATED, "CateringException", saved.id(), saved.siteCode(), before, saved);
        return saved;
    }
}

package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.maintenance.domain.RetentionClass;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import gh.edu.clet.sfl.facilities.waste.domain.ChainPolicy;
import gh.edu.clet.sfl.facilities.waste.domain.CollectionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.CustodyEvent;
import gh.edu.clet.sfl.facilities.waste.domain.CustodyStep;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.QuantityBasis;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCarrier;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteDestination;
import gh.edu.clet.sfl.facilities.waste.domain.WasteEvidence;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import gh.edu.clet.sfl.facilities.waste.domain.WasteHistoryEntry;
import gh.edu.clet.sfl.facilities.waste.domain.WastePoint;
import gh.edu.clet.sfl.facilities.waste.domain.WasteStream;
import gh.edu.clet.sfl.facilities.waste.domain.WasteUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collections and their chain of custody - SRS-SFL-S178-02 and -03.
 *
 * <p>Configure, schedule, record the collection, hand over to an approved carrier and destination, confirm
 * the destination, reconcile the certificate, close. The rules that make the chain mean something:
 * <ul>
 *   <li><strong>Approval is checked when waste moves.</strong> A carrier whose licence lapsed, or a
 *       destination that no longer accepts hazardous waste, blocks the handover even if it was fine when
 *       the collection was scheduled - and the block raises an exception rather than just an error.</li>
 *   <li><strong>The original measurement is kept.</strong> The quantity and unit as entered are stored
 *       beside the normalised kilograms, and an estimate is flagged as one.</li>
 *   <li><strong>A refusal that leaves the chain open escalates.</strong> Raising the exception commits
 *       first, in its own transaction; the refusal then throws. See {@link WasteExceptionService}.</li>
 *   <li><strong>Evidence is accepted by someone else.</strong> A hazardous chain cannot close on evidence
 *       its own submitter approved.</li>
 * </ul>
 */
@Service
public class WasteCollectionService {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final WasteStore store;
    private final WasteSupport support;
    private final WasteExceptionService exceptions;
    private final TransactionTemplate inTransaction;

    public WasteCollectionService(WasteStore store, WasteSupport support, WasteExceptionService exceptions,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.exceptions = exceptions;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    // ---- reads

    public WasteStore.Page<WasteCollection> list(String siteCode, String status, UUID streamId,
            boolean hazardousOnly, int page, int size, Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode");
        support.require(caller, SflPermission.FACILITIES_WASTE_READ, site, "WasteCollection", "list");
        String statusFilter = WasteExceptionService.enumName(CollectionStatus.class, status);
        return inTransaction.execute(tx -> store.collections(site, statusFilter, streamId, hazardousOnly,
                Math.max(0, page), Math.min(Math.max(1, size), 100)));
    }

    public CollectionDetail get(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteCollection collection = support.collection(id);
            support.require(caller, SflPermission.FACILITIES_WASTE_READ, collection.siteCode(), "WasteCollection",
                    id.toString());
            WasteStream stream = store.stream(collection.streamId()).orElseThrow();
            WastePoint point = store.point(collection.pointId()).orElseThrow();
            WasteCarrier carrier = store.carrier(collection.carrierId()).orElseThrow();
            WasteDestination destination = collection.destinationId() == null ? null
                    : store.destination(collection.destinationId()).orElse(null);
            return new CollectionDetail(collection, stream, point, carrier, destination, store.custody(id),
                    store.evidenceOf(id), store.openExceptionsOf(id), store.history(id),
                    collection.quantityBasis() == QuantityBasis.ESTIMATED);
        });
    }

    public record CollectionDetail(WasteCollection collection, WasteStream stream, WastePoint point,
            WasteCarrier carrier, WasteDestination destination, List<CustodyEvent> custody,
            List<WasteEvidence> evidence, List<WasteException> openExceptions, List<WasteHistoryEntry> history,
            boolean estimated) {
    }

    // ---- scheduling and recording

    public WasteCollection schedule(String siteCode, UUID streamId, UUID pointId, UUID carrierId,
            LocalDate scheduledFor, Caller caller) {
        return inTransaction.execute(tx -> {
            String site = WasteSupport.code(siteCode, "siteCode");
            support.require(caller, SflPermission.FACILITIES_WASTE_MANAGE, site, "WasteCollection", "new");
            if (scheduledFor == null) {
                throw new IllegalArgumentException("scheduledFor is required");
            }
            WasteStream stream = store.stream(streamId).orElseThrow(() -> new IllegalArgumentException("Unknown waste stream"));
            WastePoint point = store.point(pointId).orElseThrow(() -> new IllegalArgumentException("Unknown collection point"));
            WasteCarrier carrier = store.carrier(carrierId).orElseThrow(() -> new IllegalArgumentException("Unknown carrier"));
            if (!stream.active() || !point.active()) {
                throw new IllegalArgumentException("The stream and the collection point must both be active.");
            }
            if (!point.siteCode().equals(site)) {
                throw new IllegalArgumentException("Collection point " + point.code() + " is not at site " + site + ".");
            }
            String refusal = carrier.refusal(stream.hazardous(), scheduledFor);
            if (refusal != null) {
                throw new FacilitiesException(FacilitiesErrorCode.WASTE_CARRIER_UNAPPROVED, refusal);
            }
            Instant now = support.now();
            WasteCollection collection = new WasteCollection(UUID.randomUUID(),
                    String.format("WST-C-%06d", store.nextSequence("waste_collection_seq")), site, streamId, pointId,
                    carrierId, null, stream.hazardous(), scheduledFor, null, null, null, null, null, null, null, null,
                    false, true, CollectionStatus.SCHEDULED, null, caller.actor().actorId(), now, now, 0);
            store.insert(collection);
            support.history(site, "COLLECTION", collection.id(), null, "SCHEDULED", caller.actor().actorId(), null);
            support.audit(caller, AuditAction.WASTE_COLLECTION_CREATED, "WasteCollection", collection.id(), site, null,
                    collection);
            support.publish(WasteEvents.COLLECTION_SCHEDULED, "WasteCollection", collection.id(), site, caller.actor(),
                    "collectionId", collection.id(), "reference", collection.reference(), "streamCode", stream.code(),
                    "hazardous", stream.hazardous(), "scheduledFor", scheduledFor);
            return collection;
        });
    }

    /**
     * Records that the collection happened, with what was measured. A quantity must be flagged measured or
     * estimated; hazardous waste also needs its manifest reference now, because the manifest travels with it.
     */
    public WasteCollection record(UUID id, LocalDate collectedOn, BigDecimal quantity, String unitCode,
            QuantityBasis basis, String manifestReference, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteCollection before = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, CollectionStatus.COLLECTED);
            LocalDate day = collectedOn == null ? support.today() : collectedOn;
            if (day.isAfter(support.today())) {
                throw new IllegalArgumentException("collectedOn cannot be in the future");
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            if (basis == null) {
                throw new IllegalArgumentException("basis is required: say whether the quantity was measured or estimated");
            }
            WasteUnit unit = store.unit(WasteSupport.code(unitCode, "unit"))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown unit: " + unitCode));
            String manifest = WasteSupport.blankToNull(manifestReference);
            if (before.hazardous() && manifest == null) {
                throw new IllegalArgumentException("Hazardous waste needs its manifest reference when it is collected.");
            }
            WasteCollection after = new WasteCollection(id, before.reference(), before.siteCode(), before.streamId(),
                    before.pointId(), before.carrierId(), before.destinationId(), before.hazardous(),
                    before.scheduledFor(), day, quantity, unit.code(), unit.toKilograms(quantity), basis, manifest,
                    before.certificateReference(), before.certificateReceivedOn(), before.contaminated(),
                    before.quantityReconciled(), CollectionStatus.COLLECTED, null, before.createdBy(),
                    before.createdAt(), support.now(), before.version());
            WasteCollection saved = save(before, after, caller, "Collected " + quantity + " " + unit.code() + " ("
                    + basis.name().toLowerCase() + ")", AuditAction.WASTE_COLLECTION_UPDATED);
            WastePoint point = store.point(saved.pointId()).orElseThrow();
            WasteCarrier carrier = store.carrier(saved.carrierId()).orElseThrow();
            custody(saved, CustodyStep.COLLECTED, point.name(), carrier.name(), point.name(), manifest, caller);
            if (before.status() == CollectionStatus.MISSED) {
                store.openException(id, ExceptionType.MISSED_COLLECTION).ifPresent(open -> exceptions
                        .resolveChainExceptions(id, caller));
            }
            support.publish(WasteEvents.COLLECTION_RECORDED, "WasteCollection", id, saved.siteCode(), caller.actor(),
                    "collectionId", id, "reference", saved.reference(), "quantityKg", saved.quantityKg(), "basis",
                    basis);
            return saved;
        });
    }

    /**
     * Hands the waste to its carrier for a named destination. An unapproved carrier or destination blocks it -
     * and the block is raised as an exception that commits before this method throws.
     */
    public WasteCollection handOver(UUID id, UUID destinationId, String location, Long expectedVersion,
            Caller caller) {
        WasteCollection before = inTransaction.execute(tx -> {
            WasteCollection collection = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, collection.version());
            requireMove(collection, CollectionStatus.HANDED_OVER);
            return collection;
        });
        WasteCarrier carrier = store.carrier(before.carrierId()).orElseThrow();
        WasteDestination destination = store.destination(destinationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown destination"));
        LocalDate today = support.today();
        String carrierRefusal = carrier.refusal(before.hazardous(), today);
        if (carrierRefusal != null) {
            exceptions.raise(before.siteCode(), id, ExceptionType.UNAPPROVED_CARRIER, carrierRefusal, null, caller);
            throw new FacilitiesException(FacilitiesErrorCode.WASTE_CARRIER_UNAPPROVED, carrierRefusal);
        }
        String destinationRefusal = destination.refusal(before.hazardous(), today);
        if (destinationRefusal != null) {
            exceptions.raise(before.siteCode(), id, ExceptionType.UNAPPROVED_DESTINATION, destinationRefusal, null,
                    caller);
            throw new FacilitiesException(FacilitiesErrorCode.WASTE_DESTINATION_UNAPPROVED, destinationRefusal);
        }
        return inTransaction.execute(tx -> {
            WasteCollection current = support.collection(id);
            WasteSupport.checkVersion(before.version(), current.version());
            WasteCollection after = copy(current, destinationId, current.carrierId(), current.certificateReference(),
                    current.certificateReceivedOn(), current.contaminated(), current.quantityReconciled(),
                    CollectionStatus.HANDED_OVER, null);
            WasteCollection saved = save(current, after, caller, "Handed over to " + destination.code(),
                    AuditAction.WASTE_COLLECTION_UPDATED);
            custody(saved, CustodyStep.HANDED_OVER, carrier.name(), destination.name(),
                    WasteSupport.blankToNull(location) == null ? destination.name() : location.strip(),
                    saved.manifestReference(), caller);
            support.publish(WasteEvents.COLLECTION_HANDED_OVER, "WasteCollection", id, saved.siteCode(),
                    caller.actor(), "collectionId", id, "reference", saved.reference(), "destinationCode",
                    destination.code(), "hazardous", saved.hazardous());
            return saved;
        });
    }

    /** The destination has the waste. Hazardous waste needs accepted receiving evidence first; without it the chain stays open and escalates. */
    public WasteCollection confirmDestination(UUID id, Long expectedVersion, Caller caller) {
        WasteCollection before = inTransaction.execute(tx -> {
            WasteCollection collection = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, collection.version());
            requireMove(collection, CollectionStatus.DESTINATION_CONFIRMED);
            return collection;
        });
        if (before.hazardous() && !hasAccepted(id, EvidenceKind.RECEIVING)) {
            String message = "Hazardous waste needs accepted receiving evidence from the destination before the "
                    + "destination can be confirmed.";
            exceptions.raise(before.siteCode(), id, ExceptionType.MISSING_RECEIVING_EVIDENCE, message, null, caller);
            throw new FacilitiesException(FacilitiesErrorCode.WASTE_CHAIN_OPEN, message);
        }
        return inTransaction.execute(tx -> {
            WasteCollection current = support.collection(id);
            WasteCollection after = copy(current, current.destinationId(), current.carrierId(),
                    current.certificateReference(), current.certificateReceivedOn(), current.contaminated(),
                    current.quantityReconciled(), CollectionStatus.DESTINATION_CONFIRMED, null);
            WasteCollection saved = save(current, after, caller, "Destination confirmed",
                    AuditAction.WASTE_COLLECTION_UPDATED);
            WasteDestination destination = store.destination(saved.destinationId()).orElseThrow();
            custody(saved, CustodyStep.RECEIVED_AT_DESTINATION, destination.name(), null, destination.name(), null,
                    caller);
            return saved;
        });
    }

    /** Records the treatment or recycling certificate the destination issued. */
    public WasteCollection recordCertificate(UUID id, String certificateReference, LocalDate receivedOn,
            Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteCollection before = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != CollectionStatus.HANDED_OVER && before.status() != CollectionStatus.DESTINATION_CONFIRMED) {
                throw WasteSupport.invalid("A certificate can be recorded once the waste has been handed over.");
            }
            LocalDate day = receivedOn == null ? support.today() : receivedOn;
            WasteCollection after = copy(before, before.destinationId(), before.carrierId(),
                    WasteSupport.required(certificateReference, "certificateReference"), day, before.contaminated(),
                    before.quantityReconciled(), before.status(), null);
            WasteCollection saved = save(before, after, caller, "Certificate " + certificateReference.strip()
                    + " recorded", AuditAction.WASTE_COLLECTION_UPDATED);
            WasteDestination destination = store.destination(saved.destinationId()).orElseThrow();
            custody(saved, CustodyStep.TREATED, destination.name(), null, destination.name(),
                    certificateReference.strip(), caller);
            return saved;
        });
    }

    /** A contaminated stream: its quantity stays unreconciled and a corrective action is required. */
    public WasteCollection markContaminated(UUID id, String description, Caller caller) {
        WasteCollection before = inTransaction.execute(tx -> {
            WasteCollection collection = manageable(id, caller);
            if (!collection.status().open() || collection.status() == CollectionStatus.SCHEDULED) {
                throw WasteSupport.invalid("Only a collection that has been carried out can be marked contaminated.");
            }
            return collection;
        });
        exceptions.raise(before.siteCode(), id, ExceptionType.CONTAMINATION, WasteSupport.required(description,
                "description"), null, caller);
        return inTransaction.execute(tx -> {
            WasteCollection current = support.collection(id);
            WasteCollection after = copy(current, current.destinationId(), current.carrierId(),
                    current.certificateReference(), current.certificateReceivedOn(), true, false, current.status(), null);
            return save(current, after, caller, "Marked contaminated: " + description.strip(),
                    AuditAction.WASTE_COLLECTION_UPDATED);
        });
    }

    /** Restates the quantity after contamination (or any correction); the original entry stays in the audit trail. */
    public WasteCollection reconcile(UUID id, BigDecimal quantity, String unitCode, QuantityBasis basis,
            Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteCollection before = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, before.version());
            if (before.quantity() == null) {
                throw WasteSupport.invalid("There is no recorded quantity to reconcile.");
            }
            if (quantity == null || quantity.signum() <= 0 || basis == null) {
                throw new IllegalArgumentException("quantity and basis are required");
            }
            WasteUnit unit = store.unit(WasteSupport.code(unitCode, "unit"))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown unit: " + unitCode));
            WasteCollection after = new WasteCollection(id, before.reference(), before.siteCode(), before.streamId(),
                    before.pointId(), before.carrierId(), before.destinationId(), before.hazardous(),
                    before.scheduledFor(), before.collectedOn(), quantity, unit.code(), unit.toKilograms(quantity),
                    basis, before.manifestReference(), before.certificateReference(), before.certificateReceivedOn(),
                    before.contaminated(), true, before.status(), before.closedAt(), before.createdBy(),
                    before.createdAt(), support.now(), before.version());
            return save(before, after, caller, "Quantity reconciled from " + before.quantity() + " "
                    + before.unit() + " to " + quantity + " " + unit.code(), AuditAction.WASTE_COLLECTION_UPDATED);
        });
    }

    public WasteCollection markMissed(UUID id, String reason, Long expectedVersion, Caller caller) {
        WasteCollection before = inTransaction.execute(tx -> {
            WasteCollection collection = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, collection.version());
            requireMove(collection, CollectionStatus.MISSED);
            return collection;
        });
        exceptions.raise(before.siteCode(), id, ExceptionType.MISSED_COLLECTION,
                WasteSupport.required(reason, "reason"), null, caller);
        return inTransaction.execute(tx -> {
            WasteCollection current = support.collection(id);
            WasteCollection saved = save(current, copy(current, current.destinationId(), current.carrierId(),
                    current.certificateReference(), current.certificateReceivedOn(), current.contaminated(),
                    current.quantityReconciled(), CollectionStatus.MISSED, null), caller, reason,
                    AuditAction.WASTE_COLLECTION_UPDATED);
            support.publish(WasteEvents.COLLECTION_MISSED, "WasteCollection", id, saved.siteCode(), caller.actor(),
                    "collectionId", id, "reference", saved.reference(), "scheduledFor", saved.scheduledFor());
            return saved;
        });
    }

    public WasteCollection cancel(UUID id, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteCollection before = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, CollectionStatus.CANCELLED);
            return save(before, copy(before, before.destinationId(), before.carrierId(), before.certificateReference(),
                    before.certificateReceivedOn(), before.contaminated(), before.quantityReconciled(),
                    CollectionStatus.CANCELLED, null), caller, WasteSupport.required(reason, "reason"),
                    AuditAction.WASTE_COLLECTION_UPDATED);
        });
    }

    /**
     * Closes the chain - SRS acceptance criterion: a hazardous collection lacking receiving evidence stays
     * open and escalates. Every gap {@link ChainPolicy} finds is raised as an exception (committed before
     * the refusal) and the refusal lists them all.
     */
    public WasteCollection close(UUID id, Long expectedVersion, Caller caller) {
        WasteCollection before = inTransaction.execute(tx -> {
            WasteCollection collection = manageable(id, caller);
            WasteSupport.checkVersion(expectedVersion, collection.version());
            if (collection.status() != CollectionStatus.HANDED_OVER
                    && collection.status() != CollectionStatus.DESTINATION_CONFIRMED) {
                throw WasteSupport.invalid("Only a collection that has been handed over can be closed.");
            }
            return collection;
        });
        List<ChainPolicy.Gap> gaps = ChainPolicy.gaps(before, store.evidenceOf(id), store.openExceptionsOf(id));
        if (!gaps.isEmpty()) {
            for (ChainPolicy.Gap gap : gaps) {
                exceptions.raise(before.siteCode(), id, gap.type(), gap.message(), null, caller);
            }
            throw new FacilitiesException(FacilitiesErrorCode.WASTE_CHAIN_OPEN, "The chain of custody stays open: "
                    + gaps.stream().map(ChainPolicy.Gap::message).collect(Collectors.joining(" ")));
        }
        if (before.status() != CollectionStatus.DESTINATION_CONFIRMED) {
            throw WasteSupport.invalid("Confirm the destination before closing the collection.");
        }
        return inTransaction.execute(tx -> {
            WasteCollection current = support.collection(id);
            WasteCollection saved = save(current, copy(current, current.destinationId(), current.carrierId(),
                    current.certificateReference(), current.certificateReceivedOn(), current.contaminated(),
                    current.quantityReconciled(), CollectionStatus.CLOSED, support.now()), caller,
                    "Chain of custody complete", AuditAction.WASTE_COLLECTION_CLOSED);
            exceptions.resolveChainExceptions(id, caller);
            support.publish(WasteEvents.COLLECTION_CLOSED, "WasteCollection", id, saved.siteCode(), caller.actor(),
                    "collectionId", id, "reference", saved.reference(), "quantityKg", saved.quantityKg(), "basis",
                    saved.quantityBasis(), "hazardous", saved.hazardous());
            return saved;
        });
    }

    // ---- evidence

    public WasteEvidence submitEvidence(UUID collectionId, EvidenceKind kind, String reference, String fileName,
            String mediaType, long sizeBytes, String contentHash, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteCollection collection = manageable(collectionId, caller);
            if (!collection.status().open()) {
                throw WasteSupport.invalid("Evidence cannot be added to a closed or cancelled collection.");
            }
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            if (contentHash == null || !SHA_256.matcher(contentHash.strip()).matches()) {
                throw new IllegalArgumentException("contentHash must be a SHA-256 digest (64 hex characters)");
            }
            // Regulated chain-of-custody records are kept for the safety-critical period when hazardous.
            RetentionClass retention = collection.hazardous() ? RetentionClass.SAFETY_CRITICAL
                    : RetentionClass.COMPLIANCE;
            WasteEvidence evidence = new WasteEvidence(UUID.randomUUID(), collectionId, collection.siteCode(), kind,
                    WasteSupport.required(reference, "reference"), WasteSupport.required(fileName, "fileName"),
                    WasteSupport.required(mediaType, "mediaType"), sizeBytes, contentHash.strip().toLowerCase(),
                    retention.name(), EvidenceStatus.SUBMITTED, caller.actor().actorId(), support.now(), null, null,
                    null);
            store.insert(evidence);
            support.history(collection.siteCode(), "EVIDENCE", evidence.id(), null, "SUBMITTED",
                    caller.actor().actorId(), kind.name());
            support.audit(caller, AuditAction.WASTE_EVIDENCE_SUBMITTED, "WasteEvidence", evidence.id(),
                    collection.siteCode(), null, evidence);
            return evidence;
        });
    }

    public WasteEvidence reviewEvidence(UUID evidenceId, boolean accept, String reason, Caller caller) {
        return inTransaction.execute(tx -> {
            WasteEvidence before = store.evidence(evidenceId)
                    .orElseThrow(() -> WasteSupport.notFound("Waste evidence", evidenceId));
            support.require(caller, SflPermission.FACILITIES_WASTE_VERIFY, before.siteCode(), "WasteEvidence",
                    evidenceId.toString());
            if (before.status() != EvidenceStatus.SUBMITTED) {
                throw WasteSupport.invalid("This evidence has already been reviewed.");
            }
            String reviewer = caller.actor().actorId();
            if (reviewer.equals(before.submittedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.WASTE_SELF_VERIFICATION);
            }
            if (!accept) {
                WasteSupport.required(reason, "reason");
            }
            EvidenceStatus next = accept ? EvidenceStatus.ACCEPTED : EvidenceStatus.REJECTED;
            store.review(evidenceId, next, reviewer, support.now(), reason);
            support.history(before.siteCode(), "EVIDENCE", evidenceId, "SUBMITTED", next.name(), reviewer, reason);
            WasteEvidence after = store.evidence(evidenceId).orElseThrow();
            support.audit(caller, AuditAction.WASTE_EVIDENCE_REVIEWED, "WasteEvidence", evidenceId,
                    before.siteCode(), before, after);
            return after;
        });
    }

    // ---- sweep

    /**
     * Marks scheduled collections that nobody recorded as missed once {@code graceDays} have passed, and
     * raises the exception to the facilities owner. Idempotent: an open missed-collection exception is not
     * raised twice.
     */
    public int sweepMissed(int graceDays, gh.edu.clet.sfl.common.security.ActorContext actor) {
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        int marked = 0;
        for (WasteCollection due : store.scheduledBefore(support.today().minusDays(graceDays))) {
            String reason = "Not collected within " + graceDays + " day(s) of " + due.scheduledFor();
            exceptions.raise(due.siteCode(), due.id(), ExceptionType.MISSED_COLLECTION, reason, null, caller);
            inTransaction.executeWithoutResult(tx -> {
                WasteCollection current = support.collection(due.id());
                if (current.status() == CollectionStatus.SCHEDULED) {
                    save(current, copy(current, current.destinationId(), current.carrierId(),
                            current.certificateReference(), current.certificateReceivedOn(), current.contaminated(),
                            current.quantityReconciled(), CollectionStatus.MISSED, null), caller, reason,
                            AuditAction.WASTE_COLLECTION_UPDATED);
                    support.publish(WasteEvents.COLLECTION_MISSED, "WasteCollection", current.id(),
                            current.siteCode(), actor, "collectionId", current.id(), "reference", current.reference(),
                            "scheduledFor", current.scheduledFor());
                }
            });
            marked++;
        }
        return marked;
    }

    // ---- internals

    private boolean hasAccepted(UUID collectionId, EvidenceKind kind) {
        return store.evidenceOf(collectionId).stream()
                .anyMatch(e -> e.kind() == kind && e.status() == EvidenceStatus.ACCEPTED);
    }

    private WasteCollection manageable(UUID id, Caller caller) {
        WasteCollection collection = support.collection(id);
        support.require(caller, SflPermission.FACILITIES_WASTE_MANAGE, collection.siteCode(), "WasteCollection",
                id.toString());
        return collection;
    }

    private static void requireMove(WasteCollection collection, CollectionStatus next) {
        if (!collection.status().canMoveTo(next)) {
            throw new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION,
                    "A " + collection.status() + " collection cannot become " + next + ".");
        }
    }

    private WasteCollection save(WasteCollection before, WasteCollection after, Caller caller, String reason,
            AuditAction action) {
        if (!store.update(after, before.version())) {
            throw WasteSupport.conflict();
        }
        WasteCollection saved = support.collection(after.id());
        support.history(saved.siteCode(), "COLLECTION", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, action, "WasteCollection", saved.id(), saved.siteCode(), before, saved);
        return saved;
    }

    private WasteCollection copy(WasteCollection b, UUID destinationId, UUID carrierId, String certificate,
            LocalDate certificateOn, boolean contaminated, boolean reconciled, CollectionStatus status,
            Instant closedAt) {
        return new WasteCollection(b.id(), b.reference(), b.siteCode(), b.streamId(), b.pointId(), carrierId,
                destinationId, b.hazardous(), b.scheduledFor(), b.collectedOn(), b.quantity(), b.unit(),
                b.quantityKg(), b.quantityBasis(), b.manifestReference(), certificate, certificateOn, contaminated,
                reconciled, status, closedAt, b.createdBy(), b.createdAt(), support.now(), b.version());
    }

    private void custody(WasteCollection collection, CustodyStep step, String from, String to, String location,
            String evidenceReference, Caller caller) {
        CustodyEvent event = new CustodyEvent(UUID.randomUUID(), collection.id(), collection.siteCode(), step, from,
                to, location, evidenceReference, null, caller.actor().actorId(), support.now());
        store.insert(event);
        support.audit(caller, AuditAction.WASTE_CUSTODY_RECORDED, "WasteCollection", collection.id(),
                collection.siteCode(), null, event);
    }
}

package gh.edu.clet.sfl.fleetlogistics.fuel.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.AuditPort;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.IdempotencyPort;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.IntegrationEventPublisher;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.NotificationPort;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.NotificationPort.NotificationKind;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverScope;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverScopeResolver;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.event.FleetEventType;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.RecordNotFoundException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.AuditAction;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.AuditEvent;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.RecordMetadata;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FinanceAuditVisibilityPort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelEvidencePort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelFleetReferencePort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelOutboxAdminPort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelRepository;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.exception.FuelPolicyPeriodOverlapException;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.exception.FuelPolicyVersionNotAdvancedException;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.DriverLogbook;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelAnomalyCase;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelImportBatch;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelImportRow;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelPolicy;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelPostedPrice;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelReconciliation;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelTransaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FuelApplicationService {
    private static final AtomicLong NUMBERS = new AtomicLong();
    private final FuelRepository repository;
    private final FuelFleetReferencePort fleet;
    private final FuelAccessPolicy access;
    private final AuditPort audit;
    private final IntegrationEventPublisher events;
    private final IdempotencyPort idempotency;
    private final Clock clock;
    private final NotificationPort notifications;
    private final FinanceAuditVisibilityPort financeAudit;
    private final FuelOutboxAdminPort outboxAdmin;

    /**
     * Resolves which driver a signed-in person is. Shared with S166 so both modules agree on the
     * answer.
     */
    private final DriverScopeResolver driverScopes;

    /** Read-only view of S166 evidence, for the "has this photograph been used before" rule. */
    private final FuelEvidencePort evidence;

    public FuelApplicationService(
            FuelRepository r,
            FuelFleetReferencePort f,
            FuelAccessPolicy a,
            AuditPort audit,
            IntegrationEventPublisher e,
            IdempotencyPort i,
            Clock c,
            NotificationPort n,
            FinanceAuditVisibilityPort fa,
            FuelOutboxAdminPort ox,
            DriverScopeResolver ds,
            FuelEvidencePort ev) {
        repository = r;
        fleet = f;
        access = a;
        this.audit = audit;
        events = e;
        idempotency = i;
        clock = c;
        notifications = n;
        financeAudit = fa;
        outboxAdmin = ox;
        driverScopes = ds;
        evidence = ev;
    }

    public FuelOutboxAdminPort.OutboxHealth integrationHealth(ActorContext actor) {
        access.requirePermission(actor, SflPermission.FUEL_INTEGRATION_REPLAY, "FuelOutbox");
        return outboxAdmin.health();
    }

    @Transactional
    public boolean replayIntegration(UUID messageId, ActorContext actor, SourceChannel channel) {
        access.requirePermission(actor, SflPermission.FUEL_INTEGRATION_REPLAY, "FuelOutbox");
        boolean requeued = outboxAdmin.replay(messageId);
        audit.record(
                actor,
                channel,
                SiteCode.of("SYSTEM"),
                AuditAction.INTEGRATION_REPLAYED,
                "FuelOutboxMessage",
                messageId.toString(),
                null,
                Map.of("requeued", requeued));
        return requeued;
    }

    public record CreatePolicy(
            String siteCode,
            String name,
            Instant effectiveFrom,
            Instant effectiveTo,
            int policyVersion,
            BigDecimal maxPerTransaction,
            BigDecimal dailyLimit,
            BigDecimal monthlyLimit,
            BigDecimal tankCapacity,
            BigDecimal minConsumption,
            BigDecimal maxConsumption,
            long odometerJumpTolerance,
            boolean receiptRequired,
            int receiptGraceHours,
            BigDecimal materialityAmount,
            int anomalySlaHours,
            BigDecimal costVarianceTolerance,
            int repeatedPatternWindowHours,
            int repeatedPatternThreshold,
            Set<String> allowedFuelProducts,
            Set<String> approvedVendors,
            ActorContext actor,
            SourceChannel channel) {
        public CreatePolicy {
            costVarianceTolerance =
                    costVarianceTolerance == null
                            ? FuelPolicy.DEFAULT_COST_VARIANCE_TOLERANCE
                            : costVarianceTolerance;
            repeatedPatternWindowHours =
                    repeatedPatternWindowHours < 1
                            ? FuelPolicy.DEFAULT_REPEATED_PATTERN_WINDOW_HOURS
                            : repeatedPatternWindowHours;
            repeatedPatternThreshold =
                    repeatedPatternThreshold < 1
                            ? FuelPolicy.DEFAULT_REPEATED_PATTERN_THRESHOLD
                            : repeatedPatternThreshold;
        }

        public CreatePolicy(
                String siteCode,
                String name,
                Instant effectiveFrom,
                Instant effectiveTo,
                int policyVersion,
                BigDecimal maxPerTransaction,
                BigDecimal dailyLimit,
                BigDecimal monthlyLimit,
                BigDecimal tankCapacity,
                BigDecimal minConsumption,
                BigDecimal maxConsumption,
                long odometerJumpTolerance,
                boolean receiptRequired,
                int receiptGraceHours,
                BigDecimal materialityAmount,
                int anomalySlaHours,
                Set<String> allowedFuelProducts,
                Set<String> approvedVendors,
                ActorContext actor,
                SourceChannel channel) {
            this(
                    siteCode,
                    name,
                    effectiveFrom,
                    effectiveTo,
                    policyVersion,
                    maxPerTransaction,
                    dailyLimit,
                    monthlyLimit,
                    tankCapacity,
                    minConsumption,
                    maxConsumption,
                    odometerJumpTolerance,
                    receiptRequired,
                    receiptGraceHours,
                    materialityAmount,
                    anomalySlaHours,
                    FuelPolicy.DEFAULT_COST_VARIANCE_TOLERANCE,
                    FuelPolicy.DEFAULT_REPEATED_PATTERN_WINDOW_HOURS,
                    FuelPolicy.DEFAULT_REPEATED_PATTERN_THRESHOLD,
                    allowedFuelProducts,
                    approvedVendors,
                    actor,
                    channel);
        }
    }

    public record CaptureFuel(
            String siteCode,
            String providerTransactionId,
            String sourceSystem,
            UUID vehicleId,
            UUID driverId,
            UUID tripId,
            Instant occurredAt,
            String vendorReference,
            String stationReference,
            String fuelProduct,
            BigDecimal quantity,
            String quantityUnit,
            BigDecimal unitPrice,
            BigDecimal totalCost,
            String currency,
            String cardReference,
            long odometerReading,
            UUID receiptEvidenceId,
            UUID pumpEvidenceId,
            String comments,
            String idempotencyKey,
            ActorContext actor,
            SourceChannel channel) {}

    /** Record a forecourt price, closing whatever open price it supersedes. */
    public record RecordPostedPrice(
            String siteCode,
            String vendor,
            String fuelProduct,
            BigDecimal unitPrice,
            String currency,
            Instant effectiveFrom,
            FuelPostedPrice.Source source,
            String notes,
            ActorContext actor,
            SourceChannel channel) {}

    public record CreateLogbook(
            String siteCode,
            UUID driverId,
            UUID vehicleId,
            UUID tripId,
            LocalDate journeyDate,
            Instant startTime,
            Instant endTime,
            String origin,
            String destination,
            String routeNotes,
            DriverLogbook.UseClassification useClassification,
            String purpose,
            String passengerLoadNotes,
            long startOdometer,
            Long endOdometer,
            boolean declarationAccepted,
            UUID evidenceId,
            ActorContext actor,
            SourceChannel channel) {}

    public record UpdateLogbook(
            UUID driverId,
            UUID vehicleId,
            UUID tripId,
            LocalDate journeyDate,
            Instant startTime,
            Instant endTime,
            String origin,
            String destination,
            String routeNotes,
            DriverLogbook.UseClassification useClassification,
            String purpose,
            String passengerLoadNotes,
            long startOdometer,
            Long endOdometer,
            boolean declarationAccepted,
            UUID evidenceId,
            ActorContext actor,
            SourceChannel channel) {}

    /**
     * Creates an effective-dated policy, refusing one that overlaps an active policy for the site.
     *
     * <p>The domain model documented "no overlapping active policy for the same scope" as an
     * invariant and nothing enforced it - not the record, which cannot see its siblings, and not
     * the database. With two active policies covering one instant, {@code findApplicablePolicy}
     * returns whichever row the ordering surfaces, so the rules a transaction is judged against and
     * the policy version stamped on its reconciliation stop being reproducible. That defeats the
     * point of an effective-dated policy, so the overlap is refused here, inside the same
     * transaction that writes the record.
     */
    /**
     * The fields of {@link CreatePolicy}, against a policy that already exists.
     *
     * <p>Deliberately the whole set rather than a patch of changed fields: a partial update of a
     * rule set means the caller and the service have to agree on what "absent" means for a limit
     * that is legitimately null, and getting that wrong silently removes a ceiling.
     */
    public record UpdatePolicy(
            UUID policyId,
            String name,
            Instant effectiveFrom,
            Instant effectiveTo,
            int policyVersion,
            BigDecimal maxPerTransaction,
            BigDecimal dailyLimit,
            BigDecimal monthlyLimit,
            BigDecimal tankCapacity,
            BigDecimal minConsumption,
            BigDecimal maxConsumption,
            long odometerJumpTolerance,
            boolean receiptRequired,
            int receiptGraceHours,
            BigDecimal materialityAmount,
            int anomalySlaHours,
            BigDecimal costVarianceTolerance,
            int repeatedPatternWindowHours,
            int repeatedPatternThreshold,
            Set<String> allowedFuelProducts,
            Set<String> approvedVendors,
            ActorContext actor,
            SourceChannel channel) {
        public UpdatePolicy {
            costVarianceTolerance =
                    costVarianceTolerance == null
                            ? FuelPolicy.DEFAULT_COST_VARIANCE_TOLERANCE
                            : costVarianceTolerance;
            repeatedPatternWindowHours =
                    repeatedPatternWindowHours < 1
                            ? FuelPolicy.DEFAULT_REPEATED_PATTERN_WINDOW_HOURS
                            : repeatedPatternWindowHours;
            repeatedPatternThreshold =
                    repeatedPatternThreshold < 1
                            ? FuelPolicy.DEFAULT_REPEATED_PATTERN_THRESHOLD
                            : repeatedPatternThreshold;
        }
    }

    @Transactional
    public FuelPolicy createPolicy(CreatePolicy c) {
        access.require(
                c.actor(), SflPermission.FUEL_POLICY_MANAGE, c.siteCode(), "FuelPolicy", null);
        requireNoOverlap(SiteCode.of(c.siteCode()).value(), c.effectiveFrom(), c.effectiveTo());
        return persistPolicy(c);
    }

    /**
     * Revises a policy in place.
     *
     * <h2>Why this edits rather than superseding</h2>
     *
     * <p>Reconciliation stamps every run with the policy id <em>and</em> the {@code policyVersion}
     * it applied, so a past judgement stays readable as the rules that produced it whatever the
     * record says now. That is what makes editing safe: the history is not derived from the current
     * row. The alternative - forcing a new policy for every correction - fills the register with
     * versions that differ by a typo in a name, and the period-overlap rule then refuses most of
     * them.
     *
     * <p>Which is safe only as far as the version keeps its meaning, and that is now enforced
     * rather than hoped for. A run records the policy id and version and <em>nothing else about the
     * rules</em>, so changing a limit while reusing the version makes that pair describe two
     * different rule sets - the first draft of this method allowed exactly that, and the edit form
     * prefills the current version, so it was the default path rather than an edge case. A revision
     * that changes an outcome-bearing field must carry a new version; renaming, or correcting a
     * date, still does not.
     *
     * <p>The overlap check runs again, excluding this policy, because widening a period is exactly
     * how an edit collides with a neighbour.
     */
    @Transactional
    public FuelPolicy updatePolicy(UpdatePolicy c) {
        var existing = policyRecord(c.policyId());
        access.require(
                c.actor(),
                SflPermission.FUEL_POLICY_MANAGE,
                existing.siteCode().value(),
                "FuelPolicy",
                existing.id().toString());
        requireNoOverlapExcluding(
                existing.siteCode().value(), c.effectiveFrom(), c.effectiveTo(), existing.id());
        Instant now = clock.instant();
        var revised =
                new FuelPolicy(
                        existing.id(),
                        existing.siteCode(),
                        c.name(),
                        c.effectiveFrom(),
                        c.effectiveTo(),
                        c.policyVersion(),
                        c.maxPerTransaction(),
                        c.dailyLimit(),
                        c.monthlyLimit(),
                        c.tankCapacity(),
                        c.minConsumption(),
                        c.maxConsumption(),
                        c.odometerJumpTolerance(),
                        c.receiptRequired(),
                        c.receiptGraceHours(),
                        c.materialityAmount(),
                        c.anomalySlaHours(),
                        c.costVarianceTolerance(),
                        c.repeatedPatternWindowHours(),
                        c.repeatedPatternThreshold(),
                        c.allowedFuelProducts(),
                        c.approvedVendors(),
                        existing.status(),
                        existing.metadata()
                                .modifiedBy(
                                        c.actor().actorId(),
                                        now,
                                        c.channel(),
                                        c.actor().correlationId()));
        // Compared after construction so the record's own normalisation - upper-cased vendor and
        // product sets, the cost-variance default - is applied to both sides. Comparing the raw
        // command against a stored record would report "goil" and "GOIL" as a change.
        if (!revised.hasSameRulesAs(existing)
                && revised.policyVersion() == existing.policyVersion()) {
            throw FuelPolicyVersionNotAdvancedException.of(existing.id(), existing.policyVersion());
        }
        var saved = repository.savePolicy(revised);
        audit.record(
                c.actor(),
                c.channel(),
                saved.siteCode(),
                AuditAction.UPDATE,
                "FuelPolicy",
                saved.id().toString(),
                existing,
                saved);
        return saved;
    }

    /**
     * Withdraws a policy, which is what deleting one has to mean here.
     *
     * <p>A policy is cited by every reconciliation run it judged. Removing the row would leave
     * those runs pointing at nothing - the audit trail would still say a transaction was judged
     * under policy X and there would be no X to read - so a fuel register that can be tidied into
     * incoherence is worse than one that cannot be tidied at all.
     *
     * <p>{@code ARCHIVED} is the answer already in the model: {@link FuelPolicy#appliesAt} requires
     * {@code ACTIVE}, so an archived policy stops applying to anything new the moment it is
     * written, while staying readable for everything it has already decided. It also frees the
     * period, so the replacement can cover the same dates without tripping the overlap rule.
     */
    @Transactional
    public FuelPolicy withdrawPolicy(
            UUID id, String reason, ActorContext actor, SourceChannel channel) {
        var existing = policyRecord(id);
        access.require(
                actor,
                SflPermission.FUEL_POLICY_MANAGE,
                existing.siteCode().value(),
                "FuelPolicy",
                existing.id().toString());
        if (existing.status() == FuelPolicy.Status.ARCHIVED) return existing;
        Instant now = clock.instant();
        var withdrawn =
                new FuelPolicy(
                        existing.id(),
                        existing.siteCode(),
                        existing.name(),
                        existing.effectiveFrom(),
                        existing.effectiveTo(),
                        existing.policyVersion(),
                        existing.maxPerTransaction(),
                        existing.dailyLimit(),
                        existing.monthlyLimit(),
                        existing.tankCapacity(),
                        existing.minConsumption(),
                        existing.maxConsumption(),
                        existing.odometerJumpTolerance(),
                        existing.receiptRequired(),
                        existing.receiptGraceHours(),
                        existing.materialityAmount(),
                        existing.anomalySlaHours(),
                        existing.costVarianceTolerance(),
                        existing.repeatedPatternWindowHours(),
                        existing.repeatedPatternThreshold(),
                        existing.allowedFuelProducts(),
                        existing.approvedVendors(),
                        FuelPolicy.Status.ARCHIVED,
                        existing.metadata()
                                .modifiedBy(actor.actorId(), now, channel, actor.correlationId()));
        var saved = repository.savePolicy(withdrawn);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("policy", saved);
        // The reason is the point of the record: an archived policy with no explanation tells a
        // reviewer that somebody withdrew it and nothing about why.
        after.put("reason", reason);
        // STATE_TRANSITION rather than a delete action: the status moved ACTIVE -> ARCHIVED, which
        // is
        // exactly what happened and exactly what the enum already has a name for.
        audit.record(
                actor,
                channel,
                saved.siteCode(),
                AuditAction.STATE_TRANSITION,
                "FuelPolicy",
                saved.id().toString(),
                existing,
                after);
        return saved;
    }

    private FuelPolicy policyRecord(UUID id) {
        return repository
                .findPolicy(id)
                .orElseThrow(() -> RecordNotFoundException.of("FuelPolicy", id));
    }

    private void requireNoOverlap(String site, Instant from, Instant to) {
        requireNoOverlapExcluding(site, from, to, null);
    }

    private void requireNoOverlapExcluding(String site, Instant from, Instant to, UUID excluding) {
        var clashes = repository.findOverlappingActivePolicies(site, from, to, excluding);
        if (clashes.isEmpty()) return;
        throw FuelPolicyPeriodOverlapException.of(
                site,
                from,
                to,
                clashes.stream()
                        .map(
                                p ->
                                        new FuelPolicyPeriodOverlapException.Conflict(
                                                p.id(),
                                                p.name(),
                                                p.policyVersion(),
                                                p.effectiveFrom(),
                                                p.effectiveTo()))
                        .toList());
    }

    private FuelPolicy persistPolicy(CreatePolicy c) {
        Instant now = clock.instant();
        var p =
                new FuelPolicy(
                        UUID.randomUUID(),
                        SiteCode.of(c.siteCode()),
                        c.name(),
                        c.effectiveFrom(),
                        c.effectiveTo(),
                        c.policyVersion(),
                        c.maxPerTransaction(),
                        c.dailyLimit(),
                        c.monthlyLimit(),
                        c.tankCapacity(),
                        c.minConsumption(),
                        c.maxConsumption(),
                        c.odometerJumpTolerance(),
                        c.receiptRequired(),
                        c.receiptGraceHours(),
                        c.materialityAmount(),
                        c.anomalySlaHours(),
                        c.costVarianceTolerance(),
                        c.repeatedPatternWindowHours(),
                        c.repeatedPatternThreshold(),
                        c.allowedFuelProducts(),
                        c.approvedVendors(),
                        FuelPolicy.Status.ACTIVE,
                        RecordMetadata.createdBy(
                                c.actor().actorId(), now, c.channel(), c.actor().correlationId()));
        var saved = repository.savePolicy(p);
        audit.record(
                c.actor(),
                c.channel(),
                saved.siteCode(),
                AuditAction.CREATE,
                "FuelPolicy",
                saved.id().toString(),
                null,
                saved);
        return saved;
    }

    @Transactional
    public FuelTransaction capture(CaptureFuel c) {
        SflPermission capturePermission =
                c.sourceSystem().equalsIgnoreCase("MANUAL")
                        ? SflPermission.FUEL_TRANSACTION_CAPTURE
                        : access.has(c.actor(), SflPermission.FUEL_TRANSACTION_IMPORT)
                                ? SflPermission.FUEL_TRANSACTION_IMPORT
                                : SflPermission.FUEL_INTEGRATION_INGEST;
        access.require(c.actor(), capturePermission, c.siteCode(), "FuelTransaction", null);
        String fp = idempotency.fingerprint(c);
        Optional<UUID> replay =
                idempotency.findExistingResult("capture-fuel", c.idempotencyKey(), fp);
        if (replay.isPresent()) return transaction(replay.get(), c.actor());
        var duplicate =
                repository.findProviderTransaction(
                        c.siteCode(), c.sourceSystem(), c.providerTransactionId());
        if (duplicate.isPresent()) return duplicate.get();
        fleet.resolve(c.vehicleId(), c.driverId(), c.tripId(), SiteCode.of(c.siteCode()).value());
        Instant now = clock.instant();
        var tx =
                new FuelTransaction(
                        UUID.randomUUID(),
                        SiteCode.of(c.siteCode()),
                        c.providerTransactionId(),
                        c.sourceSystem(),
                        c.vehicleId(),
                        c.driverId(),
                        c.tripId(),
                        c.occurredAt(),
                        c.vendorReference(),
                        c.stationReference(),
                        c.fuelProduct(),
                        c.quantity(),
                        c.quantityUnit(),
                        c.unitPrice(),
                        c.totalCost(),
                        Currency.getInstance(c.currency().toUpperCase()),
                        c.cardReference(),
                        c.odometerReading(),
                        c.receiptEvidenceId(),
                        c.pumpEvidenceId(),
                        c.comments(),
                        FuelTransaction.Status.RECEIVED,
                        FuelTransaction.Lifecycle.ACTIVE,
                        now,
                        c.idempotencyKey(),
                        RecordMetadata.createdBy(
                                c.actor().actorId(), now, c.channel(), c.actor().correlationId()));
        var saved = repository.saveTransaction(tx);
        audit.record(
                c.actor(),
                c.channel(),
                saved.siteCode(),
                AuditAction.CREATE,
                "FuelTransaction",
                saved.id().toString(),
                null,
                saved);
        events.publish(
                FleetEventType.FUEL_TRANSACTION_RECEIVED,
                "FuelTransaction",
                saved.id().toString(),
                saved.siteCode(),
                c.actor(),
                Map.of(
                        "transactionId",
                        saved.id(),
                        "vehicleId",
                        saved.vehicleId(),
                        "driverId",
                        saved.driverId(),
                        "quantity",
                        saved.quantity(),
                        "currency",
                        saved.currency().getCurrencyCode()));
        idempotency.recordResult(
                "capture-fuel",
                c.idempotencyKey(),
                fp,
                saved.id(),
                saved.siteCode().value(),
                c.actor().actorId());
        return saved;
    }

    @Transactional
    public FuelTransaction reconcile(UUID id, ActorContext actor, SourceChannel channel) {
        var before = transaction(id, actor);
        access.require(
                actor,
                SflPermission.FUEL_RECONCILIATION_RUN,
                before.siteCode().value(),
                "FuelTransaction",
                id.toString());
        var policy =
                repository
                        .findApplicablePolicy(before.siteCode().value(), before.occurredAt())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No active fuel policy applies to this transaction"));
        var snapshot =
                fleet.resolve(
                        before.vehicleId(),
                        before.driverId(),
                        before.tripId(),
                        before.siteCode().value());
        Map<String, Object> rules = new LinkedHashMap<>();
        List<FuelAnomalyCase.Type> failures = new ArrayList<>();
        check(
                rules,
                failures,
                "MAX_PER_TRANSACTION",
                before.quantity().compareTo(policy.maxPerTransaction()) <= 0,
                FuelAnomalyCase.Type.LIMIT_EXCEEDED,
                Map.of("threshold", policy.maxPerTransaction(), "observed", before.quantity()));
        if (policy.tankCapacity() != null)
            check(
                    rules,
                    failures,
                    "TANK_CAPACITY",
                    before.quantity().compareTo(policy.tankCapacity()) <= 0,
                    FuelAnomalyCase.Type.TANK_CAPACITY,
                    Map.of("threshold", policy.tankCapacity(), "observed", before.quantity()));
        check(
                rules,
                failures,
                "FUEL_PRODUCT",
                policy.allowsProduct(before.fuelProduct()),
                FuelAnomalyCase.Type.FUEL_PRODUCT);
        check(
                rules,
                failures,
                "APPROVED_VENDOR",
                policy.allowsVendor(before.vendorReference()),
                FuelAnomalyCase.Type.VENDOR);
        checkPolicyRollingLimits(rules, failures, before, policy);
        /*
         * SRS-SFL-S168fuel-04. masked_card_reference was captured from V10 and meant nothing until
         * there was a register to resolve it against. A card is only checked when the provider sent
         * a reference; a cash purchase legitimately has none.
         */
        if (before.maskedCardReference() != null && !before.maskedCardReference().isBlank()) {
            var card =
                    repository.findLiveCardByReference(
                            before.siteCode().value(), before.maskedCardReference());
            check(
                    rules,
                    failures,
                    "CARD_KNOWN",
                    card.isPresent()
                            && card.get()
                                    .usableOn(
                                            before.occurredAt()
                                                    .atZone(java.time.ZoneOffset.UTC)
                                                    .toLocalDate()),
                    FuelAnomalyCase.Type.CARD_UNKNOWN);
            if (card.isPresent()) {
                var liveCard = card.get();
                check(
                        rules,
                        failures,
                        "CARD_VEHICLE_MATCH",
                        !liveCard.mismatchesVehicle(before.vehicleId()),
                        FuelAnomalyCase.Type.CARD_VEHICLE_MISMATCH);
                if (liveCard.perTransactionLimit() != null)
                    check(
                            rules,
                            failures,
                            "CARD_TRANSACTION_LIMIT",
                            before.totalCost() == null
                                    || before.totalCost().compareTo(liveCard.perTransactionLimit())
                                            <= 0,
                            FuelAnomalyCase.Type.CARD_LIMIT_EXCEEDED,
                            Map.of(
                                    "threshold",
                                    liveCard.perTransactionLimit(),
                                    "observed",
                                    before.totalCost()));
                checkCardRollingLimits(rules, failures, before, policy, liveCard);
            }
        }
        check(
                rules,
                failures,
                "DRIVER_ELIGIBLE",
                // CONDITIONAL is a driver who may still be assigned (a warning, such as a licence
                // expiring soon) - the same line trip assignment draws, so fuel does not disagree.
                "ELIGIBLE".equals(snapshot.driverEligibility())
                        || "CONDITIONAL".equals(snapshot.driverEligibility()),
                FuelAnomalyCase.Type.DRIVER_INELIGIBLE);
        check(
                rules,
                failures,
                "VEHICLE_OPERATIONAL",
                "ACTIVE".equals(snapshot.vehicleLifecycle())
                        && !"UNAVAILABLE".equals(snapshot.vehicleAvailability()),
                FuelAnomalyCase.Type.VEHICLE_UNAVAILABLE);
        check(
                rules,
                failures,
                "TRIP_MATCH",
                snapshot.tripMatches(),
                FuelAnomalyCase.Type.OUTSIDE_TRIP);
        /*
         * The odometer baseline. For a new purchase it is the vehicle's accepted reading. For one that
         * is history - being checked again, or with later purchases already recorded - the vehicle has
         * since driven on, so comparing against today's reading would always "go backwards". History is
         * compared with the purchase before it instead, and the jump check (which measures distance
         * since the last accepted reading) only applies to a new purchase.
         */
        var previous =
                repository.findPreviousTransaction(
                        before.siteCode().value(), before.vehicleId(), before.occurredAt());
        boolean historical =
                before.status() == FuelTransaction.Status.RECONCILED
                        || before.status() == FuelTransaction.Status.EXCEPTION
                        || repository.hasLaterTransaction(
                                before.siteCode().value(),
                                before.vehicleId(),
                                before.occurredAt(),
                                before.id());
        long odometerBaseline =
                historical
                        ? previous.map(FuelTransaction::odometerReading)
                                .orElse(
                                        Math.min(
                                                snapshot.acceptedOdometer(),
                                                before.odometerReading()))
                        : snapshot.acceptedOdometer();
        check(
                rules,
                failures,
                "ODOMETER_NON_REGRESSION",
                before.odometerReading() >= odometerBaseline,
                FuelAnomalyCase.Type.ODOMETER_REGRESSION);
        if (!historical)
            check(
                    rules,
                    failures,
                    "ODOMETER_JUMP",
                    before.odometerReading() - odometerBaseline <= policy.odometerJumpTolerance(),
                    FuelAnomalyCase.Type.ODOMETER_JUMP,
                    Map.of(
                            "threshold",
                            policy.odometerJumpTolerance(),
                            "observed",
                            before.odometerReading() - odometerBaseline));
        boolean withinGrace =
                before.occurredAt()
                        .plusSeconds(policy.receiptGraceHours() * 3600L)
                        .isAfter(clock.instant());
        boolean receiptOk =
                !policy.receiptRequired() || before.receiptEvidenceId() != null || withinGrace;
        check(
                rules,
                failures,
                "RECEIPT",
                receiptOk,
                FuelAnomalyCase.Type.MISSING_RECEIPT,
                Map.of(
                        "receiptRequired",
                        policy.receiptRequired(),
                        "graceHours",
                        policy.receiptGraceHours()));
        checkPumpImage(rules, failures, before, policy, withinGrace);
        checkPostedPrice(rules, failures, before, policy);
        checkEvidenceNotReused(rules, failures, before);
        BigDecimal consumption = null;
        if (previous.isPresent()) {
            long km = before.odometerReading() - previous.get().odometerReading();
            if (km > 0) {
                consumption =
                        before.quantity().divide(BigDecimal.valueOf(km), 4, RoundingMode.HALF_UP);
                if (policy.minConsumption() != null && policy.maxConsumption() != null)
                    check(
                            rules,
                            failures,
                            "CONSUMPTION_RANGE",
                            consumption.compareTo(policy.minConsumption()) >= 0
                                    && consumption.compareTo(policy.maxConsumption()) <= 0,
                            FuelAnomalyCase.Type.ABNORMAL_CONSUMPTION,
                            Map.of(
                                    "min",
                                    policy.minConsumption(),
                                    "max",
                                    policy.maxConsumption(),
                                    "observed",
                                    consumption));
            }
            if (previous.get().unitPrice().signum() > 0) {
                BigDecimal variance =
                        before.unitPrice()
                                .subtract(previous.get().unitPrice())
                                .abs()
                                .divide(previous.get().unitPrice(), 4, RoundingMode.HALF_UP);
                check(
                        rules,
                        failures,
                        "COST_VARIANCE",
                        variance.compareTo(policy.costVarianceTolerance()) <= 0,
                        FuelAnomalyCase.Type.COST_VARIANCE,
                        Map.of(
                                "threshold",
                                policy.costVarianceTolerance(),
                                "observed",
                                variance,
                                "policyVersion",
                                policy.policyVersion()));
            }
        }
        if (before.tripId() != null) {
            var logbook = repository.findLogbookForTrip(before.tripId());
            if (logbook.isPresent() && logbook.get().endOdometer() != null)
                check(
                        rules,
                        failures,
                        "LOGBOOK_MATCH",
                        Math.abs(before.odometerReading() - logbook.get().endOdometer())
                                <= policy.odometerJumpTolerance(),
                        FuelAnomalyCase.Type.LOGBOOK_MISMATCH);
        }
        long recentAnomalies =
                repository.countRecentAnomalies(
                        List.of(before.siteCode().value()),
                        before.vehicleId(),
                        before.driverId(),
                        clock.instant().minusSeconds(policy.repeatedPatternWindowHours() * 3600L));
        check(
                rules,
                failures,
                "REPEATED_PATTERN",
                recentAnomalies < policy.repeatedPatternThreshold(),
                FuelAnomalyCase.Type.UNUSUAL_PATTERN,
                Map.of(
                        "threshold",
                        policy.repeatedPatternThreshold(),
                        "observed",
                        recentAnomalies,
                        "windowHours",
                        policy.repeatedPatternWindowHours(),
                        "policyVersion",
                        policy.policyVersion()));
        Instant now = clock.instant();
        boolean passed = failures.isEmpty();
        var after =
                before.withStatus(
                        passed
                                ? FuelTransaction.Status.RECONCILED
                                : FuelTransaction.Status.EXCEPTION,
                        before.metadata()
                                .modifiedBy(actor.actorId(), now, channel, actor.correlationId()));
        after = repository.saveTransaction(after);
        repository.saveReconciliation(
                UUID.randomUUID(),
                id,
                policy.id(),
                policy.policyVersion(),
                after.status().name(),
                consumption,
                now,
                actor.actorId(),
                rules,
                actor.correlationId());
        if (before.odometerReading() >= snapshot.acceptedOdometer()
                && before.odometerReading() - snapshot.acceptedOdometer()
                        <= policy.odometerJumpTolerance())
            fleet.acceptOdometer(
                    before.vehicleId(),
                    before.odometerReading(),
                    before.occurredAt(),
                    actor,
                    channel);
        for (var failure : failures)
            createAnomaly(after, policy, failure, List.of(failure.name()), actor, channel);
        audit.record(
                actor,
                channel,
                after.siteCode(),
                AuditAction.STATE_TRANSITION,
                "FuelTransaction",
                id.toString(),
                before,
                after);
        events.publish(
                passed
                        ? FleetEventType.FUEL_TRANSACTION_RECONCILED
                        : FleetEventType.FUEL_EXCEPTION_DETECTED,
                "FuelTransaction",
                id.toString(),
                after.siteCode(),
                actor,
                rules);
        return after;
    }

    @Transactional
    public DriverLogbook createLogbook(CreateLogbook c) {
        access.require(
                c.actor(), SflPermission.FUEL_LOGBOOK_CREATE, c.siteCode(), "DriverLogbook", null);
        var refs =
                fleet.resolve(
                        c.vehicleId(), c.driverId(), c.tripId(), SiteCode.of(c.siteCode()).value());
        // A driver records their own journeys: matched on the driver profile their sign-in is bound
        // to, never on the staff number, which is not what an identity provider issues.
        requireOwnFuelRecord(c.actor(), c.driverId(), c.siteCode(), "DriverLogbook", null);
        Instant now = clock.instant();
        var l =
                new DriverLogbook(
                        UUID.randomUUID(),
                        number("LOG"),
                        SiteCode.of(c.siteCode()),
                        c.driverId(),
                        c.vehicleId(),
                        c.tripId(),
                        c.journeyDate(),
                        c.startTime(),
                        c.endTime(),
                        c.origin(),
                        c.destination(),
                        c.routeNotes(),
                        c.useClassification(),
                        c.purpose(),
                        c.passengerLoadNotes(),
                        c.startOdometer(),
                        c.endOdometer(),
                        c.declarationAccepted(),
                        c.evidenceId(),
                        DriverLogbook.Status.DRAFT,
                        null,
                        null,
                        null,
                        null,
                        RecordMetadata.createdBy(
                                c.actor().actorId(), now, c.channel(), c.actor().correlationId()));
        var saved = repository.saveLogbook(l);
        audit.record(
                c.actor(),
                c.channel(),
                saved.siteCode(),
                AuditAction.CREATE,
                "DriverLogbook",
                saved.id().toString(),
                null,
                saved);
        return saved;
    }

    @Transactional
    public DriverLogbook updateLogbook(UUID id, UpdateLogbook c) {
        var before = logbook(id, c.actor());
        access.require(
                c.actor(),
                SflPermission.FUEL_LOGBOOK_CREATE,
                before.siteCode().value(),
                "DriverLogbook",
                id.toString());
        var refs =
                fleet.resolve(c.vehicleId(), c.driverId(), c.tripId(), before.siteCode().value());
        requireOwnFuelRecord(
                c.actor(),
                before.driverId(),
                before.siteCode().value(),
                "DriverLogbook",
                id.toString());
        requireOwnFuelRecord(
                c.actor(), c.driverId(), before.siteCode().value(), "DriverLogbook", id.toString());
        var after =
                before.amend(
                        c.driverId(),
                        c.vehicleId(),
                        c.tripId(),
                        c.journeyDate(),
                        c.startTime(),
                        c.endTime(),
                        c.origin(),
                        c.destination(),
                        c.routeNotes(),
                        c.useClassification(),
                        c.purpose(),
                        c.passengerLoadNotes(),
                        c.startOdometer(),
                        c.endOdometer(),
                        c.declarationAccepted(),
                        c.evidenceId(),
                        before.metadata()
                                .modifiedBy(
                                        c.actor().actorId(),
                                        clock.instant(),
                                        c.channel(),
                                        c.actor().correlationId()));
        after = repository.saveLogbook(after);
        audit.record(
                c.actor(),
                c.channel(),
                after.siteCode(),
                AuditAction.UPDATE,
                "DriverLogbook",
                id.toString(),
                before,
                after);
        return after;
    }

    @Transactional
    public DriverLogbook transitionLogbook(
            UUID id, String action, String comment, ActorContext actor, SourceChannel channel) {
        var before = logbook(id, actor);
        SflPermission permission =
                switch (action) {
                    case "submit" -> SflPermission.FUEL_LOGBOOK_SUBMIT;
                    case "reopen" -> SflPermission.FUEL_LOGBOOK_REOPEN;
                    default -> SflPermission.FUEL_LOGBOOK_REVIEW;
                };
        access.require(
                actor, permission, before.siteCode().value(), "DriverLogbook", id.toString());
        requireOwnFuelRecord(
                actor,
                before.driverId(),
                before.siteCode().value(),
                "DriverLogbook",
                id.toString());
        if (action.equals("review") || action.equals("approve") || action.equals("return"))
            requireIndependentReviewer(before, actor);
        var meta =
                before.metadata()
                        .modifiedBy(
                                actor.actorId(), clock.instant(), channel, actor.correlationId());
        var after =
                switch (action) {
                    case "submit" -> before.submit(clock.instant(), meta);
                    case "review" -> before.startReview(meta);
                    case "return" -> before.returned(comment, meta);
                    case "approve" -> before.approved(clock.instant(), comment, meta);
                    case "reopen" -> before.reopened(comment, meta);
                    case "cancel" -> before.cancelled(comment, meta);
                    default -> throw new IllegalArgumentException("Unknown logbook transition");
                };
        after = repository.saveLogbook(after);
        audit.record(
                actor,
                channel,
                after.siteCode(),
                AuditAction.STATE_TRANSITION,
                "DriverLogbook",
                id.toString(),
                before,
                after);
        FleetEventType event =
                switch (action) {
                    case "submit" -> FleetEventType.DRIVER_LOGBOOK_SUBMITTED;
                    case "return" -> FleetEventType.DRIVER_LOGBOOK_RETURNED;
                    case "approve" -> FleetEventType.DRIVER_LOGBOOK_APPROVED;
                    default -> null;
                };
        if (event != null)
            events.publish(
                    event,
                    "DriverLogbook",
                    id.toString(),
                    after.siteCode(),
                    actor,
                    Map.of("logbookId", id, "status", after.status()));
        notifyLogbook(action, after);
        return after;
    }

    /**
     * Separation of duties: a logbook is reviewed by someone other than the person who wrote it or
     * the driver it belongs to. A fleet manager who also drives cannot sign off their own mileage.
     */
    private void requireIndependentReviewer(DriverLogbook logbook, ActorContext actor) {
        boolean wroteIt = actor.actorId().equals(logbook.metadata().createdBy());
        boolean drivesIt =
                driverScopes.resolve(actor, true) instanceof DriverScope.Own own
                        && own.driverId().equals(logbook.driverId());
        if (wroteIt || drivesIt)
            throw new IllegalArgumentException(
                    "You cannot review or approve a logbook you recorded or that is for your own"
                            + " journeys. Ask another fleet manager to review it.");
    }

    private void notifyLogbook(String action, DriverLogbook l) {
        Map<String, String> ctx = logbookContext(l);
        switch (action) {
            case "submit" -> notifications.notifyRole(
                    l.siteCode(),
                    SflRole.FLEET_MANAGER,
                    NotificationKind.WORK_ASSIGNED,
                    l.logbookNumber(),
                    ctx);
            case "return" -> notifications.notifyAssignee(
                    l.siteCode(),
                    l.metadata().createdBy(),
                    NotificationKind.WORK_BLOCKED,
                    l.logbookNumber(),
                    ctx);
            case "approve" -> notifications.notifyAssignee(
                    l.siteCode(),
                    l.metadata().createdBy(),
                    NotificationKind.WORK_ASSIGNED,
                    l.logbookNumber(),
                    ctx);
            default -> {}
        }
    }

    private static Map<String, String> logbookContext(DriverLogbook l) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("logbookNumber", l.logbookNumber());
        m.put("status", l.status().name());
        if (l.tripId() != null) m.put("tripId", l.tripId().toString());
        if (l.driverId() != null) m.put("driverId", l.driverId().toString());
        if (l.metadata().auditCorrelationId() != null)
            m.put("correlationId", l.metadata().auditCorrelationId());
        return m;
    }

    @Transactional
    public FuelAnomalyCase transitionAnomaly(
            UUID id,
            String action,
            String value,
            UUID evidence,
            ActorContext actor,
            SourceChannel channel) {
        var before = anomaly(id, actor);
        SflPermission p =
                Set.of("approve", "reject", "close").contains(action)
                        ? SflPermission.FUEL_ANOMALY_APPROVE
                        : action.equals("escalate")
                                ? SflPermission.FUEL_ANOMALY_ESCALATE
                                : SflPermission.FUEL_ANOMALY_MANAGE;
        access.require(actor, p, before.siteCode().value(), "FuelAnomalyCase", id.toString());
        var meta =
                before.metadata()
                        .modifiedBy(
                                actor.actorId(), clock.instant(), channel, actor.correlationId());
        var after =
                switch (action) {
                    case "assign" -> before.assign(value, meta);
                    case "reassign" -> before.reassign(value, meta);
                    case "review" -> before.review(meta);
                    case "request-explanation" -> before.requestExplanation(meta);
                    case "explain" -> before.explain(value, evidence, meta);
                    case "approve" -> before.decide(FuelAnomalyCase.Decision.APPROVED, value, meta);
                    case "reject" -> before.decide(FuelAnomalyCase.Decision.REJECTED, value, meta);
                    case "escalate" -> before.escalate(value, meta);
                    case "hold" -> before.hold(value, meta);
                    case "resume" -> before.resume(meta);
                    case "cancel" -> before.cancel(value, meta);
                    case "close" -> before.close(value, evidence, meta);
                    case "reopen" -> before.reopen(value, meta);
                    default -> throw new IllegalArgumentException("Unknown anomaly transition");
                };
        after = repository.saveAnomaly(after);
        audit.record(
                actor,
                channel,
                after.siteCode(),
                anomalyAuditAction(action),
                "FuelAnomalyCase",
                id.toString(),
                before,
                after);
        notifyAnomaly(action, after);
        FleetEventType event =
                switch (action) {
                    case "assign", "reassign" -> FleetEventType.FUEL_ANOMALY_ASSIGNED;
                    case "approve" -> FleetEventType.FUEL_ANOMALY_APPROVED;
                    case "reject" -> FleetEventType.FUEL_ANOMALY_REJECTED;
                    case "escalate" -> FleetEventType.FUEL_ANOMALY_ESCALATED;
                    default -> null;
                };
        if (event != null)
            events.publish(
                    event,
                    "FuelAnomalyCase",
                    id.toString(),
                    after.siteCode(),
                    actor,
                    Map.of("anomalyId", id, "status", after.status()));
        if (action.equals("escalate") && after.material())
            financeAudit.surfaceMaterialException(after, actor);
        return after;
    }

    private static AuditAction anomalyAuditAction(String action) {
        return switch (action) {
            case "assign" -> AuditAction.ASSIGN;
            case "reassign" -> AuditAction.REASSIGN;
            case "hold" -> AuditAction.HOLD;
            case "resume" -> AuditAction.RESUME;
            case "escalate" -> AuditAction.ESCALATE;
            case "cancel" -> AuditAction.CANCEL;
            case "close" -> AuditAction.CLOSE;
            case "reopen" -> AuditAction.REOPEN;
            default -> AuditAction.STATE_TRANSITION;
        };
    }

    private void notifyAnomaly(String action, FuelAnomalyCase a) {
        switch (action) {
            case "assign", "reassign" -> {
                if (a.assignee() != null)
                    notifications.notifyAssignee(
                            a.siteCode(),
                            a.assignee(),
                            NotificationKind.WORK_ASSIGNED,
                            a.anomalyNumber(),
                            anomalyContext(a));
            }
            case "escalate" -> notifications.notifyRole(
                    a.siteCode(),
                    SflRole.FLEET_MANAGER,
                    NotificationKind.WORK_ESCALATED,
                    a.anomalyNumber(),
                    anomalyContext(a));
            case "hold" -> {
                if (a.assignee() != null)
                    notifications.notifyAssignee(
                            a.siteCode(),
                            a.assignee(),
                            NotificationKind.WORK_BLOCKED,
                            a.anomalyNumber(),
                            anomalyContext(a));
            }
            default -> {}
        }
    }

    private static Map<String, String> anomalyContext(FuelAnomalyCase a) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("anomalyNumber", a.anomalyNumber());
        m.put("type", a.type().name());
        m.put("severity", a.severity().name());
        m.put("status", a.status().name());
        m.put("material", Boolean.toString(a.material()));
        if (a.transactionId() != null) m.put("transactionId", a.transactionId().toString());
        if (a.vehicleId() != null) m.put("vehicleId", a.vehicleId().toString());
        if (a.driverId() != null) m.put("driverId", a.driverId().toString());
        if (a.metadata().auditCorrelationId() != null)
            m.put("correlationId", a.metadata().auditCorrelationId());
        return m;
    }

    @Transactional
    public FuelTransaction voidTransaction(
            UUID id, String reason, ActorContext actor, SourceChannel channel) {
        var before = transaction(id, actor);
        access.require(
                actor,
                SflPermission.FUEL_TRANSACTION_VOID,
                before.siteCode().value(),
                "FuelTransaction",
                id.toString());
        var after =
                repository.saveTransaction(
                        before.voided(
                                reason,
                                before.metadata()
                                        .modifiedBy(
                                                actor.actorId(),
                                                clock.instant(),
                                                channel,
                                                actor.correlationId())));
        audit.record(
                actor,
                channel,
                after.siteCode(),
                AuditAction.CANCEL,
                "FuelTransaction",
                id.toString(),
                before,
                after);
        return after;
    }

    /**
     * One fuel transaction.
     *
     * <p>The per-driver check was missing here while the list had none either, so a driver holding
     * any transaction id read it: vehicle, site, litres, cost, card and vendor. Narrowed on {@code
     * driverId} - the transaction's own field - rather than on {@code createdBy} as logbooks are,
     * because these records are written by the provider feed and nobody's {@code createdBy} is a
     * driver.
     */
    public FuelTransaction transaction(UUID id, ActorContext actor) {
        var t =
                repository
                        .findTransaction(id)
                        .orElseThrow(() -> RecordNotFoundException.of("FuelTransaction", id));
        access.require(
                actor,
                SflPermission.FUEL_TRANSACTION_READ,
                t.siteCode().value(),
                "FuelTransaction",
                id.toString());
        requireOwnFuelRecord(
                actor, t.driverId(), t.siteCode().value(), "FuelTransaction", id.toString());
        return t;
    }

    /**
     * Refuses a driver-only actor a fuel record belonging to another driver.
     *
     * <p>An unbound driver is refused everything rather than allowed everything - the same
     * fail-closed choice the trip list makes. A record with no driver at all is also refused to
     * them: an unattributed fuel transaction is not theirs, and treating "no owner" as "anyone's"
     * is how the narrowing leaks in exactly the cases worth investigating.
     */
    private void requireOwnFuelRecord(
            ActorContext actor, UUID recordDriverId, String site, String resource, String id) {
        if (!access.isDriverOnly(actor)) return;
        var scope = driverScopes.resolve(actor, true);
        if (scope instanceof DriverScope.Own own && own.driverId().equals(recordDriverId)) return;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(
                "reason",
                scope instanceof DriverScope.Nothing nothing
                        ? nothing.reason()
                        : "A driver may see only their own " + resource + " records");
        details.put("siteCode", site);
        details.put("resourceType", resource);
        if (id != null && !id.isBlank()) details.put("resourceId", id);
        throw new FleetAuthorizationException(details);
    }

    public DriverLogbook logbook(UUID id, ActorContext actor) {
        var l =
                repository
                        .findLogbook(id)
                        .orElseThrow(() -> RecordNotFoundException.of("DriverLogbook", id));
        access.require(
                actor,
                SflPermission.FUEL_LOGBOOK_READ,
                l.siteCode().value(),
                "DriverLogbook",
                id.toString());
        requireOwnFuelRecord(
                actor, l.driverId(), l.siteCode().value(), "DriverLogbook", id.toString());
        return l;
    }

    public FuelAnomalyCase anomaly(UUID id, ActorContext actor) {
        var a =
                repository
                        .findAnomaly(id)
                        .orElseThrow(() -> RecordNotFoundException.of("FuelAnomalyCase", id));
        access.require(
                actor,
                SflPermission.FUEL_ANOMALY_READ,
                a.siteCode().value(),
                "FuelAnomalyCase",
                id.toString());
        return a;
    }

    /* ---------------------------------------------------------------------------- paged reads */

    /**
     * The fuel transaction list, narrowed to the actor's own when they are a driver.
     *
     * <p>It had no narrowing at all: a driver's fuel screen was every transaction at their site,
     * which is every colleague's fuel spend, card and vehicle. The driver filter is
     * <strong>overridden</strong> rather than merged with the caller's, so passing {@code
     * ?driverId=} somebody else returns your own records and not theirs.
     */
    public FuelRepository.FuelPage<FuelTransaction> transactions(
            String site,
            FuelTransaction.Status status,
            UUID vehicle,
            UUID driver,
            String source,
            String vendor,
            Instant from,
            Instant to,
            FuelRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.FUEL_TRANSACTION_READ, site, "FuelTransaction", null);
        UUID effectiveDriver = driver;
        if (access.isDriverOnly(actor)) {
            var scope = driverScopes.resolve(actor, true);
            // An unbound driver sees nothing. Answered without touching the database, because there
            // is
            // no driver id that would produce an honest empty page - null would produce every row.
            if (!(scope instanceof DriverScope.Own own))
                return new FuelRepository.FuelPage<FuelTransaction>(
                        List.of(), paging.page(), paging.size(), 0L, 0, paging.sort());
            effectiveDriver = own.driverId();
        }
        return repository.findTransactions(
                new FuelRepository.TransactionQuery(
                        List.of(SiteCode.of(site).value()),
                        site,
                        status,
                        vehicle,
                        effectiveDriver,
                        source,
                        vendor,
                        from,
                        to,
                        paging));
    }

    /**
     * A driver-only actor is restricted to their own records here, not in the dashboard.
     *
     * <p>Scoping a query by what the caller may see belongs on this side of the wire: a client-side
     * filter is a display convention, and the records would still have crossed the boundary.
     */
    public FuelRepository.FuelPage<DriverLogbook> logbooks(
            String site,
            DriverLogbook.Status status,
            UUID driver,
            UUID vehicle,
            DriverLogbook.UseClassification use,
            LocalDate journeyFrom,
            LocalDate journeyTo,
            FuelRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.FUEL_LOGBOOK_READ, site, "DriverLogbook", null);
        return repository.findLogbooks(
                new FuelRepository.LogbookQuery(
                        List.of(SiteCode.of(site).value()),
                        actor.actorId(),
                        access.isDriverOnly(actor),
                        status,
                        driver,
                        vehicle,
                        use,
                        journeyFrom,
                        journeyTo,
                        paging));
    }

    /**
     * The anomaly queue, filtered server-side.
     *
     * <p>{@code dueBefore} is what makes "breaching SLA" a query rather than a guess: the sweep
     * scheduler already used it, and until now nothing else could reach it.
     */
    public FuelRepository.FuelPage<FuelAnomalyCase> anomalies(
            String site,
            FuelAnomalyCase.Status status,
            FuelAnomalyCase.Type type,
            FuelAnomalyCase.Severity severity,
            String assignee,
            Boolean unassigned,
            Boolean material,
            Boolean openOnly,
            Instant dueBefore,
            UUID transactionId,
            FuelRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.FUEL_ANOMALY_READ, site, "FuelAnomalyCase", null);
        return repository.findAnomalies(
                new FuelRepository.AnomalyQuery(
                        List.of(SiteCode.of(site).value()),
                        status,
                        type,
                        severity,
                        assignee,
                        unassigned,
                        material,
                        openOnly,
                        dueBefore,
                        transactionId,
                        null,
                        null,
                        paging));
    }

    public FuelRepository.FuelPage<FuelPolicy> policies(
            String site,
            FuelPolicy.Status status,
            boolean inForceOnly,
            FuelRepository.Paging paging,
            ActorContext actor) {
        access.require(actor, SflPermission.FUEL_POLICY_READ, site, "FuelPolicy", null);
        return repository.findPolicies(
                new FuelRepository.PolicyQuery(
                        List.of(SiteCode.of(site).value()),
                        status,
                        inForceOnly ? clock.instant() : null,
                        paging));
    }

    public FuelPolicy policy(UUID id, ActorContext actor) {
        var p =
                repository
                        .findPolicy(id)
                        .orElseThrow(() -> RecordNotFoundException.of("FuelPolicy", id));
        access.require(
                actor,
                SflPermission.FUEL_POLICY_READ,
                p.siteCode().value(),
                "FuelPolicy",
                id.toString());
        return p;
    }

    /**
     * Every reconciliation run against the transaction, newest first.
     *
     * <p>The rows were written from the first release and readable from none of it, so a screen
     * could report that a transaction failed but never which rules it passed. Reading them is what
     * makes a decision reproducible: the policy version it was judged against and the full rule
     * result map.
     */
    public List<FuelReconciliation> reconciliations(UUID transactionId, ActorContext actor) {
        var tx = transaction(transactionId, actor);
        access.require(
                actor,
                SflPermission.FUEL_TRANSACTION_READ,
                tx.siteCode().value(),
                "FuelReconciliation",
                transactionId.toString());
        return repository.findReconciliations(transactionId);
    }

    public FuelRepository.FuelPage<FuelImportBatch> importBatches(
            String site, String source, FuelRepository.Paging paging, ActorContext actor) {
        access.require(actor, SflPermission.FUEL_TRANSACTION_READ, site, "FuelImportBatch", null);
        return repository.findImportBatches(
                new FuelRepository.ImportQuery(List.of(SiteCode.of(site).value()), source, paging));
    }

    public FuelImportBatch importBatch(UUID id, ActorContext actor) {
        var batch =
                repository
                        .findImportBatch(id)
                        .orElseThrow(() -> RecordNotFoundException.of("FuelImportBatch", id));
        access.require(
                actor,
                SflPermission.FUEL_TRANSACTION_READ,
                batch.siteCode().value(),
                "FuelImportBatch",
                id.toString());
        return batch;
    }

    /**
     * The audit trail for one fuel record.
     *
     * <p>Every fuel state change was already written through {@link AuditPort}; what was missing
     * was a way to read it back against a single record. The read is authorised against the record
     * itself, so a caller cannot enumerate another site's history through it.
     */
    public List<AuditEvent> history(String resourceType, UUID id, ActorContext actor) {
        String site =
                switch (resourceType) {
                    case "FuelTransaction" -> transaction(id, actor).siteCode().value();
                    case "DriverLogbook" -> logbook(id, actor).siteCode().value();
                    case "FuelAnomalyCase" -> anomaly(id, actor).siteCode().value();
                    case "FuelPolicy" -> policy(id, actor).siteCode().value();
                    default -> throw new IllegalArgumentException("Unknown fuel resource type");
                };
        return audit.search(
                new AuditPort.AuditQuery(
                        List.of(site),
                        resourceType,
                        id.toString(),
                        null,
                        null,
                        null,
                        null,
                        0,
                        200));
    }

    /**
     * One batch's rows, paged.
     *
     * <p>The detail read returns every row, which is fine for a hundred and not for a file with
     * thousands.
     */
    public FuelRepository.FuelPage<FuelImportRow> importRows(
            UUID id,
            FuelImportRow.Status status,
            FuelRepository.Paging paging,
            ActorContext actor) {
        // importBatch already authorises against the batch's own site, so the rows inherit that
        // check
        // rather than repeating it with a permission that does not exist.
        importBatch(id, actor);
        return repository.findImportRows(id, status, paging);
    }

    /**
     * Fuel spend and volume by day, aggregated by the service.
     *
     * <p>The dashboard chart bucketed this in the browser from a page of fetched transactions, so
     * it described that page rather than the site. Aggregated in SQL it describes the site, and the
     * screen can stop captioning it as derived.
     */
    public List<FuelRepository.DailyFuelTotals> dailyTotals(
            String site, Instant from, Instant to, ActorContext actor) {
        access.require(actor, SflPermission.FUEL_REPORT_READ, site, "FuelDashboard", null);
        return repository.dailyTotals(List.of(SiteCode.of(site).value()), site, from, to);
    }

    /** Open anomaly counts by type, so a by-type chart stops reading a page of records. */
    public Map<String, Long> anomalyCountsByType(String site, ActorContext actor) {
        access.require(actor, SflPermission.FUEL_REPORT_READ, site, "FuelDashboard", null);
        return repository.anomalyCountsByType(List.of(SiteCode.of(site).value()), site);
    }

    public Map<String, Object> dashboard(String site, ActorContext actor) {
        access.require(actor, SflPermission.FUEL_REPORT_READ, site, "FuelDashboard", null);
        Instant now = clock.instant();
        var result =
                new LinkedHashMap<>(
                        repository.dashboard(List.of(SiteCode.of(site).value()), site, now));
        Instant updated = (Instant) result.get("sourceUpdatedAt");
        result.put("stale", updated == null || updated.isBefore(now.minusSeconds(900)));
        return result;
    }

    public String transactionReportCsv(String site, ActorContext actor) {
        access.require(
                actor, SflPermission.FUEL_REPORT_EXPORT, site, "FuelTransactionReport", null);
        StringBuilder csv =
                new StringBuilder(
                        "transactionId,occurredAt,vehicleId,driverId,product,quantity,unit,totalCost,currency,status,vendor,receiptEvidenceId\r\n");
        var window =
                new FuelRepository.TransactionQuery(
                        List.of(SiteCode.of(site).value()),
                        site,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new FuelRepository.Paging(
                                0, FuelRepository.Paging.MAX_SIZE, "occurredAt,desc"));
        for (var t : repository.findTransactions(window).content()) {
            csv.append(t.id())
                    .append(',')
                    .append(t.occurredAt())
                    .append(',')
                    .append(t.vehicleId())
                    .append(',')
                    .append(t.driverId())
                    .append(',')
                    .append(csv(t.fuelProduct()))
                    .append(',')
                    .append(t.quantity())
                    .append(',')
                    .append(csv(t.quantityUnit()))
                    .append(',')
                    .append(t.totalCost())
                    .append(',')
                    .append(t.currency().getCurrencyCode())
                    .append(',')
                    .append(t.status())
                    .append(',')
                    .append(csv(t.vendorReference()))
                    .append(',')
                    .append(t.receiptEvidenceId() == null ? "" : t.receiptEvidenceId())
                    .append("\r\n");
        }
        return csv.toString();
    }

    @Transactional
    public FuelAnomalyCase raiseMissingLogbook(
            String site,
            UUID tripId,
            UUID vehicleId,
            UUID driverId,
            ActorContext actor,
            SourceChannel channel) {
        var existing = repository.findAnomalyForTrip(tripId, FuelAnomalyCase.Type.MISSING_LOGBOOK);
        if (existing.isPresent()) return existing.get();
        access.require(actor, SflPermission.FUEL_ANOMALY_MANAGE, site, "FuelAnomalyCase", null);
        Instant now = clock.instant();
        int sla =
                repository
                        .findApplicablePolicy(SiteCode.of(site).value(), now)
                        .map(FuelPolicy::anomalySlaHours)
                        .orElse(24);
        var anomaly =
                new FuelAnomalyCase(
                        UUID.randomUUID(),
                        number("ANM"),
                        SiteCode.of(site),
                        null,
                        null,
                        vehicleId,
                        driverId,
                        tripId,
                        FuelAnomalyCase.Type.MISSING_LOGBOOK,
                        FuelAnomalyCase.Severity.MEDIUM,
                        false,
                        FuelAnomalyCase.Status.DETECTED,
                        null,
                        now.plusSeconds(sla * 3600L),
                        null,
                        null,
                        null,
                        null,
                        0,
                        List.of("COMPLETED_TRIP_WITHOUT_LOGBOOK"),
                        RecordMetadata.createdBy(
                                actor.actorId(), now, channel, actor.correlationId()));
        anomaly = repository.saveAnomaly(anomaly);
        audit.record(
                actor,
                channel,
                anomaly.siteCode(),
                AuditAction.CREATE,
                "FuelAnomalyCase",
                anomaly.id().toString(),
                null,
                anomaly);
        events.publish(
                FleetEventType.DRIVER_LOGBOOK_OVERDUE,
                "DriverLogbook",
                tripId.toString(),
                anomaly.siteCode(),
                actor,
                Map.of(
                        "tripId",
                        tripId,
                        "driverId",
                        driverId,
                        "vehicleId",
                        vehicleId,
                        "anomalyId",
                        anomaly.id()));
        return anomaly;
    }

    /**
     * The pump photograph, held to the same standard as the receipt.
     *
     * <p>Gated on the same {@code receiptRequired} switch rather than a flag of its own, and only
     * for manually captured transactions. Both halves matter. A site that does not demand
     * documentary evidence for fuel should not have half of it demanded anyway; and a provider feed
     * delivers thousands of transactions that never passed through a driver's hands, so requiring a
     * photograph of them would raise an anomaly per row and drown the queue on the day the
     * integration is switched on.
     */
    private void checkPumpImage(
            Map<String, Object> rules,
            List<FuelAnomalyCase.Type> failures,
            FuelTransaction tx,
            FuelPolicy policy,
            boolean withinGrace) {
        boolean captured = "MANUAL".equalsIgnoreCase(tx.sourceSystem());
        boolean required = policy.receiptRequired() && captured;
        check(
                rules,
                failures,
                "PUMP_IMAGE",
                !required || tx.pumpEvidenceId() != null || withinGrace,
                FuelAnomalyCase.Type.MISSING_PUMP_IMAGE,
                Map.of(
                        "required",
                        required,
                        "sourceSystem",
                        tx.sourceSystem(),
                        "graceHours",
                        policy.receiptGraceHours()));
    }

    /**
     * The unit price against the price the vendor posted when the fuel was bought.
     *
     * <h2>Why this rule is the one that matters</h2>
     *
     * <p>COST_VARIANCE, a few lines above, compares a transaction to the previous one for the same
     * vehicle. That detects a <em>change</em>, which means it fires on a genuine national price
     * rise and stays perfectly silent on a driver who overstates by a steady twenty per cent every
     * week. This compares against the forecourt price actually in force, so it does the opposite:
     * price rises pass once the new price is recorded, and a persistent overstatement fails every
     * time.
     *
     * <h2>No reference price is a pass, and says so</h2>
     *
     * <p>A site that has not recorded its prices yet gets {@code passed: true} with a {@code
     * referencePrice} of "none". Not silence - the rule result is written into the reconciliation
     * record either way, so "this transaction was never actually price-checked" is visible to
     * anyone reading it rather than indistinguishable from "it passed". Inventing a comparison
     * against a price nobody recorded would be worse than not checking.
     */
    private void checkPostedPrice(
            Map<String, Object> rules,
            List<FuelAnomalyCase.Type> failures,
            FuelTransaction tx,
            FuelPolicy policy) {
        var posted =
                repository.findPostedPrice(
                        tx.siteCode().value(),
                        tx.vendorReference(),
                        tx.fuelProduct(),
                        tx.occurredAt());
        if (posted.isEmpty()) {
            check(
                    rules,
                    failures,
                    "POSTED_PRICE",
                    true,
                    FuelAnomalyCase.Type.PRICE_DEVIATION,
                    Map.of(
                            "referencePrice",
                            "none",
                            "vendor",
                            tx.vendorReference(),
                            "product",
                            tx.fuelProduct(),
                            "note",
                            "No posted price is on file for this vendor and product; the price was not checked."));
            return;
        }
        var reference = posted.get();
        BigDecimal deviation = reference.deviationFrom(tx.unitPrice());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referencePrice", reference.unitPrice());
        details.put("observedPrice", tx.unitPrice());
        details.put("deviation", deviation);
        details.put("threshold", policy.costVarianceTolerance());
        details.put("priceSource", reference.source().name());
        details.put("effectiveFrom", reference.effectiveFrom().toString());
        // The overstatement in money, which is the number a manager reviewing the case wants first.
        details.put(
                "overstatedBy",
                tx.unitPrice()
                        .subtract(reference.unitPrice())
                        .multiply(tx.quantity())
                        .setScale(2, RoundingMode.HALF_UP));
        check(
                rules,
                failures,
                "POSTED_PRICE",
                deviation.compareTo(policy.costVarianceTolerance()) <= 0,
                FuelAnomalyCase.Type.PRICE_DEVIATION,
                details);
    }

    /**
     * Neither image may have been filed against another transaction.
     *
     * <p>Photograph one pump, claim four fills - the cheapest fuel fraud there is, and until the
     * platform held the bytes there was no way to see it. Now both images are hashed on upload, so
     * the check is one indexed lookup per image.
     *
     * <p>Its limits are worth being honest about: a second photograph of the same receipt has a
     * different digest and passes this. It catches reuse, not staging. That is why it is one rule
     * among several rather than the answer on its own.
     */
    private void checkEvidenceNotReused(
            Map<String, Object> rules, List<FuelAnomalyCase.Type> failures, FuelTransaction tx) {
        var reused = new ArrayList<Map<String, Object>>();
        for (var entry :
                Map.of(
                                "receipt",
                                Optional.ofNullable(tx.receiptEvidenceId()),
                                "pump",
                                Optional.ofNullable(tx.pumpEvidenceId()))
                        .entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            for (var duplicate : evidence.findDuplicates(entry.getValue().get())) {
                reused.add(
                        Map.of(
                                "role",
                                entry.getKey(),
                                "evidenceId",
                                entry.getValue().get().toString(),
                                "alsoFiledAs",
                                duplicate.id().toString(),
                                "fileName",
                                duplicate.fileName(),
                                "sha256Hash",
                                duplicate.sha256Hash()));
            }
        }
        check(
                rules,
                failures,
                "EVIDENCE_UNIQUE",
                reused.isEmpty(),
                FuelAnomalyCase.Type.EVIDENCE_REUSED,
                reused.isEmpty() ? Map.of() : Map.of("duplicates", reused));
    }

    /* ------------------------------------------------------------------------- posted prices */

    /**
     * Records the price a vendor is posting, closing the one it replaces.
     *
     * <p>The close is what keeps history judgeable. Overwriting the old row would make every
     * transaction from before the change suddenly measured against a price that did not exist when
     * it happened, and reconciliation is supposed to be reproducible - run it again in a year and
     * it must reach the same verdict. So the previous price is closed at the instant the new one
     * begins, and both remain readable.
     */
    @Transactional
    public FuelPostedPrice recordPostedPrice(RecordPostedPrice c) {
        access.require(
                c.actor(), SflPermission.FUEL_POLICY_MANAGE, c.siteCode(), "FuelPostedPrice", null);
        String site = SiteCode.of(c.siteCode()).value();
        String vendor = c.vendor().strip().toUpperCase();
        String product = c.fuelProduct().strip().toUpperCase();
        Instant now = clock.instant();
        Instant from = c.effectiveFrom() == null ? now : c.effectiveFrom();

        var open = repository.findOpenPostedPrice(site, vendor, product);
        if (open.isPresent()) {
            var previous = open.get();
            if (!from.isAfter(previous.effectiveFrom())) {
                throw new IllegalArgumentException(
                        "A price for this vendor and product is already in force from "
                                + previous.effectiveFrom()
                                + "; the new price must start after it.");
            }
            repository.savePostedPrice(
                    previous.supersededAt(
                            from,
                            previous.metadata()
                                    .modifiedBy(
                                            c.actor().actorId(),
                                            now,
                                            c.channel(),
                                            c.actor().correlationId())));
        }

        var price =
                new FuelPostedPrice(
                        UUID.randomUUID(),
                        SiteCode.of(site),
                        vendor,
                        product,
                        c.unitPrice(),
                        c.currency(),
                        from,
                        null,
                        c.source() == null ? FuelPostedPrice.Source.ADMINISTERED : c.source(),
                        c.notes(),
                        RecordMetadata.createdBy(
                                c.actor().actorId(), now, c.channel(), c.actor().correlationId()));
        var saved = repository.savePostedPrice(price);
        audit.record(
                c.actor(),
                c.channel(),
                saved.siteCode(),
                AuditAction.CREATE,
                "FuelPostedPrice",
                saved.id().toString(),
                open.orElse(null),
                saved);
        return saved;
    }

    /**
     * Prices on file for a site. Used by the capture form to fill in the price rather than ask for
     * it.
     */
    public List<FuelPostedPrice> postedPrices(
            String site, String vendor, String product, boolean inForceOnly, ActorContext actor) {
        access.require(actor, SflPermission.FUEL_TRANSACTION_READ, site, "FuelPostedPrice", null);
        return repository.findPostedPrices(
                SiteCode.of(site).value(), vendor, product, inForceOnly, clock.instant());
    }

    /**
     * The vendors a site's policy approves, at an instant.
     *
     * <p>Exists so the capture form can offer a list instead of a text box. The vendor was free
     * text, which meant "GOIL", "Goil Tema" and "goil filling station" were three vendors as far as
     * the APPROVED_VENDOR rule was concerned - and since {@code allowsVendor} passes everything
     * when the approved set is empty, a site that had not configured one was checking nothing at
     * all while appearing to.
     *
     * <p>An empty list is a real answer meaning "this policy approves any vendor", and the caller
     * has to say so rather than showing an empty dropdown.
     */
    public List<String> approvedVendors(String site, ActorContext actor) {
        access.require(actor, SflPermission.FUEL_TRANSACTION_READ, site, "FuelPolicy", null);
        return repository
                .findApplicablePolicy(SiteCode.of(site).value(), clock.instant())
                .map(p -> p.approvedVendors().stream().sorted().toList())
                .orElseGet(List::of);
    }

    private void checkPolicyRollingLimits(
            Map<String, Object> rules,
            List<FuelAnomalyCase.Type> failures,
            FuelTransaction tx,
            FuelPolicy policy) {
        var periods = periods(tx.occurredAt());
        if (policy.dailyLimit() != null) {
            checkRollingLimit(
                    rules,
                    failures,
                    "POLICY_DAILY_VEHICLE_LIMIT",
                    policy.dailyLimit(),
                    sumQuantity(
                            tx, periods.dayStart(), periods.dayEnd(), tx.vehicleId(), null, null),
                    FuelAnomalyCase.Type.DAILY_LIMIT_EXCEEDED,
                    "vehicle",
                    "day");
            checkRollingLimit(
                    rules,
                    failures,
                    "POLICY_DAILY_DRIVER_LIMIT",
                    policy.dailyLimit(),
                    sumQuantity(
                            tx, periods.dayStart(), periods.dayEnd(), null, tx.driverId(), null),
                    FuelAnomalyCase.Type.DAILY_LIMIT_EXCEEDED,
                    "driver",
                    "day");
        }
        if (policy.monthlyLimit() != null) {
            checkRollingLimit(
                    rules,
                    failures,
                    "POLICY_MONTHLY_VEHICLE_LIMIT",
                    policy.monthlyLimit(),
                    sumQuantity(
                            tx,
                            periods.monthStart(),
                            periods.monthEnd(),
                            tx.vehicleId(),
                            null,
                            null),
                    FuelAnomalyCase.Type.MONTHLY_LIMIT_EXCEEDED,
                    "vehicle",
                    "month");
            checkRollingLimit(
                    rules,
                    failures,
                    "POLICY_MONTHLY_DRIVER_LIMIT",
                    policy.monthlyLimit(),
                    sumQuantity(
                            tx,
                            periods.monthStart(),
                            periods.monthEnd(),
                            null,
                            tx.driverId(),
                            null),
                    FuelAnomalyCase.Type.MONTHLY_LIMIT_EXCEEDED,
                    "driver",
                    "month");
        }
    }

    private void checkCardRollingLimits(
            Map<String, Object> rules,
            List<FuelAnomalyCase.Type> failures,
            FuelTransaction tx,
            FuelPolicy policy,
            gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelCard card) {
        var periods = periods(tx.occurredAt());
        BigDecimal daily = card.dailyLimit() == null ? policy.dailyLimit() : card.dailyLimit();
        BigDecimal monthly =
                card.monthlyLimit() == null ? policy.monthlyLimit() : card.monthlyLimit();
        String dailySource = card.dailyLimit() == null ? "policy" : "card";
        String monthlySource = card.monthlyLimit() == null ? "policy" : "card";
        if (daily != null)
            checkRollingLimit(
                    rules,
                    failures,
                    "CARD_DAILY_LIMIT",
                    daily,
                    card.dailyLimit() == null
                            ? sumQuantity(
                                    tx,
                                    periods.dayStart(),
                                    periods.dayEnd(),
                                    null,
                                    null,
                                    card.maskedReference())
                            : sumCost(
                                    tx,
                                    periods.dayStart(),
                                    periods.dayEnd(),
                                    null,
                                    null,
                                    card.maskedReference()),
                    FuelAnomalyCase.Type.CARD_DAILY_LIMIT_EXCEEDED,
                    "card:" + dailySource,
                    "day");
        if (monthly != null)
            checkRollingLimit(
                    rules,
                    failures,
                    "CARD_MONTHLY_LIMIT",
                    monthly,
                    card.monthlyLimit() == null
                            ? sumQuantity(
                                    tx,
                                    periods.monthStart(),
                                    periods.monthEnd(),
                                    null,
                                    null,
                                    card.maskedReference())
                            : sumCost(
                                    tx,
                                    periods.monthStart(),
                                    periods.monthEnd(),
                                    null,
                                    null,
                                    card.maskedReference()),
                    FuelAnomalyCase.Type.CARD_MONTHLY_LIMIT_EXCEEDED,
                    "card:" + monthlySource,
                    "month");
    }

    private BigDecimal sumQuantity(
            FuelTransaction tx,
            Instant from,
            Instant to,
            UUID vehicleId,
            UUID driverId,
            String card) {
        return repository.sumTransactionQuantity(
                new FuelRepository.SpendWindowQuery(
                        tx.siteCode().value(), vehicleId, driverId, card, from, to));
    }

    private BigDecimal sumCost(
            FuelTransaction tx,
            Instant from,
            Instant to,
            UUID vehicleId,
            UUID driverId,
            String card) {
        return repository.sumTransactionCost(
                new FuelRepository.SpendWindowQuery(
                        tx.siteCode().value(), vehicleId, driverId, card, from, to));
    }

    private void checkRollingLimit(
            Map<String, Object> rules,
            List<FuelAnomalyCase.Type> failures,
            String rule,
            BigDecimal limit,
            BigDecimal observed,
            FuelAnomalyCase.Type type,
            String scope,
            String period) {
        check(
                rules,
                failures,
                rule,
                observed.compareTo(limit) <= 0,
                type,
                Map.of("threshold", limit, "observed", observed, "scope", scope, "period", period));
    }

    private static Periods periods(Instant at) {
        var day = at.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        var monthStart = day.withDayOfMonth(1);
        return new Periods(
                day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                day.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                monthStart.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                monthStart.plusMonths(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
    }

    private record Periods(
            Instant dayStart, Instant dayEnd, Instant monthStart, Instant monthEnd) {}

    private void createAnomaly(
            FuelTransaction tx,
            FuelPolicy p,
            FuelAnomalyCase.Type type,
            List<String> rules,
            ActorContext actor,
            SourceChannel channel) {
        if (repository.findAnomaly(tx.id(), type).isPresent()) return;
        Instant now = clock.instant();
        boolean material = tx.totalCost().compareTo(p.materialityAmount()) >= 0;
        var severity = material ? FuelAnomalyCase.Severity.HIGH : FuelAnomalyCase.Severity.MEDIUM;
        var a =
                new FuelAnomalyCase(
                        UUID.randomUUID(),
                        number("ANM"),
                        tx.siteCode(),
                        tx.id(),
                        null,
                        tx.vehicleId(),
                        tx.driverId(),
                        tx.tripId(),
                        type,
                        severity,
                        material,
                        FuelAnomalyCase.Status.DETECTED,
                        null,
                        now.plusSeconds(p.anomalySlaHours() * 3600L),
                        null,
                        null,
                        null,
                        null,
                        0,
                        rules,
                        RecordMetadata.createdBy(
                                actor.actorId(), now, channel, actor.correlationId()));
        a = repository.saveAnomaly(a);
        audit.record(
                actor,
                channel,
                a.siteCode(),
                AuditAction.CREATE,
                "FuelAnomalyCase",
                a.id().toString(),
                null,
                a);
        events.publish(
                FleetEventType.FUEL_EXCEPTION_DETECTED,
                "FuelAnomalyCase",
                a.id().toString(),
                a.siteCode(),
                actor,
                Map.of("anomalyId", a.id(), "type", a.type(), "material", a.material()));
        notifications.notifyRole(
                a.siteCode(),
                SflRole.FLEET_MANAGER,
                NotificationKind.WORK_ASSIGNED,
                a.anomalyNumber(),
                anomalyContext(a));
        if (material) financeAudit.surfaceMaterialException(a, actor);
    }

    private static void check(
            Map<String, Object> results,
            List<FuelAnomalyCase.Type> failures,
            String rule,
            boolean passed,
            FuelAnomalyCase.Type type) {
        check(results, failures, rule, passed, type, Map.of());
    }

    private static void check(
            Map<String, Object> results,
            List<FuelAnomalyCase.Type> failures,
            String rule,
            boolean passed,
            FuelAnomalyCase.Type type,
            Map<String, Object> details) {
        var outcome = new LinkedHashMap<String, Object>();
        outcome.put("passed", passed);
        outcome.putAll(details);
        results.put(rule, outcome);
        if (!passed && !failures.contains(type)) failures.add(type);
    }

    private static String number(String prefix) {
        return prefix + "-" + Instant.now().toEpochMilli() + "-" + NUMBERS.incrementAndGet();
    }

    /**
     * Quotes a CSV cell and neutralises formula injection: a value beginning with {@code = + - @}
     * (or a tab/CR, which some spreadsheet parsers also treat as a formula lead-in) is prefixed
     * with a bare quote before the field is quoted, so a vendor-supplied reference like {@code
     * =CMD(...)} lands in the cell as text instead of executing when the export is opened in a
     * spreadsheet.
     */
    private static String csv(String value) {
        String raw = String.valueOf(value);
        if (!raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0) {
            raw = "'" + raw;
        }
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }
}

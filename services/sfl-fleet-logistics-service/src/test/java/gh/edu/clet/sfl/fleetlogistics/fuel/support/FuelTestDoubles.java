package gh.edu.clet.sfl.fleetlogistics.fuel.support;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FinanceAuditVisibilityPort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelEvidencePort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelFleetReferencePort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelOutboxAdminPort;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelRepository;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.DriverLogbook;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelAnomalyCase;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelCard;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelImportBatch;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelImportRow;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelPolicy;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelPostedPrice;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelReconciliation;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelTransaction;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory port implementations for S168_fuel application-service tests, following the same
 * pattern as {@code fleet.support.FleetTestDoubles}: real implementations of the contracts, not
 * mocks, so a test exercises behaviour rather than interaction bookkeeping.
 */
public final class FuelTestDoubles {

    private FuelTestDoubles() {
    }

    public static ActorContext actor(String subject, Set<SflRole> roles, Set<String> sites) {
        return new ActorContext(new SiteScopedPrincipal(subject, subject, roles, sites, false), "corr-test");
    }

    public static ActorContext fuelManager(String... sites) {
        return actor("fuel-manager@clet.edu.gh", Set.of(SflRole.FLEET_MANAGER), Set.of(sites));
    }

    public static ActorContext fuelOfficer(String... sites) {
        return actor("fuel-officer@clet.edu.gh", Set.of(SflRole.FLEET_LOGISTICS_OFFICER), Set.of(sites));
    }

    public static ActorContext driver(String subject, String... sites) {
        return actor(subject, Set.of(SflRole.FLEET_DRIVER), Set.of(sites));
    }

    public static ActorContext admin(String... sites) {
        return actor("admin@clet.edu.gh", Set.of(SflRole.SFL_ADMIN), Set.of(sites));
    }

    /** In-memory persistence for every S168_fuel aggregate, backing application-service tests. */
    public static final class InMemoryFuelRepository implements FuelRepository {

        private final Map<UUID, FuelCard> cards = new LinkedHashMap<>();
        private final Map<UUID, FuelPolicy> policies = new LinkedHashMap<>();
        private final Map<UUID, FuelPostedPrice> postedPrices = new LinkedHashMap<>();
        private final Map<UUID, FuelTransaction> transactions = new LinkedHashMap<>();
        private final Map<UUID, DriverLogbook> logbooks = new LinkedHashMap<>();
        private final Map<UUID, FuelAnomalyCase> anomalies = new LinkedHashMap<>();
        private final List<FuelReconciliation> reconciliations = new ArrayList<>();
        private final Map<UUID, FuelImportBatch> importBatches = new LinkedHashMap<>();

        // ---- cards -----------------------------------------------------------------------------

        @Override
        public FuelCard saveCard(FuelCard card) {
            cards.put(card.id(), card);
            return card;
        }

        @Override
        public Optional<FuelCard> findCard(UUID id) {
            return Optional.ofNullable(cards.get(id));
        }

        @Override
        public Optional<FuelCard> findLiveCardByReference(String siteCode, String maskedReference) {
            return cards.values().stream()
                    .filter(c -> c.siteCode().value().equals(siteCode))
                    .filter(c -> c.maskedReference().equals(maskedReference))
                    .filter(c -> c.status() != FuelCard.Status.CANCELLED)
                    .findFirst();
        }

        @Override
        public FuelPage<FuelCard> findCards(CardQuery query) {
            List<FuelCard> matching = cards.values().stream()
                    .filter(c -> query.sites().contains(c.siteCode().value()))
                    .filter(c -> query.status() == null || c.status() == query.status())
                    .filter(c -> query.vehicleId() == null || query.vehicleId().equals(c.vehicleId()))
                    .filter(c -> query.driverId() == null || query.driverId().equals(c.driverId()))
                    .filter(c -> query.maskedReference() == null || c.maskedReference().equals(query.maskedReference()))
                    .toList();
            return paged(matching, query.paging());
        }

        // ---- policies --------------------------------------------------------------------------

        @Override
        public FuelPolicy savePolicy(FuelPolicy policy) {
            policies.put(policy.id(), policy);
            return policy;
        }

        @Override
        public Optional<FuelPolicy> findApplicablePolicy(String siteCode, Instant at) {
            return policies.values().stream()
                    .filter(p -> p.siteCode().value().equals(siteCode))
                    .filter(p -> p.appliesAt(at))
                    .findFirst();
        }

        @Override
        public Optional<FuelPolicy> findPolicy(UUID id) {
            return Optional.ofNullable(policies.get(id));
        }

        @Override
        public FuelPage<FuelPolicy> findPolicies(PolicyQuery query) {
            List<FuelPolicy> matching = policies.values().stream()
                    .filter(p -> query.sites().contains(p.siteCode().value()))
                    .filter(p -> query.status() == null || p.status() == query.status())
                    .filter(p -> query.inForceAt() == null || p.appliesAt(query.inForceAt()))
                    .toList();
            return paged(matching, query.paging());
        }

        @Override
        public List<FuelPolicy> findOverlappingActivePolicies(String siteCode, Instant from, Instant to,
                UUID excludingId) {
            return policies.values().stream()
                    .filter(p -> p.siteCode().value().equals(siteCode))
                    .filter(p -> p.status() == FuelPolicy.Status.ACTIVE)
                    .filter(p -> excludingId == null || !p.id().equals(excludingId))
                    .filter(p -> overlaps(p.effectiveFrom(), p.effectiveTo(), from, to))
                    .toList();
        }

        private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
            boolean startsBeforeOtherEnds = bEnd == null || aStart.isBefore(bEnd);
            boolean otherStartsBeforeEnds = aEnd == null || bStart.isBefore(aEnd);
            return startsBeforeOtherEnds && otherStartsBeforeEnds;
        }

        // ---- posted prices ---------------------------------------------------------------------

        @Override
        public FuelPostedPrice savePostedPrice(FuelPostedPrice price) {
            postedPrices.put(price.id(), price);
            return price;
        }

        @Override
        public Optional<FuelPostedPrice> findPostedPrice(String siteCode, String vendor, String fuelProduct,
                Instant at) {
            return postedPrices.values().stream()
                    .filter(p -> p.siteCode().value().equals(siteCode))
                    .filter(p -> p.vendor().equalsIgnoreCase(vendor))
                    .filter(p -> p.fuelProduct().equalsIgnoreCase(fuelProduct))
                    .filter(p -> !p.effectiveFrom().isAfter(at))
                    .filter(p -> p.effectiveTo() == null || p.effectiveTo().isAfter(at))
                    .findFirst();
        }

        @Override
        public List<FuelPostedPrice> findPostedPrices(String siteCode, String vendor, String fuelProduct,
                boolean inForceOnly, Instant at) {
            return postedPrices.values().stream()
                    .filter(p -> p.siteCode().value().equals(siteCode))
                    .filter(p -> vendor == null || p.vendor().equalsIgnoreCase(vendor))
                    .filter(p -> fuelProduct == null || p.fuelProduct().equalsIgnoreCase(fuelProduct))
                    .filter(p -> !inForceOnly || (!p.effectiveFrom().isAfter(at)
                            && (p.effectiveTo() == null || p.effectiveTo().isAfter(at))))
                    .sorted(Comparator.comparing(FuelPostedPrice::effectiveFrom).reversed())
                    .toList();
        }

        @Override
        public Optional<FuelPostedPrice> findOpenPostedPrice(String siteCode, String vendor, String fuelProduct) {
            return postedPrices.values().stream()
                    .filter(p -> p.siteCode().value().equals(siteCode))
                    .filter(p -> p.vendor().equalsIgnoreCase(vendor))
                    .filter(p -> p.fuelProduct().equalsIgnoreCase(fuelProduct))
                    .filter(p -> p.effectiveTo() == null)
                    .findFirst();
        }

        // ---- transactions ----------------------------------------------------------------------

        @Override
        public FuelTransaction saveTransaction(FuelTransaction transaction) {
            transactions.put(transaction.id(), transaction);
            return transaction;
        }

        @Override
        public Optional<FuelTransaction> findTransaction(UUID id) {
            return Optional.ofNullable(transactions.get(id));
        }

        @Override
        public Optional<FuelTransaction> findProviderTransaction(String siteCode, String sourceSystem,
                String providerId) {
            if (providerId == null) return Optional.empty();
            return transactions.values().stream()
                    .filter(t -> t.siteCode().value().equals(siteCode))
                    .filter(t -> t.sourceSystem().equals(sourceSystem))
                    .filter(t -> providerId.equals(t.providerTransactionId()))
                    .findFirst();
        }

        @Override
        public FuelPage<FuelTransaction> findTransactions(TransactionQuery query) {
            List<FuelTransaction> matching = transactions.values().stream()
                    .filter(t -> query.sites().contains(t.siteCode().value()))
                    .filter(t -> query.status() == null || t.status() == query.status())
                    .filter(t -> query.vehicleId() == null || query.vehicleId().equals(t.vehicleId()))
                    .filter(t -> query.driverId() == null || query.driverId().equals(t.driverId()))
                    .filter(t -> query.sourceSystem() == null || t.sourceSystem().equals(query.sourceSystem()))
                    .filter(t -> query.vendorReference() == null || t.vendorReference().equals(query.vendorReference()))
                    .filter(t -> query.from() == null || !t.occurredAt().isBefore(query.from()))
                    .filter(t -> query.to() == null || t.occurredAt().isBefore(query.to()))
                    .sorted(Comparator.comparing(FuelTransaction::occurredAt).reversed())
                    .toList();
            return paged(matching, query.paging());
        }

        @Override
        public BigDecimal sumTransactionQuantity(SpendWindowQuery query) {
            return windowed(query).map(FuelTransaction::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public BigDecimal sumTransactionCost(SpendWindowQuery query) {
            return windowed(query).map(FuelTransaction::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private java.util.stream.Stream<FuelTransaction> windowed(SpendWindowQuery query) {
            return transactions.values().stream()
                    .filter(t -> t.siteCode().value().equals(query.siteCode()))
                    .filter(t -> t.lifecycle() == FuelTransaction.Lifecycle.ACTIVE)
                    .filter(t -> query.vehicleId() == null || query.vehicleId().equals(t.vehicleId()))
                    .filter(t -> query.driverId() == null || query.driverId().equals(t.driverId()))
                    .filter(t -> query.maskedCardReference() == null
                            || query.maskedCardReference().equals(t.maskedCardReference()))
                    .filter(t -> !t.occurredAt().isBefore(query.fromInclusive()))
                    .filter(t -> t.occurredAt().isBefore(query.toExclusive()));
        }

        @Override
        public Optional<FuelTransaction> findPreviousTransaction(String siteCode, UUID vehicleId, Instant before) {
            return transactions.values().stream()
                    .filter(t -> t.siteCode().value().equals(siteCode))
                    .filter(t -> t.vehicleId().equals(vehicleId))
                    .filter(t -> t.lifecycle() == FuelTransaction.Lifecycle.ACTIVE)
                    .filter(t -> t.occurredAt().isBefore(before))
                    .max(Comparator.comparing(FuelTransaction::occurredAt));
        }

        @Override
        public boolean hasLaterTransaction(String siteCode, UUID vehicleId, Instant after, UUID excludingId) {
            return transactions.values().stream()
                    .filter(t -> t.siteCode().value().equals(siteCode))
                    .filter(t -> t.vehicleId().equals(vehicleId))
                    .filter(t -> t.lifecycle() == FuelTransaction.Lifecycle.ACTIVE)
                    .filter(t -> !t.id().equals(excludingId))
                    .anyMatch(t -> t.occurredAt().isAfter(after));
        }

        // ---- logbooks --------------------------------------------------------------------------

        @Override
        public DriverLogbook saveLogbook(DriverLogbook logbook) {
            logbooks.put(logbook.id(), logbook);
            return logbook;
        }

        @Override
        public Optional<DriverLogbook> findLogbook(UUID id) {
            return Optional.ofNullable(logbooks.get(id));
        }

        @Override
        public FuelPage<DriverLogbook> findLogbooks(LogbookQuery query) {
            List<DriverLogbook> matching = logbooks.values().stream()
                    .filter(l -> query.sites().contains(l.siteCode().value()))
                    .filter(l -> query.status() == null || l.status() == query.status())
                    .filter(l -> query.driverId() == null || query.driverId().equals(l.driverId()))
                    .filter(l -> query.vehicleId() == null || query.vehicleId().equals(l.vehicleId()))
                    .filter(l -> !query.ownOnly() || l.metadata().createdBy().equals(query.actorId()))
                    .toList();
            return paged(matching, query.paging());
        }

        @Override
        public Optional<DriverLogbook> findLogbookForTrip(UUID tripId) {
            return logbooks.values().stream()
                    .filter(l -> tripId.equals(l.tripId()))
                    .filter(l -> l.status() != DriverLogbook.Status.CANCELLED)
                    .findFirst();
        }

        // ---- anomalies -------------------------------------------------------------------------

        @Override
        public FuelAnomalyCase saveAnomaly(FuelAnomalyCase anomaly) {
            anomalies.put(anomaly.id(), anomaly);
            return anomaly;
        }

        @Override
        public Optional<FuelAnomalyCase> findAnomaly(UUID id) {
            return Optional.ofNullable(anomalies.get(id));
        }

        @Override
        public Optional<FuelAnomalyCase> findAnomaly(UUID transactionId, FuelAnomalyCase.Type type) {
            return anomalies.values().stream()
                    .filter(a -> transactionId.equals(a.transactionId()) && a.type() == type)
                    .findFirst();
        }

        @Override
        public Optional<FuelAnomalyCase> findAnomalyForTrip(UUID tripId, FuelAnomalyCase.Type type) {
            return anomalies.values().stream()
                    .filter(a -> tripId.equals(a.tripId()) && a.type() == type)
                    .findFirst();
        }

        @Override
        public FuelPage<FuelAnomalyCase> findAnomalies(AnomalyQuery query) {
            List<FuelAnomalyCase> matching = anomalies.values().stream()
                    .filter(a -> query.sites().contains(a.siteCode().value()))
                    .filter(a -> query.status() == null || a.status() == query.status())
                    .filter(a -> query.type() == null || a.type() == query.type())
                    .filter(a -> query.severity() == null || a.severity() == query.severity())
                    .filter(a -> query.assignee() == null || query.assignee().equals(a.assignee()))
                    .filter(a -> !Boolean.TRUE.equals(query.unassigned()) || a.assignee() == null)
                    .filter(a -> query.material() == null || query.material() == a.material())
                    .filter(a -> !Boolean.TRUE.equals(query.openOnly())
                            || (a.status() != FuelAnomalyCase.Status.CLOSED
                            && a.status() != FuelAnomalyCase.Status.CANCELLED))
                    .filter(a -> query.dueBefore() == null
                            || (a.slaDueAt() != null && a.slaDueAt().isBefore(query.dueBefore())))
                    .filter(a -> query.transactionId() == null || query.transactionId().equals(a.transactionId()))
                    .toList();
            return paged(matching, query.paging());
        }

        @Override
        public long countRecentAnomalies(List<String> sites, UUID vehicleId, UUID driverId, Instant since) {
            return anomalies.values().stream()
                    .filter(a -> sites.contains(a.siteCode().value()))
                    .filter(a -> (vehicleId != null && vehicleId.equals(a.vehicleId()))
                            || (driverId != null && driverId.equals(a.driverId())))
                    .filter(a -> a.metadata().createdAt() != null && a.metadata().createdAt().isAfter(since))
                    .filter(a -> a.status() != FuelAnomalyCase.Status.CANCELLED)
                    .count();
        }

        // ---- reconciliations -------------------------------------------------------------------

        @Override
        public void saveReconciliation(UUID id, UUID transactionId, UUID policyId, Integer policyVersion,
                String outcome, BigDecimal consumption, Instant evaluatedAt, String actor,
                Map<String, Object> ruleResults, String correlationId) {
            reconciliations.add(new FuelReconciliation(id, transactionId, policyId, policyVersion, outcome,
                    consumption, evaluatedAt, actor, ruleResults, correlationId));
        }

        @Override
        public List<FuelReconciliation> findReconciliations(UUID transactionId) {
            return reconciliations.stream()
                    .filter(r -> r.transactionId().equals(transactionId))
                    .sorted(Comparator.comparing(FuelReconciliation::evaluatedAt).reversed())
                    .toList();
        }

        // ---- imports ---------------------------------------------------------------------------

        @Override
        public FuelImportBatch saveImportBatch(FuelImportBatch batch) {
            importBatches.put(batch.id(), batch);
            return batch;
        }

        @Override
        public FuelPage<FuelImportBatch> findImportBatches(ImportQuery query) {
            List<FuelImportBatch> matching = importBatches.values().stream()
                    .filter(b -> query.sites().contains(b.siteCode().value()))
                    .filter(b -> query.sourceSystem() == null || b.sourceSystem().equals(query.sourceSystem()))
                    .toList();
            return paged(matching, query.paging());
        }

        @Override
        public Optional<FuelImportBatch> findImportBatch(UUID id) {
            return Optional.ofNullable(importBatches.get(id));
        }

        @Override
        public FuelPage<FuelImportRow> findImportRows(UUID batchId, FuelImportRow.Status status, Paging paging) {
            List<FuelImportRow> matching = findImportBatch(batchId).map(FuelImportBatch::rows).orElse(List.of())
                    .stream()
                    .filter(r -> status == null || r.status() == status)
                    .toList();
            return paged(matching, paging);
        }

        @Override
        public List<DailyFuelTotals> dailyTotals(List<String> sites, String site, Instant from, Instant to) {
            return List.of();
        }

        @Override
        public Map<String, Long> anomalyCountsByType(List<String> sites, String site) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (FuelAnomalyCase a : anomalies.values()) {
                if (sites.contains(a.siteCode().value())) {
                    counts.merge(a.type().name(), 1L, Long::sum);
                }
            }
            return counts;
        }

        @Override
        public Optional<FuelImportBatch> findImportBatchByHash(String siteCode, String sourceSystem,
                String fileHash) {
            return importBatches.values().stream()
                    .filter(b -> b.siteCode().value().equals(siteCode))
                    .filter(b -> b.sourceSystem().equals(sourceSystem))
                    .filter(b -> fileHash.equals(b.fileHash()))
                    .findFirst();
        }

        // ---- dashboard / sweep support ------------------------------------------------------

        @Override
        public Map<String, Object> dashboard(List<String> sites, String siteCode, Instant now) {
            return Map.of();
        }

        @Override
        public List<String> activeSites() {
            Set<String> sites = new java.util.LinkedHashSet<>();
            transactions.values().forEach(t -> sites.add(t.siteCode().value()));
            anomalies.values().forEach(a -> sites.add(a.siteCode().value()));
            return List.copyOf(sites);
        }

        @Override
        public List<UUID> findLateReceiptTransactionIds(String siteCode, int limit) {
            return List.of();
        }

        @Override
        public List<MissingLogbookTrip> findMissingLogbookTrips(String siteCode, int limit) {
            return List.of();
        }

        // ---- helpers ---------------------------------------------------------------------------

        private static <T> FuelPage<T> paged(List<T> matching, Paging paging) {
            int from = Math.min(paging.offset(), matching.size());
            int to = Math.min(from + paging.size(), matching.size());
            return FuelPage.of(matching.subList(from, to), paging.page(), paging.size(), matching.size(),
                    paging.sort());
        }

        public int transactionCount() {
            return transactions.size();
        }
    }

    /** A configurable fleet-reference snapshot; every field defaults to a clean, passing state. */
    public static final class StubFleetReferencePort implements FuelFleetReferencePort {

        private Snapshot snapshot = new Snapshot(0L, "ACTIVE", "AVAILABLE", "ELIGIBLE", true, "STAFF-1");
        private final List<Long> acceptedOdometerReadings = new ArrayList<>();

        public StubFleetReferencePort withSnapshot(Snapshot value) {
            this.snapshot = value;
            return this;
        }

        @Override
        public Snapshot resolve(UUID vehicleId, UUID driverId, UUID tripId, String siteCode) {
            return snapshot;
        }

        @Override
        public void acceptOdometer(UUID vehicleId, long reading, Instant recordedAt, ActorContext actor,
                SourceChannel channel) {
            acceptedOdometerReadings.add(reading);
        }

        public List<Long> acceptedOdometerReadings() {
            return List.copyOf(acceptedOdometerReadings);
        }
    }

    /** Records material exceptions surfaced to Finance/Audit. */
    public static final class RecordingFinanceAuditPort implements FinanceAuditVisibilityPort {

        private final List<UUID> surfaced = new ArrayList<>();

        @Override
        public void surfaceMaterialException(FuelAnomalyCase anomaly, ActorContext actor) {
            surfaced.add(anomaly.id());
        }

        public List<UUID> surfaced() {
            return List.copyOf(surfaced);
        }
    }

    /** An outbox with nothing pending; every message is deliverable. */
    public static final class StubOutboxAdminPort implements FuelOutboxAdminPort {

        @Override
        public OutboxHealth health() {
            return new OutboxHealth(0, 0, 0, List.of());
        }

        @Override
        public boolean replay(UUID messageId) {
            return false;
        }
    }

    /** Evidence lookup with no known duplicates unless told otherwise. */
    public static final class InMemoryEvidencePort implements FuelEvidencePort {

        private final Map<UUID, List<EvidenceFacts>> duplicatesById = new LinkedHashMap<>();

        public InMemoryEvidencePort withDuplicate(UUID evidenceId, EvidenceFacts duplicate) {
            duplicatesById.computeIfAbsent(evidenceId, id -> new ArrayList<>()).add(duplicate);
            return this;
        }

        @Override
        public Optional<EvidenceFacts> find(UUID evidenceId) {
            return Optional.empty();
        }

        @Override
        public List<EvidenceFacts> findDuplicates(UUID evidenceId) {
            return duplicatesById.getOrDefault(evidenceId, List.of());
        }
    }
}

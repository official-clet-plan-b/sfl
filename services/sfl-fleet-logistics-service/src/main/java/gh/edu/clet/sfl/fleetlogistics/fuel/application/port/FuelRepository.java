package gh.edu.clet.sfl.fleetlogistics.fuel.application.port;

import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.DriverLogbook;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelAnomalyCase;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelCard;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelImportBatch;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelImportRow;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelPolicy;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelPostedPrice;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelReconciliation;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.model.FuelTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for S168_fuel. It exposes domain records, never JDBC/JPA types. */
public interface FuelRepository {

    /**
     * A page of fuel records.
     *
     * <p>Deliberately shaped like the fleet {@code PageResponse} so the two modules page the same
     * way from an operator's point of view, but declared here rather than imported from the API
     * layer - the port must not depend on a transport type.
     *
     * <p>{@code sort} is echoed back because the caller may have asked for a default: a client that
     * cannot see which ordering it got cannot tell a stable page from a shifting one.
     */
    record FuelPage<T>(
            List<T> content, int page, int size, long totalElements, int totalPages, String sort) {

        public static <T> FuelPage<T> of(
                List<T> content, int page, int size, long totalElements, String sort) {
            int pages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
            return new FuelPage<>(content, page, size, totalElements, pages, sort);
        }

        public static <T> FuelPage<T> empty(int page, int size, String sort) {
            return new FuelPage<>(List.of(), page, size, 0L, 0, sort);
        }
    }

    /**
     * Paging and ordering, normalised.
     *
     * <p>{@code sort} is a key from the resource's own allow-list, never raw SQL - the adapter maps
     * it to a column plus a deterministic tiebreak on {@code id}, because a page over rows that
     * share a sort value will otherwise skip or repeat records between requests.
     */
    record Paging(int page, int size, String sort) {

        public static final int MAX_SIZE = 200;

        public Paging {
            page = Math.max(page, 0);
            size = size <= 0 ? 25 : Math.min(size, MAX_SIZE);
        }

        public int offset() {
            return page * size;
        }
    }

    /** Filters `GET /api/v1/fuel/transactions` accepts. Any field may be null. */
    record TransactionQuery(
            List<String> sites,
            String siteCode,
            FuelTransaction.Status status,
            UUID vehicleId,
            UUID driverId,
            String sourceSystem,
            String vendorReference,
            Instant from,
            Instant to,
            Paging paging) {}

    /** Filters `GET /api/v1/fuel/logbooks` accepts. */
    // ---- fuel cards (SRS-SFL-S168fuel-04) ------------------------------------------------------

    FuelCard saveCard(FuelCard card);

    java.util.Optional<FuelCard> findCard(java.util.UUID id);

    /**
     * The reconciliation lookup: the live card behind a transaction's masked reference.
     *
     * <p>Excludes cancelled cards, because a cancelled reference may have been reissued and the
     * live one is the answer to "whose card is this now". Historic resolution goes through {@link
     * #findCard} by id, which is what a transaction stores once matched.
     */
    java.util.Optional<FuelCard> findLiveCardByReference(String siteCode, String maskedReference);

    FuelPage<FuelCard> findCards(CardQuery query);

    /** Filters {@code GET /api/v1/fuel/cards} accepts. */
    record CardQuery(
            java.util.List<String> sites,
            FuelCard.Status status,
            java.util.UUID vehicleId,
            java.util.UUID driverId,
            String maskedReference,
            Paging paging) {}

    record LogbookQuery(
            List<String> sites,
            String actorId,
            boolean ownOnly,
            DriverLogbook.Status status,
            UUID driverId,
            UUID vehicleId,
            DriverLogbook.UseClassification useClassification,
            java.time.LocalDate journeyFrom,
            java.time.LocalDate journeyTo,
            Paging paging) {}

    /**
     * Filters `GET /api/v1/fuel/anomalies` accepts.
     *
     * <p>{@code dueBefore} was reachable only from the sweep scheduler before; exposing it is what
     * makes a "breaching SLA" queue a server-side query rather than a client-side guess over
     * whatever the window happened to return.
     */
    record AnomalyQuery(
            List<String> sites,
            FuelAnomalyCase.Status status,
            FuelAnomalyCase.Type type,
            FuelAnomalyCase.Severity severity,
            String assignee,
            Boolean unassigned,
            Boolean material,
            Boolean openOnly,
            Instant dueBefore,
            UUID transactionId,
            UUID vehicleId,
            UUID driverId,
            Paging paging) {}

    /** Filters `GET /api/v1/fuel/policies` accepts. */
    record PolicyQuery(
            List<String> sites, FuelPolicy.Status status, Instant inForceAt, Paging paging) {}

    /** Filters `GET /api/v1/fuel/imports` accepts. */
    record ImportQuery(List<String> sites, String sourceSystem, Paging paging) {}

    // --- policies ------------------------------------------------------------------------------

    FuelPolicy savePolicy(FuelPolicy policy);

    Optional<FuelPolicy> findApplicablePolicy(String siteCode, Instant at);

    Optional<FuelPolicy> findPolicy(UUID id);

    FuelPage<FuelPolicy> findPolicies(PolicyQuery query);

    /**
     * ACTIVE policies for the site whose effective period intersects {@code [from, to)}.
     *
     * <p>An open-ended policy runs to infinity, so a null {@code effective_to} on either side
     * counts as an overlap. Used to enforce the documented no-overlap invariant at creation.
     */
    List<FuelPolicy> findOverlappingActivePolicies(
            String siteCode, Instant from, Instant to, UUID excludingId);

    // --- posted prices -------------------------------------------------------------------------

    FuelPostedPrice savePostedPrice(FuelPostedPrice price);

    /**
     * The price posted for this vendor and product at that instant, if one is on file.
     *
     * <p>Empty is a real and common answer, not a failure: a site that has not recorded its
     * forecourt prices yet has none, and the reconciliation rule says so rather than inventing a
     * comparison. The caller must treat "no reference price" and "price matches" as different
     * outcomes, because the first is a configuration gap somebody should close and the second is a
     * transaction that passed.
     */
    Optional<FuelPostedPrice> findPostedPrice(
            String siteCode, String vendor, String fuelProduct, Instant at);

    /**
     * Every price on file for a site, newest effective date first. Vendor and product are optional.
     */
    List<FuelPostedPrice> findPostedPrices(
            String siteCode, String vendor, String fuelProduct, boolean inForceOnly, Instant at);

    /** The open-ended price for this vendor and product, which a new one must close. */
    Optional<FuelPostedPrice> findOpenPostedPrice(
            String siteCode, String vendor, String fuelProduct);

    // --- transactions --------------------------------------------------------------------------

    FuelTransaction saveTransaction(FuelTransaction transaction);

    Optional<FuelTransaction> findTransaction(UUID id);

    Optional<FuelTransaction> findProviderTransaction(
            String siteCode, String sourceSystem, String providerId);

    FuelPage<FuelTransaction> findTransactions(TransactionQuery query);

    /**
     * Total active transaction quantity in a time window for a scoped actor/entity.
     *
     * <p>Used by site policy daily/monthly ceilings. The transaction being evaluated is already
     * stored before reconciliation starts, so the sum naturally includes it.
     */
    BigDecimal sumTransactionQuantity(SpendWindowQuery query);

    /**
     * Total active transaction cost in a time window for a scoped card.
     *
     * <p>Used by explicit fuel-card spend ceilings.
     */
    BigDecimal sumTransactionCost(SpendWindowQuery query);

    record SpendWindowQuery(
            String siteCode,
            UUID vehicleId,
            UUID driverId,
            String maskedCardReference,
            Instant fromInclusive,
            Instant toExclusive) {}

    /** Most recent active transaction for the vehicle strictly before {@code before}. */
    Optional<FuelTransaction> findPreviousTransaction(
            String siteCode, UUID vehicleId, Instant before);

    /**
     * Whether the vehicle has another active transaction after {@code after} - i.e. this one is
     * history.
     */
    boolean hasLaterTransaction(String siteCode, UUID vehicleId, Instant after, UUID excludingId);

    // --- logbooks ------------------------------------------------------------------------------

    DriverLogbook saveLogbook(DriverLogbook logbook);

    Optional<DriverLogbook> findLogbook(UUID id);

    FuelPage<DriverLogbook> findLogbooks(LogbookQuery query);

    /** Latest non-cancelled driver logbook associated with the trip, for mismatch detection. */
    Optional<DriverLogbook> findLogbookForTrip(UUID tripId);

    // --- anomalies -----------------------------------------------------------------------------

    FuelAnomalyCase saveAnomaly(FuelAnomalyCase anomaly);

    Optional<FuelAnomalyCase> findAnomaly(UUID id);

    Optional<FuelAnomalyCase> findAnomaly(UUID transactionId, FuelAnomalyCase.Type type);

    Optional<FuelAnomalyCase> findAnomalyForTrip(UUID tripId, FuelAnomalyCase.Type type);

    FuelPage<FuelAnomalyCase> findAnomalies(AnomalyQuery query);

    /** Count of cases raised for the same vehicle or driver since {@code since}. */
    long countRecentAnomalies(List<String> sites, UUID vehicleId, UUID driverId, Instant since);

    // --- reconciliations -----------------------------------------------------------------------

    void saveReconciliation(
            UUID id,
            UUID transactionId,
            UUID policyId,
            Integer policyVersion,
            String outcome,
            BigDecimal consumption,
            Instant evaluatedAt,
            String actor,
            Map<String, Object> ruleResults,
            String correlationId);

    /** Every run against the transaction, newest first. A rerun appends rather than amending. */
    List<FuelReconciliation> findReconciliations(UUID transactionId);

    // --- imports -------------------------------------------------------------------------------

    /** Records the batch and its rows. Raises the duplicate-file domain error rather than a 500. */
    FuelImportBatch saveImportBatch(FuelImportBatch batch);

    /** Batch header only; the rows are a separate read so a list does not carry every row. */
    FuelPage<FuelImportBatch> findImportBatches(ImportQuery query);

    /** Batch with its rows populated. */
    Optional<FuelImportBatch> findImportBatch(UUID id);

    /**
     * One batch's rows, paged.
     *
     * <p>The detail read returns every row, which is fine for a hundred and not for a file with
     * thousands. Paged here so a large import can be reviewed a screen at a time.
     *
     * <p>{@code status} is nullable and filters in SQL. It has to: the only view of an import that
     * matters is the rejected rows, and a status filter applied to a page would have found only the
     * rejections that happened to land on the page being looked at.
     */
    FuelPage<FuelImportRow> findImportRows(
            UUID batchId, FuelImportRow.Status status, Paging paging);

    /**
     * Fuel spend and volume by day.
     *
     * <p>The dashboard bucketed this in the browser from a page of fetched transactions, which
     * meant the chart described that page rather than the site. Aggregated in SQL, it describes the
     * site.
     */
    List<DailyFuelTotals> dailyTotals(List<String> sites, String site, Instant from, Instant to);

    /** Open anomaly counts by type, so a by-type chart stops reading a page of records. */
    Map<String, Long> anomalyCountsByType(List<String> sites, String site);

    /** One day's spend and volume. {@code day} is a date, not an instant: the bucket is a day. */
    record DailyFuelTotals(
            java.time.LocalDate day,
            java.math.BigDecimal totalCost,
            java.math.BigDecimal quantity,
            long transactionCount) {}

    Optional<FuelImportBatch> findImportBatchByHash(
            String siteCode, String sourceSystem, String fileHash);

    // --- dashboard -----------------------------------------------------------------------------

    Map<String, Object> dashboard(List<String> sites, String siteCode, Instant now);

    // --- scheduled-sweep support -----------------------------------------------------------

    /** Sites with any fuel activity, so the sweep does not have to be told where to look. */
    List<String> activeSites();

    /**
     * Reconciled transactions still missing a receipt once their policy's grace window has elapsed.
     * Re-running {@link
     * gh.edu.clet.sfl.fleetlogistics.fuel.application.service.FuelApplicationService#reconcile}
     * against them is what raises the missing-receipt anomaly; nothing else re-checks a transaction
     * once it has already reconciled.
     */
    List<UUID> findLateReceiptTransactionIds(String siteCode, int limit);

    /**
     * Completed trips with a vehicle and driver but no live (non-cancelled) logbook filed against
     * them.
     */
    List<MissingLogbookTrip> findMissingLogbookTrips(String siteCode, int limit);

    record MissingLogbookTrip(UUID tripId, UUID vehicleId, UUID driverId) {}
}

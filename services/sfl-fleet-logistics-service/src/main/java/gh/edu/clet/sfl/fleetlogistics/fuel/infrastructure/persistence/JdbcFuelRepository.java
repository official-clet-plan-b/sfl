package gh.edu.clet.sfl.fleetlogistics.fuel.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.RecordMetadata;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelRepository;
import gh.edu.clet.sfl.fleetlogistics.fuel.domain.exception.FuelImportAlreadyProcessedException;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Compact JDBC adapter; all business transitions remain in domain/application code. Instant values
 * are bound as UTC OffsetDateTime because the PostgreSQL JDBC driver cannot infer a SQL type for
 * java.time.Instant; columns are TIMESTAMPTZ so UTC OffsetDateTime is exact.
 */
@Repository
public class JdbcFuelRepository implements FuelRepository {
    private static final TypeReference<Map<String, Object>> RULE_RESULTS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcFuelRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /* ------------------------------------------------------------------ paging and filtering */

    /**
     * A WHERE clause and its bind values, minus the leading site-scope array.
     *
     * <p>Built once per query and used twice - for the count and for the page - so the two can
     * never disagree about which records they are describing.
     */
    private record Where(StringBuilder sql, List<Object> args) {
        static Where scoped() {
            return new Where(new StringBuilder("site_code = ANY (?)"), new ArrayList<>());
        }

        Where and(String fragment, Object value) {
            if (value != null) {
                sql.append(" AND ").append(fragment);
                args.add(value);
            }
            return this;
        }

        /** A predicate with no bind value - for IS NULL / IS NOT NULL and set tests. */
        Where when(boolean apply, String fragment) {
            if (apply) sql.append(" AND ").append(fragment);
            return this;
        }
    }

    /**
     * A validated ORDER BY.
     *
     * <p>The requested key is looked up in a per-resource allow-list rather than interpolated, so a
     * sort parameter can never reach SQL as text. Every ordering ends in {@code id}: rows that
     * share a sort value would otherwise be free to swap places between two requests, and a page
     * boundary falling inside such a group silently skips or repeats records.
     */
    private record Order(String sql, String describedAs) {}

    private static Order order(
            String requested,
            Map<String, String> allowed,
            String defaultKey,
            boolean defaultDescending) {
        String key = defaultKey;
        boolean descending = defaultDescending;
        if (requested != null && !requested.isBlank()) {
            String[] parts = requested.split(",");
            String candidate = parts[0].trim();
            if (allowed.containsKey(candidate)) {
                key = candidate;
                descending = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
            }
        }
        String direction = descending ? "DESC" : "ASC";
        return new Order(
                allowed.get(key) + " " + direction + ", id " + direction, key + ": " + direction);
    }

    private static final Map<String, String> TRANSACTION_SORTS =
            Map.of(
                    "occurredAt",
                    "occurred_at",
                    "totalCost",
                    "total_cost",
                    "quantity",
                    "quantity",
                    "odometerReading",
                    "odometer_reading",
                    "status",
                    "status",
                    "ingestedAt",
                    "ingestion_timestamp");
    private static final Map<String, String> LOGBOOK_SORTS =
            Map.of(
                    "journeyDate",
                    "journey_date",
                    "submittedAt",
                    "submitted_at",
                    "approvedAt",
                    "approved_at",
                    "status",
                    "status",
                    "createdAt",
                    "created_at");
    private static final Map<String, String> ANOMALY_SORTS =
            Map.of(
                    "slaDueAt",
                    "sla_due_at",
                    "severity",
                    "severity",
                    "status",
                    "status",
                    "createdAt",
                    "created_at",
                    "escalationLevel",
                    "escalation_level");
    private static final Map<String, String> POLICY_SORTS =
            Map.of(
                    "effectiveFrom",
                    "effective_from",
                    "policyVersion",
                    "policy_version",
                    "name",
                    "policy_name",
                    "status",
                    "status");
    private static final Map<String, String> IMPORT_ROW_SORTS =
            Map.of("rowNumber", "row_number", "status", "status", "errorCode", "error_code");
    private static final Map<String, String> IMPORT_SORTS =
            Map.of(
                    "submittedAt",
                    "submitted_at",
                    "totalRows",
                    "total_rows",
                    "rejectedRows",
                    "rejected_rows");

    /**
     * Binds the site array at index 1, then the clause's own values. Returns the next free index.
     */
    private static int bind(
            PreparedStatement ps, Connection con, List<String> sites, List<Object> args)
            throws SQLException {
        int i = 1;
        ps.setArray(i++, con.createArrayOf("varchar", sites.toArray()));
        for (Object arg : args) ps.setObject(i++, arg);
        return i;
    }

    private <T> FuelPage<T> page(
            String table,
            List<String> sites,
            Where where,
            Order order,
            Paging paging,
            RowMapper<T> mapper) {
        Long total =
                jdbc.query(
                        con ->
                                prepareAndBind(
                                        con,
                                        "SELECT COUNT(*) FROM " + table + " WHERE " + where.sql(),
                                        ps -> bind(ps, con, sites, where.args())),
                        rs -> rs.next() ? rs.getLong(1) : 0L);
        long totalElements = total == null ? 0L : total;
        if (totalElements == 0L)
            return FuelPage.empty(paging.page(), paging.size(), order.describedAs());

        List<T> content =
                jdbc.query(
                        con ->
                                prepareAndBind(
                                        con,
                                        "SELECT * FROM "
                                                + table
                                                + " WHERE "
                                                + where.sql()
                                                + " ORDER BY "
                                                + order.sql()
                                                + " LIMIT ? OFFSET ?",
                                        ps -> {
                                            int i = bind(ps, con, sites, where.args());
                                            ps.setInt(i++, paging.size());
                                            ps.setInt(i, paging.offset());
                                        }),
                        mapper);
        return FuelPage.of(
                content, paging.page(), paging.size(), totalElements, order.describedAs());
    }

    /* ------------------------------------------------------------------------------ policies */

    /**
     * Updates a policy if the caller's read is still current, or inserts one carrying this id for
     * the first time.
     *
     * <p>Was an unconditional {@code ON CONFLICT DO UPDATE} that bumped {@code version} without
     * ever checking it - every concurrent edit silently won, and the loser's changes vanished with
     * no error. Now the same conditional-update shape every other {@code save*} in this file uses:
     * update guarded by the version the caller read, insert only when the row does not exist yet,
     * and a version conflict throws rather than overwrites. The update list leaves {@code id} and
     * the creation stamp alone - who created a policy does not change because somebody later
     * revised its limits.
     */
    @Override
    public FuelPolicy savePolicy(FuelPolicy p) {
        int updated =
                jdbc.update(
                        """
            UPDATE fleet_logistics.fuel_policies SET
                policy_name=?, effective_from=?, effective_to=?, policy_version=?,
                max_per_transaction=?, daily_limit=?, monthly_limit=?, tank_capacity=?,
                min_consumption=?, max_consumption=?, odometer_jump_tolerance=?,
                receipt_required=?, receipt_grace_hours=?, materiality_amount=?, anomaly_sla_hours=?,
                cost_variance_tolerance=?, repeated_pattern_window_hours=?, repeated_pattern_threshold=?,
                allowed_fuel_products=?, approved_vendors=?, status=?,
                last_modified_by=?, last_modified_at=?, source_channel=?, audit_correlation_id=?,
                version=version+1
            WHERE id=? AND version=?
            """,
                        p.name(),
                        ts(p.effectiveFrom()),
                        ts(p.effectiveTo()),
                        p.policyVersion(),
                        p.maxPerTransaction(),
                        p.dailyLimit(),
                        p.monthlyLimit(),
                        p.tankCapacity(),
                        p.minConsumption(),
                        p.maxConsumption(),
                        p.odometerJumpTolerance(),
                        p.receiptRequired(),
                        p.receiptGraceHours(),
                        p.materialityAmount(),
                        p.anomalySlaHours(),
                        p.costVarianceTolerance(),
                        p.repeatedPatternWindowHours(),
                        p.repeatedPatternThreshold(),
                        String.join(",", p.allowedFuelProducts()),
                        String.join(",", p.approvedVendors()),
                        p.status().name(),
                        p.metadata().lastModifiedBy(),
                        ts(p.metadata().lastModifiedAt()),
                        p.metadata().sourceChannel().name(),
                        p.metadata().auditCorrelationId(),
                        p.id(),
                        p.metadata().version());
        if (updated == 0 && findPolicy(p.id()).isEmpty()) {
            jdbc.update(
                    """
                INSERT INTO fleet_logistics.fuel_policies (id,site_code,policy_name,effective_from,effective_to,policy_version,max_per_transaction,daily_limit,monthly_limit,tank_capacity,min_consumption,max_consumption,odometer_jump_tolerance,receipt_required,receipt_grace_hours,materiality_amount,anomaly_sla_hours,cost_variance_tolerance,repeated_pattern_window_hours,repeated_pattern_threshold,allowed_fuel_products,approved_vendors,status,created_by,created_at,last_modified_by,last_modified_at,source_channel,audit_correlation_id,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    p.id(),
                    p.siteCode().value(),
                    p.name(),
                    ts(p.effectiveFrom()),
                    ts(p.effectiveTo()),
                    p.policyVersion(),
                    p.maxPerTransaction(),
                    p.dailyLimit(),
                    p.monthlyLimit(),
                    p.tankCapacity(),
                    p.minConsumption(),
                    p.maxConsumption(),
                    p.odometerJumpTolerance(),
                    p.receiptRequired(),
                    p.receiptGraceHours(),
                    p.materialityAmount(),
                    p.anomalySlaHours(),
                    p.costVarianceTolerance(),
                    p.repeatedPatternWindowHours(),
                    p.repeatedPatternThreshold(),
                    String.join(",", p.allowedFuelProducts()),
                    String.join(",", p.approvedVendors()),
                    p.status().name(),
                    p.metadata().createdBy(),
                    ts(p.metadata().createdAt()),
                    p.metadata().lastModifiedBy(),
                    ts(p.metadata().lastModifiedAt()),
                    p.metadata().sourceChannel().name(),
                    p.metadata().auditCorrelationId(),
                    p.metadata().version());
        } else if (updated == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "FuelPolicy version conflict");
        }
        return findPolicy(p.id()).orElseThrow();
    }

    @Override
    public Optional<FuelPolicy> findApplicablePolicy(String site, Instant at) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_policies WHERE site_code=? AND status='ACTIVE' AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) ORDER BY effective_from DESC,policy_version DESC LIMIT 1",
                this::policy,
                site,
                ts(at),
                ts(at));
    }

    @Override
    public Optional<FuelPolicy> findPolicy(UUID id) {
        return one("SELECT * FROM fleet_logistics.fuel_policies WHERE id=?", this::policy, id);
    }

    @Override
    public FuelPage<FuelPolicy> findPolicies(PolicyQuery q) {
        Order order = order(q.paging().sort(), POLICY_SORTS, "effectiveFrom", true);
        if (q.sites().isEmpty())
            return FuelPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped().and("status=?", q.status() == null ? null : q.status().name());
        // "In force at" is an interval test, not a status: an ACTIVE policy whose period has not
        // started is not in force, and one with no end date runs until something supersedes it.
        if (q.inForceAt() != null) {
            where.sql()
                    .append(
                            " AND status='ACTIVE' AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)");
            where.args().add(ts(q.inForceAt()));
            where.args().add(ts(q.inForceAt()));
        }
        return page(
                "fleet_logistics.fuel_policies", q.sites(), where, order, q.paging(), this::policy);
    }

    @Override
    public List<FuelPolicy> findOverlappingActivePolicies(
            String site, Instant from, Instant to, UUID excluding) {
        // Half-open intervals, null meaning unbounded: two periods overlap unless one ends at or
        // before the other begins. The null branches are written explicitly rather than relying on
        // three-valued logic, so an open-ended policy on either side is treated as running forever.
        StringBuilder sql =
                new StringBuilder(
                        """
            SELECT * FROM fleet_logistics.fuel_policies
            WHERE site_code=? AND status='ACTIVE'
              AND (effective_to IS NULL OR effective_to > ?)
            """);
        List<Object> args = new ArrayList<>();
        args.add(site);
        args.add(ts(from));
        if (to != null) {
            sql.append(" AND effective_from < ?");
            args.add(ts(to));
        }
        if (excluding != null) {
            sql.append(" AND id<>?");
            args.add(excluding);
        }
        sql.append(" ORDER BY effective_from");
        return jdbc.query(sql.toString(), this::policy, args.toArray());
    }

    /* ------------------------------------------------------------------------- posted prices */

    @Override
    public FuelPostedPrice savePostedPrice(FuelPostedPrice p) {
        int updated =
                jdbc.update(
                        """
            UPDATE fleet_logistics.fuel_posted_prices SET unit_price=?,currency=?,effective_to=?,source=?,notes=?,last_modified_by=?,last_modified_at=?,source_channel=?,audit_correlation_id=?,version=version+1 WHERE id=? AND version=?
            """,
                        p.unitPrice(),
                        p.currency(),
                        ts(p.effectiveTo()),
                        p.source().name(),
                        p.notes(),
                        p.metadata().lastModifiedBy(),
                        ts(p.metadata().lastModifiedAt()),
                        p.metadata().sourceChannel().name(),
                        p.metadata().auditCorrelationId(),
                        p.id(),
                        p.metadata().version());
        if (updated == 0 && findPostedPriceById(p.id()).isEmpty()) {
            jdbc.update(
                    """
                INSERT INTO fleet_logistics.fuel_posted_prices (id,site_code,vendor,fuel_product,unit_price,currency,effective_from,effective_to,source,notes,created_by,created_at,last_modified_by,last_modified_at,source_channel,audit_correlation_id,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    p.id(),
                    p.siteCode().value(),
                    p.vendor(),
                    p.fuelProduct(),
                    p.unitPrice(),
                    p.currency(),
                    ts(p.effectiveFrom()),
                    ts(p.effectiveTo()),
                    p.source().name(),
                    p.notes(),
                    p.metadata().createdBy(),
                    ts(p.metadata().createdAt()),
                    p.metadata().lastModifiedBy(),
                    ts(p.metadata().lastModifiedAt()),
                    p.metadata().sourceChannel().name(),
                    p.metadata().auditCorrelationId(),
                    p.metadata().version());
        } else if (updated == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "FuelPostedPrice version conflict");
        }
        return findPostedPriceById(p.id()).orElseThrow();
    }

    private Optional<FuelPostedPrice> findPostedPriceById(UUID id) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_posted_prices WHERE id=?",
                this::postedPrice,
                id);
    }

    @Override
    public Optional<FuelPostedPrice> findPostedPrice(
            String site, String vendor, String product, Instant at) {
        // ORDER BY effective_from DESC LIMIT 1 rather than trusting the interval to be unique: the
        // partial unique index only guarantees one *open* row per vendor and product, and closed
        // rows
        // can legitimately abut. Newest-wins is the same rule findApplicablePolicy uses.
        return one(
                """
            SELECT * FROM fleet_logistics.fuel_posted_prices
            WHERE site_code=? AND vendor=? AND fuel_product=? AND effective_from<=?
              AND (effective_to IS NULL OR effective_to>?)
            ORDER BY effective_from DESC LIMIT 1
            """,
                this::postedPrice,
                site,
                vendor.toUpperCase(),
                product.toUpperCase(),
                ts(at),
                ts(at));
    }

    @Override
    public List<FuelPostedPrice> findPostedPrices(
            String site, String vendor, String product, boolean inForceOnly, Instant at) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT * FROM fleet_logistics.fuel_posted_prices WHERE site_code=?");
        List<Object> args = new ArrayList<>();
        args.add(site);
        if (vendor != null && !vendor.isBlank()) {
            sql.append(" AND vendor=?");
            args.add(vendor.toUpperCase());
        }
        if (product != null && !product.isBlank()) {
            sql.append(" AND fuel_product=?");
            args.add(product.toUpperCase());
        }
        if (inForceOnly) {
            sql.append(" AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)");
            args.add(ts(at));
            args.add(ts(at));
        }
        sql.append(" ORDER BY vendor, fuel_product, effective_from DESC");
        return jdbc.query(sql.toString(), this::postedPrice, args.toArray());
    }

    @Override
    public Optional<FuelPostedPrice> findOpenPostedPrice(
            String site, String vendor, String product) {
        return one(
                """
            SELECT * FROM fleet_logistics.fuel_posted_prices
            WHERE site_code=? AND vendor=? AND fuel_product=? AND effective_to IS NULL
            """,
                this::postedPrice,
                site,
                vendor.toUpperCase(),
                product.toUpperCase());
    }

    /* -------------------------------------------------------------------------- transactions */

    @Override
    public FuelTransaction saveTransaction(FuelTransaction t) {
        // The update list is deliberately short: a fuel transaction is an immutable record of an
        // event, and only its workflow state, its comments and its evidence links may change after
        // capture. `pump_evidence_id` joins that list for the same reason `receipt_evidence_id` is
        // on
        // it - an image can legitimately be attached after the fact, within the policy's grace
        // window.
        int updated =
                jdbc.update(
                        """
            UPDATE fleet_logistics.fuel_transactions SET status=?,lifecycle_status=?,comments=?,receipt_evidence_id=?,pump_evidence_id=?,last_modified_by=?,last_modified_at=?,source_channel=?,audit_correlation_id=?,version=version+1 WHERE id=? AND version=?
            """,
                        t.status().name(),
                        t.lifecycle().name(),
                        t.comments(),
                        t.receiptEvidenceId(),
                        t.pumpEvidenceId(),
                        t.metadata().lastModifiedBy(),
                        ts(t.metadata().lastModifiedAt()),
                        t.metadata().sourceChannel().name(),
                        t.metadata().auditCorrelationId(),
                        t.id(),
                        t.metadata().version());
        if (updated == 0 && findTransaction(t.id()).isEmpty()) {
            jdbc.update(
                    """
                INSERT INTO fleet_logistics.fuel_transactions (id,site_code,provider_transaction_id,source_system,vehicle_id,driver_id,trip_id,occurred_at,vendor_reference,station_reference,fuel_product,quantity,quantity_unit,unit_price,total_cost,currency,masked_card_reference,odometer_reading,receipt_evidence_id,pump_evidence_id,comments,status,lifecycle_status,ingestion_timestamp,idempotency_key,created_by,created_at,last_modified_by,last_modified_at,source_channel,audit_correlation_id,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    t.id(),
                    t.siteCode().value(),
                    t.providerTransactionId(),
                    t.sourceSystem(),
                    t.vehicleId(),
                    t.driverId(),
                    t.tripId(),
                    ts(t.occurredAt()),
                    t.vendorReference(),
                    t.stationReference(),
                    t.fuelProduct(),
                    t.quantity(),
                    t.quantityUnit(),
                    t.unitPrice(),
                    t.totalCost(),
                    t.currency().getCurrencyCode(),
                    t.maskedCardReference(),
                    t.odometerReading(),
                    t.receiptEvidenceId(),
                    t.pumpEvidenceId(),
                    t.comments(),
                    t.status().name(),
                    t.lifecycle().name(),
                    ts(t.ingestionTimestamp()),
                    t.idempotencyKey(),
                    t.metadata().createdBy(),
                    ts(t.metadata().createdAt()),
                    t.metadata().lastModifiedBy(),
                    ts(t.metadata().lastModifiedAt()),
                    t.metadata().sourceChannel().name(),
                    t.metadata().auditCorrelationId(),
                    t.metadata().version());
        } else if (updated == 0)
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "FuelTransaction version conflict");
        return findTransaction(t.id()).orElseThrow();
    }

    @Override
    public Optional<FuelTransaction> findTransaction(UUID id) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_transactions WHERE id=?",
                this::transaction,
                id);
    }

    @Override
    public Optional<FuelTransaction> findProviderTransaction(
            String site, String source, String providerId) {
        if (providerId == null) return Optional.empty();
        return one(
                "SELECT * FROM fleet_logistics.fuel_transactions WHERE site_code=? AND source_system=? AND provider_transaction_id=?",
                this::transaction,
                site,
                source,
                providerId);
    }

    @Override
    public FuelPage<FuelTransaction> findTransactions(TransactionQuery q) {
        Order order = order(q.paging().sort(), TRANSACTION_SORTS, "occurredAt", true);
        if (q.sites().isEmpty())
            return FuelPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where =
                Where.scoped()
                        .and("site_code=?", q.siteCode())
                        .and("status=?", q.status() == null ? null : q.status().name())
                        .and("vehicle_id=?", q.vehicleId())
                        .and("driver_id=?", q.driverId())
                        .and("source_system=?", q.sourceSystem())
                        // Vendor is a contains-match: an operator searches for "CLET", not the
                        // exact string.
                        .and(
                                "vendor_reference ILIKE ?",
                                q.vendorReference() == null
                                        ? null
                                        : "%" + q.vendorReference() + "%")
                        .and("occurred_at>=?", q.from() == null ? null : ts(q.from()))
                        .and("occurred_at<?", q.to() == null ? null : ts(q.to()));
        return page(
                "fleet_logistics.fuel_transactions",
                q.sites(),
                where,
                order,
                q.paging(),
                this::transaction);
    }

    @Override
    public BigDecimal sumTransactionQuantity(SpendWindowQuery q) {
        StringBuilder sql =
                new StringBuilder(
                        """
                SELECT COALESCE(SUM(quantity),0)
                  FROM fleet_logistics.fuel_transactions
                 WHERE site_code=? AND lifecycle_status='ACTIVE'
                   AND occurred_at>=? AND occurred_at<?
                """);
        List<Object> args = new ArrayList<>();
        args.add(SiteCode.of(q.siteCode()).value());
        args.add(ts(q.fromInclusive()));
        args.add(ts(q.toExclusive()));
        if (q.vehicleId() != null) {
            sql.append(" AND vehicle_id=?");
            args.add(q.vehicleId());
        }
        if (q.driverId() != null) {
            sql.append(" AND driver_id=?");
            args.add(q.driverId());
        }
        if (q.maskedCardReference() != null && !q.maskedCardReference().isBlank()) {
            sql.append(" AND masked_card_reference=?");
            args.add(q.maskedCardReference().strip());
        }
        BigDecimal total = jdbc.queryForObject(sql.toString(), BigDecimal.class, args.toArray());
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    public BigDecimal sumTransactionCost(SpendWindowQuery q) {
        StringBuilder sql =
                new StringBuilder(
                        """
                SELECT COALESCE(SUM(total_cost),0)
                  FROM fleet_logistics.fuel_transactions
                 WHERE site_code=? AND lifecycle_status='ACTIVE'
                   AND occurred_at>=? AND occurred_at<?
                """);
        List<Object> args = new ArrayList<>();
        args.add(SiteCode.of(q.siteCode()).value());
        args.add(ts(q.fromInclusive()));
        args.add(ts(q.toExclusive()));
        if (q.vehicleId() != null) {
            sql.append(" AND vehicle_id=?");
            args.add(q.vehicleId());
        }
        if (q.driverId() != null) {
            sql.append(" AND driver_id=?");
            args.add(q.driverId());
        }
        if (q.maskedCardReference() != null && !q.maskedCardReference().isBlank()) {
            sql.append(" AND masked_card_reference=?");
            args.add(q.maskedCardReference().strip());
        }
        BigDecimal total = jdbc.queryForObject(sql.toString(), BigDecimal.class, args.toArray());
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    public Optional<FuelTransaction> findPreviousTransaction(
            String site, UUID vehicle, Instant before) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_transactions WHERE site_code=? AND vehicle_id=? AND occurred_at<? AND lifecycle_status='ACTIVE' ORDER BY occurred_at DESC LIMIT 1",
                this::transaction,
                site,
                vehicle,
                ts(before));
    }

    @Override
    public boolean hasLaterTransaction(String site, UUID vehicle, Instant after, UUID excludingId) {
        Boolean exists =
                jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM fleet_logistics.fuel_transactions WHERE site_code=? AND vehicle_id=? AND occurred_at>? AND id<>? AND lifecycle_status='ACTIVE')",
                        Boolean.class,
                        site,
                        vehicle,
                        ts(after),
                        excludingId);
        return Boolean.TRUE.equals(exists);
    }

    /* ------------------------------------------------------------------------------ logbooks */

    @Override
    public DriverLogbook saveLogbook(DriverLogbook l) {
        int updated =
                jdbc.update(
                        """
            UPDATE fleet_logistics.driver_logbooks
               SET driver_id=?,vehicle_id=?,trip_id=?,journey_date=?,start_time=?,end_time=?,origin=?,
                   destination=?,route_notes=?,use_classification=?,purpose=?,passenger_load_notes=?,
                   start_odometer=?,end_odometer=?,declaration_accepted=?,evidence_id=?,status=?,
                   review_comment=?,transition_reason=?,submitted_at=?,approved_at=?,last_modified_by=?,
                   last_modified_at=?,source_channel=?,audit_correlation_id=?,version=version+1
             WHERE id=? AND version=?
            """,
                        l.driverId(),
                        l.vehicleId(),
                        l.tripId(),
                        l.journeyDate(),
                        ts(l.startTime()),
                        ts(l.endTime()),
                        l.origin(),
                        l.destination(),
                        l.routeNotes(),
                        l.useClassification().name(),
                        l.purpose(),
                        l.passengerLoadNotes(),
                        l.startOdometer(),
                        l.endOdometer(),
                        l.declarationAccepted(),
                        l.evidenceId(),
                        l.status().name(),
                        l.reviewComment(),
                        l.transitionReason(),
                        ts(l.submittedAt()),
                        ts(l.approvedAt()),
                        l.metadata().lastModifiedBy(),
                        ts(l.metadata().lastModifiedAt()),
                        l.metadata().sourceChannel().name(),
                        l.metadata().auditCorrelationId(),
                        l.id(),
                        l.metadata().version());
        if (updated == 0 && findLogbook(l.id()).isEmpty())
            jdbc.update(
                    """
            INSERT INTO fleet_logistics.driver_logbooks (id,logbook_number,site_code,driver_id,vehicle_id,trip_id,journey_date,start_time,end_time,origin,destination,route_notes,use_classification,purpose,passenger_load_notes,start_odometer,end_odometer,declaration_accepted,evidence_id,status,review_comment,transition_reason,submitted_at,approved_at,created_by,created_at,last_modified_by,last_modified_at,source_channel,audit_correlation_id,version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                    l.id(),
                    l.logbookNumber(),
                    l.siteCode().value(),
                    l.driverId(),
                    l.vehicleId(),
                    l.tripId(),
                    l.journeyDate(),
                    ts(l.startTime()),
                    ts(l.endTime()),
                    l.origin(),
                    l.destination(),
                    l.routeNotes(),
                    l.useClassification().name(),
                    l.purpose(),
                    l.passengerLoadNotes(),
                    l.startOdometer(),
                    l.endOdometer(),
                    l.declarationAccepted(),
                    l.evidenceId(),
                    l.status().name(),
                    l.reviewComment(),
                    l.transitionReason(),
                    ts(l.submittedAt()),
                    ts(l.approvedAt()),
                    l.metadata().createdBy(),
                    ts(l.metadata().createdAt()),
                    l.metadata().lastModifiedBy(),
                    ts(l.metadata().lastModifiedAt()),
                    l.metadata().sourceChannel().name(),
                    l.metadata().auditCorrelationId(),
                    l.metadata().version());
        else if (updated == 0)
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "DriverLogbook version conflict");
        return findLogbook(l.id()).orElseThrow();
    }

    @Override
    public Optional<DriverLogbook> findLogbook(UUID id) {
        return one("SELECT * FROM fleet_logistics.driver_logbooks WHERE id=?", this::logbook, id);
    }

    @Override
    public FuelPage<DriverLogbook> findLogbooks(LogbookQuery q) {
        Order order = order(q.paging().sort(), LOGBOOK_SORTS, "journeyDate", true);
        if (q.sites().isEmpty())
            return FuelPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where =
                Where.scoped()
                        // A FLEET_DRIVER-only actor sees their own records and nothing else.
                        .and("created_by=?", q.ownOnly() ? q.actorId() : null)
                        .and("status=?", q.status() == null ? null : q.status().name())
                        .and("driver_id=?", q.driverId())
                        .and("vehicle_id=?", q.vehicleId())
                        .and(
                                "use_classification=?",
                                q.useClassification() == null ? null : q.useClassification().name())
                        .and("journey_date>=?", q.journeyFrom())
                        .and("journey_date<=?", q.journeyTo());
        return page(
                "fleet_logistics.driver_logbooks",
                q.sites(),
                where,
                order,
                q.paging(),
                this::logbook);
    }

    @Override
    public Optional<DriverLogbook> findLogbookForTrip(UUID trip) {
        if (trip == null) return Optional.empty();
        return one(
                "SELECT * FROM fleet_logistics.driver_logbooks WHERE trip_id=? AND status<>'CANCELLED' ORDER BY journey_date DESC LIMIT 1",
                this::logbook,
                trip);
    }

    /* ----------------------------------------------------------------------------- anomalies */

    @Override
    public FuelAnomalyCase saveAnomaly(FuelAnomalyCase a) {
        int updated =
                jdbc.update(
                        "UPDATE fleet_logistics.fuel_anomaly_cases SET status=?,assignee=?,explanation=?,evidence_id=?,manager_decision=?,closure_reason=?,escalation_level=?,last_modified_by=?,last_modified_at=?,source_channel=?,audit_correlation_id=?,version=version+1 WHERE id=? AND version=?",
                        a.status().name(),
                        a.assignee(),
                        a.explanation(),
                        a.evidenceId(),
                        a.decision() == null ? null : a.decision().name(),
                        a.closureReason(),
                        a.escalationLevel(),
                        a.metadata().lastModifiedBy(),
                        ts(a.metadata().lastModifiedAt()),
                        a.metadata().sourceChannel().name(),
                        a.metadata().auditCorrelationId(),
                        a.id(),
                        a.metadata().version());
        if (updated == 0 && findAnomaly(a.id()).isEmpty())
            jdbc.update(
                    """
        INSERT INTO fleet_logistics.fuel_anomaly_cases (id,anomaly_number,site_code,transaction_id,logbook_id,vehicle_id,driver_id,trip_id,anomaly_type,severity,material,status,assignee,sla_due_at,explanation,evidence_id,manager_decision,closure_reason,escalation_level,detected_rules,created_by,created_at,last_modified_by,last_modified_at,source_channel,audit_correlation_id,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
        """,
                    a.id(),
                    a.anomalyNumber(),
                    a.siteCode().value(),
                    a.transactionId(),
                    a.logbookId(),
                    a.vehicleId(),
                    a.driverId(),
                    a.tripId(),
                    a.type().name(),
                    a.severity().name(),
                    a.material(),
                    a.status().name(),
                    a.assignee(),
                    ts(a.slaDueAt()),
                    a.explanation(),
                    a.evidenceId(),
                    a.decision() == null ? null : a.decision().name(),
                    a.closureReason(),
                    a.escalationLevel(),
                    json(a.detectedRules()),
                    a.metadata().createdBy(),
                    ts(a.metadata().createdAt()),
                    a.metadata().lastModifiedBy(),
                    ts(a.metadata().lastModifiedAt()),
                    a.metadata().sourceChannel().name(),
                    a.metadata().auditCorrelationId(),
                    a.metadata().version());
        else if (updated == 0)
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "FuelAnomalyCase version conflict");
        return findAnomaly(a.id()).orElseThrow();
    }

    @Override
    public Optional<FuelAnomalyCase> findAnomaly(UUID id) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_anomaly_cases WHERE id=?", this::anomaly, id);
    }

    @Override
    public Optional<FuelAnomalyCase> findAnomaly(UUID tx, FuelAnomalyCase.Type type) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_anomaly_cases WHERE transaction_id=? AND anomaly_type=?",
                this::anomaly,
                tx,
                type.name());
    }

    @Override
    public Optional<FuelAnomalyCase> findAnomalyForTrip(UUID trip, FuelAnomalyCase.Type type) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_anomaly_cases WHERE trip_id=? AND transaction_id IS NULL AND anomaly_type=?",
                this::anomaly,
                trip,
                type.name());
    }

    /** Neither closed nor cancelled - what "open" means everywhere in this module. */
    private static final String OPEN_STATUSES = "status NOT IN ('CLOSED','CANCELLED')";

    @Override
    public FuelPage<FuelAnomalyCase> findAnomalies(AnomalyQuery q) {
        Order order = order(q.paging().sort(), ANOMALY_SORTS, "slaDueAt", false);
        if (q.sites().isEmpty())
            return FuelPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where =
                Where.scoped()
                        .and("status=?", q.status() == null ? null : q.status().name())
                        .and("anomaly_type=?", q.type() == null ? null : q.type().name())
                        .and("severity=?", q.severity() == null ? null : q.severity().name())
                        .and(
                                "assignee ILIKE ?",
                                q.assignee() == null ? null : "%" + q.assignee() + "%")
                        .and("material=?", q.material())
                        .and("sla_due_at<?", q.dueBefore() == null ? null : ts(q.dueBefore()))
                        .and("transaction_id=?", q.transactionId())
                        .and("vehicle_id=?", q.vehicleId())
                        .and("driver_id=?", q.driverId())
                        .when(Boolean.TRUE.equals(q.openOnly()), OPEN_STATUSES)
                        .when(Boolean.TRUE.equals(q.unassigned()), "assignee IS NULL")
                        .when(Boolean.FALSE.equals(q.unassigned()), "assignee IS NOT NULL");
        return page(
                "fleet_logistics.fuel_anomaly_cases",
                q.sites(),
                where,
                order,
                q.paging(),
                this::anomaly);
    }

    @Override
    public long countRecentAnomalies(List<String> sites, UUID vehicle, UUID driver, Instant since) {
        if (sites.isEmpty()) return 0L;
        Long count =
                jdbc.query(
                        con ->
                                prepareAndBind(
                                        con,
                                        "SELECT COUNT(*) FROM fleet_logistics.fuel_anomaly_cases WHERE site_code = ANY (?) AND (vehicle_id=? OR driver_id=?) AND created_at>=? AND status<>'CANCELLED'",
                                        ps -> {
                                            ps.setArray(
                                                    1,
                                                    con.createArrayOf("varchar", sites.toArray()));
                                            ps.setObject(2, vehicle);
                                            ps.setObject(3, driver);
                                            ps.setObject(4, ts(since));
                                        }),
                        rs -> rs.next() ? rs.getLong(1) : 0L);
        return count == null ? 0L : count;
    }

    /* ----------------------------------------------------------------------- reconciliations */

    @Override
    public void saveReconciliation(
            UUID id,
            UUID tx,
            UUID policy,
            Integer version,
            String outcome,
            BigDecimal consumption,
            Instant at,
            String actor,
            Map<String, Object> rules,
            String correlation) {
        jdbc.update(
                "INSERT INTO fleet_logistics.fuel_reconciliations(id,transaction_id,policy_id,policy_version,outcome,calculated_consumption,evaluated_at,evaluated_by,rule_results,correlation_id) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)",
                id,
                tx,
                policy,
                version,
                outcome,
                consumption,
                ts(at),
                actor,
                json(rules),
                correlation);
    }

    @Override
    public List<FuelReconciliation> findReconciliations(UUID transactionId) {
        return jdbc.query(
                "SELECT * FROM fleet_logistics.fuel_reconciliations WHERE transaction_id=? ORDER BY evaluated_at DESC",
                this::reconciliation,
                transactionId);
    }

    /* ------------------------------------------------------------------------------- imports */

    @Override
    public FuelImportBatch saveImportBatch(FuelImportBatch b) {
        try {
            jdbc.update(
                    """
                INSERT INTO fleet_logistics.fuel_import_batches(id,site_code,source_system,file_name,file_hash,status,total_rows,accepted_rows,rejected_rows,submitted_by,submitted_at,correlation_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    b.id(),
                    b.siteCode().value(),
                    b.sourceSystem(),
                    b.fileName(),
                    b.fileHash(),
                    b.status().name(),
                    b.totalRows(),
                    b.acceptedRows(),
                    b.rejectedRows(),
                    b.submittedBy(),
                    ts(b.submittedAt()),
                    b.correlationId());
        } catch (DuplicateKeyException duplicate) {
            // uq_fuel_import_file. Before this, the violation escaped unmapped and the operator got
            // a
            // bare 500; the original batch is looked up so the error can name it.
            UUID existing =
                    findImportBatchByHash(b.siteCode().value(), b.sourceSystem(), b.fileHash())
                            .map(FuelImportBatch::id)
                            .orElse(null);
            throw FuelImportAlreadyProcessedException.of(
                    b.siteCode().value(), b.sourceSystem(), b.fileName(), b.fileHash(), existing);
        }
        for (FuelImportRow row : b.rows()) {
            jdbc.update(
                    """
                INSERT INTO fleet_logistics.fuel_import_rows(id,batch_id,row_number,status,transaction_id,error_code,error_message,raw_record)
                VALUES (?,?,?,?,?,?,?,?::jsonb)
                """,
                    row.id() == null ? UUID.randomUUID() : row.id(),
                    b.id(),
                    row.rowNumber(),
                    row.status().name(),
                    row.transactionId(),
                    row.errorCode(),
                    row.errorMessage(),
                    "{}");
        }
        return findImportBatch(b.id()).orElse(b);
    }

    @Override
    public FuelPage<FuelImportBatch> findImportBatches(ImportQuery q) {
        Order order = order(q.paging().sort(), IMPORT_SORTS, "submittedAt", true);
        if (q.sites().isEmpty())
            return FuelPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped().and("source_system=?", q.sourceSystem());
        // Headers only: a list that dragged every row with it would be unusable at scale.
        return page(
                "fleet_logistics.fuel_import_batches",
                q.sites(),
                where,
                order,
                q.paging(),
                this::importBatchHeader);
    }

    @Override
    public Optional<FuelImportBatch> findImportBatch(UUID id) {
        return one(
                        "SELECT * FROM fleet_logistics.fuel_import_batches WHERE id=?",
                        this::importBatchHeader,
                        id)
                .map(
                        batch ->
                                withRows(
                                        batch,
                                        jdbc.query(
                                                "SELECT * FROM fleet_logistics.fuel_import_rows WHERE batch_id=? ORDER BY row_number",
                                                this::importRow,
                                                id)));
    }

    @Override
    public Optional<FuelImportBatch> findImportBatchByHash(
            String site, String source, String hash) {
        return one(
                "SELECT * FROM fleet_logistics.fuel_import_batches WHERE site_code=? AND source_system=? AND file_hash=?",
                this::importBatchHeader,
                site,
                source,
                hash);
    }

    private static FuelImportBatch withRows(FuelImportBatch batch, List<FuelImportRow> rows) {
        return new FuelImportBatch(
                batch.id(),
                batch.siteCode(),
                batch.sourceSystem(),
                batch.fileName(),
                batch.fileHash(),
                batch.status(),
                batch.totalRows(),
                batch.acceptedRows(),
                batch.rejectedRows(),
                batch.submittedBy(),
                batch.submittedAt(),
                batch.correlationId(),
                rows);
    }

    /* ----------------------------------------------------------------------------- dashboard */

    /**
     * The site's fuel standing in one round trip.
     *
     * <p>The five transaction figures still come from {@code fuel_dashboard_summary}. The anomaly,
     * logbook and import indicators are counted here rather than derived by the client: a dashboard
     * that sums whatever a capped list returned reports the truth about its own window, not about
     * the site, and the difference only shows up once there is enough data for it to matter.
     */
    @Override
    public FuelPage<FuelImportRow> findImportRows(
            UUID batchId, FuelImportRow.Status status, Paging paging) {
        Order order = order(paging.sort(), IMPORT_ROW_SORTS, "rowNumber", false);
        // The same predicate for the count and the page, so the total describes what is being
        // paged.
        String where = "WHERE batch_id=?" + (status == null ? "" : " AND status=?");
        List<Object> args = new ArrayList<>();
        args.add(batchId);
        if (status != null) args.add(status.name());
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM fleet_logistics.fuel_import_rows " + where,
                        Long.class,
                        args.toArray());
        long totalElements = total == null ? 0L : total;
        if (totalElements == 0L)
            return FuelPage.empty(paging.page(), paging.size(), order.describedAs());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.size());
        pageArgs.add(paging.offset());
        List<FuelImportRow> content =
                jdbc.query(
                        "SELECT * FROM fleet_logistics.fuel_import_rows "
                                + where
                                + " ORDER BY "
                                + order.sql()
                                + " LIMIT ? OFFSET ?",
                        this::importRow,
                        pageArgs.toArray());
        return FuelPage.of(
                content, paging.page(), paging.size(), totalElements, order.describedAs());
    }

    @Override
    public List<DailyFuelTotals> dailyTotals(
            List<String> sites, String site, Instant from, Instant to) {
        if (sites.isEmpty()) return List.of();
        // date_trunc to a day in UTC, matching how every other timestamp in this schema is stored.
        StringBuilder sql =
                new StringBuilder(
                        """
                SELECT (date_trunc('day', occurred_at) AT TIME ZONE 'UTC')::date AS day,
                       COALESCE(SUM(total_cost),0) AS total_cost,
                       COALESCE(SUM(quantity),0) AS quantity,
                       COUNT(*) AS transaction_count
                  FROM fleet_logistics.fuel_transactions
                 WHERE site_code = ANY (?) AND lifecycle_status='ACTIVE'
                """);
        List<Object> args = new ArrayList<>();
        if (site != null) {
            sql.append(" AND site_code=?");
            args.add(SiteCode.of(site).value());
        }
        if (from != null) {
            sql.append(" AND occurred_at>=?");
            args.add(ts(from));
        }
        if (to != null) {
            sql.append(" AND occurred_at<?");
            args.add(ts(to));
        }
        sql.append(" GROUP BY day ORDER BY day");
        String query = sql.toString();
        return jdbc.query(
                con ->
                        prepareAndBind(
                                con,
                                query,
                                ps -> {
                                    int i = 1;
                                    ps.setArray(i++, con.createArrayOf("varchar", sites.toArray()));
                                    for (Object arg : args) ps.setObject(i++, arg);
                                }),
                (rs, n) ->
                        new DailyFuelTotals(
                                rs.getObject("day", java.time.LocalDate.class),
                                rs.getBigDecimal("total_cost"),
                                rs.getBigDecimal("quantity"),
                                rs.getLong("transaction_count")));
    }

    @Override
    public Map<String, Long> anomalyCountsByType(List<String> sites, String site) {
        if (sites.isEmpty()) return Map.of();
        StringBuilder sql =
                new StringBuilder(
                        """
                SELECT anomaly_type, COUNT(*) FROM fleet_logistics.fuel_anomaly_cases
                 WHERE site_code = ANY (?) AND status NOT IN ('CLOSED','CANCELLED')
                """);
        List<Object> args = new ArrayList<>();
        if (site != null) {
            sql.append(" AND site_code=?");
            args.add(SiteCode.of(site).value());
        }
        sql.append(" GROUP BY anomaly_type");
        String query = sql.toString();
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(
                con ->
                        prepareAndBind(
                                con,
                                query,
                                ps -> {
                                    int i = 1;
                                    ps.setArray(i++, con.createArrayOf("varchar", sites.toArray()));
                                    for (Object arg : args) ps.setObject(i++, arg);
                                }),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    @Override
    public Map<String, Object> dashboard(List<String> sites, String site, Instant now) {
        if (sites.isEmpty()) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        String scope = (site == null ? "" : " AND site_code=?");

        jdbc.query(
                con ->
                        prepareAndBind(
                                con,
                                "SELECT COALESCE(SUM(transaction_count),0),COALESCE(SUM(fuel_volume),0),COALESCE(SUM(fuel_spend),0),COALESCE(SUM(reconciled_count),0),COALESCE(SUM(exception_count),0),MAX(source_updated_at) AS source_updated_at FROM fleet_logistics.fuel_dashboard_summary WHERE site_code = ANY (?)"
                                        + scope,
                                ps -> {
                                    ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
                                    if (site != null) ps.setString(2, site);
                                }),
                rs -> {
                    if (rs.next()) {
                        m.put("transactionCount", rs.getLong(1));
                        m.put("fuelVolume", rs.getBigDecimal(2));
                        m.put("fuelSpend", rs.getBigDecimal(3));
                        m.put("reconciledCount", rs.getLong(4));
                        m.put("exceptionCount", rs.getLong(5));
                        m.put("sourceUpdatedAt", instant(rs, "source_updated_at"));
                    }
                    return null;
                });

        // Transactions that have never been reconciled - the backlog a run would clear.
        m.put(
                "awaitingReconciliation",
                count("fleet_logistics.fuel_transactions", "status='RECEIVED'", sites, site, null));

        m.put(
                "openAnomalies",
                count("fleet_logistics.fuel_anomaly_cases", OPEN_STATUSES, sites, site, null));
        m.put(
                "anomaliesBreachingSla",
                count(
                        "fleet_logistics.fuel_anomaly_cases",
                        OPEN_STATUSES + " AND sla_due_at<?",
                        sites,
                        site,
                        ts(now)));
        m.put(
                "materialOpenAnomalies",
                count(
                        "fleet_logistics.fuel_anomaly_cases",
                        OPEN_STATUSES + " AND material=true",
                        sites,
                        site,
                        null));
        m.put(
                "unassignedAnomalies",
                count(
                        "fleet_logistics.fuel_anomaly_cases",
                        OPEN_STATUSES + " AND assignee IS NULL",
                        sites,
                        site,
                        null));

        m.put(
                "pendingLogbookReviews",
                count(
                        "fleet_logistics.driver_logbooks",
                        "status IN ('SUBMITTED','RESUBMITTED','UNDER_REVIEW')",
                        sites,
                        site,
                        null));
        m.put(
                "draftLogbooks",
                count("fleet_logistics.driver_logbooks", "status='DRAFT'", sites, site, null));

        m.put(
                "importBatches",
                count("fleet_logistics.fuel_import_batches", "1=1", sites, site, null));
        m.put(
                "importBatchesWithErrors",
                count("fleet_logistics.fuel_import_batches", "rejected_rows>0", sites, site, null));
        jdbc.query(
                con ->
                        prepareAndBind(
                                con,
                                "SELECT MAX(submitted_at) FROM fleet_logistics.fuel_import_batches WHERE site_code = ANY (?)"
                                        + scope,
                                ps -> {
                                    ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
                                    if (site != null) ps.setString(2, site);
                                }),
                rs -> {
                    m.put("lastImportAt", rs.next() ? instant(rs, 1) : null);
                    return null;
                });

        return m;
    }

    @Override
    public List<String> activeSites() {
        return jdbc.query(
                """
                SELECT DISTINCT site_code FROM fleet_logistics.fuel_transactions
                UNION SELECT DISTINCT site_code FROM fleet_logistics.fuel_anomaly_cases
                UNION SELECT DISTINCT site_code FROM fleet_logistics.trips WHERE status='COMPLETED'
                """,
                (rs, n) -> rs.getString(1));
    }

    @Override
    public List<UUID> findLateReceiptTransactionIds(String siteCode, int limit) {
        return jdbc.query(
                """
                SELECT t.id FROM fleet_logistics.fuel_transactions t
                WHERE t.site_code=? AND t.status='RECONCILED' AND t.receipt_evidence_id IS NULL
                  AND EXISTS (SELECT 1 FROM fleet_logistics.fuel_policies p WHERE p.site_code=t.site_code
                      AND p.status='ACTIVE' AND p.receipt_required=true AND p.effective_from<=t.occurred_at
                      AND (p.effective_to IS NULL OR p.effective_to>t.occurred_at)
                      AND t.occurred_at + make_interval(hours => p.receipt_grace_hours) <= now())
                ORDER BY t.occurred_at LIMIT ?
                """,
                (rs, n) -> (UUID) rs.getObject(1),
                siteCode,
                bound(limit));
    }

    @Override
    public List<MissingLogbookTrip> findMissingLogbookTrips(String siteCode, int limit) {
        return jdbc.query(
                """
                SELECT t.id,t.vehicle_id,t.driver_id FROM fleet_logistics.trips t
                WHERE t.site_code=? AND t.status='COMPLETED' AND t.vehicle_id IS NOT NULL AND t.driver_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM fleet_logistics.driver_logbooks l WHERE l.trip_id=t.id AND l.status<>'CANCELLED')
                ORDER BY t.id LIMIT ?
                """,
                (rs, n) ->
                        new MissingLogbookTrip(
                                (UUID) rs.getObject(1),
                                (UUID) rs.getObject(2),
                                (UUID) rs.getObject(3)),
                siteCode,
                bound(limit));
    }

    private static int bound(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }

    /**
     * A {@code PreparedStatement} created and then closed if binding its parameters fails.
     *
     * <p>Binding used to run directly inside the {@code PreparedStatementCreator} lambda: every
     * site-scoped query in this class builds its statement in two steps, {@code
     * con.prepareStatement(sql)} then one or more {@code ps.setXxx(...)}/ {@code
     * createArrayOf(...)} calls, all of which declare {@code throws SQLException}. If a bind call
     * threw, the already-created statement was never returned to {@code JdbcTemplate} and so never
     * reached the {@code close()} it normally guarantees.
     */
    private static PreparedStatement prepareAndBind(Connection con, String sql, SqlBinder binder)
            throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        try {
            binder.bind(ps);
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /** One scoped COUNT. {@code extra} is bound after the site scope when present. */
    private long count(
            String table, String predicate, List<String> sites, String site, Object extra) {
        Long value =
                jdbc.query(
                        con ->
                                prepareAndBind(
                                        con,
                                        "SELECT COUNT(*) FROM "
                                                + table
                                                + " WHERE site_code = ANY (?)"
                                                + (site == null ? "" : " AND site_code=?")
                                                + " AND "
                                                + predicate,
                                        ps -> {
                                            int i = 1;
                                            ps.setArray(
                                                    i++,
                                                    con.createArrayOf("varchar", sites.toArray()));
                                            if (site != null) ps.setString(i++, site);
                                            if (extra != null) ps.setObject(i, extra);
                                        }),
                        rs -> rs.next() ? rs.getLong(1) : 0L);
        return value == null ? 0L : value;
    }

    /* ------------------------------------------------------------------------------- mappers */

    // ---- fuel cards (SRS-SFL-S168fuel-04)
    // ---------------------------------------------------------

    private static final Map<String, String> CARD_SORTS =
            Map.of(
                    "issuedOn",
                    "issued_on",
                    "maskedReference",
                    "masked_reference",
                    "status",
                    "status");

    /**
     * Updates a card if the caller's read is still current, or inserts one carrying this id for the
     * first time.
     *
     * <p>The update was already guarded by {@code WHERE id=? AND version=?}, but a version conflict
     * (row exists, {@code updated == 0}) fell through both branches and returned the caller's own,
     * never-persisted argument as if it had saved - the caller had no way to know their write was
     * dropped. Now a conflict throws, matching every other {@code save*} in this file, and a
     * successful save returns a fresh read so the caller sees the version the database actually
     * holds.
     */
    @Override
    public FuelCard saveCard(FuelCard c) {
        int updated =
                jdbc.update(
                        "UPDATE fleet_logistics.fuel_cards SET vehicle_id=?,driver_id=?,status=?,expires_on=?,daily_limit=?,monthly_limit=?,per_transaction_limit=?,suspension_reason=?,notes=?,last_modified_by=?,last_modified_at=?,source_channel=?,audit_correlation_id=?,version=version+1 WHERE id=? AND version=?",
                        c.vehicleId(),
                        c.driverId(),
                        c.status().name(),
                        c.expiresOn(),
                        c.dailyLimit(),
                        c.monthlyLimit(),
                        c.perTransactionLimit(),
                        c.suspensionReason(),
                        c.notes(),
                        c.metadata().lastModifiedBy(),
                        ts(c.metadata().lastModifiedAt()),
                        c.metadata().sourceChannel().name(),
                        c.metadata().auditCorrelationId(),
                        c.id(),
                        c.metadata().version());
        if (updated == 0 && findCard(c.id()).isEmpty()) {
            jdbc.update(
                    """
                INSERT INTO fleet_logistics.fuel_cards (id,site_code,masked_reference,provider,vehicle_id,driver_id,status,issued_on,expires_on,daily_limit,monthly_limit,per_transaction_limit,suspension_reason,notes,created_by,created_at,last_modified_by,last_modified_at,source_channel,audit_correlation_id,version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                    c.id(),
                    c.siteCode().value(),
                    c.maskedReference(),
                    c.provider(),
                    c.vehicleId(),
                    c.driverId(),
                    c.status().name(),
                    c.issuedOn(),
                    c.expiresOn(),
                    c.dailyLimit(),
                    c.monthlyLimit(),
                    c.perTransactionLimit(),
                    c.suspensionReason(),
                    c.notes(),
                    c.metadata().createdBy(),
                    ts(c.metadata().createdAt()),
                    c.metadata().lastModifiedBy(),
                    ts(c.metadata().lastModifiedAt()),
                    c.metadata().sourceChannel().name(),
                    c.metadata().auditCorrelationId(),
                    c.metadata().version());
        } else if (updated == 0) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "FuelCard version conflict");
        }
        return findCard(c.id()).orElseThrow();
    }

    @Override
    public Optional<FuelCard> findCard(UUID id) {
        return one("SELECT * FROM fleet_logistics.fuel_cards WHERE id=?", this::card, id);
    }

    @Override
    public Optional<FuelCard> findLiveCardByReference(String site, String maskedReference) {
        if (site == null || maskedReference == null || maskedReference.isBlank())
            return Optional.empty();
        return one(
                "SELECT * FROM fleet_logistics.fuel_cards WHERE site_code=? AND masked_reference=? AND status<>'CANCELLED' LIMIT 1",
                this::card,
                site,
                maskedReference.strip());
    }

    @Override
    public FuelPage<FuelCard> findCards(CardQuery q) {
        Order order = order(q.paging().sort(), CARD_SORTS, "issuedOn", true);
        if (q.sites().isEmpty())
            return FuelPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where =
                Where.scoped()
                        .and("status=?", q.status() == null ? null : q.status().name())
                        .and("vehicle_id=?", q.vehicleId())
                        .and("driver_id=?", q.driverId())
                        .and("masked_reference=?", q.maskedReference());
        return page("fleet_logistics.fuel_cards", q.sites(), where, order, q.paging(), this::card);
    }

    private FuelCard card(ResultSet r, int n) throws SQLException {
        return new FuelCard(
                uuid(r, "id"),
                SiteCode.of(r.getString("site_code")),
                r.getString("masked_reference"),
                r.getString("provider"),
                uuid(r, "vehicle_id"),
                uuid(r, "driver_id"),
                FuelCard.Status.valueOf(r.getString("status")),
                r.getObject("issued_on", LocalDate.class),
                r.getObject("expires_on", LocalDate.class),
                r.getBigDecimal("daily_limit"),
                r.getBigDecimal("monthly_limit"),
                r.getBigDecimal("per_transaction_limit"),
                r.getString("suspension_reason"),
                r.getString("notes"),
                metadata(r));
    }

    private FuelPolicy policy(ResultSet r, int n) throws SQLException {
        return new FuelPolicy(
                uuid(r, "id"),
                SiteCode.of(r.getString("site_code")),
                r.getString("policy_name"),
                instant(r, "effective_from"),
                instant(r, "effective_to"),
                r.getInt("policy_version"),
                r.getBigDecimal("max_per_transaction"),
                r.getBigDecimal("daily_limit"),
                r.getBigDecimal("monthly_limit"),
                r.getBigDecimal("tank_capacity"),
                r.getBigDecimal("min_consumption"),
                r.getBigDecimal("max_consumption"),
                r.getLong("odometer_jump_tolerance"),
                r.getBoolean("receipt_required"),
                r.getInt("receipt_grace_hours"),
                r.getBigDecimal("materiality_amount"),
                r.getInt("anomaly_sla_hours"),
                r.getBigDecimal("cost_variance_tolerance"),
                r.getInt("repeated_pattern_window_hours"),
                r.getInt("repeated_pattern_threshold"),
                csv(r.getString("allowed_fuel_products")),
                csv(r.getString("approved_vendors")),
                FuelPolicy.Status.valueOf(r.getString("status")),
                metadata(r));
    }

    private FuelTransaction transaction(ResultSet r, int n) throws SQLException {
        return new FuelTransaction(
                uuid(r, "id"),
                SiteCode.of(r.getString("site_code")),
                r.getString("provider_transaction_id"),
                r.getString("source_system"),
                uuid(r, "vehicle_id"),
                uuid(r, "driver_id"),
                uuid(r, "trip_id"),
                instant(r, "occurred_at"),
                r.getString("vendor_reference"),
                r.getString("station_reference"),
                r.getString("fuel_product"),
                r.getBigDecimal("quantity"),
                r.getString("quantity_unit"),
                r.getBigDecimal("unit_price"),
                r.getBigDecimal("total_cost"),
                Currency.getInstance(r.getString("currency")),
                r.getString("masked_card_reference"),
                r.getLong("odometer_reading"),
                uuid(r, "receipt_evidence_id"),
                uuid(r, "pump_evidence_id"),
                r.getString("comments"),
                FuelTransaction.Status.valueOf(r.getString("status")),
                FuelTransaction.Lifecycle.valueOf(r.getString("lifecycle_status")),
                instant(r, "ingestion_timestamp"),
                r.getString("idempotency_key"),
                metadata(r));
    }

    private FuelPostedPrice postedPrice(ResultSet r, int n) throws SQLException {
        return new FuelPostedPrice(
                uuid(r, "id"),
                SiteCode.of(r.getString("site_code")),
                r.getString("vendor"),
                r.getString("fuel_product"),
                r.getBigDecimal("unit_price"),
                r.getString("currency"),
                instant(r, "effective_from"),
                instant(r, "effective_to"),
                FuelPostedPrice.Source.valueOf(r.getString("source")),
                r.getString("notes"),
                metadata(r));
    }

    private DriverLogbook logbook(ResultSet r, int n) throws SQLException {
        return new DriverLogbook(
                uuid(r, "id"),
                r.getString("logbook_number"),
                SiteCode.of(r.getString("site_code")),
                uuid(r, "driver_id"),
                uuid(r, "vehicle_id"),
                uuid(r, "trip_id"),
                r.getObject("journey_date", LocalDate.class),
                instant(r, "start_time"),
                instant(r, "end_time"),
                r.getString("origin"),
                r.getString("destination"),
                r.getString("route_notes"),
                DriverLogbook.UseClassification.valueOf(r.getString("use_classification")),
                r.getString("purpose"),
                r.getString("passenger_load_notes"),
                r.getLong("start_odometer"),
                (Long) r.getObject("end_odometer"),
                r.getBoolean("declaration_accepted"),
                uuid(r, "evidence_id"),
                DriverLogbook.Status.valueOf(r.getString("status")),
                r.getString("review_comment"),
                r.getString("transition_reason"),
                instant(r, "submitted_at"),
                instant(r, "approved_at"),
                metadata(r));
    }

    private FuelAnomalyCase anomaly(ResultSet r, int n) throws SQLException {
        return new FuelAnomalyCase(
                uuid(r, "id"),
                r.getString("anomaly_number"),
                SiteCode.of(r.getString("site_code")),
                uuid(r, "transaction_id"),
                uuid(r, "logbook_id"),
                uuid(r, "vehicle_id"),
                uuid(r, "driver_id"),
                uuid(r, "trip_id"),
                FuelAnomalyCase.Type.valueOf(r.getString("anomaly_type")),
                FuelAnomalyCase.Severity.valueOf(r.getString("severity")),
                r.getBoolean("material"),
                FuelAnomalyCase.Status.valueOf(r.getString("status")),
                r.getString("assignee"),
                instant(r, "sla_due_at"),
                r.getString("explanation"),
                uuid(r, "evidence_id"),
                r.getString("manager_decision") == null
                        ? null
                        : FuelAnomalyCase.Decision.valueOf(r.getString("manager_decision")),
                r.getString("closure_reason"),
                r.getInt("escalation_level"),
                readList(r.getString("detected_rules")),
                metadata(r));
    }

    private FuelReconciliation reconciliation(ResultSet r, int n) throws SQLException {
        Integer version = (Integer) r.getObject("policy_version");
        return new FuelReconciliation(
                uuid(r, "id"),
                uuid(r, "transaction_id"),
                uuid(r, "policy_id"),
                version,
                r.getString("outcome"),
                r.getBigDecimal("calculated_consumption"),
                instant(r, "evaluated_at"),
                r.getString("evaluated_by"),
                readMap(r.getString("rule_results")),
                r.getString("correlation_id"));
    }

    private FuelImportBatch importBatchHeader(ResultSet r, int n) throws SQLException {
        return new FuelImportBatch(
                uuid(r, "id"),
                SiteCode.of(r.getString("site_code")),
                r.getString("source_system"),
                r.getString("file_name"),
                r.getString("file_hash"),
                FuelImportBatch.Status.valueOf(r.getString("status")),
                r.getInt("total_rows"),
                r.getInt("accepted_rows"),
                r.getInt("rejected_rows"),
                r.getString("submitted_by"),
                instant(r, "submitted_at"),
                r.getString("correlation_id"),
                List.of());
    }

    private FuelImportRow importRow(ResultSet r, int n) throws SQLException {
        return new FuelImportRow(
                uuid(r, "id"),
                r.getInt("row_number"),
                FuelImportRow.Status.valueOf(r.getString("status")),
                uuid(r, "transaction_id"),
                r.getString("error_code"),
                r.getString("error_message"));
    }

    /* ------------------------------------------------------------------------------- helpers */

    private RecordMetadata metadata(ResultSet r) throws SQLException {
        return RecordMetadata.rehydrate(
                r.getString("created_by"),
                instant(r, "created_at"),
                r.getString("last_modified_by"),
                instant(r, "last_modified_at"),
                r.getLong("version"),
                SourceChannel.valueOf(r.getString("source_channel")),
                r.getString("audit_correlation_id"));
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private UUID uuid(ResultSet r, String name) throws SQLException {
        Object v = r.getObject(name);
        return v == null ? null : (UUID) v;
    }

    private Instant instant(ResultSet r, String name) throws SQLException {
        var v = r.getTimestamp(name);
        return v == null ? null : v.toInstant();
    }

    private Instant instant(ResultSet r, int index) throws SQLException {
        var v = r.getTimestamp(index);
        return v == null ? null : v.toInstant();
    }

    /**
     * Bind an Instant as a UTC OffsetDateTime; the pgjdbc driver cannot infer a type for Instant.
     */
    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private Set<String> csv(String v) {
        return v == null || v.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(v.split(",")));
    }

    private String json(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Cannot serialize fuel record", e);
        }
    }

    private List<String> readList(String v) {
        try {
            return v == null
                    ? List.of()
                    : json.readValue(
                            v,
                            json.getTypeFactory()
                                    .constructCollectionType(List.class, String.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Invalid stored rule list", e);
        }
    }

    private Map<String, Object> readMap(String v) {
        try {
            return v == null || v.isBlank() ? Map.of() : json.readValue(v, RULE_RESULTS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Invalid stored rule results", e);
        }
    }
}

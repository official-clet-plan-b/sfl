package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.facilities.waste.domain.ApprovalStatus;
import gh.edu.clet.sfl.facilities.waste.domain.CollectionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.CustodyEvent;
import gh.edu.clet.sfl.facilities.waste.domain.CustodyStep;
import gh.edu.clet.sfl.facilities.waste.domain.DestinationType;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.waste.domain.EvidenceStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionStatus;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.QuantityBasis;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCarrier;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCategory;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteDestination;
import gh.edu.clet.sfl.facilities.waste.domain.WasteEvidence;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import gh.edu.clet.sfl.facilities.waste.domain.WasteHistoryEntry;
import gh.edu.clet.sfl.facilities.waste.domain.WastePoint;
import gh.edu.clet.sfl.facilities.waste.domain.WasteStream;
import gh.edu.clet.sfl.facilities.waste.domain.WasteUnit;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for the S178 tables. The service owns the transaction and the authorisation; this owns the columns.
 * Every query runs under the site-scope row-level policy, so a statement here cannot return another
 * site's collection even if the service forgot to check.
 */
@Repository
public class WasteStore {

    private static final String COLLECTION = """
            id, reference, site_code, stream_id, point_id, carrier_id, destination_id, hazardous, scheduled_for,
            collected_on, quantity, unit, quantity_kg, quantity_basis, manifest_reference, certificate_reference,
            certificate_received_on, contaminated, quantity_reconciled, status, closed_at, created_by, created_at,
            updated_at, version""";

    private static final String EVIDENCE = """
            id, collection_id, site_code, kind, reference, file_name, media_type, size_bytes, content_hash,
            retention_class, status, submitted_by, submitted_at, reviewed_by, reviewed_at, review_reason""";

    private static final String EXCEPTION = """
            id, reference, site_code, collection_id, exception_type, description, owner_reference, due_on, status,
            escalated_to, incident_state, incident_reference, work_order_state, work_order_id, work_order_number,
            resolution, resolved_by, resolved_at, created_by, created_at, updated_at, version""";

    private final JdbcTemplate jdbc;

    public WasteStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextSequence(String name) {
        return jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "facilities." + name);
    }

    // ---- units

    public List<WasteUnit> units() {
        return jdbc.query("SELECT code, name, kilograms_per_unit FROM facilities.waste_units ORDER BY code",
                (rs, n) -> new WasteUnit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)));
    }

    public Optional<WasteUnit> unit(String code) {
        return jdbc.query("SELECT code, name, kilograms_per_unit FROM facilities.waste_units WHERE code = ?",
                (rs, n) -> new WasteUnit(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)), code)
                .stream().findFirst();
    }

    public void saveUnit(WasteUnit unit) {
        jdbc.update("""
                INSERT INTO facilities.waste_units (code, name, kilograms_per_unit) VALUES (?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, kilograms_per_unit = EXCLUDED.kilograms_per_unit
                """, unit.code(), unit.name(), unit.kilogramsPerUnit());
    }

    // ---- carriers

    public void insert(WasteCarrier c) {
        jdbc.update("""
                INSERT INTO facilities.waste_carriers (id, code, name, licence_reference, licence_expires_on,
                    hazardous_approved, status, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.code(), c.name(), c.licenceReference(), c.licenceExpiresOn(), c.hazardousApproved(),
                c.status().name(), c.createdBy(), ts(c.createdAt()), ts(c.updatedAt()), c.version());
    }

    public Optional<WasteCarrier> carrier(UUID id) {
        return jdbc.query("SELECT * FROM facilities.waste_carriers WHERE id = ?", (rs, n) -> carrier(rs), id)
                .stream().findFirst();
    }

    public List<WasteCarrier> carriers() {
        return jdbc.query("SELECT * FROM facilities.waste_carriers ORDER BY code", (rs, n) -> carrier(rs));
    }

    public boolean update(WasteCarrier c, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.waste_carriers SET name = ?, licence_reference = ?, licence_expires_on = ?,
                    hazardous_approved = ?, status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, c.name(), c.licenceReference(), c.licenceExpiresOn(), c.hazardousApproved(), c.status().name(),
                ts(c.updatedAt()), c.id(), expectedVersion) == 1;
    }

    // ---- destinations

    public void insert(WasteDestination d) {
        jdbc.update("""
                INSERT INTO facilities.waste_destinations (id, code, name, destination_type, permit_reference,
                    permit_expires_on, accepts_hazardous, status, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, d.id(), d.code(), d.name(), d.destinationType().name(), d.permitReference(), d.permitExpiresOn(),
                d.acceptsHazardous(), d.status().name(), d.createdBy(), ts(d.createdAt()), ts(d.updatedAt()),
                d.version());
    }

    public Optional<WasteDestination> destination(UUID id) {
        return jdbc.query("SELECT * FROM facilities.waste_destinations WHERE id = ?", (rs, n) -> destination(rs), id)
                .stream().findFirst();
    }

    public List<WasteDestination> destinations() {
        return jdbc.query("SELECT * FROM facilities.waste_destinations ORDER BY code", (rs, n) -> destination(rs));
    }

    public boolean update(WasteDestination d, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.waste_destinations SET name = ?, destination_type = ?, permit_reference = ?,
                    permit_expires_on = ?, accepts_hazardous = ?, status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, d.name(), d.destinationType().name(), d.permitReference(), d.permitExpiresOn(),
                d.acceptsHazardous(), d.status().name(), ts(d.updatedAt()), d.id(), expectedVersion) == 1;
    }

    // ---- streams

    public void insert(WasteStream s) {
        jdbc.update("""
                INSERT INTO facilities.waste_streams (id, code, name, category, hazardous, diverted, handling_rules,
                    active, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, s.id(), s.code(), s.name(), s.category().name(), s.hazardous(), s.diverted(), s.handlingRules(),
                s.active(), s.createdBy(), ts(s.createdAt()), ts(s.updatedAt()), s.version());
    }

    public Optional<WasteStream> stream(UUID id) {
        return jdbc.query("SELECT * FROM facilities.waste_streams WHERE id = ?", (rs, n) -> stream(rs), id)
                .stream().findFirst();
    }

    public List<WasteStream> streams() {
        return jdbc.query("SELECT * FROM facilities.waste_streams ORDER BY code", (rs, n) -> stream(rs));
    }

    public boolean update(WasteStream s, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.waste_streams SET name = ?, category = ?, hazardous = ?, diverted = ?,
                    handling_rules = ?, active = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, s.name(), s.category().name(), s.hazardous(), s.diverted(), s.handlingRules(), s.active(),
                ts(s.updatedAt()), s.id(), expectedVersion) == 1;
    }

    // ---- points

    public void insert(WastePoint p) {
        jdbc.update("""
                INSERT INTO facilities.waste_points (id, site_code, code, name, room_id, container_description,
                    active, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, p.id(), p.siteCode(), p.code(), p.name(), p.roomId(), p.containerDescription(), p.active(),
                p.createdBy(), ts(p.createdAt()), ts(p.updatedAt()), p.version());
    }

    public Optional<WastePoint> point(UUID id) {
        return jdbc.query("SELECT * FROM facilities.waste_points WHERE id = ?", (rs, n) -> point(rs), id)
                .stream().findFirst();
    }

    public List<WastePoint> points(String site) {
        return jdbc.query("SELECT * FROM facilities.waste_points WHERE site_code = ? ORDER BY code",
                (rs, n) -> point(rs), site);
    }

    public boolean update(WastePoint p, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.waste_points SET name = ?, room_id = ?, container_description = ?, active = ?,
                    updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, p.name(), p.roomId(), p.containerDescription(), p.active(), ts(p.updatedAt()), p.id(),
                expectedVersion) == 1;
    }

    // ---- collections

    public void insert(WasteCollection c) {
        jdbc.update("INSERT INTO facilities.waste_collections (" + COLLECTION + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.reference(), c.siteCode(), c.streamId(), c.pointId(), c.carrierId(), c.destinationId(),
                c.hazardous(), c.scheduledFor(), c.collectedOn(), c.quantity(), c.unit(), c.quantityKg(),
                c.quantityBasis() == null ? null : c.quantityBasis().name(), c.manifestReference(),
                c.certificateReference(), c.certificateReceivedOn(), c.contaminated(), c.quantityReconciled(),
                c.status().name(), ts(c.closedAt()), c.createdBy(), ts(c.createdAt()), ts(c.updatedAt()),
                c.version());
    }

    public Optional<WasteCollection> collection(UUID id) {
        return jdbc.query("SELECT " + COLLECTION + " FROM facilities.waste_collections WHERE id = ?",
                (rs, n) -> collection(rs), id).stream().findFirst();
    }

    public boolean update(WasteCollection c, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.waste_collections SET destination_id = ?, carrier_id = ?, collected_on = ?,
                    quantity = ?, unit = ?, quantity_kg = ?, quantity_basis = ?, manifest_reference = ?,
                    certificate_reference = ?, certificate_received_on = ?, contaminated = ?,
                    quantity_reconciled = ?, status = ?, closed_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, c.destinationId(), c.carrierId(), c.collectedOn(), c.quantity(), c.unit(), c.quantityKg(),
                c.quantityBasis() == null ? null : c.quantityBasis().name(), c.manifestReference(),
                c.certificateReference(), c.certificateReceivedOn(), c.contaminated(), c.quantityReconciled(),
                c.status().name(), ts(c.closedAt()), ts(c.updatedAt()), c.id(), expectedVersion) == 1;
    }

    public Page<WasteCollection> collections(String site, String status, UUID streamId, boolean hazardousOnly,
            int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (streamId != null) {
            where.append(" AND stream_id = ?");
            args.add(streamId);
        }
        if (hazardousOnly) {
            where.append(" AND hazardous");
        }
        return page("facilities.waste_collections", COLLECTION, where.toString(), args,
                " ORDER BY scheduled_for DESC, reference DESC", page, size, (rs, n) -> collection(rs));
    }

    /** Scheduled collections whose date has passed by more than the grace period, for the missed-collection sweep. */
    public List<WasteCollection> scheduledBefore(LocalDate cutoff) {
        return jdbc.query("SELECT " + COLLECTION + " FROM facilities.waste_collections WHERE status = 'SCHEDULED'"
                + " AND scheduled_for < ? ORDER BY scheduled_for", (rs, n) -> collection(rs), cutoff);
    }

    // ---- custody

    public void insert(CustodyEvent e) {
        jdbc.update("""
                INSERT INTO facilities.waste_custody_events (id, collection_id, site_code, step, from_party, to_party,
                    location, evidence_reference, note, recorded_by, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, e.id(), e.collectionId(), e.siteCode(), e.step().name(), e.fromParty(), e.toParty(),
                e.location(), e.evidenceReference(), e.note(), e.recordedBy(), ts(e.occurredAt()));
    }

    public List<CustodyEvent> custody(UUID collectionId) {
        return jdbc.query("SELECT * FROM facilities.waste_custody_events WHERE collection_id = ?"
                + " ORDER BY occurred_at, id", (rs, n) -> new CustodyEvent(rs.getObject("id", UUID.class),
                        rs.getObject("collection_id", UUID.class), rs.getString("site_code"),
                        CustodyStep.valueOf(rs.getString("step")), rs.getString("from_party"),
                        rs.getString("to_party"), rs.getString("location"), rs.getString("evidence_reference"),
                        rs.getString("note"), rs.getString("recorded_by"), instant(rs, "occurred_at")), collectionId);
    }

    // ---- evidence

    public void insert(WasteEvidence e) {
        jdbc.update("INSERT INTO facilities.waste_evidence (" + EVIDENCE + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?)", e.id(), e.collectionId(), e.siteCode(), e.kind().name(), e.reference(),
                e.fileName(), e.mediaType(), e.sizeBytes(), e.contentHash(), e.retentionClass(), e.status().name(),
                e.submittedBy(), ts(e.submittedAt()), e.reviewedBy(), ts(e.reviewedAt()), e.reviewReason());
    }

    public Optional<WasteEvidence> evidence(UUID id) {
        return jdbc.query("SELECT " + EVIDENCE + " FROM facilities.waste_evidence WHERE id = ?",
                (rs, n) -> evidence(rs), id).stream().findFirst();
    }

    public List<WasteEvidence> evidenceOf(UUID collectionId) {
        return jdbc.query("SELECT " + EVIDENCE + " FROM facilities.waste_evidence WHERE collection_id = ?"
                + " ORDER BY submitted_at", (rs, n) -> evidence(rs), collectionId);
    }

    public void review(UUID id, EvidenceStatus status, String reviewer, Instant at, String reason) {
        jdbc.update("UPDATE facilities.waste_evidence SET status = ?, reviewed_by = ?, reviewed_at = ?,"
                + " review_reason = ? WHERE id = ?", status.name(), reviewer, ts(at), reason, id);
    }

    // ---- exceptions

    /** Inserts unless an open exception of the same type already exists for the collection. */
    public boolean insert(WasteException e) {
        return jdbc.update("INSERT INTO facilities.waste_exceptions (" + EXCEPTION + ") VALUES (?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                e.id(), e.reference(), e.siteCode(), e.collectionId(), e.exceptionType().name(), e.description(),
                e.ownerReference(), e.dueOn(), e.status().name(), e.escalatedTo(), e.incidentState(),
                e.incidentReference(), e.workOrderState(), e.workOrderId(), e.workOrderNumber(), e.resolution(),
                e.resolvedBy(), ts(e.resolvedAt()), e.createdBy(), ts(e.createdAt()), ts(e.updatedAt()),
                e.version()) == 1;
    }

    public Optional<WasteException> exception(UUID id) {
        return jdbc.query("SELECT " + EXCEPTION + " FROM facilities.waste_exceptions WHERE id = ?",
                (rs, n) -> exception(rs), id).stream().findFirst();
    }

    public Optional<WasteException> openException(UUID collectionId, ExceptionType type) {
        return jdbc.query("SELECT " + EXCEPTION + " FROM facilities.waste_exceptions WHERE collection_id = ?"
                + " AND exception_type = ? AND status <> 'RESOLVED'", (rs, n) -> exception(rs), collectionId,
                type.name()).stream().findFirst();
    }

    public List<WasteException> openExceptionsOf(UUID collectionId) {
        return jdbc.query("SELECT " + EXCEPTION + " FROM facilities.waste_exceptions WHERE collection_id = ?"
                + " AND status <> 'RESOLVED'", (rs, n) -> exception(rs), collectionId);
    }

    public boolean update(WasteException e, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.waste_exceptions SET owner_reference = ?, due_on = ?, status = ?, escalated_to = ?,
                    incident_state = ?, incident_reference = ?, work_order_state = ?, work_order_id = ?,
                    work_order_number = ?, resolution = ?, resolved_by = ?, resolved_at = ?, updated_at = ?,
                    version = version + 1
                 WHERE id = ? AND version = ?
                """, e.ownerReference(), e.dueOn(), e.status().name(), e.escalatedTo(), e.incidentState(),
                e.incidentReference(), e.workOrderState(), e.workOrderId(), e.workOrderNumber(), e.resolution(),
                e.resolvedBy(), ts(e.resolvedAt()), ts(e.updatedAt()), e.id(), expectedVersion) == 1;
    }

    public Page<WasteException> exceptions(String site, String status, String type, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" AND exception_type = ?");
            args.add(type);
        }
        return page("facilities.waste_exceptions", EXCEPTION, where.toString(), args,
                " ORDER BY (status = 'RESOLVED'), due_on, reference", page, size, (rs, n) -> exception(rs));
    }

    // ---- history

    public void record(WasteHistoryEntry h) {
        jdbc.update("INSERT INTO facilities.waste_history (id, site_code, subject_type, subject_id, from_status,"
                + " to_status, actor, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", h.id(), h.siteCode(),
                h.subjectType(), h.subjectId(), h.fromStatus(), h.toStatus(), h.actor(), h.reason(),
                ts(h.occurredAt()));
    }

    public List<WasteHistoryEntry> history(UUID subjectId) {
        return jdbc.query("SELECT * FROM facilities.waste_history WHERE subject_id = ? ORDER BY occurred_at, id",
                (rs, n) -> new WasteHistoryEntry(rs.getObject("id", UUID.class), rs.getString("site_code"),
                        rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor"),
                        rs.getString("reason"), instant(rs, "occurred_at")), subjectId);
    }

    // ---- reporting

    /**
     * Quantities per stream for collections carried out in the window, measured and estimated apart.
     * {@code divertedMeasuredKg} counts only measured kilograms of a diverting stream that went anywhere but
     * landfill; a collection with no destination yet is not diverted.
     */
    public List<StreamTotals> streamTotals(String site, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT s.code, s.name, s.hazardous, s.diverted,
                       COALESCE(SUM(c.quantity_kg) FILTER (WHERE c.quantity_basis = 'MEASURED'), 0) AS measured_kg,
                       COALESCE(SUM(c.quantity_kg) FILTER (WHERE c.quantity_basis = 'ESTIMATED'), 0) AS estimated_kg,
                       COALESCE(SUM(c.quantity_kg) FILTER (WHERE c.quantity_basis = 'MEASURED' AND s.diverted
                                AND d.destination_type IS NOT NULL AND d.destination_type <> 'LANDFILL'), 0) AS diverted_kg,
                       count(c.id) AS collections,
                       count(c.id) FILTER (WHERE c.quantity_basis = 'ESTIMATED') AS estimated_collections,
                       COALESCE(array_agg(c.reference ORDER BY c.reference) FILTER (WHERE c.id IS NOT NULL),
                                ARRAY[]::varchar[]) AS refs
                  FROM facilities.waste_collections c
                  JOIN facilities.waste_streams s ON s.id = c.stream_id
                  LEFT JOIN facilities.waste_destinations d ON d.id = c.destination_id
                 WHERE c.site_code = ? AND c.collected_on BETWEEN ? AND ? AND c.quantity_kg IS NOT NULL
                   AND c.status NOT IN ('CANCELLED', 'MISSED')
                 GROUP BY s.code, s.name, s.hazardous, s.diverted ORDER BY s.code
                """, (rs, n) -> new StreamTotals(rs.getString("code"), rs.getString("name"), rs.getBoolean("hazardous"),
                rs.getBoolean("diverted"), rs.getBigDecimal("measured_kg"), rs.getBigDecimal("estimated_kg"),
                rs.getBigDecimal("diverted_kg"), rs.getLong("collections"), rs.getLong("estimated_collections"),
                List.of((String[]) rs.getArray("refs").getArray())), site, from, to);
    }

    /** Handed-over-or-later collections, and how many of them hold a certificate. */
    public long[] certificateCounts(String site) {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE certificate_reference IS NOT NULL)
                  FROM facilities.waste_collections
                 WHERE site_code = ? AND status IN ('HANDED_OVER', 'DESTINATION_CONFIRMED', 'CLOSED')
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, site);
    }

    public List<LocalDate> missedScheduledDates(String site) {
        return jdbc.query("SELECT scheduled_for FROM facilities.waste_collections WHERE site_code = ?"
                + " AND status = 'MISSED' ORDER BY scheduled_for", (rs, n) -> rs.getObject(1, LocalDate.class), site);
    }

    public long openHazardousChainExceptions(String site) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM facilities.waste_exceptions e JOIN facilities.waste_collections c ON c.id = e.collection_id
                 WHERE e.site_code = ? AND e.status <> 'RESOLVED' AND c.hazardous
                   AND e.exception_type IN ('MISSING_RECEIVING_EVIDENCE', 'MISSING_CERTIFICATE', 'UNAPPROVED_CARRIER', 'UNAPPROVED_DESTINATION')
                """, Long.class, site);
    }

    public long openExceptionCount(String site, LocalDate today) {
        return jdbc.queryForObject("SELECT count(*) FROM facilities.waste_exceptions WHERE site_code = ?"
                + " AND status <> 'RESOLVED'", Long.class, site);
    }

    public long overdueExceptionCount(String site, LocalDate today) {
        return jdbc.queryForObject("SELECT count(*) FROM facilities.waste_exceptions WHERE site_code = ?"
                + " AND status <> 'RESOLVED' AND due_on < ?", Long.class, site, today);
    }

    public record StreamTotals(String streamCode, String streamName, boolean hazardous, boolean diverted,
            BigDecimal measuredKg, BigDecimal estimatedKg, BigDecimal divertedMeasuredKg, long collections,
            long estimatedCollections, List<String> sourceReferences) {
    }

    // ---- paging and mapping

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    private <T> Page<T> page(String table, String columns, String where, List<Object> args, String order, int page,
            int size, RowMapper<T> mapper) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<T> items = jdbc.query("SELECT " + columns + " FROM " + table + where + order + " LIMIT ? OFFSET ?",
                mapper, pageArgs.toArray());
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static WasteCarrier carrier(ResultSet rs) throws SQLException {
        return new WasteCarrier(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("licence_reference"), date(rs, "licence_expires_on"), rs.getBoolean("hazardous_approved"),
                ApprovalStatus.valueOf(rs.getString("status")), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static WasteDestination destination(ResultSet rs) throws SQLException {
        return new WasteDestination(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                DestinationType.valueOf(rs.getString("destination_type")), rs.getString("permit_reference"),
                date(rs, "permit_expires_on"), rs.getBoolean("accepts_hazardous"),
                ApprovalStatus.valueOf(rs.getString("status")), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static WasteStream stream(ResultSet rs) throws SQLException {
        return new WasteStream(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                WasteCategory.valueOf(rs.getString("category")), rs.getBoolean("hazardous"), rs.getBoolean("diverted"),
                rs.getString("handling_rules"), rs.getBoolean("active"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static WastePoint point(ResultSet rs) throws SQLException {
        return new WastePoint(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getString("code"),
                rs.getString("name"), rs.getObject("room_id", UUID.class), rs.getString("container_description"),
                rs.getBoolean("active"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static WasteCollection collection(ResultSet rs) throws SQLException {
        String basis = rs.getString("quantity_basis");
        return new WasteCollection(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getString("site_code"), rs.getObject("stream_id", UUID.class), rs.getObject("point_id", UUID.class),
                rs.getObject("carrier_id", UUID.class), rs.getObject("destination_id", UUID.class),
                rs.getBoolean("hazardous"), date(rs, "scheduled_for"), date(rs, "collected_on"),
                rs.getBigDecimal("quantity"), rs.getString("unit"), rs.getBigDecimal("quantity_kg"),
                basis == null ? null : QuantityBasis.valueOf(basis), rs.getString("manifest_reference"),
                rs.getString("certificate_reference"), date(rs, "certificate_received_on"),
                rs.getBoolean("contaminated"), rs.getBoolean("quantity_reconciled"),
                CollectionStatus.valueOf(rs.getString("status")), instant(rs, "closed_at"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static WasteEvidence evidence(ResultSet rs) throws SQLException {
        return new WasteEvidence(rs.getObject("id", UUID.class), rs.getObject("collection_id", UUID.class),
                rs.getString("site_code"), EvidenceKind.valueOf(rs.getString("kind")), rs.getString("reference"),
                rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
                rs.getString("content_hash"), rs.getString("retention_class"),
                EvidenceStatus.valueOf(rs.getString("status")), rs.getString("submitted_by"),
                instant(rs, "submitted_at"), rs.getString("reviewed_by"), instant(rs, "reviewed_at"),
                rs.getString("review_reason"));
    }

    private static WasteException exception(ResultSet rs) throws SQLException {
        return new WasteException(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("site_code"),
                rs.getObject("collection_id", UUID.class), ExceptionType.valueOf(rs.getString("exception_type")),
                rs.getString("description"), rs.getString("owner_reference"), date(rs, "due_on"),
                ExceptionStatus.valueOf(rs.getString("status")), rs.getString("escalated_to"),
                rs.getString("incident_state"), rs.getString("incident_reference"), rs.getString("work_order_state"),
                rs.getObject("work_order_id", UUID.class), rs.getString("work_order_number"),
                rs.getString("resolution"), rs.getString("resolved_by"), instant(rs, "resolved_at"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }
}

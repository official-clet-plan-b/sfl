package gh.edu.clet.sfl.facilities.catering.application;

import gh.edu.clet.sfl.facilities.catering.domain.Allergen;
import gh.edu.clet.sfl.facilities.catering.domain.CateringCheck;
import gh.edu.clet.sfl.facilities.catering.domain.CateringEvidence;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.catering.domain.CateringHistoryEntry;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.CheckResult;
import gh.edu.clet.sfl.facilities.catering.domain.CheckType;
import gh.edu.clet.sfl.facilities.catering.domain.ContextType;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryRequest;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryTag;
import gh.edu.clet.sfl.facilities.catering.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionStatus;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.catering.domain.FinanceState;
import gh.edu.clet.sfl.facilities.catering.domain.HoldType;
import gh.edu.clet.sfl.facilities.catering.domain.Menu;
import gh.edu.clet.sfl.facilities.catering.domain.MenuItem;
import gh.edu.clet.sfl.facilities.catering.domain.MenuStatus;
import gh.edu.clet.sfl.facilities.catering.domain.NeedStatus;
import gh.edu.clet.sfl.facilities.catering.domain.NeedType;
import gh.edu.clet.sfl.facilities.catering.domain.ServiceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Supplier;
import gh.edu.clet.sfl.facilities.catering.domain.SupplierStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Variance;
import gh.edu.clet.sfl.facilities.catering.domain.VarianceKind;
import gh.edu.clet.sfl.facilities.catering.domain.VarianceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Venue;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for the S172 tables. The service owns the transaction and the authorisation; this owns the columns. Every
 * query runs under the site-scope row-level policy, so a statement here cannot return another site's service
 * even if the service forgot to check.
 */
@Repository
public class CateringStore {

    private static final String SERVICE = """
            id, reference, site_code, venue_id, menu_id, supplier_id, context_type, context_reference, title, service_date,
            starts_at, cancellation_cutoff, expected_guests, planned_portions, delivered_portions, status, requested_by,
            approved_by, approved_at, capacity_exception_reason, capacity_approved_by, supplier_exception_reason,
            supplier_approved_by, purchase_reference, invoice_reference, finance_state, delivered_at, closed_at,
            cancellation_reason, created_by, created_at, updated_at, version""";

    private static final String EXCEPTION = """
            id, reference, site_code, service_id, exception_type, description, owner_reference, status, incident_state,
            incident_reference, resolution, resolved_by, resolved_at, created_by, created_at, updated_at, version""";

    private final JdbcTemplate jdbc;

    public CateringStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextSequence(String name) {
        return jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "facilities." + name);
    }

    // ---- suppliers

    public void insert(Supplier s) {
        jdbc.update("""
                INSERT INTO facilities.cat_suppliers (id, code, name, certificate_reference, certificate_expires_on,
                    finance_reference, status, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, s.id(), s.code(), s.name(), s.certificateReference(), s.certificateExpiresOn(),
                s.financeReference(), s.status().name(), s.createdBy(), ts(s.createdAt()), ts(s.updatedAt()), s.version());
    }

    public Optional<Supplier> supplier(UUID id) {
        return jdbc.query("SELECT * FROM facilities.cat_suppliers WHERE id = ?", (rs, n) -> supplier(rs), id)
                .stream().findFirst();
    }

    public List<Supplier> suppliers() {
        return jdbc.query("SELECT * FROM facilities.cat_suppliers ORDER BY code", (rs, n) -> supplier(rs));
    }

    public boolean update(Supplier s, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.cat_suppliers SET name = ?, certificate_reference = ?, certificate_expires_on = ?,
                    finance_reference = ?, status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, s.name(), s.certificateReference(), s.certificateExpiresOn(), s.financeReference(),
                s.status().name(), ts(s.updatedAt()), s.id(), expectedVersion) == 1;
    }

    // ---- venues

    public void insert(Venue v) {
        jdbc.update("""
                INSERT INTO facilities.cat_venues (id, site_code, code, name, room_id, capacity, active, created_by,
                    created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, v.id(), v.siteCode(), v.code(), v.name(), v.roomId(), v.capacity(), v.active(), v.createdBy(),
                ts(v.createdAt()), ts(v.updatedAt()), v.version());
    }

    public Optional<Venue> venue(UUID id) {
        return jdbc.query("SELECT * FROM facilities.cat_venues WHERE id = ?", (rs, n) -> venue(rs), id)
                .stream().findFirst();
    }

    public List<Venue> venues(String site) {
        return jdbc.query("SELECT * FROM facilities.cat_venues WHERE site_code = ? ORDER BY code", (rs, n) -> venue(rs), site);
    }

    public boolean update(Venue v, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.cat_venues SET name = ?, capacity = ?, active = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, v.name(), v.capacity(), v.active(), ts(v.updatedAt()), v.id(), expectedVersion) == 1;
    }

    // ---- menus and items

    public void insert(Menu m) {
        jdbc.update("""
                INSERT INTO facilities.cat_menus (id, site_code, code, name, description, status, approved_by,
                    approved_at, created_by, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, m.id(), m.siteCode(), m.code(), m.name(), m.description(), m.status().name(), m.approvedBy(),
                ts(m.approvedAt()), m.createdBy(), ts(m.createdAt()), ts(m.updatedAt()), m.version());
    }

    public Optional<Menu> menu(UUID id) {
        return jdbc.query("SELECT * FROM facilities.cat_menus WHERE id = ?", (rs, n) -> menu(rs), id).stream().findFirst();
    }

    public List<Menu> menus(String site) {
        return jdbc.query("SELECT * FROM facilities.cat_menus WHERE site_code = ? ORDER BY code", (rs, n) -> menu(rs), site);
    }

    public boolean update(Menu m, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.cat_menus SET name = ?, description = ?, status = ?, approved_by = ?, approved_at = ?,
                    updated_at = ?, version = version + 1 WHERE id = ? AND version = ?
                """, m.name(), m.description(), m.status().name(), m.approvedBy(), ts(m.approvedAt()), ts(m.updatedAt()),
                m.id(), expectedVersion) == 1;
    }

    public void insert(MenuItem i) {
        jdbc.update("""
                INSERT INTO facilities.cat_menu_items (id, menu_id, site_code, name, allergens, allergens_declared,
                    dietary_tags, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, i.id(), i.menuId(), i.siteCode(), i.name(), csv(i.allergens()), i.allergensDeclared(),
                csv(i.dietaryTags()), i.createdBy(), ts(i.createdAt()));
    }

    public Optional<MenuItem> item(UUID id) {
        return jdbc.query("SELECT * FROM facilities.cat_menu_items WHERE id = ?", (rs, n) -> item(rs), id)
                .stream().findFirst();
    }

    public List<MenuItem> itemsOf(UUID menuId) {
        return jdbc.query("SELECT * FROM facilities.cat_menu_items WHERE menu_id = ? ORDER BY created_at, name",
                (rs, n) -> item(rs), menuId);
    }

    public void updateItem(MenuItem i) {
        jdbc.update("UPDATE facilities.cat_menu_items SET name = ?, allergens = ?, allergens_declared = ?,"
                + " dietary_tags = ? WHERE id = ?", i.name(), csv(i.allergens()), i.allergensDeclared(),
                csv(i.dietaryTags()), i.id());
    }

    // ---- services

    public void insert(CateringService s) {
        jdbc.update("INSERT INTO facilities.cat_services (" + SERVICE + ") VALUES (" + "?, ".repeat(32) + "?)",
                s.id(), s.reference(), s.siteCode(), s.venueId(), s.menuId(), s.supplierId(), s.contextType().name(),
                s.contextReference(), s.title(), s.serviceDate(), ts(s.startsAt()), ts(s.cancellationCutoff()),
                s.expectedGuests(), s.plannedPortions(), s.deliveredPortions(), s.status().name(), s.requestedBy(),
                s.approvedBy(), ts(s.approvedAt()), s.capacityExceptionReason(), s.capacityApprovedBy(),
                s.supplierExceptionReason(), s.supplierApprovedBy(), s.purchaseReference(), s.invoiceReference(),
                s.financeState().name(), ts(s.deliveredAt()), ts(s.closedAt()), s.cancellationReason(), s.createdBy(),
                ts(s.createdAt()), ts(s.updatedAt()), s.version());
    }

    public Optional<CateringService> service(UUID id) {
        return jdbc.query("SELECT " + SERVICE + " FROM facilities.cat_services WHERE id = ?", (rs, n) -> service(rs), id)
                .stream().findFirst();
    }

    public boolean update(CateringService s, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.cat_services SET venue_id = ?, menu_id = ?, supplier_id = ?, title = ?, service_date = ?,
                    starts_at = ?, cancellation_cutoff = ?, expected_guests = ?, planned_portions = ?,
                    delivered_portions = ?, status = ?, requested_by = ?, approved_by = ?, approved_at = ?,
                    capacity_exception_reason = ?, capacity_approved_by = ?, supplier_exception_reason = ?,
                    supplier_approved_by = ?, purchase_reference = ?, invoice_reference = ?, finance_state = ?,
                    delivered_at = ?, closed_at = ?, cancellation_reason = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, s.venueId(), s.menuId(), s.supplierId(), s.title(), s.serviceDate(), ts(s.startsAt()),
                ts(s.cancellationCutoff()), s.expectedGuests(), s.plannedPortions(), s.deliveredPortions(),
                s.status().name(), s.requestedBy(), s.approvedBy(), ts(s.approvedAt()), s.capacityExceptionReason(),
                s.capacityApprovedBy(), s.supplierExceptionReason(), s.supplierApprovedBy(), s.purchaseReference(),
                s.invoiceReference(), s.financeState().name(), ts(s.deliveredAt()), ts(s.closedAt()),
                s.cancellationReason(), ts(s.updatedAt()), s.id(), expectedVersion) == 1;
    }

    /** Services still being planned that serve a menu - a controlled change to the menu sends them back for approval. */
    public List<CateringService> planningServicesOfMenu(UUID menuId) {
        return jdbc.query("SELECT " + SERVICE + " FROM facilities.cat_services WHERE menu_id = ?"
                + " AND status IN ('PENDING_APPROVAL', 'APPROVED', 'CONFIRMED')", (rs, n) -> service(rs), menuId);
    }

    public Page<CateringService> services(String site, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        return page("facilities.cat_services", SERVICE, where.toString(), args, " ORDER BY service_date DESC, reference DESC",
                page, size, (rs, n) -> service(rs));
    }

    // ---- dietary requests

    public void insert(DietaryRequest r) {
        jdbc.update("""
                INSERT INTO facilities.cat_dietary_requests (id, service_id, site_code, person_reference, need_type,
                    need_code, authorised_by, status, substitute_item_id, substitution_approved_by, waiver_reason,
                    created_by, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, r.id(), r.serviceId(), r.siteCode(), r.personReference(), r.needType().name(), r.needCode(),
                r.authorisedBy(), r.status().name(), r.substituteItemId(), r.substitutionApprovedBy(), r.waiverReason(),
                r.createdBy(), ts(r.createdAt()), ts(r.updatedAt()), r.version());
    }

    public Optional<DietaryRequest> request(UUID id) {
        return jdbc.query("SELECT * FROM facilities.cat_dietary_requests WHERE id = ?", (rs, n) -> request(rs), id)
                .stream().findFirst();
    }

    public List<DietaryRequest> requestsOf(UUID serviceId) {
        return jdbc.query("SELECT * FROM facilities.cat_dietary_requests WHERE service_id = ? ORDER BY created_at",
                (rs, n) -> request(rs), serviceId);
    }

    public boolean update(DietaryRequest r, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.cat_dietary_requests SET status = ?, substitute_item_id = ?, substitution_approved_by = ?,
                    waiver_reason = ?, updated_at = ?, version = version + 1 WHERE id = ? AND version = ?
                """, r.status().name(), r.substituteItemId(), r.substitutionApprovedBy(), r.waiverReason(),
                ts(r.updatedAt()), r.id(), expectedVersion) == 1;
    }

    // ---- checks

    public void insert(CateringCheck c) {
        jdbc.update("""
                INSERT INTO facilities.cat_checks (id, site_code, service_id, supplier_id, check_type, hold_type,
                    temperature_c, result, notes, checked_by, checked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.siteCode(), c.serviceId(), c.supplierId(), c.checkType().name(),
                c.holdType() == null ? null : c.holdType().name(), c.temperatureC(), c.result().name(), c.notes(),
                c.checkedBy(), ts(c.checkedAt()));
    }

    public List<CateringCheck> checksOf(UUID serviceId) {
        return jdbc.query("SELECT * FROM facilities.cat_checks WHERE service_id = ? ORDER BY checked_at", (rs, n) -> check(rs), serviceId);
    }

    public List<CateringCheck> supplierChecks(UUID supplierId) {
        return jdbc.query("SELECT * FROM facilities.cat_checks WHERE supplier_id = ? AND check_type = 'SUPPLIER'"
                + " ORDER BY checked_at DESC LIMIT 20", (rs, n) -> check(rs), supplierId);
    }

    public boolean passingSupplierCheckSince(UUID supplierId, Instant since) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM facilities.cat_checks WHERE supplier_id = ?"
                + " AND check_type = 'SUPPLIER' AND result = 'PASS' AND checked_at >= ?)", Boolean.class, supplierId,
                ts(since));
    }

    // ---- exceptions

    public void insert(CateringException e) {
        jdbc.update("INSERT INTO facilities.cat_exceptions (" + EXCEPTION + ") VALUES (" + "?, ".repeat(16) + "?)",
                e.id(), e.reference(), e.siteCode(), e.serviceId(), e.exceptionType().name(), e.description(),
                e.ownerReference(), e.status().name(), e.incidentState(), e.incidentReference(), e.resolution(),
                e.resolvedBy(), ts(e.resolvedAt()), e.createdBy(), ts(e.createdAt()), ts(e.updatedAt()), e.version());
    }

    public Optional<CateringException> exception(UUID id) {
        return jdbc.query("SELECT " + EXCEPTION + " FROM facilities.cat_exceptions WHERE id = ?", (rs, n) -> exception(rs), id)
                .stream().findFirst();
    }

    public boolean update(CateringException e, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.cat_exceptions SET owner_reference = ?, status = ?, incident_state = ?,
                    incident_reference = ?, resolution = ?, resolved_by = ?, resolved_at = ?, updated_at = ?,
                    version = version + 1 WHERE id = ? AND version = ?
                """, e.ownerReference(), e.status().name(), e.incidentState(), e.incidentReference(), e.resolution(),
                e.resolvedBy(), ts(e.resolvedAt()), ts(e.updatedAt()), e.id(), expectedVersion) == 1;
    }

    public Page<CateringException> exceptions(String site, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        return page("facilities.cat_exceptions", EXCEPTION, where.toString(), args,
                " ORDER BY (status = 'RESOLVED'), created_at DESC", page, size, (rs, n) -> exception(rs));
    }

    public List<CateringException> exceptionsOf(UUID serviceId) {
        return jdbc.query("SELECT " + EXCEPTION + " FROM facilities.cat_exceptions WHERE service_id = ? ORDER BY created_at",
                (rs, n) -> exception(rs), serviceId);
    }

    // ---- variances

    public void insert(Variance v) {
        jdbc.update("""
                INSERT INTO facilities.cat_variances (id, service_id, site_code, kind, planned, actual, difference,
                    owner_reference, reason, status, approved_by, approved_at, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, v.id(), v.serviceId(), v.siteCode(), v.kind().name(), v.planned(), v.actual(), v.difference(),
                v.ownerReference(), v.reason(), v.status().name(), v.approvedBy(), ts(v.approvedAt()), v.createdBy(),
                ts(v.createdAt()));
    }

    public Optional<Variance> variance(UUID id) {
        return jdbc.query("SELECT * FROM facilities.cat_variances WHERE id = ?", (rs, n) -> variance(rs), id)
                .stream().findFirst();
    }

    public List<Variance> variancesOf(UUID serviceId) {
        return jdbc.query("SELECT * FROM facilities.cat_variances WHERE service_id = ? ORDER BY created_at",
                (rs, n) -> variance(rs), serviceId);
    }

    public void approve(UUID id, String by, Instant at) {
        jdbc.update("UPDATE facilities.cat_variances SET status = 'APPROVED', approved_by = ?, approved_at = ? WHERE id = ?",
                by, ts(at), id);
    }

    // ---- evidence and history

    public void insert(CateringEvidence e) {
        jdbc.update("""
                INSERT INTO facilities.cat_evidence (id, service_id, site_code, kind, reference, file_name, media_type,
                    size_bytes, content_hash, retention_class, submitted_by, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, e.id(), e.serviceId(), e.siteCode(), e.kind().name(), e.reference(), e.fileName(), e.mediaType(),
                e.sizeBytes(), e.contentHash(), e.retentionClass(), e.submittedBy(), ts(e.submittedAt()));
    }

    public List<CateringEvidence> evidenceOf(UUID serviceId) {
        return jdbc.query("SELECT * FROM facilities.cat_evidence WHERE service_id = ? ORDER BY submitted_at",
                (rs, n) -> new CateringEvidence(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                        rs.getString("site_code"), EvidenceKind.valueOf(rs.getString("kind")), rs.getString("reference"),
                        rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
                        rs.getString("content_hash"), rs.getString("retention_class"), rs.getString("submitted_by"),
                        instant(rs, "submitted_at")), serviceId);
    }

    public void record(CateringHistoryEntry h) {
        jdbc.update("INSERT INTO facilities.cat_history (id, site_code, subject_type, subject_id, from_status, to_status,"
                + " actor, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", h.id(), h.siteCode(),
                h.subjectType(), h.subjectId(), h.fromStatus(), h.toStatus(), h.actor(), h.reason(), ts(h.occurredAt()));
    }

    public List<CateringHistoryEntry> history(UUID subjectId) {
        return jdbc.query("SELECT * FROM facilities.cat_history WHERE subject_id = ? ORDER BY occurred_at, id",
                (rs, n) -> new CateringHistoryEntry(rs.getObject("id", UUID.class), rs.getString("site_code"),
                        rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor"),
                        rs.getString("reason"), instant(rs, "occurred_at")), subjectId);
    }

    // ---- metrics

    /** {confirmed or later, delivered or later}: services dated in the window plus any already delivered - "confirmed services delivered". */
    public long[] deliveryCounts(String site, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status IN ('CONFIRMED', 'DELIVERED', 'RECONCILED', 'CLOSED')),
                       count(*) FILTER (WHERE status IN ('DELIVERED', 'RECONCILED', 'CLOSED'))
                  FROM facilities.cat_services WHERE site_code = ?
                   AND (service_date BETWEEN ? AND ? OR status IN ('DELIVERED', 'RECONCILED', 'CLOSED'))
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, site, from, to);
    }

    /** {delivered services, delivered services with a passing temperature check} - "food-safety checks completed". */
    public long[] checkCounts(String site, LocalDate from) {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE EXISTS (SELECT 1 FROM facilities.cat_checks c WHERE c.service_id = s.id
                       AND c.check_type = 'TEMPERATURE' AND c.result = 'PASS'))
                  FROM facilities.cat_services s WHERE s.site_code = ? AND s.status IN ('DELIVERED', 'RECONCILED', 'CLOSED')
                   AND s.service_date >= ?
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, site, from);
    }

    /** {dietary requests, requests that needed a substitution or a waiver} - the dietary exception rate. */
    public long[] dietaryCounts(String site, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE r.status <> 'OPEN')
                  FROM facilities.cat_dietary_requests r JOIN facilities.cat_services s ON s.id = r.service_id
                 WHERE r.site_code = ? AND s.service_date BETWEEN ? AND ?
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, site, from, to);
    }

    public List<Instant> openVarianceDates(String site) {
        return jdbc.query("SELECT created_at FROM facilities.cat_variances WHERE site_code = ? AND status = 'OPEN'"
                + " ORDER BY created_at", (rs, n) -> instant(rs, "created_at"), site);
    }

    public BigDecimal netQuantityVariance(String site) {
        BigDecimal value = jdbc.queryForObject("SELECT COALESCE(sum(difference), 0) FROM facilities.cat_variances"
                + " WHERE site_code = ? AND kind = 'QUANTITY'", BigDecimal.class, site);
        return value == null ? BigDecimal.ZERO : value;
    }

    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
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
        List<T> items = jdbc.query("SELECT " + columns + " FROM " + table + where + order + " LIMIT ? OFFSET ?", mapper,
                pageArgs.toArray());
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

    private static <E extends Enum<E>> String csv(Set<E> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static <E extends Enum<E>> Set<E> parse(Class<E> type, String csv) {
        Set<E> out = EnumSet.noneOf(type);
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(s -> out.add(Enum.valueOf(type, s)));
        }
        return out;
    }

    private static Supplier supplier(ResultSet rs) throws SQLException {
        return new Supplier(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("certificate_reference"), date(rs, "certificate_expires_on"), rs.getString("finance_reference"),
                SupplierStatus.valueOf(rs.getString("status")), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Venue venue(ResultSet rs) throws SQLException {
        return new Venue(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getString("code"),
                rs.getString("name"), rs.getObject("room_id", UUID.class), rs.getInt("capacity"), rs.getBoolean("active"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Menu menu(ResultSet rs) throws SQLException {
        return new Menu(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), MenuStatus.valueOf(rs.getString("status")),
                rs.getString("approved_by"), instant(rs, "approved_at"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static MenuItem item(ResultSet rs) throws SQLException {
        return new MenuItem(rs.getObject("id", UUID.class), rs.getObject("menu_id", UUID.class), rs.getString("site_code"),
                rs.getString("name"), parse(Allergen.class, rs.getString("allergens")), rs.getBoolean("allergens_declared"),
                parse(DietaryTag.class, rs.getString("dietary_tags")), rs.getString("created_by"), instant(rs, "created_at"));
    }

    private static CateringService service(ResultSet rs) throws SQLException {
        int delivered = rs.getInt("delivered_portions");
        Integer deliveredPortions = rs.wasNull() ? null : delivered;
        return new CateringService(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("site_code"),
                rs.getObject("venue_id", UUID.class), rs.getObject("menu_id", UUID.class),
                rs.getObject("supplier_id", UUID.class), ContextType.valueOf(rs.getString("context_type")),
                rs.getString("context_reference"), rs.getString("title"), date(rs, "service_date"),
                instant(rs, "starts_at"), instant(rs, "cancellation_cutoff"), rs.getInt("expected_guests"),
                rs.getInt("planned_portions"), deliveredPortions, ServiceStatus.valueOf(rs.getString("status")),
                rs.getString("requested_by"), rs.getString("approved_by"), instant(rs, "approved_at"),
                rs.getString("capacity_exception_reason"), rs.getString("capacity_approved_by"),
                rs.getString("supplier_exception_reason"), rs.getString("supplier_approved_by"),
                rs.getString("purchase_reference"), rs.getString("invoice_reference"),
                FinanceState.valueOf(rs.getString("finance_state")), instant(rs, "delivered_at"),
                instant(rs, "closed_at"), rs.getString("cancellation_reason"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static DietaryRequest request(ResultSet rs) throws SQLException {
        return new DietaryRequest(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("site_code"), rs.getString("person_reference"), NeedType.valueOf(rs.getString("need_type")),
                rs.getString("need_code"), rs.getString("authorised_by"), NeedStatus.valueOf(rs.getString("status")),
                rs.getObject("substitute_item_id", UUID.class), rs.getString("substitution_approved_by"),
                rs.getString("waiver_reason"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static CateringCheck check(ResultSet rs) throws SQLException {
        String hold = rs.getString("hold_type");
        return new CateringCheck(rs.getObject("id", UUID.class), rs.getString("site_code"),
                rs.getObject("service_id", UUID.class), rs.getObject("supplier_id", UUID.class),
                CheckType.valueOf(rs.getString("check_type")), hold == null ? null : HoldType.valueOf(hold),
                rs.getBigDecimal("temperature_c"), CheckResult.valueOf(rs.getString("result")), rs.getString("notes"),
                rs.getString("checked_by"), instant(rs, "checked_at"));
    }

    private static CateringException exception(ResultSet rs) throws SQLException {
        return new CateringException(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("site_code"),
                rs.getObject("service_id", UUID.class), ExceptionType.valueOf(rs.getString("exception_type")),
                rs.getString("description"), rs.getString("owner_reference"),
                ExceptionStatus.valueOf(rs.getString("status")), rs.getString("incident_state"),
                rs.getString("incident_reference"), rs.getString("resolution"), rs.getString("resolved_by"),
                instant(rs, "resolved_at"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Variance variance(ResultSet rs) throws SQLException {
        return new Variance(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("site_code"), VarianceKind.valueOf(rs.getString("kind")), rs.getBigDecimal("planned"),
                rs.getBigDecimal("actual"), rs.getBigDecimal("difference"), rs.getString("owner_reference"),
                rs.getString("reason"), VarianceStatus.valueOf(rs.getString("status")), rs.getString("approved_by"),
                instant(rs, "approved_at"), rs.getString("created_by"), instant(rs, "created_at"));
    }
}

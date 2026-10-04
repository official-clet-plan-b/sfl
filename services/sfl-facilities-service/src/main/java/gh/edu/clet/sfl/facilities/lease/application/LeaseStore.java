package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.facilities.lease.domain.AgreementKind;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementStatus;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementVersion;
import gh.edu.clet.sfl.facilities.lease.domain.AlertLevel;
import gh.edu.clet.sfl.facilities.lease.domain.AlertReason;
import gh.edu.clet.sfl.facilities.lease.domain.Amendment;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentKind;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentStatus;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.BusinessCalendar;
import gh.edu.clet.sfl.facilities.lease.domain.CounterpartyState;
import gh.edu.clet.sfl.facilities.lease.domain.Direction;
import gh.edu.clet.sfl.facilities.lease.domain.DocumentKind;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseAlert;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseDocument;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseHistoryEntry;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import gh.edu.clet.sfl.facilities.lease.domain.WorkOrderTrigger;
import gh.edu.clet.sfl.facilities.lease.domain.Obligation;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationKind;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationStatus;
import gh.edu.clet.sfl.facilities.lease.domain.RenewalType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * SQL for the S177 tables. The service owns the transaction and the authorisation; this owns the columns. Every
 * site-scoped query runs under the row-level policy, so a statement here cannot return another site's agreement.
 */
@Repository
public class LeaseStore {

    private static final String AGREEMENT = """
            id, reference, site_code, room_id, property_reference, agreement_kind, direction, title, counterparty_reference,
            counterparty_state, contract_reference, finance_reference, owner_reference, start_date, end_date, renewal_type,
            renewal_term_months, notice_days, notice_date, rent_review_date, annual_rent, deposit_amount, currency, status,
            version_number, requested_by, approved_by, approved_at, terminated_on, created_by, created_at, updated_at, version""";

    private static final String AMENDMENT = """
            id, reference, agreement_id, site_code, kind, material, status, reason, new_end_date, new_annual_rent,
            new_deposit_amount, new_notice_days, new_rent_review_date, new_renewal_term_months, effective_on, prior_version,
            proposed_by, proposed_at, decided_by, decided_at, decision_reason, legal_review_note, legal_cleared_by,
            legal_cleared_at, version""";

    private static final String OBLIGATION = """
            id, agreement_id, site_code, kind, title, due_on, owner_reference, status, completed_on, completed_by,
            completion_note, generated, created_by, created_at, updated_at, version""";

    private static final String DOCUMENT = """
            id, agreement_id, site_code, kind, reference, file_name, media_type, size_bytes, content_hash, retention_class,
            expires_on, submitted_by, submitted_at""";

    private final JdbcTemplate jdbc;

    public LeaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextSequence(String name) {
        return jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "facilities." + name);
    }

    // ---- calendar and settings

    public record Settings(String timezone, Set<DayOfWeek> weekend) {
    }

    public Settings settings() {
        return jdbc.queryForObject("SELECT timezone, weekend_days FROM facilities.lease_settings WHERE id = 1",
                (rs, n) -> new Settings(rs.getString(1), parseDays(rs.getString(2))));
    }

    public void saveSettings(String timezone, Set<DayOfWeek> weekend) {
        jdbc.update("UPDATE facilities.lease_settings SET timezone = ?, weekend_days = ? WHERE id = 1", timezone,
                weekend.stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
    }

    public List<LocalDate> holidays() {
        return jdbc.query("SELECT holiday_date FROM facilities.lease_holidays ORDER BY holiday_date",
                (rs, n) -> rs.getObject(1, LocalDate.class));
    }

    public List<Holiday> holidayRows() {
        return jdbc.query("SELECT holiday_date, name FROM facilities.lease_holidays ORDER BY holiday_date",
                (rs, n) -> new Holiday(rs.getObject(1, LocalDate.class), rs.getString(2)));
    }

    public void addHoliday(LocalDate date, String name) {
        jdbc.update("INSERT INTO facilities.lease_holidays (holiday_date, name) VALUES (?, ?)"
                + " ON CONFLICT (holiday_date) DO UPDATE SET name = EXCLUDED.name", date, name);
    }

    public void removeHoliday(LocalDate date) {
        jdbc.update("DELETE FROM facilities.lease_holidays WHERE holiday_date = ?", date);
    }

    public BusinessCalendar calendar() {
        return new BusinessCalendar(settings().weekend(), new HashSet<>(holidays()));
    }

    public record Holiday(LocalDate date, String name) {
    }

    // ---- agreements

    public void insert(Agreement a) {
        jdbc.update("INSERT INTO facilities.lease_agreements (" + AGREEMENT + ") VALUES (" + "?, ".repeat(32) + "?)",
                a.id(), a.reference(), a.siteCode(), a.roomId(), a.propertyReference(), a.kind().name(),
                a.direction().name(), a.title(), a.counterpartyReference(), a.counterpartyState().name(),
                a.contractReference(), a.financeReference(), a.ownerReference(), a.startDate(), a.endDate(),
                a.renewalType().name(), a.renewalTermMonths(), a.noticeDays(), a.noticeDate(), a.rentReviewDate(),
                a.annualRent(), a.depositAmount(), a.currency(), a.status().name(), a.versionNumber(), a.requestedBy(),
                a.approvedBy(), ts(a.approvedAt()), a.terminatedOn(), a.createdBy(), ts(a.createdAt()),
                ts(a.updatedAt()), a.version());
    }

    public Optional<Agreement> agreement(UUID id) {
        return jdbc.query("SELECT " + AGREEMENT + " FROM facilities.lease_agreements WHERE id = ?",
                (rs, n) -> agreement(rs), id).stream().findFirst();
    }

    public boolean update(Agreement a, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.lease_agreements SET room_id = ?, property_reference = ?, title = ?,
                    counterparty_reference = ?, counterparty_state = ?, contract_reference = ?, finance_reference = ?,
                    owner_reference = ?, start_date = ?, end_date = ?, renewal_type = ?, renewal_term_months = ?,
                    notice_days = ?, notice_date = ?, rent_review_date = ?, annual_rent = ?, deposit_amount = ?,
                    currency = ?, status = ?, version_number = ?, requested_by = ?, approved_by = ?, approved_at = ?,
                    terminated_on = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, a.roomId(), a.propertyReference(), a.title(), a.counterpartyReference(), a.counterpartyState().name(),
                a.contractReference(), a.financeReference(), a.ownerReference(), a.startDate(), a.endDate(),
                a.renewalType().name(), a.renewalTermMonths(), a.noticeDays(), a.noticeDate(), a.rentReviewDate(),
                a.annualRent(), a.depositAmount(), a.currency(), a.status().name(), a.versionNumber(), a.requestedBy(),
                a.approvedBy(), ts(a.approvedAt()), a.terminatedOn(), ts(a.updatedAt()), a.id(), expectedVersion) == 1;
    }

    public Page<Agreement> agreements(String site, String status, String owner, Integer endsWithinDays, LocalDate today,
            int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (owner != null) {
            where.append(" AND owner_reference = ?");
            args.add(owner);
        }
        if (endsWithinDays != null) {
            where.append(" AND status = 'ACTIVE' AND end_date <= ?");
            args.add(today.plusDays(endsWithinDays));
        }
        return page("facilities.lease_agreements", AGREEMENT, where.toString(), args, " ORDER BY end_date, reference", page,
                size, (rs, n) -> agreement(rs));
    }

    public List<Agreement> activeAgreements(String site) {
        return jdbc.query("SELECT " + AGREEMENT + " FROM facilities.lease_agreements WHERE site_code = ? AND status = 'ACTIVE'"
                + " ORDER BY end_date", (rs, n) -> agreement(rs), site);
    }

    /** Active agreements whose end date has passed - the daily control marks them expired. */
    public List<Agreement> endedActive(LocalDate today) {
        return jdbc.query("SELECT " + AGREEMENT + " FROM facilities.lease_agreements WHERE status = 'ACTIVE' AND end_date < ?",
                (rs, n) -> agreement(rs), today);
    }

    public List<Agreement> allActive() {
        return jdbc.query("SELECT " + AGREEMENT + " FROM facilities.lease_agreements WHERE status = 'ACTIVE'",
                (rs, n) -> agreement(rs));
    }

    /** Expired agreements that have no review work order yet - the daily control raises one for each. */
    public List<Agreement> expiredWithoutReview() {
        return jdbc.query("SELECT " + AGREEMENT + " FROM facilities.lease_agreements a WHERE status = 'EXPIRED' AND NOT EXISTS"
                + " (SELECT 1 FROM facilities.lease_work_orders w WHERE w.agreement_id = a.id AND w.trigger_kind = 'EXPIRED_REVIEW')",
                (rs, n) -> agreement(rs));
    }

    // ---- work orders

    /** False if an expiry review already exists for the agreement (the unique index), so a repeat is a no-op. */
    public boolean insert(LeaseWorkOrder w) {
        return jdbc.update("""
                INSERT INTO facilities.lease_work_orders (id, site_code, agreement_id, obligation_id, trigger_kind, description, state,
                    work_order_id, work_order_number, requested_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) ON CONFLICT DO NOTHING
                """, w.id(), w.siteCode(), w.agreementId(), w.obligationId(), w.trigger().name(), w.description(), w.state(),
                w.workOrderId(), w.workOrderNumber(), w.requestedBy(), ts(w.createdAt()), ts(w.updatedAt())) == 1;
    }

    public Optional<LeaseWorkOrder> workOrder(UUID id) {
        return jdbc.query("SELECT * FROM facilities.lease_work_orders WHERE id = ?", (rs, n) -> workOrder(rs), id).stream().findFirst();
    }

    public List<LeaseWorkOrder> workOrdersOf(UUID agreementId) {
        return jdbc.query("SELECT * FROM facilities.lease_work_orders WHERE agreement_id = ? ORDER BY created_at DESC",
                (rs, n) -> workOrder(rs), agreementId);
    }

    public List<LeaseWorkOrder> pendingWorkOrders() {
        return jdbc.query("SELECT * FROM facilities.lease_work_orders WHERE state = 'PENDING_MANUAL' ORDER BY created_at",
                (rs, n) -> workOrder(rs));
    }

    public boolean link(LeaseWorkOrder w, long expectedVersion) {
        return jdbc.update("UPDATE facilities.lease_work_orders SET state = ?, work_order_id = ?, work_order_number = ?, updated_at = ?,"
                + " version = version + 1 WHERE id = ? AND version = ?", w.state(), w.workOrderId(), w.workOrderNumber(), ts(w.updatedAt()),
                w.id(), expectedVersion) == 1;
    }

    private static LeaseWorkOrder workOrder(ResultSet rs) throws SQLException {
        return new LeaseWorkOrder(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getObject("agreement_id", UUID.class),
                rs.getObject("obligation_id", UUID.class), WorkOrderTrigger.valueOf(rs.getString("trigger_kind")), rs.getString("description"),
                rs.getString("state"), rs.getObject("work_order_id", UUID.class), rs.getString("work_order_number"), rs.getString("requested_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    // ---- versions

    public void insert(AgreementVersion v) {
        jdbc.update("""
                INSERT INTO facilities.lease_versions (id, agreement_id, site_code, version_number, end_date, annual_rent,
                    deposit_amount, notice_days, rent_review_date, renewal_term_months, owner_reference,
                    counterparty_reference, status, amendment_id, approved_by, approved_at, recorded_by, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, v.id(), v.agreementId(), v.siteCode(), v.versionNumber(), v.endDate(), v.annualRent(),
                v.depositAmount(), v.noticeDays(), v.rentReviewDate(), v.renewalTermMonths(), v.ownerReference(),
                v.counterpartyReference(), v.status().name(), v.amendmentId(), v.approvedBy(), ts(v.approvedAt()),
                v.recordedBy(), ts(v.recordedAt()));
    }

    public List<AgreementVersion> versions(UUID agreementId) {
        return jdbc.query("SELECT * FROM facilities.lease_versions WHERE agreement_id = ? ORDER BY version_number",
                (rs, n) -> new AgreementVersion(rs.getObject("id", UUID.class), rs.getObject("agreement_id", UUID.class),
                        rs.getString("site_code"), rs.getInt("version_number"), date(rs, "end_date"),
                        rs.getBigDecimal("annual_rent"), rs.getBigDecimal("deposit_amount"), integer(rs, "notice_days"),
                        date(rs, "rent_review_date"), integer(rs, "renewal_term_months"), rs.getString("owner_reference"),
                        rs.getString("counterparty_reference"), AgreementStatus.valueOf(rs.getString("status")),
                        rs.getObject("amendment_id", UUID.class), rs.getString("approved_by"), instant(rs, "approved_at"),
                        rs.getString("recorded_by"), instant(rs, "recorded_at")), agreementId);
    }

    // ---- amendments

    public void insert(Amendment a) {
        jdbc.update("INSERT INTO facilities.lease_amendments (" + AMENDMENT + ") VALUES (" + "?, ".repeat(24) + "?)",
                a.id(), a.reference(), a.agreementId(), a.siteCode(), a.kind().name(), a.material(), a.status().name(),
                a.reason(), a.newEndDate(), a.newAnnualRent(), a.newDepositAmount(), a.newNoticeDays(),
                a.newRentReviewDate(), a.newRenewalTermMonths(), a.effectiveOn(), a.priorVersion(), a.proposedBy(),
                ts(a.proposedAt()), a.decidedBy(), ts(a.decidedAt()), a.decisionReason(), a.legalReviewNote(),
                a.legalClearedBy(), ts(a.legalClearedAt()), a.version());
    }

    public Optional<Amendment> amendment(UUID id) {
        return jdbc.query("SELECT " + AMENDMENT + " FROM facilities.lease_amendments WHERE id = ?", (rs, n) -> amendment(rs), id)
                .stream().findFirst();
    }

    public boolean update(Amendment a, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.lease_amendments SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ?,
                    legal_review_note = ?, legal_cleared_by = ?, legal_cleared_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, a.status().name(), a.decidedBy(), ts(a.decidedAt()), a.decisionReason(), a.legalReviewNote(),
                a.legalClearedBy(), ts(a.legalClearedAt()), a.id(), expectedVersion) == 1;
    }

    public List<Amendment> amendmentsOf(UUID agreementId) {
        return jdbc.query("SELECT " + AMENDMENT + " FROM facilities.lease_amendments WHERE agreement_id = ? ORDER BY proposed_at",
                (rs, n) -> amendment(rs), agreementId);
    }

    public List<Amendment> openAmendmentsOf(UUID agreementId) {
        return jdbc.query("SELECT " + AMENDMENT + " FROM facilities.lease_amendments WHERE agreement_id = ?"
                + " AND status IN ('PROPOSED', 'LEGAL_REVIEW') ORDER BY proposed_at", (rs, n) -> amendment(rs), agreementId);
    }

    public Page<Amendment> amendments(String site, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        return page("facilities.lease_amendments", AMENDMENT, where.toString(), args,
                " ORDER BY (status NOT IN ('PROPOSED', 'LEGAL_REVIEW')), proposed_at DESC", page, size, (rs, n) -> amendment(rs));
    }

    // ---- obligations

    public void insert(Obligation o) {
        jdbc.update("INSERT INTO facilities.lease_obligations (" + OBLIGATION + ") VALUES (" + "?, ".repeat(15) + "?)",
                o.id(), o.agreementId(), o.siteCode(), o.kind().name(), o.title(), o.dueOn(), o.ownerReference(),
                o.status().name(), o.completedOn(), o.completedBy(), o.completionNote(), o.generated(), o.createdBy(),
                ts(o.createdAt()), ts(o.updatedAt()), o.version());
    }

    public Optional<Obligation> obligation(UUID id) {
        return jdbc.query("SELECT " + OBLIGATION + " FROM facilities.lease_obligations WHERE id = ?", (rs, n) -> obligation(rs), id)
                .stream().findFirst();
    }

    public boolean update(Obligation o, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.lease_obligations SET title = ?, due_on = ?, owner_reference = ?, status = ?,
                    completed_on = ?, completed_by = ?, completion_note = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, o.title(), o.dueOn(), o.ownerReference(), o.status().name(), o.completedOn(), o.completedBy(),
                o.completionNote(), ts(o.updatedAt()), o.id(), expectedVersion) == 1;
    }

    public List<Obligation> obligationsOf(UUID agreementId) {
        return jdbc.query("SELECT " + OBLIGATION + " FROM facilities.lease_obligations WHERE agreement_id = ? ORDER BY due_on, kind",
                (rs, n) -> obligation(rs), agreementId);
    }

    public Page<Obligation> obligations(String site, String status, Integer dueWithinDays, LocalDate today, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (dueWithinDays != null) {
            where.append(" AND status = 'OPEN' AND due_on <= ?");
            args.add(today.plusDays(dueWithinDays));
        }
        return page("facilities.lease_obligations", OBLIGATION, where.toString(), args, " ORDER BY due_on, kind", page, size,
                (rs, n) -> obligation(rs));
    }

    /** Open obligations on live agreements: what the daily control looks at. */
    public List<Obligation> openObligationsOfActive() {
        return jdbc.query("SELECT o.* FROM facilities.lease_obligations o JOIN facilities.lease_agreements a ON a.id = o.agreement_id"
                + " WHERE o.status = 'OPEN' AND a.status = 'ACTIVE' ORDER BY o.due_on", (rs, n) -> obligation(rs));
    }

    public void cancelGeneratedOpen(UUID agreementId, Instant now) {
        jdbc.update("UPDATE facilities.lease_obligations SET status = 'WAIVED', completion_note = 'Superseded by an approved change',"
                + " updated_at = ?, version = version + 1 WHERE agreement_id = ? AND generated AND status = 'OPEN'",
                ts(now), agreementId);
    }

    // ---- documents

    public void insert(LeaseDocument d) {
        jdbc.update("INSERT INTO facilities.lease_documents (" + DOCUMENT + ") VALUES (" + "?, ".repeat(12) + "?)",
                d.id(), d.agreementId(), d.siteCode(), d.kind().name(), d.reference(), d.fileName(), d.mediaType(),
                d.sizeBytes(), d.contentHash(), d.retentionClass(), d.expiresOn(), d.submittedBy(), ts(d.submittedAt()));
    }

    public List<LeaseDocument> documentsOf(UUID agreementId) {
        return jdbc.query("SELECT " + DOCUMENT + " FROM facilities.lease_documents WHERE agreement_id = ? ORDER BY submitted_at",
                (rs, n) -> new LeaseDocument(rs.getObject("id", UUID.class), rs.getObject("agreement_id", UUID.class),
                        rs.getString("site_code"), DocumentKind.valueOf(rs.getString("kind")), rs.getString("reference"),
                        rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
                        rs.getString("content_hash"), rs.getString("retention_class"), date(rs, "expires_on"),
                        rs.getString("submitted_by"), instant(rs, "submitted_at")), agreementId);
    }

    /** Documents on live agreements that have passed their expiry - insurance, compliance certificates. */
    public List<LeaseDocument> expiredDocumentsOfActive(LocalDate today) {
        return jdbc.query("SELECT d.* FROM facilities.lease_documents d JOIN facilities.lease_agreements a ON a.id = d.agreement_id"
                + " WHERE a.status = 'ACTIVE' AND d.expires_on IS NOT NULL AND d.expires_on < ?",
                (rs, n) -> new LeaseDocument(rs.getObject("id", UUID.class), rs.getObject("agreement_id", UUID.class),
                        rs.getString("site_code"), DocumentKind.valueOf(rs.getString("kind")), rs.getString("reference"),
                        rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
                        rs.getString("content_hash"), rs.getString("retention_class"), date(rs, "expires_on"),
                        rs.getString("submitted_by"), instant(rs, "submitted_at")), today);
    }

    // ---- alerts

    /** Inserts once per agreement, obligation, level and reason; returns false if it was already raised. */
    public boolean insert(LeaseAlert a) {
        return jdbc.update("""
                INSERT INTO facilities.lease_alerts (id, site_code, agreement_id, obligation_id, level, reason, detail, raised_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, a.id(), a.siteCode(), a.agreementId(), a.obligationId(), a.level().name(), a.reason().name(),
                a.detail(), ts(a.raisedAt())) == 1;
    }

    public Optional<LeaseAlert> alert(UUID id) {
        return jdbc.query("SELECT * FROM facilities.lease_alerts WHERE id = ?", (rs, n) -> alert(rs), id).stream().findFirst();
    }

    public void acknowledge(UUID id, String by, Instant at) {
        jdbc.update("UPDATE facilities.lease_alerts SET acknowledged_by = ?, acknowledged_at = ? WHERE id = ?"
                + " AND acknowledged_at IS NULL", by, ts(at), id);
    }

    public Page<LeaseAlert> alerts(String site, boolean openOnly, int page, int size) {
        String where = " WHERE site_code = ?" + (openOnly ? " AND acknowledged_at IS NULL" : "");
        return page("facilities.lease_alerts", "*", where, List.of(site), " ORDER BY raised_at DESC", page, size, (rs, n) -> alert(rs));
    }

    public List<LeaseAlert> alertsOf(UUID agreementId) {
        return jdbc.query("SELECT * FROM facilities.lease_alerts WHERE agreement_id = ? ORDER BY raised_at DESC",
                (rs, n) -> alert(rs), agreementId);
    }

    // ---- history

    public void record(LeaseHistoryEntry h) {
        jdbc.update("INSERT INTO facilities.lease_history (id, site_code, subject_type, subject_id, from_status, to_status,"
                + " actor, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", h.id(), h.siteCode(), h.subjectType(),
                h.subjectId(), h.fromStatus(), h.toStatus(), h.actor(), h.reason(), ts(h.occurredAt()));
    }

    public List<LeaseHistoryEntry> history(UUID subjectId) {
        return jdbc.query("SELECT * FROM facilities.lease_history WHERE subject_id = ? ORDER BY occurred_at, id",
                (rs, n) -> new LeaseHistoryEntry(rs.getObject("id", UUID.class), rs.getString("site_code"),
                        rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("actor"), rs.getString("reason"), instant(rs, "occurred_at")),
                subjectId);
    }

    // ---- reporting

    // Package-private: only the application service in this package can supply the fixed SQL templates.
    long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    public List<OwnerRow> byOwner(String site, LocalDate today) {
        return jdbc.query("""
                SELECT COALESCE(a.owner_reference, 'Unassigned') AS owner, count(DISTINCT a.id) AS agreements,
                       count(o.id) FILTER (WHERE o.status = 'OPEN') AS open_obligations,
                       count(o.id) FILTER (WHERE o.status = 'OPEN' AND o.due_on < ?) AS overdue
                  FROM facilities.lease_agreements a LEFT JOIN facilities.lease_obligations o ON o.agreement_id = a.id
                 WHERE a.site_code = ? AND a.status = 'ACTIVE' GROUP BY 1 ORDER BY 1
                """, (rs, n) -> new OwnerRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), today, site);
    }

    public record OwnerRow(String owner, long agreements, long openObligations, long overdueObligations) {
    }

    /** {renewal obligations due or done, done on time} - "renewal actions completed on time". */
    public long[] renewalTimeliness(String site, LocalDate today) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'DONE' OR due_on < ?),
                       count(*) FILTER (WHERE status = 'DONE' AND completed_on <= due_on)
                  FROM facilities.lease_obligations WHERE site_code = ? AND kind = 'RENEWAL'
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}, today, site);
    }

    public Double meanAmendmentCycleHours(String site) {
        return jdbc.queryForObject("SELECT avg(extract(epoch FROM (decided_at - proposed_at)) / 3600.0) FROM"
                + " facilities.lease_amendments WHERE site_code = ? AND status IN ('APPROVED', 'REJECTED')", Double.class, site);
    }

    // ---- paging and mapping

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    private <T> Page<T> page(String table, String columns, String where, List<Object> args, String order, int page, int size,
            RowMapper<T> mapper) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<T> items = jdbc.query("SELECT " + columns + " FROM " + table + where + order + " LIMIT ? OFFSET ?", mapper,
                pageArgs.toArray());
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    private static Set<DayOfWeek> parseDays(String csv) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(s -> out.add(DayOfWeek.valueOf(s)));
        return out;
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

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Agreement agreement(ResultSet rs) throws SQLException {
        return new Agreement(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("site_code"),
                rs.getObject("room_id", UUID.class), rs.getString("property_reference"),
                AgreementKind.valueOf(rs.getString("agreement_kind")), Direction.valueOf(rs.getString("direction")),
                rs.getString("title"), rs.getString("counterparty_reference"),
                CounterpartyState.valueOf(rs.getString("counterparty_state")), rs.getString("contract_reference"),
                rs.getString("finance_reference"), rs.getString("owner_reference"), date(rs, "start_date"), date(rs, "end_date"),
                RenewalType.valueOf(rs.getString("renewal_type")), integer(rs, "renewal_term_months"), integer(rs, "notice_days"),
                date(rs, "notice_date"), date(rs, "rent_review_date"), rs.getBigDecimal("annual_rent"),
                rs.getBigDecimal("deposit_amount"), rs.getString("currency"), AgreementStatus.valueOf(rs.getString("status")),
                rs.getInt("version_number"), rs.getString("requested_by"), rs.getString("approved_by"),
                instant(rs, "approved_at"), date(rs, "terminated_on"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static Amendment amendment(ResultSet rs) throws SQLException {
        return new Amendment(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("agreement_id", UUID.class),
                rs.getString("site_code"), AmendmentKind.valueOf(rs.getString("kind")), rs.getBoolean("material"),
                AmendmentStatus.valueOf(rs.getString("status")), rs.getString("reason"), date(rs, "new_end_date"),
                rs.getBigDecimal("new_annual_rent"), rs.getBigDecimal("new_deposit_amount"), integer(rs, "new_notice_days"),
                date(rs, "new_rent_review_date"), integer(rs, "new_renewal_term_months"), date(rs, "effective_on"),
                rs.getInt("prior_version"), rs.getString("proposed_by"), instant(rs, "proposed_at"), rs.getString("decided_by"),
                instant(rs, "decided_at"), rs.getString("decision_reason"), rs.getString("legal_review_note"),
                rs.getString("legal_cleared_by"), instant(rs, "legal_cleared_at"), rs.getLong("version"));
    }

    private static Obligation obligation(ResultSet rs) throws SQLException {
        return new Obligation(rs.getObject("id", UUID.class), rs.getObject("agreement_id", UUID.class), rs.getString("site_code"),
                ObligationKind.valueOf(rs.getString("kind")), rs.getString("title"), date(rs, "due_on"),
                rs.getString("owner_reference"), ObligationStatus.valueOf(rs.getString("status")), date(rs, "completed_on"),
                rs.getString("completed_by"), rs.getString("completion_note"), rs.getBoolean("generated"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static LeaseAlert alert(ResultSet rs) throws SQLException {
        return new LeaseAlert(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getObject("agreement_id", UUID.class),
                rs.getObject("obligation_id", UUID.class), AlertLevel.valueOf(rs.getString("level")),
                AlertReason.valueOf(rs.getString("reason")), rs.getString("detail"), instant(rs, "raised_at"),
                rs.getString("acknowledged_by"), instant(rs, "acknowledged_at"));
    }
}

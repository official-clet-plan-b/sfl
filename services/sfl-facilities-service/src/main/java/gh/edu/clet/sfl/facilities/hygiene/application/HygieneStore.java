package gh.edu.clet.sfl.facilities.hygiene.application;

import gh.edu.clet.sfl.facilities.hygiene.domain.ActionStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosureMode;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlType;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationLevel;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.hygiene.domain.EvidenceStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.FindingStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.Frequency;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEscalation;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEvidence;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneHistoryEntry;
import gh.edu.clet.sfl.facilities.hygiene.domain.LinkState;
import gh.edu.clet.sfl.facilities.hygiene.domain.RiskCategory;
import gh.edu.clet.sfl.facilities.hygiene.domain.Severity;
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
import org.springframework.stereotype.Repository;

/**
 * SQL for the S170 tables. Kept apart from the service so the rules read as rules and the statements
 * read as statements; the service owns the transaction and the authorisation, this owns the columns.
 *
 * <p>Every query runs under the site-scope row-level policy, so a statement here cannot return another
 * site's row even if the service forgot to check - the service still checks, because a 403 is a better
 * answer than an empty list.
 */
@Repository
public class HygieneStore {

    private static final String CONTROL_COLUMNS = """
            id, reference, site_code, room_id, location_label, control_type, risk_category, title, owner_reference,
            frequency, due_on, status, started_at, completed_on, completed_by, provider_reference, provider_confirmed,
            provider_confirmed_by, provider_confirmed_at, previous_control_id, notes, overdue_notified_at, created_by,
            created_at, updated_at, version""";

    private static final String FINDING_COLUMNS = """
            id, reference, control_id, site_code, room_id, category, title, description, severity, status, owner_reference,
            target_date, requires_incident, incident_state, incident_reference, work_order_state, work_order_id,
            work_order_number, repeat_of_id, escalation_level, closure_mode, closure_reason, closure_approved_by,
            closed_at, closed_by, created_by, created_at, updated_at, version""";

    private static final String ACTION_COLUMNS = """
            id, finding_id, site_code, description, owner_reference, due_on, status, completed_at, completed_by,
            verified_at, verified_by, rejection_reason, created_by, created_at, updated_at, version""";

    private static final String EVIDENCE_COLUMNS = """
            id, finding_id, action_id, site_code, reference, file_name, media_type, size_bytes, content_hash,
            retention_class, notes, status, submitted_by, submitted_at, reviewed_by, reviewed_at, review_reason""";

    private final JdbcTemplate jdbc;

    public HygieneStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- controls

    public void insert(HygieneControl c) {
        jdbc.update("INSERT INTO facilities.hygiene_controls (" + CONTROL_COLUMNS + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.reference(), c.siteCode(), c.roomId(), c.locationLabel(), c.controlType().name(),
                c.riskCategory().name(), c.title(), c.ownerReference(), c.frequency().name(), c.dueOn(),
                c.status().name(), ts(c.startedAt()), c.completedOn(), c.completedBy(), c.providerReference(),
                c.providerConfirmed(), c.providerConfirmedBy(), ts(c.providerConfirmedAt()), c.previousControlId(),
                c.notes(), ts(c.overdueNotifiedAt()), c.createdBy(), ts(c.createdAt()), ts(c.updatedAt()), c.version());
    }

    public Optional<HygieneControl> findControl(UUID id) {
        return jdbc.query("SELECT " + CONTROL_COLUMNS + " FROM facilities.hygiene_controls WHERE id = ?",
                (rs, n) -> control(rs), id).stream().findFirst();
    }

    /** Writes every mutable column; the version guard makes a stale writer fail instead of overwrite. */
    public boolean update(HygieneControl c, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.hygiene_controls
                   SET room_id = ?, location_label = ?, title = ?, owner_reference = ?, due_on = ?, status = ?,
                       started_at = ?, completed_on = ?, completed_by = ?, provider_reference = ?,
                       provider_confirmed = ?, provider_confirmed_by = ?, provider_confirmed_at = ?, notes = ?,
                       overdue_notified_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, c.roomId(), c.locationLabel(), c.title(), c.ownerReference(), c.dueOn(), c.status().name(),
                ts(c.startedAt()), c.completedOn(), c.completedBy(), c.providerReference(), c.providerConfirmed(),
                c.providerConfirmedBy(), ts(c.providerConfirmedAt()), c.notes(), ts(c.overdueNotifiedAt()),
                ts(c.updatedAt()), c.id(), expectedVersion) == 1;
    }

    public boolean nextControlExists(UUID previousId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM facilities.hygiene_controls WHERE previous_control_id = ?)",
                Boolean.class, previousId));
    }

    public long nextSequence(String sequenceName) {
        return jdbc.queryForObject("SELECT nextval(?::regclass)", Long.class, "facilities." + sequenceName);
    }

    public Page<HygieneControl> controls(String site, String status, String type, boolean overdueOnly, LocalDate today,
            int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (type != null) {
            where.append(" AND control_type = ?");
            args.add(type);
        }
        if (overdueOnly) {
            where.append(" AND status IN ('SCHEDULED', 'IN_PROGRESS') AND due_on < ?");
            args.add(today);
        }
        return page("facilities.hygiene_controls", CONTROL_COLUMNS, where.toString(), args,
                " ORDER BY due_on, reference", page, size, (rs, n) -> control(rs));
    }

    /** Open controls past their due date by at least {@code graceDays}, across the sites the caller may see. */
    public List<HygieneControl> openControlsDueBefore(LocalDate cutoff) {
        return jdbc.query("SELECT " + CONTROL_COLUMNS
                + " FROM facilities.hygiene_controls WHERE status IN ('SCHEDULED', 'IN_PROGRESS') AND due_on < ?"
                + " ORDER BY due_on", (rs, n) -> control(rs), cutoff);
    }

    // ---- findings

    public void insert(HygieneFinding f) {
        jdbc.update("INSERT INTO facilities.hygiene_findings (" + FINDING_COLUMNS + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                f.id(), f.reference(), f.controlId(), f.siteCode(), f.roomId(), f.category().name(), f.title(),
                f.description(), f.severity().name(), f.status().name(), f.ownerReference(), f.targetDate(),
                f.requiresIncident(), f.incidentState().name(), f.incidentReference(), f.workOrderState().name(),
                f.workOrderId(), f.workOrderNumber(), f.repeatOfId(), f.escalationLevel().name(),
                f.closureMode() == null ? null : f.closureMode().name(), f.closureReason(), f.closureApprovedBy(),
                ts(f.closedAt()), f.closedBy(), f.createdBy(), ts(f.createdAt()), ts(f.updatedAt()), f.version());
    }

    public Optional<HygieneFinding> findFinding(UUID id) {
        return jdbc.query("SELECT " + FINDING_COLUMNS + " FROM facilities.hygiene_findings WHERE id = ?",
                (rs, n) -> finding(rs), id).stream().findFirst();
    }

    public boolean update(HygieneFinding f, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.hygiene_findings
                   SET title = ?, description = ?, status = ?, owner_reference = ?, target_date = ?,
                       incident_state = ?, incident_reference = ?, work_order_state = ?, work_order_id = ?,
                       work_order_number = ?, escalation_level = ?, closure_mode = ?, closure_reason = ?,
                       closure_approved_by = ?, closed_at = ?, closed_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, f.title(), f.description(), f.status().name(), f.ownerReference(), f.targetDate(),
                f.incidentState().name(), f.incidentReference(), f.workOrderState().name(), f.workOrderId(),
                f.workOrderNumber(), f.escalationLevel().name(),
                f.closureMode() == null ? null : f.closureMode().name(), f.closureReason(), f.closureApprovedBy(),
                ts(f.closedAt()), f.closedBy(), ts(f.updatedAt()), f.id(), expectedVersion) == 1;
    }

    public Page<HygieneFinding> findings(String site, String status, String severity, UUID controlId,
            boolean overdueOnly, LocalDate today, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE site_code = ?");
        List<Object> args = new ArrayList<>(List.of(site));
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (severity != null) {
            where.append(" AND severity = ?");
            args.add(severity);
        }
        if (controlId != null) {
            where.append(" AND control_id = ?");
            args.add(controlId);
        }
        if (overdueOnly) {
            where.append(" AND status <> 'CLOSED' AND target_date < ?");
            args.add(today);
        }
        return page("facilities.hygiene_findings", FINDING_COLUMNS, where.toString(), args,
                " ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,"
                        + " target_date NULLS LAST, reference",
                page, size, (rs, n) -> finding(rs));
    }

    /** An earlier critical finding of the same category in the same place, inside the repeat window. */
    public Optional<HygieneFinding> earlierCritical(String site, RiskCategory category, UUID roomId, Instant since) {
        return jdbc.query("SELECT " + FINDING_COLUMNS + """
                 FROM facilities.hygiene_findings
                WHERE site_code = ? AND category = ? AND severity = 'CRITICAL' AND created_at >= ?
                  AND (room_id IS NOT DISTINCT FROM ?)
                ORDER BY created_at DESC LIMIT 1""", (rs, n) -> finding(rs), site, category.name(), ts(since), roomId)
                .stream().findFirst();
    }

    // ---- actions

    public void insert(HygieneAction a) {
        jdbc.update("INSERT INTO facilities.hygiene_actions (" + ACTION_COLUMNS + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.id(), a.findingId(), a.siteCode(), a.description(), a.ownerReference(), a.dueOn(),
                a.status().name(), ts(a.completedAt()), a.completedBy(), ts(a.verifiedAt()), a.verifiedBy(),
                a.rejectionReason(), a.createdBy(), ts(a.createdAt()), ts(a.updatedAt()), a.version());
    }

    public Optional<HygieneAction> findAction(UUID id) {
        return jdbc.query("SELECT " + ACTION_COLUMNS + " FROM facilities.hygiene_actions WHERE id = ?",
                (rs, n) -> action(rs), id).stream().findFirst();
    }

    public boolean update(HygieneAction a, long expectedVersion) {
        return jdbc.update("""
                UPDATE facilities.hygiene_actions
                   SET status = ?, completed_at = ?, completed_by = ?, verified_at = ?, verified_by = ?,
                       rejection_reason = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, a.status().name(), ts(a.completedAt()), a.completedBy(), ts(a.verifiedAt()), a.verifiedBy(),
                a.rejectionReason(), ts(a.updatedAt()), a.id(), expectedVersion) == 1;
    }

    public List<HygieneAction> actionsOf(UUID findingId) {
        return jdbc.query("SELECT " + ACTION_COLUMNS + " FROM facilities.hygiene_actions WHERE finding_id = ?"
                + " ORDER BY created_at", (rs, n) -> action(rs), findingId);
    }

    public List<HygieneAction> openActionsDueBefore(LocalDate cutoff) {
        return jdbc.query("SELECT " + ACTION_COLUMNS + " FROM facilities.hygiene_actions"
                + " WHERE status IN ('OPEN', 'IN_PROGRESS', 'REJECTED') AND due_on < ? ORDER BY due_on",
                (rs, n) -> action(rs), cutoff);
    }

    // ---- evidence

    public void insert(HygieneEvidence e) {
        jdbc.update("INSERT INTO facilities.hygiene_evidence (" + EVIDENCE_COLUMNS + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.id(), e.findingId(), e.actionId(), e.siteCode(), e.reference(), e.fileName(), e.mediaType(),
                e.sizeBytes(), e.contentHash(), e.retentionClass(), e.notes(), e.status().name(), e.submittedBy(),
                ts(e.submittedAt()), e.reviewedBy(), ts(e.reviewedAt()), e.reviewReason());
    }

    public Optional<HygieneEvidence> findEvidence(UUID id) {
        return jdbc.query("SELECT " + EVIDENCE_COLUMNS + " FROM facilities.hygiene_evidence WHERE id = ?",
                (rs, n) -> evidence(rs), id).stream().findFirst();
    }

    public void review(UUID id, EvidenceStatus status, String reviewer, Instant at, String reason) {
        jdbc.update("UPDATE facilities.hygiene_evidence SET status = ?, reviewed_by = ?, reviewed_at = ?,"
                + " review_reason = ? WHERE id = ?", status.name(), reviewer, ts(at), reason, id);
    }

    public List<HygieneEvidence> evidenceOf(UUID findingId) {
        return jdbc.query("SELECT " + EVIDENCE_COLUMNS + " FROM facilities.hygiene_evidence WHERE finding_id = ?"
                + " ORDER BY submitted_at", (rs, n) -> evidence(rs), findingId);
    }

    // ---- escalations and history

    /** Records an escalation once per subject, level and reason; returns false if it was already raised. */
    public boolean insert(HygieneEscalation e) {
        return jdbc.update("""
                INSERT INTO facilities.hygiene_escalations
                    (id, site_code, subject_type, subject_id, subject_reference, level, reason, detail, raised_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (subject_id, level, reason) DO NOTHING
                """, e.id(), e.siteCode(), e.subjectType(), e.subjectId(), e.subjectReference(), e.level().name(),
                e.reason().name(), e.detail(), ts(e.raisedAt())) == 1;
    }

    public Optional<HygieneEscalation> findEscalation(UUID id) {
        return jdbc.query("SELECT * FROM facilities.hygiene_escalations WHERE id = ?", (rs, n) -> escalation(rs), id)
                .stream().findFirst();
    }

    public void acknowledge(UUID id, String by, Instant at) {
        jdbc.update("UPDATE facilities.hygiene_escalations SET acknowledged_by = ?, acknowledged_at = ?"
                + " WHERE id = ? AND acknowledged_at IS NULL", by, ts(at), id);
    }

    public Page<HygieneEscalation> escalations(String site, boolean openOnly, int page, int size) {
        String where = " WHERE site_code = ?" + (openOnly ? " AND acknowledged_at IS NULL" : "");
        return page("facilities.hygiene_escalations", "*", where, List.of(site), " ORDER BY raised_at DESC", page, size,
                (rs, n) -> escalation(rs));
    }

    public void record(HygieneHistoryEntry h) {
        jdbc.update("INSERT INTO facilities.hygiene_history (id, site_code, subject_type, subject_id, from_status,"
                + " to_status, actor, reason, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                h.id(), h.siteCode(), h.subjectType(), h.subjectId(), h.fromStatus(), h.toStatus(), h.actor(),
                h.reason(), ts(h.occurredAt()));
    }

    public List<HygieneHistoryEntry> history(UUID subjectId) {
        return jdbc.query("SELECT * FROM facilities.hygiene_history WHERE subject_id = ? ORDER BY occurred_at, id",
                (rs, n) -> new HygieneHistoryEntry(rs.getObject("id", UUID.class), rs.getString("site_code"),
                        rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("actor"),
                        rs.getString("reason"), instant(rs, "occurred_at")), subjectId);
    }

    // ---- dashboard

    public Kpis kpis(String site, LocalDate today, LocalDate since) {
        long dueInPeriod = count("SELECT count(*) FROM facilities.hygiene_controls WHERE site_code = ? AND due_on >= ?"
                + " AND due_on <= ? AND status <> 'CANCELLED'", site, since, today);
        long completedInPeriod = count("SELECT count(*) FROM facilities.hygiene_controls WHERE site_code = ? AND due_on >= ?"
                + " AND due_on <= ? AND status = 'COMPLETED'", site, since, today);
        long overdueControls = count("SELECT count(*) FROM facilities.hygiene_controls WHERE site_code = ?"
                + " AND status IN ('SCHEDULED', 'IN_PROGRESS') AND due_on < ?", site, today);
        long openFindings = count("SELECT count(*) FROM facilities.hygiene_findings WHERE site_code = ?"
                + " AND status <> 'CLOSED'", site);
        long openCritical = count("SELECT count(*) FROM facilities.hygiene_findings WHERE site_code = ?"
                + " AND status <> 'CLOSED' AND severity = 'CRITICAL'", site);
        long overdueFindings = count("SELECT count(*) FROM facilities.hygiene_findings WHERE site_code = ?"
                + " AND status <> 'CLOSED' AND target_date < ?", site, today);
        long overdueActions = count("SELECT count(*) FROM facilities.hygiene_actions WHERE site_code = ?"
                + " AND status IN ('OPEN', 'IN_PROGRESS', 'REJECTED') AND due_on < ?", site, today);
        long openEscalations = count("SELECT count(*) FROM facilities.hygiene_escalations WHERE site_code = ?"
                + " AND acknowledged_at IS NULL", site);
        Double hours = jdbc.queryForObject("""
                SELECT avg(extract(epoch FROM (closed_at - created_at)) / 3600.0)
                  FROM facilities.hygiene_findings
                 WHERE site_code = ? AND severity = 'CRITICAL' AND status = 'CLOSED'
                """, Double.class, site);
        return new Kpis(dueInPeriod, completedInPeriod, overdueControls, openFindings, openCritical, overdueFindings,
                overdueActions, openEscalations, hours);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    public record Kpis(long controlsDue, long controlsCompleted, long overdueControls, long openFindings,
            long openCriticalFindings, long overdueFindings, long overdueActions, long openEscalations,
            Double meanHoursToCloseCritical) {
    }

    // ---- paging and mapping

    public record Page<T>(List<T> items, long total, int page, int size) {
    }

    private <T> Page<T> page(String table, String columns, String where, List<Object> args, String order, int page,
            int size, org.springframework.jdbc.core.RowMapper<T> mapper) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<T> items = jdbc.query("SELECT " + columns + " FROM " + table + where + order + " LIMIT ? OFFSET ?", mapper,
                pageArgs.toArray());
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    /** The PostgreSQL driver cannot bind a bare {@code Instant}; a {@code Timestamp} it can. */
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

    private static HygieneControl control(ResultSet rs) throws SQLException {
        return new HygieneControl(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("site_code"),
                rs.getObject("room_id", UUID.class), rs.getString("location_label"),
                ControlType.valueOf(rs.getString("control_type")), RiskCategory.valueOf(rs.getString("risk_category")),
                rs.getString("title"), rs.getString("owner_reference"), Frequency.valueOf(rs.getString("frequency")),
                date(rs, "due_on"), ControlStatus.valueOf(rs.getString("status")), instant(rs, "started_at"),
                date(rs, "completed_on"), rs.getString("completed_by"), rs.getString("provider_reference"),
                rs.getBoolean("provider_confirmed"), rs.getString("provider_confirmed_by"),
                instant(rs, "provider_confirmed_at"), rs.getObject("previous_control_id", UUID.class),
                rs.getString("notes"), instant(rs, "overdue_notified_at"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static HygieneFinding finding(ResultSet rs) throws SQLException {
        String closure = rs.getString("closure_mode");
        return new HygieneFinding(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getObject("control_id", UUID.class), rs.getString("site_code"), rs.getObject("room_id", UUID.class),
                RiskCategory.valueOf(rs.getString("category")), rs.getString("title"), rs.getString("description"),
                Severity.valueOf(rs.getString("severity")), FindingStatus.valueOf(rs.getString("status")),
                rs.getString("owner_reference"), date(rs, "target_date"), rs.getBoolean("requires_incident"),
                LinkState.valueOf(rs.getString("incident_state")), rs.getString("incident_reference"),
                LinkState.valueOf(rs.getString("work_order_state")), rs.getObject("work_order_id", UUID.class),
                rs.getString("work_order_number"), rs.getObject("repeat_of_id", UUID.class),
                EscalationLevel.valueOf(rs.getString("escalation_level")),
                closure == null ? null : ClosureMode.valueOf(closure), rs.getString("closure_reason"),
                rs.getString("closure_approved_by"), instant(rs, "closed_at"), rs.getString("closed_by"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static HygieneAction action(ResultSet rs) throws SQLException {
        return new HygieneAction(rs.getObject("id", UUID.class), rs.getObject("finding_id", UUID.class),
                rs.getString("site_code"), rs.getString("description"), rs.getString("owner_reference"),
                date(rs, "due_on"), ActionStatus.valueOf(rs.getString("status")), instant(rs, "completed_at"),
                rs.getString("completed_by"), instant(rs, "verified_at"), rs.getString("verified_by"),
                rs.getString("rejection_reason"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static HygieneEvidence evidence(ResultSet rs) throws SQLException {
        return new HygieneEvidence(rs.getObject("id", UUID.class), rs.getObject("finding_id", UUID.class),
                rs.getObject("action_id", UUID.class), rs.getString("site_code"), rs.getString("reference"),
                rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"),
                rs.getString("content_hash"), rs.getString("retention_class"), rs.getString("notes"),
                EvidenceStatus.valueOf(rs.getString("status")), rs.getString("submitted_by"),
                instant(rs, "submitted_at"), rs.getString("reviewed_by"), instant(rs, "reviewed_at"),
                rs.getString("review_reason"));
    }

    private static HygieneEscalation escalation(ResultSet rs) throws SQLException {
        return new HygieneEscalation(rs.getObject("id", UUID.class), rs.getString("site_code"),
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                rs.getString("subject_reference"), EscalationLevel.valueOf(rs.getString("level")),
                EscalationReason.valueOf(rs.getString("reason")), rs.getString("detail"), instant(rs, "raised_at"),
                rs.getString("acknowledged_by"), instant(rs, "acknowledged_at"));
    }
}

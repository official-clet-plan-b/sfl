package gh.edu.clet.sfl.safetysecurity.permit.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitPage;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalDecision;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalPurpose;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.CompetencyCheck;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EvidenceKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ExtensionStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.FlagType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.HistoryEntry;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.OriginSystem;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEscalation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEvidence;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitExtension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitFlag;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitNotification;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitSuspension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.WorkRole;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * JDBC-backed {@link PermitRepository}, in the style of {@code JdbcDrillRepository}. Row-level security applies exactly as
 * to JPA: the site scope is set on the transaction's connection, which {@link JdbcTemplate} shares.
 */
@Component
public class JdbcPermitRepository implements PermitRepository {

    private static final String S = "safety_security.";
    private static final Map<String, String> SORTS = Map.of("startsAt", "starts_at", "endsAt", "ends_at", "reference", "reference",
            "status", "status", "title", "title", "updatedAt", "updated_at");

    private final JdbcTemplate jdbc;

    public JdbcPermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- types ----------------------------------------------------------------------------------

    @Override
    public List<PermitType> types(boolean activeOnly) {
        return jdbc.query("SELECT * FROM " + S + "permit_types" + (activeOnly ? " WHERE active" : "") + " ORDER BY name", TYPE);
    }

    @Override
    public Optional<PermitType> findType(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "permit_types WHERE id = ?", TYPE, id).stream().findFirst();
    }

    @Override
    public Optional<PermitType> findTypeByCode(String code) {
        return jdbc.query("SELECT * FROM " + S + "permit_types WHERE code = ?", TYPE, code).stream().findFirst();
    }

    @Override
    public PermitType insertType(PermitType t) {
        jdbc.update("INSERT INTO " + S + "permit_types (id, code, name, description, risk_level, activity_type, risk_assessment_required,"
                + " two_stage, requires_isolation, max_validity_hours, required_competencies, active, created_by, created_at, updated_at,"
                + " record_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", t.id(), t.code(), t.name(), t.description(), t.riskLevel().name(),
                t.activityType(), t.riskAssessmentRequired(), t.twoStage(), t.requiresIsolation(), t.maxValidityHours(),
                t.requiredCompetencies().toArray(String[]::new), t.active(), t.createdBy(), ts(t.createdAt()), ts(t.updatedAt()));
        return findType(t.id()).orElseThrow();
    }

    @Override
    public PermitType updateType(PermitType t, long expectedVersion) {
        int n = jdbc.update("UPDATE " + S + "permit_types SET name = ?, description = ?, risk_level = ?, activity_type = ?,"
                + " risk_assessment_required = ?, two_stage = ?, requires_isolation = ?, max_validity_hours = ?, required_competencies = ?,"
                + " active = ?, updated_at = ?, record_version = record_version + 1 WHERE id = ? AND record_version = ?", t.name(), t.description(),
                t.riskLevel().name(), t.activityType(), t.riskAssessmentRequired(), t.twoStage(), t.requiresIsolation(), t.maxValidityHours(),
                t.requiredCompetencies().toArray(String[]::new), t.active(), ts(t.updatedAt()), t.id(), expectedVersion);
        if (n == 0) {
            throw PermitException.conflict();
        }
        return findType(t.id()).orElseThrow();
    }

    // ---- permits --------------------------------------------------------------------------------

    @Override
    public long nextReference() {
        return jdbc.queryForObject("SELECT nextval('safety_security.permit_seq')", Long.class);
    }

    @Override
    public Permit insertPermit(Permit p) {
        jdbc.update("INSERT INTO " + S + """
                permits (id, site_code, reference, permit_type_id, work_type, title, work_description, location_code, zone_id, zone_code,
                    starts_at, ends_at, status, status_reason, risk_assessment_id, risk_assessment_reference, risk_assessment_version,
                    risk_level, risk_review_due_at, contractor_reference, supervisor_reference, supervisor_contact, origin_system,
                    origin_reference, approval_round, requested_by, submitted_at, issued_at, completion_statement, work_completed_at,
                    work_completed_by, closed_at, closed_by, created_by, created_at, updated_at, record_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, p.id(), p.siteCode(), p.reference(), p.permitTypeId(), p.workType(), p.title(), p.workDescription(), p.locationCode(),
                p.zoneId(), p.zoneCode(), ts(p.startsAt()), ts(p.endsAt()), p.status().name(), p.statusReason(), p.riskAssessmentId(),
                p.riskAssessmentReference(), p.riskAssessmentVersion(), name(p.riskLevel()), ts(p.riskReviewDueAt()), p.contractorReference(),
                p.supervisorReference(), p.supervisorContact(), p.originSystem().name(), p.originReference(), p.approvalRound(), p.requestedBy(),
                ts(p.submittedAt()), ts(p.issuedAt()), p.completionStatement(), ts(p.workCompletedAt()), p.workCompletedBy(), ts(p.closedAt()),
                p.closedBy(), p.createdBy(), ts(p.createdAt()), ts(p.updatedAt()));
        return findPermit(p.id()).orElseThrow();
    }

    @Override
    public Optional<Permit> findPermit(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "permits WHERE id = ?", PERMIT, id).stream().findFirst();
    }

    @Override
    public Permit updatePermit(Permit p, long expectedVersion) {
        int n = jdbc.update("UPDATE " + S + """
                permits SET title = ?, work_description = ?, location_code = ?, zone_id = ?, zone_code = ?, starts_at = ?, ends_at = ?,
                    status = ?, status_reason = ?, risk_assessment_id = ?, risk_assessment_reference = ?, risk_assessment_version = ?,
                    risk_level = ?, risk_review_due_at = ?, contractor_reference = ?, supervisor_reference = ?, supervisor_contact = ?,
                    origin_system = ?, origin_reference = ?, approval_round = ?, submitted_at = ?, issued_at = ?, completion_statement = ?,
                    work_completed_at = ?, work_completed_by = ?, closed_at = ?, closed_by = ?, updated_at = ?, record_version = record_version + 1
                WHERE id = ? AND record_version = ?
                """, p.title(), p.workDescription(), p.locationCode(), p.zoneId(), p.zoneCode(), ts(p.startsAt()), ts(p.endsAt()),
                p.status().name(), p.statusReason(), p.riskAssessmentId(), p.riskAssessmentReference(), p.riskAssessmentVersion(),
                name(p.riskLevel()), ts(p.riskReviewDueAt()), p.contractorReference(), p.supervisorReference(), p.supervisorContact(),
                p.originSystem().name(), p.originReference(), p.approvalRound(), ts(p.submittedAt()), ts(p.issuedAt()), p.completionStatement(),
                ts(p.workCompletedAt()), p.workCompletedBy(), ts(p.closedAt()), p.closedBy(), ts(p.updatedAt()), p.id(), expectedVersion);
        if (n == 0) {
            throw PermitException.conflict();
        }
        return findPermit(p.id()).orElseThrow();
    }

    @Override
    public PermitPage<Permit> search(PermitQuery q) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (q.sites() != null) {
            where.append(" AND site_code = ANY (?)");
            args.add(q.sites().toArray(String[]::new));
        }
        if (q.permitTypeId() != null) {
            where.append(" AND permit_type_id = ?");
            args.add(q.permitTypeId());
        }
        if (q.status() != null) {
            where.append(" AND status = ?");
            args.add(q.status().name());
        }
        if (q.contractor() != null && !q.contractor().isBlank()) {
            where.append(" AND lower(contractor_reference) = lower(?)");
            args.add(q.contractor().strip());
        }
        if (q.from() != null) {
            where.append(" AND starts_at >= ?");
            args.add(ts(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND starts_at < ?");
            args.add(ts(q.to()));
        }
        if (q.openOnly()) {
            where.append(" AND status IN ('ACTIVE', 'SUSPENDED', 'RESUMPTION_PENDING', 'WORK_COMPLETE')");
        }
        if (q.overdueOnly()) {
            where.append(" AND status IN ('ACTIVE', 'SUSPENDED', 'RESUMPTION_PENDING', 'WORK_COMPLETE') AND ends_at < ?");
            args.add(ts(q.now()));
        }
        if (q.text() != null && !q.text().isBlank()) {
            where.append(" AND (lower(title) LIKE ? OR lower(reference) LIKE ? OR lower(location_code) LIKE ?)");
            String like = "%" + q.text().strip().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM " + S + "permits" + where, Long.class, args.toArray());
        String[] sort = (q.paging().sort() == null ? "startsAt,desc" : q.paging().sort()).split(",");
        String column = SORTS.getOrDefault(sort[0].strip(), "starts_at");
        String direction = sort.length > 1 && "asc".equalsIgnoreCase(sort[1].strip()) ? "ASC" : "DESC";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q.paging().size());
        pageArgs.add(q.paging().offset());
        List<Permit> rows = jdbc.query("SELECT * FROM " + S + "permits" + where + " ORDER BY " + column + " " + direction
                + ", id LIMIT ? OFFSET ?", PERMIT, pageArgs.toArray());
        return PermitPage.of(rows, q.paging(), total);
    }

    @Override
    public List<Permit> openPermits(List<String> sites) {
        String filter = sites == null ? "" : " AND site_code = ANY (?)";
        Object[] args = sites == null ? new Object[0] : new Object[] {sites.toArray(String[]::new)};
        return jdbc.query("SELECT * FROM " + S + "permits WHERE status IN ('ACTIVE', 'SUSPENDED', 'RESUMPTION_PENDING', 'WORK_COMPLETE')"
                + filter + " ORDER BY ends_at", PERMIT, args);
    }

    @Override
    public List<Permit> livePermitsInZones(String siteCode, List<UUID> zoneIds) {
        if (zoneIds == null || zoneIds.isEmpty()) {
            return jdbc.query("SELECT * FROM " + S + "permits WHERE site_code = ? AND status IN ('ACTIVE', 'SUSPENDED')", PERMIT, siteCode);
        }
        return jdbc.query("SELECT * FROM " + S + "permits WHERE site_code = ? AND status IN ('ACTIVE', 'SUSPENDED') AND zone_id = ANY (?)",
                PERMIT, siteCode, zoneIds.toArray(UUID[]::new));
    }

    @Override
    public List<Permit> activePermitsForAssessment(String siteCode, UUID assessmentId, Instant at) {
        return jdbc.query("SELECT * FROM " + S + "permits WHERE site_code = ? AND risk_assessment_id = ? AND status IN ('ACTIVE', 'SUSPENDED')"
                + " AND starts_at <= ? AND ends_at > ?", PERMIT, siteCode, assessmentId, ts(at), ts(at));
    }

    // ---- parts ----------------------------------------------------------------------------------

    @Override
    public List<PermitWorker> workers(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_workers WHERE permit_id = ? ORDER BY seq", WORKER, permitId);
    }

    @Override
    public PermitWorker insertWorker(PermitWorker w) {
        jdbc.update("INSERT INTO " + S + "permit_workers (id, permit_id, site_code, person_reference, display_name, work_role, created_at)"
                + " VALUES (?,?,?,?,?,?,?)", w.id(), w.permitId(), w.siteCode(), w.personReference(), w.displayName(), w.workRole().name(),
                ts(w.createdAt()));
        return w;
    }

    @Override
    public Optional<PermitWorker> findWorker(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "permit_workers WHERE id = ?", WORKER, id).stream().findFirst();
    }

    @Override
    public void deleteWorker(UUID id) {
        jdbc.update("DELETE FROM " + S + "permit_workers WHERE id = ?", id);
    }

    @Override
    public List<CompetencyCheck> checks(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_competency_checks WHERE permit_id = ? ORDER BY checked_at", CHECK, permitId);
    }

    @Override
    public CompetencyCheck insertCheck(CompetencyCheck c) {
        jdbc.update("INSERT INTO " + S + "permit_competency_checks (id, permit_id, site_code, worker_id, competency_code, competent,"
                + " evidence_reference, valid_until, checked_by, checked_at, note) VALUES (?,?,?,?,?,?,?,?,?,?,?)", c.id(), c.permitId(),
                c.siteCode(), c.workerId(), c.competencyCode(), c.competent(), c.evidenceReference(), c.validUntil(), c.checkedBy(),
                ts(c.checkedAt()), c.note());
        return c;
    }

    @Override
    public List<Isolation> isolations(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_isolations WHERE permit_id = ? ORDER BY seq", ISOLATION, permitId);
    }

    @Override
    public Isolation insertIsolation(Isolation i) {
        jdbc.update("INSERT INTO " + S + "permit_isolations (id, permit_id, site_code, kind, description, tag_reference, status, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?)", i.id(), i.permitId(), i.siteCode(), i.kind().name(), i.description(), i.tagReference(),
                i.status().name(), ts(i.createdAt()));
        return i;
    }

    @Override
    public Optional<Isolation> findIsolation(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "permit_isolations WHERE id = ?", ISOLATION, id).stream().findFirst();
    }

    @Override
    public Isolation updateIsolation(Isolation i) {
        jdbc.update("UPDATE " + S + "permit_isolations SET status = ?, tag_reference = ?, verified_by = ?, verified_at = ?, verification_note = ?,"
                + " removed_by = ?, removed_at = ?, removal_note = ? WHERE id = ?", i.status().name(), i.tagReference(), i.verifiedBy(),
                ts(i.verifiedAt()), i.verificationNote(), i.removedBy(), ts(i.removedAt()), i.removalNote(), i.id());
        return findIsolation(i.id()).orElseThrow();
    }

    @Override
    public void deleteIsolation(UUID id) {
        jdbc.update("DELETE FROM " + S + "permit_isolations WHERE id = ?", id);
    }

    @Override
    public List<PermitApproval> approvals(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_approvals WHERE permit_id = ? ORDER BY seq", APPROVAL, permitId);
    }

    @Override
    public PermitApproval insertApproval(PermitApproval a) {
        jdbc.update("INSERT INTO " + S + "permit_approvals (id, permit_id, site_code, purpose, ref_id, approval_round, stage, decision,"
                + " decided_by, decided_at, conditions, comment) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", a.id(), a.permitId(), a.siteCode(),
                a.purpose().name(), a.refId(), a.approvalRound(), a.stage().name(), a.decision().name(), a.decidedBy(), ts(a.decidedAt()),
                a.conditions(), a.comment());
        return a;
    }

    @Override
    public List<PermitExtension> extensions(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_extensions WHERE permit_id = ? ORDER BY requested_at", EXTENSION, permitId);
    }

    @Override
    public Optional<PermitExtension> findExtension(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "permit_extensions WHERE id = ?", EXTENSION, id).stream().findFirst();
    }

    @Override
    public PermitExtension insertExtension(PermitExtension e) {
        jdbc.update("INSERT INTO " + S + "permit_extensions (id, permit_id, site_code, requested_by, requested_at, previous_ends_at, new_ends_at,"
                + " reason, status) VALUES (?,?,?,?,?,?,?,?,?)", e.id(), e.permitId(), e.siteCode(), e.requestedBy(), ts(e.requestedAt()),
                ts(e.previousEndsAt()), ts(e.newEndsAt()), e.reason(), e.status().name());
        return e;
    }

    @Override
    public PermitExtension updateExtension(PermitExtension e) {
        jdbc.update("UPDATE " + S + "permit_extensions SET status = ?, decided_by = ?, decided_at = ?, decision_note = ? WHERE id = ?",
                e.status().name(), e.decidedBy(), ts(e.decidedAt()), e.decisionNote(), e.id());
        return findExtension(e.id()).orElseThrow();
    }

    @Override
    public List<PermitSuspension> suspensions(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_suspensions WHERE permit_id = ? ORDER BY suspended_at", SUSPENSION, permitId);
    }

    @Override
    public PermitSuspension insertSuspension(PermitSuspension s) {
        jdbc.update("INSERT INTO " + S + "permit_suspensions (id, permit_id, site_code, suspended_by, suspended_at, reason) VALUES (?,?,?,?,?,?)",
                s.id(), s.permitId(), s.siteCode(), s.suspendedBy(), ts(s.suspendedAt()), s.reason());
        return s;
    }

    @Override
    public void markResumed(UUID permitId, Instant at) {
        jdbc.update("UPDATE " + S + "permit_suspensions SET resumed_at = ? WHERE permit_id = ? AND resumed_at IS NULL", ts(at), permitId);
    }

    @Override
    public List<PermitNotification> notifications(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_notifications WHERE permit_id = ? ORDER BY created_at, id", NOTIFICATION, permitId);
    }

    @Override
    public PermitNotification insertNotification(PermitNotification n) {
        jdbc.update("INSERT INTO " + S + "permit_notifications (id, permit_id, site_code, suspension_id, recipient_reference, recipient_name,"
                + " recipient_role, state, created_at) VALUES (?,?,?,?,?,?,?,?,?)", n.id(), n.permitId(), n.siteCode(), n.suspensionId(),
                n.recipientReference(), n.recipientName(), n.recipientRole(), n.state(), ts(n.createdAt()));
        return n;
    }

    @Override
    public List<PermitEvidence> evidence(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_evidence WHERE permit_id = ? ORDER BY submitted_at", EVIDENCE, permitId);
    }

    @Override
    public PermitEvidence insertEvidence(PermitEvidence e) {
        jdbc.update("INSERT INTO " + S + "permit_evidence (id, permit_id, site_code, kind, reference, file_name, media_type, size_bytes,"
                + " content_hash, retention_class, submitted_by, submitted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", e.id(), e.permitId(),
                e.siteCode(), e.kind().name(), e.reference(), e.fileName(), e.mediaType(), e.sizeBytes(), e.contentHash(), e.retentionClass(),
                e.submittedBy(), ts(e.submittedAt()));
        return e;
    }

    @Override
    public List<PermitFlag> flags(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_flags WHERE permit_id = ? ORDER BY raised_at", FLAG, permitId);
    }

    @Override
    public boolean insertFlag(PermitFlag f) {
        return jdbc.update("INSERT INTO " + S + "permit_flags (id, permit_id, site_code, flag_type, reference, detail, status, raised_at)"
                + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING", f.id(), f.permitId(), f.siteCode(), f.flagType().name(), f.reference(),
                f.detail(), f.status(), ts(f.raisedAt())) == 1;
    }

    @Override
    public Optional<PermitFlag> findFlag(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "permit_flags WHERE id = ?", FLAG, id).stream().findFirst();
    }

    @Override
    public PermitFlag updateFlag(PermitFlag f) {
        jdbc.update("UPDATE " + S + "permit_flags SET status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ? WHERE id = ?", f.status(),
                f.reviewedBy(), ts(f.reviewedAt()), f.reviewNote(), f.id());
        return findFlag(f.id()).orElseThrow();
    }

    @Override
    public List<PermitFlag> openFlags(List<String> sites) {
        String filter = sites == null ? "" : " AND site_code = ANY (?)";
        Object[] args = sites == null ? new Object[0] : new Object[] {sites.toArray(String[]::new)};
        return jdbc.query("SELECT * FROM " + S + "permit_flags WHERE status = 'OPEN'" + filter + " ORDER BY raised_at", FLAG, args);
    }

    @Override
    public List<PermitEscalation> escalations(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_escalations WHERE permit_id = ? ORDER BY raised_at", ESCALATION, permitId);
    }

    @Override
    public boolean insertEscalation(PermitEscalation e) {
        return jdbc.update("INSERT INTO " + S + "permit_escalations (id, permit_id, site_code, level, raised_at) VALUES (?,?,?,?,?)"
                + " ON CONFLICT DO NOTHING", e.id(), e.permitId(), e.siteCode(), e.level().name(), ts(e.raisedAt())) == 1;
    }

    @Override
    public List<PermitEscalation> escalationsAt(List<String> sites, EscalationLevel level) {
        String filter = sites == null ? "" : " AND site_code = ANY (?)";
        Object[] args = sites == null ? new Object[] {level.name()} : new Object[] {level.name(), sites.toArray(String[]::new)};
        return jdbc.query("SELECT * FROM " + S + "permit_escalations WHERE level = ?" + filter + " ORDER BY raised_at", ESCALATION, args);
    }

    @Override
    public void insertHistory(HistoryEntry h) {
        jdbc.update("INSERT INTO " + S + "permit_history (id, permit_id, site_code, from_status, to_status, action, actor, reason, occurred_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)", h.id(), h.permitId(), h.siteCode(), h.fromStatus(), h.toStatus(), h.action(), h.actor(), h.reason(),
                ts(h.occurredAt()));
    }

    @Override
    public List<HistoryEntry> history(UUID permitId) {
        return jdbc.query("SELECT * FROM " + S + "permit_history WHERE permit_id = ? ORDER BY occurred_at, seq", HISTORY, permitId);
    }

    // ---- analytics ------------------------------------------------------------------------------

    @Override
    public List<CountRow> volumeByType(List<String> sites, Instant from, Instant to) {
        return counts("work_type", sites, from, to);
    }

    @Override
    public List<CountRow> volumeByContractor(List<String> sites, Instant from, Instant to) {
        return counts("COALESCE(contractor_reference, 'No contractor recorded')", sites, from, to);
    }

    @Override
    public List<CountRow> volumeByOutcome(List<String> sites, Instant from, Instant to) {
        return counts("status", sites, from, to);
    }

    private List<CountRow> counts(String expression, List<String> sites, Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        String where = window(sites, from, to, args);
        return jdbc.query("SELECT " + expression + " AS k, count(*) AS n FROM " + S + "permits" + where + " AND status <> 'DRAFT' GROUP BY 1"
                + " ORDER BY n DESC, k", (rs, i) -> new CountRow(rs.getString("k"), rs.getLong("n")), args.toArray());
    }

    @Override
    public Double meanOpenHours(List<String> sites, Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        String where = window(sites, from, to, args);
        return jdbc.queryForObject("SELECT avg(extract(epoch FROM (closed_at - issued_at)) / 3600.0) FROM " + S + "permits" + where
                + " AND status = 'CLOSED' AND issued_at IS NOT NULL", Double.class, args.toArray());
    }

    @Override
    public long flaggedForIncident(List<String> sites, Instant from, Instant to) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (sites != null) {
            where.append(" AND p.site_code = ANY (?)");
            args.add(sites.toArray(String[]::new));
        }
        if (from != null) {
            where.append(" AND p.starts_at >= ?");
            args.add(ts(from));
        }
        if (to != null) {
            where.append(" AND p.starts_at < ?");
            args.add(ts(to));
        }
        return jdbc.queryForObject("SELECT count(DISTINCT p.id) FROM " + S + "permits p JOIN " + S + "permit_flags f ON f.permit_id = p.id"
                + " AND f.flag_type = 'INCIDENT'" + where, Long.class, args.toArray());
    }

    private static String window(List<String> sites, Instant from, Instant to, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (sites != null) {
            where.append(" AND site_code = ANY (?)");
            args.add(sites.toArray(String[]::new));
        }
        if (from != null) {
            where.append(" AND starts_at >= ?");
            args.add(ts(from));
        }
        if (to != null) {
            where.append(" AND starts_at < ?");
            args.add(ts(to));
        }
        return where.toString();
    }

    // ---- mapping --------------------------------------------------------------------------------

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static final RowMapper<PermitType> TYPE = (rs, n) -> {
        Array competencies = rs.getArray("required_competencies");
        List<String> required = competencies == null ? List.of() : Arrays.asList((String[]) competencies.getArray());
        return new PermitType(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                PermitRiskLevel.valueOf(rs.getString("risk_level")), rs.getString("activity_type"), rs.getBoolean("risk_assessment_required"),
                rs.getBoolean("two_stage"), rs.getBoolean("requires_isolation"), rs.getInt("max_validity_hours"), required,
                rs.getBoolean("active"), rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("record_version"));
    };

    private static final RowMapper<Permit> PERMIT = (rs, n) -> new Permit(rs.getObject("id", UUID.class), rs.getString("site_code"),
            rs.getString("reference"), rs.getObject("permit_type_id", UUID.class), rs.getString("work_type"), rs.getString("title"),
            rs.getString("work_description"), rs.getString("location_code"), rs.getObject("zone_id", UUID.class), rs.getString("zone_code"),
            instant(rs, "starts_at"), instant(rs, "ends_at"), PermitStatus.valueOf(rs.getString("status")), rs.getString("status_reason"),
            rs.getObject("risk_assessment_id", UUID.class), rs.getString("risk_assessment_reference"), integer(rs, "risk_assessment_version"),
            rs.getString("risk_level") == null ? null : PermitRiskLevel.valueOf(rs.getString("risk_level")), instant(rs, "risk_review_due_at"),
            rs.getString("contractor_reference"), rs.getString("supervisor_reference"), rs.getString("supervisor_contact"),
            OriginSystem.valueOf(rs.getString("origin_system")), rs.getString("origin_reference"), rs.getInt("approval_round"),
            rs.getString("requested_by"), instant(rs, "submitted_at"), instant(rs, "issued_at"), rs.getString("completion_statement"),
            instant(rs, "work_completed_at"), rs.getString("work_completed_by"), instant(rs, "closed_at"), rs.getString("closed_by"),
            rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("record_version"));

    private static final RowMapper<PermitWorker> WORKER = (rs, n) -> new PermitWorker(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), rs.getString("person_reference"), rs.getString("display_name"),
            WorkRole.valueOf(rs.getString("work_role")), instant(rs, "created_at"));

    private static final RowMapper<CompetencyCheck> CHECK = (rs, n) -> new CompetencyCheck(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), rs.getObject("worker_id", UUID.class), rs.getString("competency_code"),
            rs.getBoolean("competent"), rs.getString("evidence_reference"), rs.getObject("valid_until", LocalDate.class), rs.getString("checked_by"),
            instant(rs, "checked_at"), rs.getString("note"));

    private static final RowMapper<Isolation> ISOLATION = (rs, n) -> new Isolation(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), IsolationKind.valueOf(rs.getString("kind")), rs.getString("description"),
            rs.getString("tag_reference"), IsolationStatus.valueOf(rs.getString("status")), rs.getString("verified_by"), instant(rs, "verified_at"),
            rs.getString("verification_note"), rs.getString("removed_by"), instant(rs, "removed_at"), rs.getString("removal_note"),
            instant(rs, "created_at"));

    private static final RowMapper<PermitApproval> APPROVAL = (rs, n) -> new PermitApproval(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), ApprovalPurpose.valueOf(rs.getString("purpose")),
            rs.getObject("ref_id", UUID.class), rs.getInt("approval_round"), ApprovalStage.valueOf(rs.getString("stage")),
            ApprovalDecision.valueOf(rs.getString("decision")), rs.getString("decided_by"), instant(rs, "decided_at"), rs.getString("conditions"),
            rs.getString("comment"));

    private static final RowMapper<PermitExtension> EXTENSION = (rs, n) -> new PermitExtension(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), rs.getString("requested_by"), instant(rs, "requested_at"),
            instant(rs, "previous_ends_at"), instant(rs, "new_ends_at"), rs.getString("reason"), ExtensionStatus.valueOf(rs.getString("status")),
            rs.getString("decided_by"), instant(rs, "decided_at"), rs.getString("decision_note"));

    private static final RowMapper<PermitSuspension> SUSPENSION = (rs, n) -> new PermitSuspension(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), rs.getString("suspended_by"), instant(rs, "suspended_at"),
            rs.getString("reason"), instant(rs, "resumed_at"));

    private static final RowMapper<PermitNotification> NOTIFICATION = (rs, n) -> new PermitNotification(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), rs.getObject("suspension_id", UUID.class),
            rs.getString("recipient_reference"), rs.getString("recipient_name"), rs.getString("recipient_role"), rs.getString("state"),
            instant(rs, "created_at"));

    private static final RowMapper<PermitEvidence> EVIDENCE = (rs, n) -> new PermitEvidence(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), EvidenceKind.valueOf(rs.getString("kind")), rs.getString("reference"),
            rs.getString("file_name"), rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("content_hash"),
            rs.getString("retention_class"), rs.getString("submitted_by"), instant(rs, "submitted_at"));

    private static final RowMapper<PermitFlag> FLAG = (rs, n) -> new PermitFlag(rs.getObject("id", UUID.class), rs.getObject("permit_id", UUID.class),
            rs.getString("site_code"), FlagType.valueOf(rs.getString("flag_type")), rs.getString("reference"), rs.getString("detail"),
            rs.getString("status"), instant(rs, "raised_at"), rs.getString("reviewed_by"), instant(rs, "reviewed_at"), rs.getString("review_note"));

    private static final RowMapper<PermitEscalation> ESCALATION = (rs, n) -> new PermitEscalation(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), EscalationLevel.valueOf(rs.getString("level")), instant(rs, "raised_at"));

    private static final RowMapper<HistoryEntry> HISTORY = (rs, n) -> new HistoryEntry(rs.getObject("id", UUID.class),
            rs.getObject("permit_id", UUID.class), rs.getString("site_code"), rs.getString("from_status"), rs.getString("to_status"),
            rs.getString("action"), rs.getString("actor"), rs.getString("reason"), instant(rs, "occurred_at"));
}

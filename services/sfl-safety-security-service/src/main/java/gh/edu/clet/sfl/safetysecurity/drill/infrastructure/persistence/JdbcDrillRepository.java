package gh.edu.clet.sfl.safetysecurity.drill.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillPage;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.BaselinePerson;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.CapaStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillModule;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillReview;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ExpectationOutcome;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.FrequencyRequirement;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.GapFollowUp;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RollCallGap;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * JDBC-backed {@link DrillRepository}, in the style of S174's {@code JdbcEmergencyRepository}.
 *
 * <p>A versioned record (a drill, a frequency requirement) is inserted at version 0 and otherwise updated only where
 * the stored version is the one before the record's - {@code RecordMetadata.modifiedBy} increments it - so a
 * concurrent writer that saved first turns this save into a version conflict rather than a lost update. The other
 * records are written once by one actor at one moment and are upserted by key.
 *
 * <p>Row-level security applies here exactly as to JPA: {@code SiteScopeGuc} sets the scope on the transaction's
 * connection, which {@link JdbcTemplate} shares.
 */
@Component
public class JdbcDrillRepository implements DrillRepository {

    private static final String S = "safety_security.";
    private static final Map<String, String> SORTS = Map.of("scheduledFor", "scheduled_for", "reference", "reference",
            "title", "title", "lastModifiedAt", "last_modified_at", "status", "status");

    private final JdbcTemplate jdbc;

    public JdbcDrillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- drills ---------------------------------------------------------------------------------

    @Override
    public Drill saveDrill(Drill drill) {
        DrillPlan plan = drill.plan();
        RecordMetadata m = drill.metadata();
        if (m.version() == 0) {
            jdbc.update("INSERT INTO " + S + """
                    drills (id, site_code, reference, drill_type, title, scenario, expected_participants,
                        assembly_zone, scheduled_for, notification_template_id, audience_group_ids, recipient_zone_ids,
                        status, status_reason, created_by, created_at, last_modified_by, last_modified_at,
                        record_version, source_channel, correlation_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, drill.id(), drill.siteCode(), drill.reference(), plan.drillType().name(), plan.title(),
                    plan.scenario(), plan.expectedParticipants(), plan.assemblyZone(), ts(plan.scheduledFor()),
                    plan.notificationTemplateId(), plan.audienceGroupIds().toArray(UUID[]::new),
                    plan.recipientZoneIds().toArray(UUID[]::new), drill.status().name(), drill.statusReason(),
                    m.createdBy(), ts(m.createdAt()), m.lastModifiedBy(), ts(m.lastModifiedAt()), m.version(),
                    m.sourceChannel().name(), m.correlationId());
        } else {
            int updated = jdbc.update("UPDATE " + S + """
                    drills SET drill_type = ?, title = ?, scenario = ?, expected_participants = ?, assembly_zone = ?,
                        scheduled_for = ?, notification_template_id = ?, audience_group_ids = ?, recipient_zone_ids = ?,
                        status = ?, status_reason = ?, last_modified_by = ?, last_modified_at = ?, record_version = ?,
                        source_channel = ?, correlation_id = ?
                    WHERE id = ? AND record_version = ?
                    """, plan.drillType().name(), plan.title(), plan.scenario(), plan.expectedParticipants(),
                    plan.assemblyZone(), ts(plan.scheduledFor()), plan.notificationTemplateId(),
                    plan.audienceGroupIds().toArray(UUID[]::new), plan.recipientZoneIds().toArray(UUID[]::new),
                    drill.status().name(), drill.statusReason(), m.lastModifiedBy(), ts(m.lastModifiedAt()),
                    m.version(), m.sourceChannel().name(), m.correlationId(), drill.id(), m.version() - 1);
            requireUpdated(updated, m.version() - 1);
        }
        jdbc.update("DELETE FROM " + S + "drill_expectations WHERE drill_id = ?", drill.id());
        List<ModuleExpectation> expectations = plan.expectations();
        for (int i = 0; i < expectations.size(); i++) {
            ModuleExpectation e = expectations.get(i);
            jdbc.update("INSERT INTO " + S + """
                    drill_expectations (drill_id, sequence_no, site_code, module, expectation, success_criterion,
                        outcome, outcome_notes)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, drill.id(), i + 1, drill.siteCode(), e.module().name(), e.expectation(), e.successCriterion(),
                    e.outcome() == null ? null : e.outcome().name(), e.outcomeNotes());
        }
        return findDrill(drill.id()).orElseThrow();
    }

    @Override
    public Optional<Drill> findDrill(UUID id) {
        List<Drill> drills = withExpectations(jdbc.query("SELECT * FROM " + S + "drills WHERE id = ?",
                (rs, n) -> drill(rs), id));
        return drills.stream().findFirst();
    }

    @Override
    public DrillPage<Drill> searchDrills(DrillQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (query.sites() != null) {
            where.append(" AND site_code = ANY (?)");
            args.add(query.sites().toArray(String[]::new));
        }
        if (query.drillType() != null) {
            where.append(" AND drill_type = ?");
            args.add(query.drillType().name());
        }
        if (query.status() != null) {
            where.append(" AND status = ?");
            args.add(query.status().name());
        }
        if (query.from() != null) {
            where.append(" AND scheduled_for >= ?");
            args.add(ts(query.from()));
        }
        if (query.to() != null) {
            where.append(" AND scheduled_for < ?");
            args.add(ts(query.to()));
        }
        if (query.search() != null && !query.search().isBlank()) {
            where.append(" AND (lower(title) LIKE ? OR lower(reference) LIKE ?"
                    + " OR lower(coalesce(scenario, '')) LIKE ?)");
            String like = "%" + query.search().strip().toLowerCase(Locale.ROOT) + "%";
            args.addAll(List.of(like, like, like));
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + S + "drills" + where, Long.class, args.toArray());
        Paging paging = query.paging() == null ? new Paging(0, 25, null) : query.paging();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(paging.size());
        pageArgs.add(paging.offset());
        List<Drill> rows = withExpectations(jdbc.query("SELECT * FROM " + S + "drills" + where + " ORDER BY "
                + orderBy(paging.sort()) + ", id LIMIT ? OFFSET ?", (rs, n) -> drill(rs), pageArgs.toArray()));
        return DrillPage.of(rows, paging, total == null ? 0 : total);
    }

    /** Sort keys are mapped through {@link #SORTS}; caller input never reaches the SQL. Default: soonest first. */
    static String orderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return "scheduled_for ASC NULLS LAST";
        }
        String[] parts = sort.split(",");
        String column = SORTS.getOrDefault(parts[0].strip(), "scheduled_for");
        boolean desc = parts.length > 1 && parts[1].strip().equalsIgnoreCase("desc");
        return column + (desc ? " DESC NULLS LAST" : " ASC NULLS LAST");
    }

    @Override
    public Optional<CreationKey> findCreationKey(String actorId, String key) {
        return jdbc.query("SELECT id, creation_request_hash FROM " + S
                + "drills WHERE created_by = ? AND creation_idempotency_key = ?",
                (rs, n) -> new CreationKey(rs.getObject("id", UUID.class), rs.getString("creation_request_hash")),
                actorId, key).stream().findFirst();
    }

    @Override
    public void recordCreationKey(UUID drillId, String key, String requestHash) {
        jdbc.update("UPDATE " + S + "drills SET creation_idempotency_key = ?, creation_request_hash = ? WHERE id = ?",
                key, requestHash, drillId);
    }

    // ---- execution, baseline and gaps -----------------------------------------------------------

    @Override
    public DrillExecution saveExecution(DrillExecution e) {
        jdbc.update("INSERT INTO " + S + """
                drill_executions (drill_id, site_code, started_at, started_by, notification_activation_id,
                    notification_number, notification_sent_at, muster_session_id, baseline_taken_at,
                    baseline_access_data_as_of, baseline_stale, baseline_count, roll_call_closed_at,
                    roll_call_closed_by, checked_in_count, gap_count, notification_to_muster_seconds)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (drill_id) DO UPDATE SET roll_call_closed_at = EXCLUDED.roll_call_closed_at,
                    roll_call_closed_by = EXCLUDED.roll_call_closed_by, checked_in_count = EXCLUDED.checked_in_count,
                    gap_count = EXCLUDED.gap_count,
                    notification_to_muster_seconds = EXCLUDED.notification_to_muster_seconds
                """, e.drillId(), e.siteCode(), ts(e.startedAt()), e.startedBy(), e.notificationActivationId(),
                e.notificationNumber(), ts(e.notificationSentAt()), e.musterSessionId(), ts(e.baselineTakenAt()),
                ts(e.baselineAccessDataAsOf()), e.baselineStale(), e.baselineCount(), ts(e.rollCallClosedAt()),
                e.rollCallClosedBy(), e.checkedInCount(), e.gapCount(), e.notificationToMusterSeconds());
        return findExecution(e.drillId()).orElseThrow();
    }

    @Override
    public Optional<DrillExecution> findExecution(UUID drillId) {
        return jdbc.query("SELECT * FROM " + S + "drill_executions WHERE drill_id = ?", (rs, n) -> new DrillExecution(
                rs.getObject("drill_id", UUID.class), rs.getString("site_code"), instant(rs, "started_at"),
                rs.getString("started_by"), rs.getObject("notification_activation_id", UUID.class),
                rs.getString("notification_number"), instant(rs, "notification_sent_at"),
                rs.getObject("muster_session_id", UUID.class), instant(rs, "baseline_taken_at"),
                instant(rs, "baseline_access_data_as_of"), rs.getBoolean("baseline_stale"),
                rs.getInt("baseline_count"), instant(rs, "roll_call_closed_at"), rs.getString("roll_call_closed_by"),
                rs.getObject("checked_in_count", Integer.class), rs.getObject("gap_count", Integer.class),
                rs.getObject("notification_to_muster_seconds", Long.class)), drillId).stream().findFirst();
    }

    @Override
    public void saveBaseline(UUID drillId, String siteCode, List<BaselinePerson> persons) {
        // A person appears once per baseline, whichever source reported them first.
        Map<String, BaselinePerson> unique = new LinkedHashMap<>();
        persons.forEach(p -> unique.putIfAbsent(p.personRef(), p));
        jdbc.batchUpdate("INSERT INTO " + S
                + "drill_baseline_persons (drill_id, person_ref, site_code, display_name, source) VALUES (?,?,?,?,?)",
                unique.values().stream().map(p -> new Object[] {drillId, p.personRef(), siteCode, p.displayName(),
                        p.source()}).toList());
    }

    @Override
    public List<BaselinePerson> findBaseline(UUID drillId) {
        return jdbc.query("SELECT person_ref, display_name, source FROM " + S
                + "drill_baseline_persons WHERE drill_id = ? ORDER BY display_name NULLS LAST, person_ref",
                (rs, n) -> new BaselinePerson(rs.getString("person_ref"), rs.getString("display_name"),
                        rs.getString("source")), drillId);
    }

    @Override
    public void saveGaps(List<RollCallGap> gaps) {
        gaps.forEach(this::saveGap);
    }

    @Override
    public RollCallGap saveGap(RollCallGap g) {
        jdbc.update("INSERT INTO " + S + """
                drill_roll_call_gaps (id, drill_id, site_code, person_ref, display_name, source, follow_up,
                    follow_up_notes, followed_up_by, followed_up_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET follow_up = EXCLUDED.follow_up,
                    follow_up_notes = EXCLUDED.follow_up_notes, followed_up_by = EXCLUDED.followed_up_by,
                    followed_up_at = EXCLUDED.followed_up_at
                """, g.id(), g.drillId(), g.siteCode(), g.personRef(), g.displayName(), g.source(),
                g.followUp() == null ? null : g.followUp().name(), g.followUpNotes(), g.followedUpBy(),
                ts(g.followedUpAt()));
        return findGap(g.id()).orElseThrow();
    }

    @Override
    public Optional<RollCallGap> findGap(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "drill_roll_call_gaps WHERE id = ?", (rs, n) -> gap(rs), id)
                .stream().findFirst();
    }

    @Override
    public List<RollCallGap> findGaps(UUID drillId) {
        return jdbc.query("SELECT * FROM " + S
                + "drill_roll_call_gaps WHERE drill_id = ? ORDER BY display_name NULLS LAST, person_ref",
                (rs, n) -> gap(rs), drillId);
    }

    // ---- after-action review --------------------------------------------------------------------

    @Override
    public DrillReview saveReview(DrillReview r) {
        jdbc.update("INSERT INTO " + S + """
                drill_reviews (drill_id, site_code, summary, timing_notes, recorded_by, recorded_at, submitted_by,
                    submitted_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (drill_id) DO UPDATE SET summary = EXCLUDED.summary, timing_notes = EXCLUDED.timing_notes,
                    recorded_by = EXCLUDED.recorded_by, recorded_at = EXCLUDED.recorded_at,
                    submitted_by = EXCLUDED.submitted_by, submitted_at = EXCLUDED.submitted_at
                """, r.drillId(), r.siteCode(), r.summary(), r.timingNotes(), r.recordedBy(), ts(r.recordedAt()),
                r.submittedBy(), ts(r.submittedAt()));
        return findReview(r.drillId()).orElseThrow();
    }

    @Override
    public Optional<DrillReview> findReview(UUID drillId) {
        return jdbc.query("SELECT * FROM " + S + "drill_reviews WHERE drill_id = ?", (rs, n) -> new DrillReview(
                rs.getObject("drill_id", UUID.class), rs.getString("site_code"), rs.getString("summary"),
                rs.getString("timing_notes"), rs.getString("recorded_by"), instant(rs, "recorded_at"),
                rs.getString("submitted_by"), instant(rs, "submitted_at")), drillId).stream().findFirst();
    }

    @Override
    public DrillFinding saveFinding(DrillFinding f) {
        jdbc.update("INSERT INTO " + S + """
                drill_findings (id, drill_id, site_code, sequence_no, description, no_action_justification,
                    recorded_by, recorded_at)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET no_action_justification = EXCLUDED.no_action_justification
                """, f.id(), f.drillId(), f.siteCode(), f.sequenceNo(), f.description(), f.noActionJustification(),
                f.recordedBy(), ts(f.recordedAt()));
        return findFinding(f.id()).orElseThrow();
    }

    @Override
    public Optional<DrillFinding> findFinding(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "drill_findings WHERE id = ?", (rs, n) -> finding(rs), id).stream()
                .findFirst();
    }

    @Override
    public List<DrillFinding> findFindings(UUID drillId) {
        return jdbc.query("SELECT * FROM " + S + "drill_findings WHERE drill_id = ? ORDER BY sequence_no",
                (rs, n) -> finding(rs), drillId);
    }

    // ---- corrective actions ---------------------------------------------------------------------

    @Override
    public DrillCorrectiveAction saveCorrectiveAction(DrillCorrectiveAction a) {
        jdbc.update("INSERT INTO " + S + """
                drill_corrective_actions (id, drill_id, finding_id, site_code, description, owner_id, due_date, status,
                    verification_notes, created_by, created_at, resolved_by, resolved_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status,
                    verification_notes = EXCLUDED.verification_notes, resolved_by = EXCLUDED.resolved_by,
                    resolved_at = EXCLUDED.resolved_at
                """, a.id(), a.drillId(), a.findingId(), a.siteCode(), a.description(), a.ownerId(),
                java.sql.Date.valueOf(a.dueDate()), a.status().name(), a.verificationNotes(), a.createdBy(),
                ts(a.createdAt()), a.resolvedBy(), ts(a.resolvedAt()));
        return findCorrectiveAction(a.id()).orElseThrow();
    }

    @Override
    public Optional<DrillCorrectiveAction> findCorrectiveAction(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "drill_corrective_actions WHERE id = ?", (rs, n) -> action(rs), id)
                .stream().findFirst();
    }

    @Override
    public List<DrillCorrectiveAction> findCorrectiveActions(UUID drillId) {
        return jdbc.query("SELECT * FROM " + S
                + "drill_corrective_actions WHERE drill_id = ? ORDER BY due_date, created_at",
                (rs, n) -> action(rs), drillId);
    }

    @Override
    public CapaCounts capaCounts(List<String> sites, LocalDate today) {
        List<Object> args = new ArrayList<>(List.of(java.sql.Date.valueOf(today), java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today), java.sql.Date.valueOf(today), java.sql.Date.valueOf(today)));
        String scope = "";
        if (sites != null) {
            scope = " WHERE site_code = ANY (?)";
            args.add(sites.toArray(String[]::new));
        }
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')) AS open,
                       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND due_date < ?) AS overdue,
                       count(*) FILTER (WHERE status = 'VERIFIED') AS verified,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')
                           AND ? - created_at::date <= 30) AS age_30,
                       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')
                           AND ? - created_at::date BETWEEN 31 AND 60) AS age_60,
                       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')
                           AND ? - created_at::date BETWEEN 61 AND 90) AS age_90,
                       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')
                           AND ? - created_at::date > 90) AS age_over
                  FROM safety_security.drill_corrective_actions""" + scope,
                (rs, n) -> new CapaCounts(rs.getLong("open"), rs.getLong("overdue"), rs.getLong("verified"),
                        rs.getLong("cancelled"), List.of(rs.getLong("age_30"), rs.getLong("age_60"),
                                rs.getLong("age_90"), rs.getLong("age_over"))),
                args.toArray());
    }

    // ---- frequency compliance -------------------------------------------------------------------

    @Override
    public FrequencyRequirement saveRequirement(FrequencyRequirement r) {
        RecordMetadata m = r.metadata();
        if (m.version() == 0) {
            jdbc.update("INSERT INTO " + S + """
                    drill_frequency_requirements (id, site_code, drill_type, interval_days, warning_days,
                        effective_from, gap_flagged_for_due_at, created_by, created_at, last_modified_by,
                        last_modified_at, record_version, source_channel, correlation_id)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, r.id(), r.siteCode(), r.drillType().name(), r.intervalDays(), r.warningDays(),
                    ts(r.effectiveFrom()), ts(r.gapFlaggedForDueAt()), m.createdBy(), ts(m.createdAt()),
                    m.lastModifiedBy(), ts(m.lastModifiedAt()), m.version(), m.sourceChannel().name(),
                    m.correlationId());
        } else {
            requireUpdated(jdbc.update("UPDATE " + S + """
                    drill_frequency_requirements SET interval_days = ?, warning_days = ?, gap_flagged_for_due_at = ?,
                        last_modified_by = ?, last_modified_at = ?, record_version = ?, source_channel = ?,
                        correlation_id = ?
                    WHERE id = ? AND record_version = ?
                    """, r.intervalDays(), r.warningDays(), ts(r.gapFlaggedForDueAt()), m.lastModifiedBy(),
                    ts(m.lastModifiedAt()), m.version(), m.sourceChannel().name(), m.correlationId(), r.id(),
                    m.version() - 1), m.version() - 1);
        }
        return findRequirement(r.id()).orElseThrow();
    }

    @Override
    public Optional<FrequencyRequirement> findRequirement(UUID id) {
        return jdbc.query("SELECT * FROM " + S + "drill_frequency_requirements WHERE id = ?",
                (rs, n) -> requirement(rs), id).stream().findFirst();
    }

    @Override
    public Optional<FrequencyRequirement> findRequirement(String siteCode, DrillType drillType) {
        return jdbc.query("SELECT * FROM " + S
                + "drill_frequency_requirements WHERE site_code = ? AND drill_type = ?",
                (rs, n) -> requirement(rs), siteCode, drillType.name()).stream().findFirst();
    }

    @Override
    public List<FrequencyRequirement> findRequirements(List<String> sites) {
        if (sites == null) {
            return jdbc.query("SELECT * FROM " + S
                    + "drill_frequency_requirements ORDER BY site_code, drill_type", (rs, n) -> requirement(rs));
        }
        return jdbc.query("SELECT * FROM " + S
                + "drill_frequency_requirements WHERE site_code = ANY (?) ORDER BY site_code, drill_type",
                (rs, n) -> requirement(rs), (Object) sites.toArray(String[]::new));
    }

    @Override
    public Optional<Instant> lastCountedDrillAt(String siteCode, DrillType drillType) {
        Timestamp last = jdbc.queryForObject("""
                SELECT max(e.started_at)
                  FROM safety_security.drills d
                  JOIN safety_security.drill_executions e ON e.drill_id = d.id
                 WHERE d.site_code = ? AND d.drill_type = ? AND d.status IN ('REVIEWED', 'CLOSED')
                """, Timestamp.class, siteCode, drillType.name());
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    // ---- dashboard ------------------------------------------------------------------------------

    @Override
    public List<SiteDrillStats> siteStats(List<String> sites, Instant since) {
        List<Object> args = new ArrayList<>();
        args.add(ts(since));
        args.add(ts(since));
        args.add(ts(since));
        args.add(ts(since));
        String scope = "";
        if (sites != null) {
            scope = " AND d.site_code = ANY (?)";
            args.add(sites.toArray(String[]::new));
        }
        // A drill is in the window when it was scheduled, or started, on or after `since`. Findings are open while
        // they have neither a justification nor only-closed actions.
        return jdbc.query("""
                WITH f AS (
                    SELECT f.drill_id,
                           (f.no_action_justification IS NULL AND NOT EXISTS (
                                SELECT 1 FROM safety_security.drill_corrective_actions a WHERE a.finding_id = f.id))
                           OR EXISTS (SELECT 1 FROM safety_security.drill_corrective_actions a
                                       WHERE a.finding_id = f.id AND a.status IN ('OPEN', 'IN_PROGRESS')) AS open
                      FROM safety_security.drill_findings f)
                SELECT d.site_code,
                       count(*) FILTER (WHERE d.status <> 'CANCELLED') AS planned,
                       count(e.drill_id) FILTER (WHERE e.started_at >= ?) AS executed,
                       count(e.drill_id) FILTER (WHERE e.started_at >= ? AND d.status IN ('REVIEWED', 'CLOSED'))
                           AS reviewed,
                       count(*) FILTER (WHERE d.status = 'CANCELLED') AS cancelled,
                       coalesce(sum(e.baseline_count) FILTER (WHERE e.roll_call_closed_at IS NOT NULL), 0)
                           AS baseline_total,
                       coalesce(sum(e.checked_in_count), 0) AS checked_in_total,
                       coalesce(sum(e.gap_count), 0) AS gaps_total,
                       coalesce(sum((SELECT count(*) FROM f WHERE f.drill_id = d.id AND f.open)), 0) AS findings_open,
                       coalesce(sum((SELECT count(*) FROM f WHERE f.drill_id = d.id AND NOT f.open)), 0)
                           AS findings_closed
                  FROM safety_security.drills d
                  LEFT JOIN safety_security.drill_executions e ON e.drill_id = d.id
                 WHERE (d.scheduled_for >= ? OR e.started_at >= ?)""" + scope
                + " GROUP BY d.site_code ORDER BY d.site_code",
                (rs, n) -> new SiteDrillStats(rs.getString("site_code"), rs.getLong("planned"),
                        rs.getLong("executed"), rs.getLong("reviewed"), rs.getLong("cancelled"),
                        rs.getLong("baseline_total"), rs.getLong("checked_in_total"), rs.getLong("gaps_total"),
                        rs.getLong("findings_open"), rs.getLong("findings_closed")),
                args.toArray());
    }

    // ---- mapping --------------------------------------------------------------------------------

    private static void requireUpdated(int updated, long expectedStoredVersion) {
        if (updated != 1) {
            throw new DrillException(DrillErrorCode.DRILL_RECORD_VERSION_CONFLICT,
                    Map.of("expectedVersion", expectedStoredVersion));
        }
    }

    private List<Drill> withExpectations(List<Drill> drills) {
        if (drills.isEmpty()) {
            return drills;
        }
        Map<UUID, List<ModuleExpectation>> byDrill = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM " + S + "drill_expectations WHERE drill_id = ANY (?) ORDER BY drill_id, sequence_no",
                rs -> {
                    String outcome = rs.getString("outcome");
                    byDrill.computeIfAbsent(rs.getObject("drill_id", UUID.class), k -> new ArrayList<>())
                            .add(new ModuleExpectation(DrillModule.valueOf(rs.getString("module")),
                                    rs.getString("expectation"), rs.getString("success_criterion"),
                                    outcome == null ? null : ExpectationOutcome.valueOf(outcome),
                                    rs.getString("outcome_notes")));
                }, (Object) drills.stream().map(Drill::id).toArray(UUID[]::new));
        return drills.stream().map(d -> new Drill(d.id(), d.siteCode(), d.reference(),
                d.plan().withExpectations(byDrill.getOrDefault(d.id(), List.of())), d.status(), d.statusReason(),
                d.metadata())).toList();
    }

    private static Drill drill(ResultSet rs) throws SQLException {
        DrillPlan plan = new DrillPlan(DrillType.valueOf(rs.getString("drill_type")), rs.getString("title"),
                rs.getString("scenario"), rs.getString("expected_participants"), rs.getString("assembly_zone"),
                instant(rs, "scheduled_for"), rs.getObject("notification_template_id", UUID.class),
                uuids(rs.getArray("audience_group_ids")), uuids(rs.getArray("recipient_zone_ids")), List.of());
        return new Drill(rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getString("reference"), plan,
                DrillStatus.valueOf(rs.getString("status")), rs.getString("status_reason"), metadata(rs));
    }

    private static RollCallGap gap(ResultSet rs) throws SQLException {
        String followUp = rs.getString("follow_up");
        return new RollCallGap(rs.getObject("id", UUID.class), rs.getObject("drill_id", UUID.class),
                rs.getString("site_code"), rs.getString("person_ref"), rs.getString("display_name"),
                rs.getString("source"), followUp == null ? null : GapFollowUp.valueOf(followUp),
                rs.getString("follow_up_notes"), rs.getString("followed_up_by"), instant(rs, "followed_up_at"));
    }

    private static DrillFinding finding(ResultSet rs) throws SQLException {
        return new DrillFinding(rs.getObject("id", UUID.class), rs.getObject("drill_id", UUID.class),
                rs.getString("site_code"), rs.getInt("sequence_no"), rs.getString("description"),
                rs.getString("no_action_justification"), rs.getString("recorded_by"), instant(rs, "recorded_at"));
    }

    private static DrillCorrectiveAction action(ResultSet rs) throws SQLException {
        return new DrillCorrectiveAction(rs.getObject("id", UUID.class), rs.getObject("drill_id", UUID.class),
                rs.getObject("finding_id", UUID.class), rs.getString("site_code"), rs.getString("description"),
                rs.getString("owner_id"), rs.getDate("due_date").toLocalDate(),
                CapaStatus.valueOf(rs.getString("status")), rs.getString("verification_notes"),
                rs.getString("created_by"), instant(rs, "created_at"), rs.getString("resolved_by"),
                instant(rs, "resolved_at"));
    }

    private static FrequencyRequirement requirement(ResultSet rs) throws SQLException {
        return new FrequencyRequirement(rs.getObject("id", UUID.class), rs.getString("site_code"),
                DrillType.valueOf(rs.getString("drill_type")), rs.getInt("interval_days"), rs.getInt("warning_days"),
                instant(rs, "effective_from"), instant(rs, "gap_flagged_for_due_at"), metadata(rs));
    }

    private static RecordMetadata metadata(ResultSet rs) throws SQLException {
        return RecordMetadata.rehydrate(rs.getString("created_by"), instant(rs, "created_at"),
                rs.getString("last_modified_by"), instant(rs, "last_modified_at"), rs.getLong("record_version"),
                SourceChannel.valueOf(rs.getString("source_channel")), rs.getString("correlation_id"));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).map(v -> v instanceof UUID u ? u : UUID.fromString(v.toString())).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}

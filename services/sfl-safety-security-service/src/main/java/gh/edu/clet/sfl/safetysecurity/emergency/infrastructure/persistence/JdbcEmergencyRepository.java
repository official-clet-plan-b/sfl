package gh.edu.clet.sfl.safetysecurity.emergency.infrastructure.persistence;

import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository.ActivationHistoryEntry;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository.EmergencyPage;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository.Paging;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Acknowledgement;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.AudienceGroup;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelStatus;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.DeliveryReceipt;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.DeliveryStatus;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.DrillRun;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.EmergencyScenario;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationActivation;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationChannel;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Priority;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecipientZone;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecordLifecycle;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SiteCode;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC persistence for S174 (schema {@code emergency_notification}). Preserves optimistic locking on
 * mutable records, site scope, callback/delivery idempotency and audit correlation. Instant values bind as
 * UTC {@link OffsetDateTime} (pgjdbc cannot infer a type for Instant; columns are TIMESTAMPTZ).
 */
@Repository
public class JdbcEmergencyRepository implements EmergencyRepository {

    private final JdbcTemplate jdbc;

    public JdbcEmergencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    /* ------------------------------------------------------------------ paging and filtering */

    /**
     * A WHERE clause and its bind values, minus the leading site-scope array.
     *
     * <p>Built once per query and used twice - for the count and for the page - so the two can never
     * disagree about which records they are describing.
     */
    private record Where(StringBuilder sql, List<Object> args) {
        static Where scoped() { return new Where(new StringBuilder("site_code = ANY (?)"), new ArrayList<>()); }
        Where and(String fragment, Object value) {
            if (value != null) { sql.append(" AND ").append(fragment); args.add(value); }
            return this;
        }
        /** A predicate with no bind value - for IS NULL / IS NOT NULL and set tests. */
        Where when(boolean apply, String fragment) { if (apply) sql.append(" AND ").append(fragment); return this; }
    }

    /**
     * A validated ORDER BY.
     *
     * <p>The requested key is looked up in a per-resource allow-list rather than interpolated, so a
     * sort parameter can never reach SQL as text. Every ordering ends in {@code id}: rows sharing a
     * sort value would otherwise be free to swap places between two requests, and a page boundary
     * falling inside such a group silently skips or repeats records.
     */
    private record Order(String sql, String describedAs) {}

    private static Order order(String requested, Map<String, String> allowed, String defaultKey,
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
        return new Order(allowed.get(key) + " " + direction + ", id " + direction, key + ": " + direction);
    }

    private static final Map<String, String> ACTIVATION_SORTS = Map.of(
            "createdAt", "created_at", "lastModifiedAt", "last_modified_at", "activationNumber", "activation_number",
            "status", "status", "priority", "priority", "mode", "mode", "approvedAt", "approved_at",
            "fastLaneMillis", "fast_lane_millis");
    private static final Map<String, String> RECORD_SORTS = Map.of(
            "createdAt", "created_at", "name", "name", "lifecycle", "lifecycle");
    private static final Map<String, String> TEMPLATE_SORTS = Map.of(
            "createdAt", "created_at", "title", "title", "templateCode", "template_code", "lifecycle", "lifecycle");
    private static final Map<String, String> DRILL_SORTS = Map.of(
            "startedAt", "started_at", "completedAt", "completed_at", "drillNumber", "drill_number",
            "status", "status", "targetRecipients", "target_recipients");

    /** Binds the site array at index 1, then the clause's own values. Returns the next free index. */
    private static int bindScoped(PreparedStatement ps, Connection con, List<String> sites, List<Object> args)
            throws SQLException {
        int i = 1;
        ps.setArray(i++, con.createArrayOf("varchar", sites.toArray()));
        for (Object arg : args) {
            ps.setObject(i++, arg);
        }
        return i;
    }

    private <T> EmergencyPage<T> page(String table, List<String> sites, Where where, Order order, Paging paging,
            RowMapper<T> mapper) {
        Long total = jdbc.query(con -> prepareAndBind(con,
                "SELECT COUNT(*) FROM " + table + " WHERE " + where.sql(),
                ps -> bindScoped(ps, con, sites, where.args())), rs -> rs.next() ? rs.getLong(1) : 0L);
        long totalElements = total == null ? 0L : total;
        if (totalElements == 0L) {
            return EmergencyPage.empty(paging.page(), paging.size(), order.describedAs());
        }
        List<T> content = jdbc.query(con -> prepareAndBind(con,
                "SELECT * FROM " + table + " WHERE " + where.sql() + " ORDER BY " + order.sql() + " LIMIT ? OFFSET ?",
                ps -> {
                    int i = bindScoped(ps, con, sites, where.args());
                    ps.setInt(i++, paging.size());
                    ps.setInt(i, paging.offset());
                }), mapper);
        return EmergencyPage.of(content, paging.page(), paging.size(), totalElements, order.describedAs());
    }

    /** A contains-match needle, or null when the term is blank so the filter is skipped entirely. */
    private static String like(String term) {
        return term == null || term.isBlank() ? null : "%" + term.strip() + "%";
    }

    // ---- Templates -------------------------------------------------------------------------------

    @Override
    public NotificationTemplate saveTemplate(NotificationTemplate t) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.notification_templates SET title=?, body=?, channels=?,
                    break_glass_eligible=?, lifecycle=?, last_modified_by=?, last_modified_at=?, source_channel=?,
                    correlation_id=?, version=version+1 WHERE id=? AND version=?
                """, t.title(), t.body(), csv(t.channels()), t.breakGlassEligible(), t.lifecycle().name(),
                t.metadata().lastModifiedBy(), ts(t.metadata().lastModifiedAt()), t.metadata().sourceChannel().name(),
                t.metadata().correlationId(), t.id(), t.metadata().version());
        if (updated == 0 && findTemplate(t.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.notification_templates (id,site_code,template_code,title,body,
                        channels,break_glass_eligible,lifecycle,created_by,created_at,last_modified_by,last_modified_at,
                        source_channel,correlation_id,version,drill)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, t.id(), t.siteCode().value(), t.templateCode(), t.title(), t.body(), csv(t.channels()),
                    t.breakGlassEligible(), t.lifecycle().name(), t.metadata().createdBy(), ts(t.metadata().createdAt()),
                    t.metadata().lastModifiedBy(), ts(t.metadata().lastModifiedAt()), t.metadata().sourceChannel().name(),
                    t.metadata().correlationId(), t.metadata().version(), t.drill());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("NotificationTemplate version conflict");
        }
        return findTemplate(t.id()).orElseThrow();
    }

    @Override
    public Optional<NotificationTemplate> findTemplate(UUID id) {
        return one("SELECT * FROM emergency_notification.notification_templates WHERE id=?", this::template, id);
    }

    @Override
    public Optional<NotificationTemplate> findTemplateByCode(String siteCode, String templateCode) {
        return one("SELECT * FROM emergency_notification.notification_templates WHERE site_code=? AND template_code=?",
                this::template, siteCode, templateCode);
    }

    @Override
    public EmergencyPage<NotificationTemplate> findTemplates(RecordQuery q) {
        Order order = order(q.paging().sort(), TEMPLATE_SORTS, "createdAt", true);
        if (q.sites().isEmpty()) return EmergencyPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped()
                .and("lifecycle=?", q.lifecycle() == null ? null : q.lifecycle().name());
        if (like(q.search()) != null) {
            where.sql().append(" AND (title ILIKE ? OR template_code ILIKE ? OR body ILIKE ?)");
            where.args().add(like(q.search()));
            where.args().add(like(q.search()));
            where.args().add(like(q.search()));
        }
        where.when(Boolean.TRUE.equals(q.breakGlassEligible()), "break_glass_eligible=true");
        where.when(Boolean.FALSE.equals(q.breakGlassEligible()), "break_glass_eligible=false");
        return page("emergency_notification.notification_templates", q.sites(), where, order, q.paging(),
                this::template);
    }

    // ---- Scenarios -------------------------------------------------------------------------------

    @Override
    public EmergencyScenario saveScenario(EmergencyScenario s) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.emergency_scenarios SET name=?, priority=?, default_template_id=?,
                    break_glass_eligible=?, lifecycle=?, last_modified_by=?, last_modified_at=?, source_channel=?,
                    correlation_id=?, version=version+1 WHERE id=? AND version=?
                """, s.name(), s.priority().name(), s.defaultTemplateId(), s.breakGlassEligible(), s.lifecycle().name(),
                s.metadata().lastModifiedBy(), ts(s.metadata().lastModifiedAt()), s.metadata().sourceChannel().name(),
                s.metadata().correlationId(), s.id(), s.metadata().version());
        if (updated == 0 && findScenario(s.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.emergency_scenarios (id,site_code,scenario_code,name,priority,
                        default_template_id,break_glass_eligible,lifecycle,created_by,created_at,last_modified_by,
                        last_modified_at,source_channel,correlation_id,version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, s.id(), s.siteCode().value(), s.scenarioCode(), s.name(), s.priority().name(),
                    s.defaultTemplateId(), s.breakGlassEligible(), s.lifecycle().name(), s.metadata().createdBy(),
                    ts(s.metadata().createdAt()), s.metadata().lastModifiedBy(), ts(s.metadata().lastModifiedAt()),
                    s.metadata().sourceChannel().name(), s.metadata().correlationId(), s.metadata().version());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("EmergencyScenario version conflict");
        }
        return findScenario(s.id()).orElseThrow();
    }

    @Override
    public Optional<EmergencyScenario> findScenario(UUID id) {
        return one("SELECT * FROM emergency_notification.emergency_scenarios WHERE id=?", this::scenario, id);
    }

    @Override
    public Optional<EmergencyScenario> findScenarioByCode(String siteCode, String scenarioCode) {
        return one("SELECT * FROM emergency_notification.emergency_scenarios WHERE site_code=? AND scenario_code=?",
                this::scenario, siteCode, scenarioCode);
    }

    @Override
    public EmergencyPage<EmergencyScenario> findScenarios(RecordQuery q) {
        Order order = order(q.paging().sort(), RECORD_SORTS, "createdAt", true);
        if (q.sites().isEmpty()) return EmergencyPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped()
                .and("lifecycle=?", q.lifecycle() == null ? null : q.lifecycle().name());
        if (like(q.search()) != null) {
            where.sql().append(" AND (name ILIKE ? OR scenario_code ILIKE ?)");
            where.args().add(like(q.search()));
            where.args().add(like(q.search()));
        }
        where.when(Boolean.TRUE.equals(q.breakGlassEligible()), "break_glass_eligible=true");
        where.when(Boolean.FALSE.equals(q.breakGlassEligible()), "break_glass_eligible=false");
        return page("emergency_notification.emergency_scenarios", q.sites(), where, order, q.paging(), this::scenario);
    }

    // ---- Audience groups -------------------------------------------------------------------------

    @Override
    public AudienceGroup saveAudienceGroup(AudienceGroup a) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.audience_groups SET name=?, directory_reference=?, recipient_count=?,
                    lifecycle=?, last_modified_by=?, last_modified_at=?, source_channel=?, correlation_id=?,
                    version=version+1 WHERE id=? AND version=?
                """, a.name(), a.directoryReference(), a.recipientCount(), a.lifecycle().name(),
                a.metadata().lastModifiedBy(), ts(a.metadata().lastModifiedAt()), a.metadata().sourceChannel().name(),
                a.metadata().correlationId(), a.id(), a.metadata().version());
        if (updated == 0 && findAudienceGroup(a.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.audience_groups (id,site_code,group_code,name,directory_reference,
                        recipient_count,lifecycle,created_by,created_at,last_modified_by,last_modified_at,source_channel,
                        correlation_id,version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, a.id(), a.siteCode().value(), a.groupCode(), a.name(), a.directoryReference(),
                    a.recipientCount(), a.lifecycle().name(), a.metadata().createdBy(), ts(a.metadata().createdAt()),
                    a.metadata().lastModifiedBy(), ts(a.metadata().lastModifiedAt()), a.metadata().sourceChannel().name(),
                    a.metadata().correlationId(), a.metadata().version());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("AudienceGroup version conflict");
        }
        return findAudienceGroup(a.id()).orElseThrow();
    }

    @Override
    public Optional<AudienceGroup> findAudienceGroup(UUID id) {
        return one("SELECT * FROM emergency_notification.audience_groups WHERE id=?", this::audience, id);
    }

    @Override
    public List<AudienceGroup> findAudienceGroupsByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        UUID[] idArray = ids.toArray(new UUID[0]);
        return jdbc.query(con -> prepareAndBind(con,
                "SELECT * FROM emergency_notification.audience_groups WHERE id = ANY(?)",
                ps -> ps.setArray(1, con.createArrayOf("uuid", idArray))), this::audience);
    }

    @Override
    public Optional<AudienceGroup> findAudienceGroupByCode(String siteCode, String groupCode) {
        return one("SELECT * FROM emergency_notification.audience_groups WHERE site_code=? AND group_code=?",
                this::audience, siteCode, groupCode);
    }

    @Override
    public EmergencyPage<AudienceGroup> findAudienceGroups(RecordQuery q) {
        Order order = order(q.paging().sort(), RECORD_SORTS, "createdAt", true);
        if (q.sites().isEmpty()) return EmergencyPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped()
                .and("lifecycle=?", q.lifecycle() == null ? null : q.lifecycle().name());
        if (like(q.search()) != null) {
            where.sql().append(" AND (name ILIKE ? OR group_code ILIKE ?)");
            where.args().add(like(q.search()));
            where.args().add(like(q.search()));
        }
        return page("emergency_notification.audience_groups", q.sites(), where, order, q.paging(), this::audience);
    }

    // ---- Recipient zones -------------------------------------------------------------------------

    @Override
    public RecipientZone saveRecipientZone(RecipientZone z) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.recipient_zones SET name=?, location_reference=?, lifecycle=?,
                    last_modified_by=?, last_modified_at=?, source_channel=?, correlation_id=?, version=version+1
                    WHERE id=? AND version=?
                """, z.name(), z.locationReference(), z.lifecycle().name(), z.metadata().lastModifiedBy(),
                ts(z.metadata().lastModifiedAt()), z.metadata().sourceChannel().name(), z.metadata().correlationId(),
                z.id(), z.metadata().version());
        if (updated == 0 && findRecipientZone(z.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.recipient_zones (id,site_code,zone_code,name,location_reference,
                        lifecycle,created_by,created_at,last_modified_by,last_modified_at,source_channel,correlation_id,
                        version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, z.id(), z.siteCode().value(), z.zoneCode(), z.name(), z.locationReference(),
                    z.lifecycle().name(), z.metadata().createdBy(), ts(z.metadata().createdAt()),
                    z.metadata().lastModifiedBy(), ts(z.metadata().lastModifiedAt()), z.metadata().sourceChannel().name(),
                    z.metadata().correlationId(), z.metadata().version());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("RecipientZone version conflict");
        }
        return findRecipientZone(z.id()).orElseThrow();
    }

    @Override
    public Optional<RecipientZone> findRecipientZone(UUID id) {
        return one("SELECT * FROM emergency_notification.recipient_zones WHERE id=?", this::zone, id);
    }

    @Override
    public Optional<RecipientZone> findRecipientZoneByCode(String siteCode, String zoneCode) {
        return one("SELECT * FROM emergency_notification.recipient_zones WHERE site_code=? AND zone_code=?",
                this::zone, siteCode, zoneCode);
    }

    @Override
    public EmergencyPage<RecipientZone> findRecipientZones(RecordQuery q) {
        Order order = order(q.paging().sort(), RECORD_SORTS, "createdAt", true);
        if (q.sites().isEmpty()) return EmergencyPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped()
                .and("lifecycle=?", q.lifecycle() == null ? null : q.lifecycle().name());
        if (like(q.search()) != null) {
            where.sql().append(" AND (name ILIKE ? OR zone_code ILIKE ?)");
            where.args().add(like(q.search()));
            where.args().add(like(q.search()));
        }
        return page("emergency_notification.recipient_zones", q.sites(), where, order, q.paging(), this::zone);
    }

    @Override
    public Optional<RecipientZone> findZone(UUID id) {
        return one("SELECT * FROM emergency_notification.recipient_zones WHERE id=?", this::zone, id);
    }

    // ---- Activations -----------------------------------------------------------------------------

    @Override
    public NotificationActivation saveActivation(NotificationActivation a) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.notification_activations SET scenario_id=?, template_id=?,
                    audience_group_ids=?, recipient_zone_ids=?, channels=?, mode=?, status=?, priority=?,
                    incident_reference=?, approved_by=?, approved_at=?, rejection_reason=?, after_action_approved_by=?,
                    after_action_approved_at=?, after_action_justification=?, all_clear_at=?, closure_reason=?,
                    delivery_summary=?, acknowledgement_summary=?, closure_evidence_id=?, escalation_level=?,
                    degraded_mode=?, fallback_path=?, fast_lane_millis=?, last_modified_by=?, last_modified_at=?,
                    source_channel=?, correlation_id=?, version=version+1 WHERE id=? AND version=?
                """, a.scenarioId(), a.templateId(), uuidCsv(a.audienceGroupIds()), uuidCsv(a.recipientZoneIds()),
                csv(a.channels()), a.mode().name(), a.status().name(), a.priority().name(), a.incidentReference(),
                a.approvedBy(), ts(a.approvedAt()), a.rejectionReason(), a.afterActionApprovedBy(),
                ts(a.afterActionApprovedAt()), a.afterActionJustification(), ts(a.allClearAt()), a.closureReason(),
                a.deliverySummary(), a.acknowledgementSummary(), a.closureEvidenceId(), a.escalationLevel(),
                a.degradedMode(), a.fallbackPath(), a.fastLaneMillis(), a.metadata().lastModifiedBy(),
                ts(a.metadata().lastModifiedAt()), a.metadata().sourceChannel().name(), a.metadata().correlationId(),
                a.id(), a.metadata().version());
        if (updated == 0 && findActivation(a.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.notification_activations (id,site_code,activation_number,
                        scenario_id,template_id,audience_group_ids,recipient_zone_ids,channels,mode,status,priority,
                        incident_reference,approved_by,approved_at,rejection_reason,after_action_approved_by,
                        after_action_approved_at,after_action_justification,all_clear_at,closure_reason,delivery_summary,
                        acknowledgement_summary,closure_evidence_id,escalation_level,degraded_mode,fallback_path,
                        fast_lane_millis,created_by,created_at,last_modified_by,last_modified_at,source_channel,
                        correlation_id,version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, a.id(), a.siteCode().value(), a.activationNumber(), a.scenarioId(), a.templateId(),
                    uuidCsv(a.audienceGroupIds()), uuidCsv(a.recipientZoneIds()), csv(a.channels()), a.mode().name(),
                    a.status().name(), a.priority().name(), a.incidentReference(), a.approvedBy(), ts(a.approvedAt()),
                    a.rejectionReason(), a.afterActionApprovedBy(), ts(a.afterActionApprovedAt()),
                    a.afterActionJustification(), ts(a.allClearAt()), a.closureReason(), a.deliverySummary(),
                    a.acknowledgementSummary(), a.closureEvidenceId(), a.escalationLevel(), a.degradedMode(),
                    a.fallbackPath(), a.fastLaneMillis(), a.metadata().createdBy(), ts(a.metadata().createdAt()),
                    a.metadata().lastModifiedBy(), ts(a.metadata().lastModifiedAt()), a.metadata().sourceChannel().name(),
                    a.metadata().correlationId(), a.metadata().version());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("NotificationActivation version conflict");
        }
        return findActivation(a.id()).orElseThrow();
    }

    @Override
    public Optional<NotificationActivation> findActivation(UUID id) {
        return one("SELECT * FROM emergency_notification.notification_activations WHERE id=?", this::activation, id);
    }

    @Override
    public EmergencyPage<NotificationActivation> findActivations(ActivationQuery q) {
        Order order = order(q.paging().sort(), ACTIVATION_SORTS, "lastModifiedAt", true);
        if (q.sites().isEmpty()) return EmergencyPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped()
                .and("status=?", q.status() == null ? null : q.status().name())
                .and("mode=?", q.mode() == null ? null : q.mode().name())
                .and("priority=?", q.priority() == null ? null : q.priority().name())
                .and("scenario_id=?", q.scenarioId())
                .and("template_id=?", q.templateId())
                .and("created_at>=?", q.from() == null ? null : ts(q.from()))
                .and("created_at<?", q.to() == null ? null : ts(q.to()));
        if (like(q.incidentReference()) != null) {
            // One control over the two references an operator actually has to hand.
            where.sql().append(" AND (incident_reference ILIKE ? OR activation_number ILIKE ?)");
            where.args().add(like(q.incidentReference()));
            where.args().add(like(q.incidentReference()));
        }
        // NotificationActivation.open() - not closed, cancelled or rejected.
        where.when(Boolean.TRUE.equals(q.openOnly()), "status NOT IN ('CLOSED','CANCELLED','REJECTED')");
        // NotificationActivation.active() - a broadcast is out and has not been stood down.
        where.when(Boolean.TRUE.equals(q.liveOnly()),
                "status IN ('ACTIVE','BREAK_GLASS_ACTIVE','PARTIALLY_DELIVERED','ESCALATED')");
        // The S174 debt: sent without approval, and nobody has accounted for it yet.
        where.when(Boolean.TRUE.equals(q.afterActionOutstanding()),
                "mode='BREAK_GLASS' AND (after_action_approved_by IS NULL OR after_action_approved_by='')"
                        + " AND status<>'CANCELLED'");
        return page("emergency_notification.notification_activations", q.sites(), where, order, q.paging(),
                this::activation);
    }

    @Override
    public void saveActivationHistory(UUID activationId, String fromStatus, String toStatus, String action,
            String actor, String comment, Instant occurredAt, String correlationId) {
        jdbc.update("""
                INSERT INTO emergency_notification.activation_history (id,activation_id,from_status,to_status,action,
                    actor,comment,occurred_at,correlation_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), activationId, fromStatus, toStatus, action, actor, comment, ts(occurredAt),
                correlationId);
    }

    @Override
    public List<ActivationHistoryEntry> findActivationHistory(UUID activationId) {
        return jdbc.query("SELECT * FROM emergency_notification.activation_history WHERE activation_id=? "
                + "ORDER BY occurred_at, id", (rs, n) -> new ActivationHistoryEntry(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("activation_id"), rs.getString("from_status"),
                rs.getString("to_status"), rs.getString("action"), rs.getString("actor"),
                rs.getString("comment"), instant(rs, "occurred_at"), rs.getString("correlation_id")),
                activationId);
    }

    // ---- Channels --------------------------------------------------------------------------------

    @Override
    public NotificationChannel saveChannel(NotificationChannel c) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.notification_channels SET status=?, target_count=?, sent_count=?,
                    delivered_count=?, failed_count=?, acknowledged_count=?, last_modified_by=?, last_modified_at=?,
                    source_channel=?, correlation_id=?, version=version+1 WHERE id=? AND version=?
                """, c.status().name(), c.targetCount(), c.sentCount(), c.deliveredCount(), c.failedCount(),
                c.acknowledgedCount(), c.metadata().lastModifiedBy(), ts(c.metadata().lastModifiedAt()),
                c.metadata().sourceChannel().name(), c.metadata().correlationId(), c.id(), c.metadata().version());
        if (updated == 0 && findChannelById(c.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.notification_channels (id,activation_id,site_code,channel_type,
                        status,target_count,sent_count,delivered_count,failed_count,acknowledged_count,created_by,
                        created_at,last_modified_by,last_modified_at,source_channel,correlation_id,version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, c.id(), c.activationId(), c.siteCode().value(), c.channelType().name(), c.status().name(),
                    c.targetCount(), c.sentCount(), c.deliveredCount(), c.failedCount(), c.acknowledgedCount(),
                    c.metadata().createdBy(), ts(c.metadata().createdAt()), c.metadata().lastModifiedBy(),
                    ts(c.metadata().lastModifiedAt()), c.metadata().sourceChannel().name(), c.metadata().correlationId(),
                    c.metadata().version());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("NotificationChannel version conflict");
        }
        return findChannelById(c.id()).orElseThrow();
    }

    private Optional<NotificationChannel> findChannelById(UUID id) {
        return one("SELECT * FROM emergency_notification.notification_channels WHERE id=?", this::channel, id);
    }

    @Override
    public List<NotificationChannel> findChannels(UUID activationId) {
        return jdbc.query("SELECT * FROM emergency_notification.notification_channels WHERE activation_id=? "
                + "ORDER BY channel_type", this::channel, activationId);
    }

    @Override
    public Optional<NotificationChannel> findChannel(UUID activationId, ChannelType type) {
        return one("SELECT * FROM emergency_notification.notification_channels WHERE activation_id=? AND channel_type=?",
                this::channel, activationId, type.name());
    }

    // ---- Delivery receipts -----------------------------------------------------------------------

    @Override
    public DeliveryReceipt saveReceipt(DeliveryReceipt r) {
        jdbc.update("""
                INSERT INTO emergency_notification.delivery_receipts (id,activation_id,site_code,channel_type,provider,
                    provider_message_id,recipient_ref,status,reason,occurred_at,created_by,created_at,source_channel,
                    correlation_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (activation_id,provider,provider_message_id) DO NOTHING
                """, r.id(), r.activationId(), r.siteCode().value(), r.channelType().name(), r.provider(),
                r.providerMessageId(), r.recipientRef(), r.status().name(), r.reason(), ts(r.occurredAt()),
                r.createdBy(), ts(r.createdAt()), r.sourceChannel().name(), r.correlationId());
        return findReceipt(r.activationId(), r.provider(), r.providerMessageId()).orElse(r);
    }

    @Override
    public Optional<DeliveryReceipt> findReceipt(UUID activationId, String provider, String providerMessageId) {
        return one("""
                SELECT * FROM emergency_notification.delivery_receipts
                WHERE activation_id=? AND provider=? AND provider_message_id=?
                """, this::receipt, activationId, provider, providerMessageId);
    }

    @Override
    public List<DeliveryReceipt> findReceipts(UUID activationId) {
        return jdbc.query("SELECT * FROM emergency_notification.delivery_receipts WHERE activation_id=? "
                + "ORDER BY occurred_at DESC", this::receipt, activationId);
    }

    // ---- Acknowledgements ------------------------------------------------------------------------

    @Override
    public Acknowledgement saveAcknowledgement(Acknowledgement a) {
        jdbc.update("""
                INSERT INTO emergency_notification.acknowledgements (id,activation_id,site_code,channel_type,
                    recipient_ref,acknowledged_at,created_by,created_at,source_channel,correlation_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (activation_id,recipient_ref) DO NOTHING
                """, a.id(), a.activationId(), a.siteCode().value(), a.channelType() == null ? null : a.channelType().name(),
                a.recipientRef(), ts(a.acknowledgedAt()), a.createdBy(), ts(a.createdAt()), a.sourceChannel().name(),
                a.correlationId());
        return findAcknowledgement(a.activationId(), a.recipientRef()).orElse(a);
    }

    @Override
    public Optional<Acknowledgement> findAcknowledgement(UUID activationId, String recipientRef) {
        return one("SELECT * FROM emergency_notification.acknowledgements WHERE activation_id=? AND recipient_ref=?",
                this::acknowledgement, activationId, recipientRef);
    }

    @Override
    public List<Acknowledgement> findAcknowledgements(UUID activationId) {
        return jdbc.query("SELECT * FROM emergency_notification.acknowledgements WHERE activation_id=? "
                + "ORDER BY acknowledged_at DESC, id", this::acknowledgement, activationId);
    }

    @Override
    public long countAcknowledgements(UUID activationId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM emergency_notification.acknowledgements WHERE activation_id=?", Long.class,
                activationId);
        return count == null ? 0L : count;
    }

    // ---- Drills ----------------------------------------------------------------------------------

    @Override
    public DrillRun saveDrill(DrillRun d) {
        int updated = jdbc.update("""
                UPDATE emergency_notification.drill_runs SET status=?, target_recipients=?, reached_recipients=?,
                    acknowledged_recipients=?, activation_millis=?, completed_at=?, notes=?, last_modified_by=?,
                    last_modified_at=?, source_channel=?, correlation_id=?, version=version+1 WHERE id=? AND version=?
                """, d.status().name(), d.targetRecipients(), d.reachedRecipients(), d.acknowledgedRecipients(),
                d.activationMillis(), ts(d.completedAt()), d.notes(), d.metadata().lastModifiedBy(),
                ts(d.metadata().lastModifiedAt()), d.metadata().sourceChannel().name(), d.metadata().correlationId(),
                d.id(), d.metadata().version());
        if (updated == 0 && findDrill(d.id()).isEmpty()) {
            jdbc.update("""
                    INSERT INTO emergency_notification.drill_runs (id,site_code,drill_number,scenario_id,status,
                        target_recipients,reached_recipients,acknowledged_recipients,activation_millis,started_at,
                        completed_at,notes,created_by,created_at,last_modified_by,last_modified_at,source_channel,
                        correlation_id,version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, d.id(), d.siteCode().value(), d.drillNumber(), d.scenarioId(), d.status().name(),
                    d.targetRecipients(), d.reachedRecipients(), d.acknowledgedRecipients(), d.activationMillis(),
                    ts(d.startedAt()), ts(d.completedAt()), d.notes(), d.metadata().createdBy(),
                    ts(d.metadata().createdAt()), d.metadata().lastModifiedBy(), ts(d.metadata().lastModifiedAt()),
                    d.metadata().sourceChannel().name(), d.metadata().correlationId(), d.metadata().version());
        } else if (updated == 0) {
            throw new OptimisticLockingFailureException("DrillRun version conflict");
        }
        return findDrill(d.id()).orElseThrow();
    }

    @Override
    public Optional<DrillRun> findDrill(UUID id) {
        return one("SELECT * FROM emergency_notification.drill_runs WHERE id=?", this::drill, id);
    }

    @Override
    public EmergencyPage<DrillRun> findDrills(DrillQuery q) {
        Order order = order(q.paging().sort(), DRILL_SORTS, "startedAt", true);
        if (q.sites().isEmpty()) return EmergencyPage.empty(q.paging().page(), q.paging().size(), order.describedAs());
        Where where = Where.scoped()
                .and("status=?", q.status() == null ? null : q.status().name())
                .and("scenario_id=?", q.scenarioId())
                .and("started_at>=?", q.from() == null ? null : ts(q.from()))
                .and("started_at<?", q.to() == null ? null : ts(q.to()));
        return page("emergency_notification.drill_runs", q.sites(), where, order, q.paging(), this::drill);
    }

    // ---- Dashboard -------------------------------------------------------------------------------

    @Override
    public Map<String, Map<String, Long>> dashboardBreakdown(List<String> sites, String site) {
        if (sites.isEmpty()) {
            return Map.of();
        }
        String scope = site == null ? null : SiteCode.of(site).value();
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        result.put("byStatus", groupCount(sites, scope,
                "SELECT status, COUNT(*) FROM emergency_notification.notification_activations WHERE %s GROUP BY status"));
        result.put("byPriority", groupCount(sites, scope,
                "SELECT priority, COUNT(*) FROM emergency_notification.notification_activations WHERE %s GROUP BY priority"));
        result.put("byMode", groupCount(sites, scope,
                "SELECT mode, COUNT(*) FROM emergency_notification.notification_activations WHERE %s GROUP BY mode"));
        // Channel is a per-fan-out record, so this counts channels sent over rather than activations.
        result.put("byChannel", groupCount(sites, scope,
                "SELECT channel_type, COUNT(*) FROM emergency_notification.notification_channels WHERE %s GROUP BY channel_type"));
        return result;
    }

    /** A one-dimension GROUP BY, site-scoped exactly the way {@code count} is. */
    private Map<String, Long> groupCount(List<String> sites, String scope, String template) {
        String predicate = scope == null ? "site_code = ANY (?)" : "site_code = ANY (?) AND site_code=?";
        String sql = String.format(template, predicate);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query(con -> prepareAndBind(con, sql, ps -> {
            ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
            if (scope != null) {
                ps.setString(2, scope);
            }
        }), (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    @Override
    public Map<String, Object> dashboardCounts(List<String> sites, String site) {
        if (sites.isEmpty()) {
            return Map.of();
        }
        String scope = site == null ? null : SiteCode.of(site).value();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("activeActivationCount", count(sites, scope,
                "SELECT COUNT(*) FROM emergency_notification.notification_activations WHERE %s AND status IN "
                        + "('ACTIVE','BREAK_GLASS_ACTIVE','PARTIALLY_DELIVERED','ESCALATED','ALL_CLEAR_PENDING')"
                        // A drill in progress is not a live emergency (S175-01); byMode still shows it.
                        + " AND mode <> 'DRILL'"));
        counts.put("breakGlassCount", count(sites, scope,
                "SELECT COUNT(*) FROM emergency_notification.notification_activations WHERE %s AND mode='BREAK_GLASS'"));
        counts.put("failedRecipientCount", count(sites, scope,
                "SELECT COALESCE(SUM(failed_count),0) FROM emergency_notification.notification_channels WHERE %s"));
        counts.put("ackPendingCount", count(sites, scope,
                "SELECT COALESCE(SUM(GREATEST(target_count-acknowledged_count,0)),0) "
                        + "FROM emergency_notification.notification_channels WHERE %s"));
        counts.put("escalatedCount", count(sites, scope,
                "SELECT COUNT(*) FROM emergency_notification.notification_activations WHERE %s AND status='ESCALATED'"));
        counts.put("allClearPendingCount", count(sites, scope,
                "SELECT COUNT(*) FROM emergency_notification.notification_activations WHERE %s AND status='ALL_CLEAR_PENDING'"));
        counts.put("drillCount", count(sites, scope,
                "SELECT COUNT(*) FROM emergency_notification.drill_runs WHERE %s AND status='COMPLETED'"));
        counts.put("sourceUpdatedAt", maxSourceUpdatedAt(sites, scope));
        return counts;
    }

    @Override
    public void saveDashboardSnapshot(String scopeKey, String siteCode, Instant generatedAt, boolean stale,
            Map<String, Object> counts, Instant sourceUpdatedAt, String warnings) {
        jdbc.update("""
                INSERT INTO emergency_notification.dashboard_snapshots (id,scope_key,site_code,generated_at,stale,
                    active_activation_count,break_glass_count,failed_recipient_count,ack_pending_count,escalated_count,
                    all_clear_pending_count,drill_count,source_updated_at,warnings)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), scopeKey, siteCode, ts(generatedAt), stale,
                asInt(counts.get("activeActivationCount")), asInt(counts.get("breakGlassCount")),
                asInt(counts.get("failedRecipientCount")), asInt(counts.get("ackPendingCount")),
                asInt(counts.get("escalatedCount")), asInt(counts.get("allClearPendingCount")),
                asInt(counts.get("drillCount")), ts(sourceUpdatedAt), warnings);
    }

    // ---- Sweeps ----------------------------------------------------------------------------------

    @Override
    public List<UUID> findActivationsForAckEscalation(String siteCode, Instant activatedBefore, int limit) {
        return jdbc.query("""
                SELECT id FROM emergency_notification.notification_activations
                WHERE site_code=? AND status IN ('ACTIVE','BREAK_GLASS_ACTIVE','PARTIALLY_DELIVERED')
                  AND mode <> 'DRILL'
                  AND last_modified_at < ?
                ORDER BY last_modified_at LIMIT ?
                """, (rs, n) -> (UUID) rs.getObject("id"), siteCode, ts(activatedBefore), bound(limit));
    }

    @Override
    public List<String> activeSites() {
        return jdbc.query("""
                SELECT DISTINCT site_code FROM emergency_notification.notification_activations
                UNION SELECT DISTINCT site_code FROM emergency_notification.drill_runs
                """, (rs, n) -> rs.getString(1));
    }

    // ---- Row mappers -----------------------------------------------------------------------------

    private NotificationTemplate template(ResultSet r, int n) throws SQLException {
        return new NotificationTemplate(uuid(r, "id"), r.getString("template_code"),
                SiteCode.of(r.getString("site_code")), r.getString("title"), r.getString("body"),
                channels(r.getString("channels")), r.getBoolean("break_glass_eligible"),
                RecordLifecycle.valueOf(r.getString("lifecycle")), metadata(r), r.getBoolean("drill"));
    }

    private EmergencyScenario scenario(ResultSet r, int n) throws SQLException {
        return new EmergencyScenario(uuid(r, "id"), r.getString("scenario_code"), SiteCode.of(r.getString("site_code")),
                r.getString("name"), Priority.valueOf(r.getString("priority")), uuid(r, "default_template_id"),
                r.getBoolean("break_glass_eligible"), RecordLifecycle.valueOf(r.getString("lifecycle")), metadata(r));
    }

    private AudienceGroup audience(ResultSet r, int n) throws SQLException {
        return new AudienceGroup(uuid(r, "id"), r.getString("group_code"), SiteCode.of(r.getString("site_code")),
                r.getString("name"), r.getString("directory_reference"), r.getInt("recipient_count"),
                RecordLifecycle.valueOf(r.getString("lifecycle")), metadata(r));
    }

    private RecipientZone zone(ResultSet r, int n) throws SQLException {
        return new RecipientZone(uuid(r, "id"), r.getString("zone_code"), SiteCode.of(r.getString("site_code")),
                r.getString("name"), r.getString("location_reference"),
                RecordLifecycle.valueOf(r.getString("lifecycle")), metadata(r));
    }

    private NotificationActivation activation(ResultSet r, int n) throws SQLException {
        Long fastLane = (Long) r.getObject("fast_lane_millis");
        return new NotificationActivation(uuid(r, "id"), r.getString("activation_number"),
                SiteCode.of(r.getString("site_code")), uuid(r, "scenario_id"), uuid(r, "template_id"),
                uuidList(r.getString("audience_group_ids")), uuidList(r.getString("recipient_zone_ids")),
                channels(r.getString("channels")), NotificationActivation.Mode.valueOf(r.getString("mode")),
                NotificationActivation.Status.valueOf(r.getString("status")), Priority.valueOf(r.getString("priority")),
                r.getString("incident_reference"), r.getString("approved_by"), instant(r, "approved_at"),
                r.getString("rejection_reason"), r.getString("after_action_approved_by"),
                instant(r, "after_action_approved_at"), r.getString("after_action_justification"),
                instant(r, "all_clear_at"), r.getString("closure_reason"), r.getString("delivery_summary"),
                r.getString("acknowledgement_summary"), uuid(r, "closure_evidence_id"), r.getInt("escalation_level"),
                r.getBoolean("degraded_mode"), r.getString("fallback_path"), fastLane, metadata(r));
    }

    private NotificationChannel channel(ResultSet r, int n) throws SQLException {
        return new NotificationChannel(uuid(r, "id"), uuid(r, "activation_id"), SiteCode.of(r.getString("site_code")),
                ChannelType.valueOf(r.getString("channel_type")), ChannelStatus.valueOf(r.getString("status")),
                r.getInt("target_count"), r.getInt("sent_count"), r.getInt("delivered_count"), r.getInt("failed_count"),
                r.getInt("acknowledged_count"), metadata(r));
    }

    private DeliveryReceipt receipt(ResultSet r, int n) throws SQLException {
        return new DeliveryReceipt(uuid(r, "id"), uuid(r, "activation_id"), SiteCode.of(r.getString("site_code")),
                ChannelType.valueOf(r.getString("channel_type")), r.getString("provider"),
                r.getString("provider_message_id"), r.getString("recipient_ref"),
                DeliveryStatus.valueOf(r.getString("status")), r.getString("reason"), instant(r, "occurred_at"),
                r.getString("created_by"), instant(r, "created_at"),
                SourceChannel.valueOf(r.getString("source_channel")), r.getString("correlation_id"));
    }

    private Acknowledgement acknowledgement(ResultSet r, int n) throws SQLException {
        String ch = r.getString("channel_type");
        return new Acknowledgement(uuid(r, "id"), uuid(r, "activation_id"), SiteCode.of(r.getString("site_code")),
                ch == null ? null : ChannelType.valueOf(ch), r.getString("recipient_ref"),
                instant(r, "acknowledged_at"), r.getString("created_by"), instant(r, "created_at"),
                SourceChannel.valueOf(r.getString("source_channel")), r.getString("correlation_id"));
    }

    private DrillRun drill(ResultSet r, int n) throws SQLException {
        Long activationMillis = (Long) r.getObject("activation_millis");
        return new DrillRun(uuid(r, "id"), r.getString("drill_number"), SiteCode.of(r.getString("site_code")),
                uuid(r, "scenario_id"), DrillRun.Status.valueOf(r.getString("status")), r.getInt("target_recipients"),
                r.getInt("reached_recipients"), r.getInt("acknowledged_recipients"), activationMillis,
                instant(r, "started_at"), instant(r, "completed_at"), r.getString("notes"), metadata(r));
    }

    private RecordMetadata metadata(ResultSet r) throws SQLException {
        return RecordMetadata.rehydrate(r.getString("created_by"), instant(r, "created_at"),
                r.getString("last_modified_by"), instant(r, "last_modified_at"), r.getLong("version"),
                SourceChannel.valueOf(r.getString("source_channel")), r.getString("correlation_id"));
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private long count(List<String> sites, String site, String template) {
        String scoped = site == null ? "site_code = ANY (?)" : "site_code = ANY (?) AND site_code=?";
        String sql = String.format(template, scoped);
        Long value = jdbc.query(con -> prepareAndBind(con, sql, ps -> {
            ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
            if (site != null) {
                ps.setString(2, site);
            }
        }), rs -> rs.next() ? rs.getLong(1) : 0L);
        return value == null ? 0L : value;
    }

    private Instant maxSourceUpdatedAt(List<String> sites, String site) {
        String scoped = site == null ? "site_code = ANY (?)" : "site_code = ANY (?) AND site_code=?";
        String sql = "SELECT MAX(last_modified_at) FROM emergency_notification.notification_activations WHERE " + scoped;
        return jdbc.query(con -> prepareAndBind(con, sql, ps -> {
            ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
            if (site != null) {
                ps.setString(2, site);
            }
        }), rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null);
    }

    private <T> List<T> list(String sql, List<String> sites, int limit, RowMapper<T> mapper) {
        if (sites.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> prepareAndBind(con, sql, ps -> {
            ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
            ps.setInt(2, bound(limit));
        }), mapper);
    }

    private <T> List<T> query(String sql, List<String> sites, List<Object> args, RowMapper<T> mapper) {
        return jdbc.query(con -> prepareAndBind(con, sql, ps -> {
            ps.setArray(1, con.createArrayOf("varchar", sites.toArray()));
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 2, args.get(i));
            }
        }), mapper);
    }

    /**
     * A {@code PreparedStatement} created and then closed if binding its parameters fails.
     *
     * <p>Every site-scoped query in this class builds its statement in two steps -
     * {@code con.prepareStatement(sql)}, then one or more {@code ps.setXxx(...)} calls including
     * {@code createArrayOf(...)}, all of which declare {@code throws SQLException}. Binding used to
     * run directly inside the {@code PreparedStatementCreator} lambda: if any bind call threw, the
     * already-created statement was never returned to {@code JdbcTemplate} and so never reached the
     * {@code close()} it normally guarantees - a real, if narrow, leaked-server-side-resource path on
     * a connection that is often still healthy enough to keep leaking through.
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

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static int bound(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private UUID uuid(ResultSet r, String name) throws SQLException {
        Object v = r.getObject(name);
        return v == null ? null : (UUID) v;
    }

    private Instant instant(ResultSet r, String name) throws SQLException {
        var v = r.getTimestamp(name);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String csv(List<ChannelType> channels) {
        List<String> names = new ArrayList<>();
        for (ChannelType c : channels) {
            names.add(c.name());
        }
        return String.join(",", names);
    }

    private static String uuidCsv(List<UUID> ids) {
        List<String> s = new ArrayList<>();
        for (UUID id : ids) {
            s.add(id.toString());
        }
        return String.join(",", s);
    }

    private static List<ChannelType> channels(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<ChannelType> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                out.add(ChannelType.valueOf(part.strip()));
            }
        }
        return out;
    }

    private static List<UUID> uuidList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isBlank())
                .map(UUID::fromString).toList();
    }
}

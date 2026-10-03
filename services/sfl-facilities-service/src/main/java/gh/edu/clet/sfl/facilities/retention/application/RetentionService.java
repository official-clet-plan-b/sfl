package gh.edu.clet.sfl.facilities.retention.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization;
import gh.edu.clet.sfl.facilities.shared.application.port.AuditPort;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention by record class (SRS 4.2). Periods are configuration, one row per system and class, so a statutory decision
 * is a data change a director makes and the audit trail records - not a deployment.
 *
 * <p>Nothing here deletes regulated evidence (NFR-REC1). Evidence that has outlived its period is <em>reported</em> for
 * authorised disposal. Only sensitive personal data - catering dietary and allergy needs - is anonymised, once its
 * period ends, with each row audited: the person reference becomes an opaque token and the need itself is kept,
 * because the allergen counts still matter to a report and no longer say who.
 */
@Service
public class RetentionService {

    static final String ORGANISATION = "organisation";
    private static final Set<String> SYSTEMS = Set.of("S170", "S172", "S177", "S178", "S179");

    private final JdbcTemplate jdbc;
    private final FacilitiesAuthorization authorization;
    private final AuditPort audit;
    private final Clock clock;

    public RetentionService(JdbcTemplate jdbc, FacilitiesAuthorization authorization, AuditPort audit, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    public record Policy(String systemCode, String recordClass, int retentionDays, String action, String basis, String updatedBy, Instant updatedAt) {
    }

    public record Due(String systemCode, UUID evidenceId, String siteCode, String reference, String fileName, String recordClass, Instant submittedAt,
            Instant dueSince) {
    }

    private void require(Caller caller, String type, String id) {
        authorization.require(caller.actor(), SflPermission.FACILITIES_RETENTION_MANAGE, caller.channel(), type, id, ORGANISATION);
    }

    @Transactional(readOnly = true)
    public List<Policy> policies(Caller caller) {
        require(caller, "RetentionPolicy", "list");
        return jdbc.query("SELECT * FROM facilities.record_retention_policies ORDER BY system_code, record_class", (rs, n) -> new Policy(
                rs.getString("system_code"), rs.getString("record_class"), rs.getInt("retention_days"), rs.getString("action"), rs.getString("basis"),
                rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()));
    }

    @Transactional
    public Policy set(String systemCode, String recordClass, int retentionDays, String basis, Caller caller) {
        require(caller, "RetentionPolicy", systemCode + ":" + recordClass);
        String system = systemCode == null ? "" : systemCode.strip().toUpperCase();
        String cls = recordClass == null ? "" : recordClass.strip().toUpperCase();
        if (retentionDays < 1 || retentionDays > 36_500) {
            throw new IllegalArgumentException("retentionDays must be between 1 and 36500.");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("basis is required: say what makes this the period (statute, policy, decision).");
        }
        List<Policy> before = jdbc.query("SELECT * FROM facilities.record_retention_policies WHERE system_code = ? AND record_class = ?",
                (rs, n) -> new Policy(rs.getString("system_code"), rs.getString("record_class"), rs.getInt("retention_days"), rs.getString("action"),
                        rs.getString("basis"), rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()), system, cls);
        if (before.isEmpty()) {
            throw new IllegalArgumentException("There is no retention class " + cls + " for " + system + ".");
        }
        Instant now = clock.instant();
        jdbc.update("UPDATE facilities.record_retention_policies SET retention_days = ?, basis = ?, updated_by = ?, updated_at = ?"
                + " WHERE system_code = ? AND record_class = ?", retentionDays, basis.strip(), caller.actor().actorId(), java.sql.Timestamp.from(now), system, cls);
        Policy after = new Policy(system, cls, retentionDays, before.get(0).action(), basis.strip(), caller.actor().actorId(), now);
        audit.record(caller.actor(), caller.channel(), AuditAction.RETENTION_POLICY_CHANGED, "RetentionPolicy", system + ":" + cls, ORGANISATION, before.get(0), after);
        return after;
    }

    /** Evidence past its period, for authorised disposal. Reported, never deleted by this service. */
    @Transactional(readOnly = true)
    public List<Due> due(String systemCode, Caller caller) {
        require(caller, "RetentionDue", "list");
        String system = systemCode == null || systemCode.isBlank() ? null : systemCode.strip().toUpperCase();
        if (system != null && !SYSTEMS.contains(system)) {
            throw new IllegalArgumentException("Unknown system " + system + ".");
        }
        String[][] sources = {{"S170", "hygiene_evidence"}, {"S178", "waste_evidence"}, {"S172", "cat_evidence"}, {"S179", "lf_evidence"}, {"S177", "lease_documents"}};
        List<Due> found = new java.util.ArrayList<>();
        for (String[] source : sources) {
            if (system != null && !system.equals(source[0])) {
                continue;
            }
            found.addAll(jdbc.query("SELECT e.id, e.site_code, e.reference, e.file_name, e.retention_class, e.submitted_at,"
                    + " e.submitted_at + make_interval(days => p.retention_days) AS due_since FROM facilities." + source[1] + " e"
                    + " JOIN facilities.record_retention_policies p ON p.system_code = ? AND p.record_class = e.retention_class AND p.action = 'REVIEW'"
                    + " WHERE e.submitted_at + make_interval(days => p.retention_days) < ? ORDER BY due_since LIMIT 500",
                    (rs, n) -> new Due(source[0], rs.getObject("id", UUID.class), rs.getString("site_code"), rs.getString("reference"),
                            rs.getString("file_name"), rs.getString("retention_class"), rs.getTimestamp("submitted_at").toInstant(),
                            rs.getTimestamp("due_since").toInstant()), source[0], java.sql.Timestamp.from(clock.instant())));
        }
        return found;
    }

    /**
     * Anonymises dietary needs whose service ended longer ago than the policy allows. Each row is audited with the
     * token it became; running it twice does nothing the second time.
     */
    @Transactional
    public int anonymiseDietary(ActorContext actor) {
        Integer days = jdbc.query("SELECT retention_days FROM facilities.record_retention_policies WHERE system_code = 'S172'"
                + " AND record_class = 'DIETARY_DATA' AND action = 'ANONYMISE'", rs -> rs.next() ? rs.getInt(1) : null);
        if (days == null) {
            return 0;
        }
        LocalDate cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(days);
        List<Object[]> rows = jdbc.query("SELECT r.id, r.site_code FROM facilities.cat_dietary_requests r JOIN facilities.cat_services s ON s.id = r.service_id"
                + " WHERE r.anonymised_at IS NULL AND s.service_date < ?", (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("site_code")}, cutoff);
        Caller caller = new Caller(actor, SourceChannel.SCHEDULER);
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            String token = "ANON-" + id.toString().substring(0, 8);
            jdbc.update("UPDATE facilities.cat_dietary_requests SET person_reference = ?, anonymised_at = ?, version = version + 1 WHERE id = ?",
                    token, java.sql.Timestamp.from(clock.instant()), id);
            audit.record(actor, caller.channel(), AuditAction.RECORD_ANONYMISED, "DietaryRequest", id.toString(), (String) row[1], null,
                    "person reference anonymised as " + token + " after " + days + " days");
        }
        return rows.size();
    }
}

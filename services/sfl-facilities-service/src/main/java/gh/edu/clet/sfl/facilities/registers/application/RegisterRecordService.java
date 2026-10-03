package gh.edu.clet.sfl.facilities.registers.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.registers.domain.RegisterRecord;
import gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization;
import gh.edu.clet.sfl.facilities.shared.application.port.AuditPort;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application boundary for the S170, S172, S177, S178 and S179 registers. */
@Service
public class RegisterRecordService {

    public static final Set<String> SYSTEMS = Set.of("S170", "S172", "S177", "S178", "S179");

    private final JdbcTemplate jdbc;
    private final FacilitiesAuthorization authorization;
    private final AuditPort audit;
    private final Clock clock;

    public RegisterRecordService(JdbcTemplate jdbc, FacilitiesAuthorization authorization, AuditPort audit, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RegisterRecord> list(String systemCode, String siteCode, String recordType, ActorContext actor,
            SourceChannel channel) {
        String system = normalizeSystem(systemCode);
        authorization.require(actor, RegisterRules.readPermission(system), siteCode, channel, "RegisterRecord", "list");
        List<String> types = RegisterRules.parseTypes(system, recordType);
        String sql = """
                SELECT id, system_code, site_code, record_type, title, status, owner_reference, due_at,
                       severity, details, created_by, created_at, updated_at, version
                  FROM facilities.register_records
                 WHERE system_code = ? AND site_code = ?
                """ + (types.isEmpty() ? "" : " AND record_type IN (" + "?, ".repeat(types.size() - 1) + "?)")
                + " ORDER BY due_at NULLS LAST, updated_at DESC";
        List<Object> arguments = new java.util.ArrayList<>(List.of(system, siteCode.strip().toUpperCase()));
        arguments.addAll(types);
        return jdbc.query(sql, (rs, rowNum) -> map(rs), arguments.toArray());
    }

    @Transactional
    public RegisterRecord create(CreateCommand command) {
        String system = normalizeSystem(command.systemCode());
        String site = command.siteCode().strip().toUpperCase();
        String recordType = required(command.recordType(), "recordType").toUpperCase();
        String status = required(command.status(), "status").toUpperCase();
        RegisterRules.validate(system, recordType, status);
        authorization.require(command.actor(), RegisterRules.writePermission(system), site, command.channel(),
                "RegisterRecord", "new");
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO facilities.register_records
                    (id, system_code, site_code, record_type, title, status, owner_reference, due_at,
                     severity, details, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, id, system, site, recordType, required(command.title(), "title"), status, command.ownerReference(),
                timestamp(command.dueAt()), command.severity(), command.details(), command.actor().actorId(),
                timestamp(now), timestamp(now));
        RegisterRecord created = find(id);
        audit.record(command.actor(), command.channel(), AuditAction.REGISTER_RECORD_CREATED, "RegisterRecord",
                id.toString(), site, null, created);
        return created;
    }

    @Transactional
    public RegisterRecord updateStatus(UUID id, String status, ActorContext actor, SourceChannel channel) {
        RegisterRecord before = find(id);
        String nextStatus = required(status, "status").toUpperCase();
        if (!RegisterRules.validStatus(before.systemCode(), nextStatus)) {
            throw new IllegalArgumentException("Status " + nextStatus + " is not valid for " + before.systemCode());
        }
        authorization.require(actor, RegisterRules.writePermission(before.systemCode()), before.siteCode(), channel,
                "RegisterRecord", id.toString());
        int changed = jdbc.update("""
                UPDATE facilities.register_records
                   SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ?
                """, nextStatus, timestamp(clock.instant()), id);
        if (changed != 1) {
            throw new IllegalArgumentException("Register record does not exist: " + id);
        }
        RegisterRecord after = find(id);
        audit.record(actor, channel, AuditAction.REGISTER_RECORD_UPDATED, "RegisterRecord", id.toString(), before.siteCode(),
                before, after);
        return after;
    }

    private RegisterRecord find(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, system_code, site_code, record_type, title, status, owner_reference, due_at,
                       severity, details, created_by, created_at, updated_at, version
                  FROM facilities.register_records WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);
    }

    private static RegisterRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RegisterRecord(rs.getObject("id", UUID.class), rs.getString("system_code"), rs.getString("site_code"),
                rs.getString("record_type"), rs.getString("title"), rs.getString("status"),
                rs.getString("owner_reference"), instant(rs, "due_at"), rs.getString("severity"),
                rs.getString("details"), rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"));
    }

    /** The PostgreSQL driver cannot bind or read a bare {@code Instant}; a {@code Timestamp} it can. */
    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalizeSystem(String value) {
        String system = required(value, "systemCode").toUpperCase();
        if (!SYSTEMS.contains(system)) {
            throw new IllegalArgumentException("Unsupported register system: " + system);
        }
        return system;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    public record CreateCommand(String systemCode, String siteCode, String recordType, String title, String status,
            String ownerReference, Instant dueAt, String severity, String details, ActorContext actor,
            SourceChannel channel) {
    }
}

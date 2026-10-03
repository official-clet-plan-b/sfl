package gh.edu.clet.sfl.safetysecurity.platform.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.emergency.infrastructure.persistence.JdbcAuditAdapter;
import gh.edu.clet.sfl.safetysecurity.platform.infrastructure.persistence.AuditAdapter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The audit records hash and replay against a real database whatever sub-millisecond fraction the clock reports.
 *
 * <p>PostgreSQL keeps microseconds; a Java instant on Linux carries nanoseconds, and both chains hash epoch milliseconds. An instant
 * 400 nanoseconds short of a millisecond boundary used to be hashed in one millisecond and stored, rounded up, in the next - so the
 * record replayed as tampered, about once in two thousand records, only ever on CI's Linux JDK. This test uses exactly such an
 * instant, so it fails every time on the old code rather than one run in several.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.emergency.scheduling.enabled=false",
        "sfl.emergency.messaging.drainer-enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable", disabledReason = "No PostgreSQL available")
class AuditTimestampPrecisionEndToEndTest extends SafetySecurityPostgresSupport {

    /** .123999600 seconds: 123.9996 ms, which PostgreSQL stores as .124000 s - the next millisecond. */
    private static final Instant JUST_SHORT_OF_A_MILLISECOND = Instant.parse("2026-10-01T12:00:00.123999600Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private static ActorContext actor() {
        return new ActorContext(new SiteScopedPrincipal("audit-probe", "probe", Set.of(SflRole.SFL_ADMIN), Set.of("*"), false), "corr-audit-probe");
    }

    @Test
    @DisplayName("a shared safety-security audit record's hash matches its stored values for an instant that rounds across a millisecond boundary")
    void platform_chain() {
        AuditAdapter audit = new AuditAdapter(jdbc, json, Clock.fixed(JUST_SHORT_OF_A_MILLISECOND, ZoneOffset.UTC));
        String probe = "AUDIT-PROBE-" + java.util.UUID.randomUUID();

        audit.record(actor(), "WEB", probe, "PRECISION_PROBE", "Probe", "p-1", null, "after", null);
        audit.record(actor(), "WEB", probe, "PRECISION_PROBE", "Probe", "p-2", null, "after", null);

        // Each record is checked on its own, from what was stored. The whole table is not replayed: other suites share it, and one of
        // them deliberately writes two overlapping entries that are not linked to each other.
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM safety_security.audit_log WHERE site_scope = ? ORDER BY sequence_no", probe);
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            String canonical = row.get("sequence_no") + "|" + row.get("actor") + "|" + row.get("action") + "|" + row.get("resource_type") + "|" + row.get("resource_id")
                    + "|" + row.get("site_scope") + "|" + row.get("source_channel") + "|"
                    + ((java.sql.Timestamp) row.get("occurred_at")).toInstant().toEpochMilli() + "|" + (row.get("previous_hash") == null ? "" : row.get("previous_hash"));
            assertThat(row.get("record_hash")).as("record %s", row.get("sequence_no")).isEqualTo(sha256(canonical));
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the emergency audit chain replays intact for the same instant")
    void emergency_chain() {
        JdbcAuditAdapter audit = new JdbcAuditAdapter(jdbc, json, Clock.fixed(JUST_SHORT_OF_A_MILLISECOND, ZoneOffset.UTC));

        audit.record(actor(), SourceChannel.WEB, "AUDIT-PROBE", "PRECISION_PROBE", "Probe", "p-1", null, "after", null);
        audit.record(actor(), SourceChannel.WEB, "AUDIT-PROBE", "PRECISION_PROBE", "Probe", "p-2", null, "after", null);

        assertThat(audit.verifyChain().intact()).isTrue();
    }
}

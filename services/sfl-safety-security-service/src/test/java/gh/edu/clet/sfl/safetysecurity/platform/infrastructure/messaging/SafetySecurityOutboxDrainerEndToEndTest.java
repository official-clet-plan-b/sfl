package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The {@code safety_security} drainer, against a real PostgreSQL - ADR 0010.
 *
 * <p>Real database on purpose, as facilities' drainer test is: {@code FOR UPDATE SKIP LOCKED}, the
 * {@code payload::text} cast off {@code jsonb} and the V18 partial index are all things an in-memory
 * double would pretend to do.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        // Off, so the scheduled tick cannot race the assertions; the test drives drain() itself.
        "sfl.safety-security.messaging.drainer-enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class SafetySecurityOutboxDrainerEndToEndTest extends SafetySecurityPostgresSupport {

    /** A transport the test can break on demand, and that records what it was asked to send. */
    static final class RecordingTransport implements SafetySecurityEventTransport {
        final List<SafetySecurityOutboxMessage> sent = new ArrayList<>();
        final AtomicBoolean failing = new AtomicBoolean(false);

        @Override
        public void send(SafetySecurityOutboxMessage message) {
            if (failing.get()) {
                throw new IllegalStateException("broker unavailable");
            }
            sent.add(message);
        }

        @Override
        public String name() {
            return "recording";
        }
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private RecordingTransport transport;
    private SafetySecurityOutboxDrainer drainer;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        jdbc.update("DELETE FROM safety_security.outbox_messages WHERE aggregate_type = 'DrainerTest'");
        drainer = new SafetySecurityOutboxDrainer(jdbc, transport, transactionManager, clock, 3, 100,
                Duration.ofSeconds(30), Duration.ofHours(1));
    }

    @Test
    void a_pending_message_is_sent_and_marked_published() {
        UUID id = insertPending("sfl.ssemp.risk-assessment-published.v1");

        drainer.drain();

        // `contains`, not `containsExactly`: the shared e2e database carries pending rows from other
        // suites, and draining those too is correct behaviour.
        assertThat(transport.sent).filteredOn(message -> message.id().equals(id)).singleElement()
                .satisfies(message -> {
                    assertThat(message.eventType()).isEqualTo("sfl.ssemp.risk-assessment-published.v1");
                    // Stored as jsonb; the `payload::text` cast in the claim is why this arrives as text.
                    assertThat(message.payload()).contains("\"probe\"");
                    assertThat(message.siteScope()).isEqualTo("E2E-SITE");
                    assertThat(message.correlationId()).isEqualTo("corr-" + id);
                });

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
    }

    @Test
    void a_failed_delivery_is_retried_with_backoff_rather_than_immediately() {
        UUID id = insertPending("sfl.ssemp.risk-assessment-signed-off.v1");
        transport.failing.set(true);

        drainer.drain();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(row.get("failure_reason")).isEqualTo("broker unavailable");
        assertThat(row.get("next_attempt_at")).isNotNull();

        // A second tick inside the backoff window must not pick it up again.
        drainer.drain();
        assertThat(((Number) row(id).get("attempt_count")).intValue()).isEqualTo(1);
    }

    @Test
    void a_message_that_keeps_failing_is_dead_lettered_and_stops_blocking_the_queue() {
        UUID poison = insertPending("sfl.ssemp.risk-assessment-review-lapsed.v1");
        transport.failing.set(true);

        // max-attempts is 3. Clear the backoff between ticks so the test drives attempts, not the clock.
        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("UPDATE safety_security.outbox_messages SET next_attempt_at = NULL WHERE id = ?", poison);
            drainer.drain();
        }

        Map<String, Object> row = row(poison);
        assertThat(row.get("status")).isEqualTo("DEAD_LETTERED");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(3);
        assertThat(row.get("dead_lettered_at")).isNotNull();
        assertThat(row.get("next_attempt_at")).isNull();

        // And the queue behind it moves.
        transport.failing.set(false);
        UUID healthy = insertPending("sfl.ssemp.risk-assessment-superseded.v1");
        drainer.drain();

        assertThat(transport.sent).extracting(SafetySecurityOutboxMessage::id).contains(healthy)
                .doesNotContain(poison);
        assertThat(row(healthy).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    void a_row_written_by_the_existing_publisher_is_drainable_without_any_change_to_the_writer() {
        // V18 added the delivery columns with defaults, so OutboxEventPublisher - unchanged - still writes
        // rows the drainer can claim. If a default were missing, this insert would fail, not the drain.
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO safety_security.outbox_messages (id,event_type,event_version,aggregate_type,
                    aggregate_id,site_scope,correlation_id,causation_id,payload,status,created_at)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,'PENDING',?)
                """, id, "sfl.ssemp.visitor-checked-in.v1", 1, "DrainerTest", id.toString(), "E2E-SITE",
                "corr-" + id, null, "{\"probe\":\"writer\"}",
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        drainer.drain();

        assertThat(row(id).get("status")).isEqualTo("PUBLISHED");
    }

    private UUID insertPending(String eventType) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO safety_security.outbox_messages
                    (id, event_type, event_version, aggregate_type, aggregate_id, site_scope,
                     correlation_id, causation_id, payload, status, created_at, attempt_count)
                VALUES (?, ?, 1, 'DrainerTest', ?, 'E2E-SITE', ?, ?, ?::jsonb, 'PENDING', ?, 0)
                """,
                id, eventType, id.toString(), "corr-" + id, "cause-" + id,
                "{\"probe\":\"" + id + "\"}", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM safety_security.outbox_messages WHERE id = ?", id);
    }
}

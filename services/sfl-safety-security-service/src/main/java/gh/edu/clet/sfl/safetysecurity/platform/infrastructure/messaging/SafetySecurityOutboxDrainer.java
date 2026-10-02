package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes what the {@code safety_security} modules record. The half of the outbox this schema never
 * had.
 *
 * <p>S160, S160a, S161, S162, S162a and S163 have written to {@code safety_security.outbox_messages}
 * inside their business transactions since V1, and nothing read the table: every event was recorded and
 * none was delivered. S165 is the first SSEMP system with a consumer waiting in another service -
 * facilities' S173 and S176 projections of the risk-assessment events, which refuse every higher-risk
 * confirmation until they hear something - so the drainer arrives with it (ADR 0010). S174's own outbox
 * in {@code emergency_notification} has its own drainer and is not touched here.
 *
 * <p>A port of facilities' {@code FacilitiesOutboxDrainer}, deliberately unchanged in mechanism:
 * <ul>
 *   <li><strong>One message, one transaction, claimed with {@code FOR UPDATE SKIP LOCKED}</strong>, so a
 *       second instance steps over rows the first is holding, and one poison payload cannot roll back the
 *       deliveries before it. The transaction is a {@link TransactionTemplate} because Spring proxies do
 *       not intercept self-invocation.</li>
 *   <li><strong>Backoff, not a tight retry loop.</strong> The delay doubles from {@code retry-base} and
 *       is capped.</li>
 *   <li><strong>Poison after N.</strong> A message that fails {@code max-attempts} times is
 *       {@code DEAD_LETTERED} with its reason rather than retried forever.</li>
 * </ul>
 * Delivery is at-least-once, which is why every consumer claims the message id in its inbox first.
 */
@Component
@ConditionalOnProperty(name = "sfl.safety-security.messaging.drainer-enabled", havingValue = "true",
        matchIfMissing = true)
public class SafetySecurityOutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(SafetySecurityOutboxDrainer.class);

    private final JdbcTemplate jdbc;
    private final SafetySecurityEventTransport transport;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration retryBase;
    private final Duration retryCap;

    public SafetySecurityOutboxDrainer(JdbcTemplate jdbc, SafetySecurityEventTransport transport,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${sfl.safety-security.messaging.max-attempts:5}") int maxAttempts,
            @Value("${sfl.safety-security.messaging.batch-size:100}") int batchSize,
            @Value("${sfl.safety-security.messaging.retry-base:PT10S}") Duration retryBase,
            @Value("${sfl.safety-security.messaging.retry-cap:PT1H}") Duration retryCap) {
        this.jdbc = jdbc;
        this.transport = transport;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.retryBase = retryBase;
        this.retryCap = retryCap;
    }

    @Scheduled(fixedDelayString = "${sfl.safety-security.messaging.drain-delay:PT10S}",
            initialDelayString = "${sfl.safety-security.messaging.drain-initial-delay:PT15S}")
    public void drain() {
        int published = 0;
        for (int processed = 0; processed < batchSize; processed++) {
            Outcome outcome = transactions.execute(status -> drainNext());
            if (outcome == Outcome.EMPTY) {
                break;
            }
            if (outcome == Outcome.PUBLISHED) {
                published++;
            }
        }
        if (published > 0) {
            log.info("Drained {} SSEMP outbox messages via {}", published, transport.name());
        }
    }

    private enum Outcome { PUBLISHED, FAILED, EMPTY }

    private Outcome drainNext() {
        List<SafetySecurityOutboxMessage> claimed = jdbc.query("""
                SELECT id, event_type, event_version, aggregate_type, aggregate_id, site_scope,
                       correlation_id, causation_id, payload::text AS payload
                  FROM safety_security.outbox_messages
                 WHERE status = 'PENDING'
                   AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                 ORDER BY created_at
                 LIMIT 1
                   FOR UPDATE SKIP LOCKED
                """,
                (rs, n) -> new SafetySecurityOutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("site_scope"),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id"),
                        rs.getString("payload")),
                now());

        if (claimed.isEmpty()) {
            return Outcome.EMPTY;
        }
        SafetySecurityOutboxMessage message = claimed.get(0);
        try {
            transport.send(message);
            jdbc.update("""
                    UPDATE safety_security.outbox_messages
                       SET status = 'PUBLISHED', published_at = ?, last_attempt_at = ?,
                           attempt_count = attempt_count + 1, failure_reason = NULL, next_attempt_at = NULL
                     WHERE id = ?
                    """, now(), now(), message.id());
            return Outcome.PUBLISHED;
        } catch (RuntimeException exception) {
            recordFailure(message, exception);
            return Outcome.FAILED;
        }
    }

    private void recordFailure(SafetySecurityOutboxMessage message, RuntimeException exception) {
        Integer previous = jdbc.queryForObject(
                "SELECT attempt_count FROM safety_security.outbox_messages WHERE id = ?", Integer.class,
                message.id());
        int attempts = (previous == null ? 0 : previous) + 1;
        boolean poisoned = attempts >= maxAttempts;
        Instant retryAt = nextAttempt(attempts);

        jdbc.update("""
                UPDATE safety_security.outbox_messages
                   SET attempt_count = ?, status = ?, failure_reason = ?, last_attempt_at = ?,
                       next_attempt_at = ?, dead_lettered_at = ?
                 WHERE id = ?
                """,
                attempts,
                poisoned ? "DEAD_LETTERED" : "PENDING",
                truncate(exception.getMessage()),
                now(),
                poisoned ? null : OffsetDateTime.ofInstant(retryAt, ZoneOffset.UTC),
                poisoned ? now() : null,
                message.id());

        if (poisoned) {
            log.error("SSEMP outbox message {} ({}) dead-lettered after {} attempts: {}", message.id(),
                    message.eventType(), attempts, exception.getMessage());
        } else {
            log.warn("SSEMP outbox delivery for {} ({}) failed on attempt {}, next attempt at {}", message.id(),
                    message.eventType(), attempts, retryAt);
        }
    }

    /** Doubles from the base and stops at the cap, so a long outage does not schedule a retry next year. */
    private Instant nextAttempt(int attempts) {
        Duration delay = retryBase.multipliedBy(1L << Math.min(attempts - 1, 16));
        return clock.instant().plus(delay.compareTo(retryCap) > 0 ? retryCap : delay);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** {@code failure_reason} is VARCHAR(2000); a driver stack trace will exceed it. */
    private static String truncate(String reason) {
        if (reason == null) {
            return "No failure message";
        }
        return reason.length() <= 2000 ? reason : reason.substring(0, 2000);
    }
}

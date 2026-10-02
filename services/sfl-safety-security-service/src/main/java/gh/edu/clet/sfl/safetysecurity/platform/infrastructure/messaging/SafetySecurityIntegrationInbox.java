package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The idempotent consumer, backed by {@code safety_security.inbox_messages} - created in V1 and never
 * written to until now.
 *
 * <p>Outbox plus a broker is at-least-once: a message will arrive twice, so every consumer has to be safe
 * to run twice, and the cheapest way is to remember what has been seen. The claim is the insert itself -
 * {@code message_id} is the primary key, so two concurrent deliveries race on the database and exactly
 * one wins.
 *
 * <p>The claim commits with the handler's work ({@code REQUIRED}): if the handler throws, the claim rolls
 * back with it and the redelivery is processed rather than swallowed.
 *
 * <p>The key is the message, not the message and the consumer - the same limitation facilities' inbox
 * records. One listener dispatches to every interested handler under one claim, so that is correct while
 * the listener is the only consumer in the service.
 *
 * <p>Not the module-specific vendor inboxes ({@code LifeSafetyIntegrationInbox} and its siblings): those
 * authenticate HMAC-signed vendor webhooks. This one deduplicates broker messages from sibling SFL
 * services, which the broker connection itself authenticates.
 */
@Component
public class SafetySecurityIntegrationInbox {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SafetySecurityIntegrationInbox(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return true when this delivery is the first and the caller should process it; false when it has
     *     already been handled and the caller should acknowledge and do nothing.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean claim(UUID messageId, String consumerName, String eventType, String correlationId) {
        try {
            jdbc.update("""
                    INSERT INTO safety_security.inbox_messages
                        (message_id, consumer_name, event_type, processed_at, correlation_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, messageId, consumerName, eventType, Timestamp.from(clock.instant()), correlationId);
            return true;
        } catch (DuplicateKeyException alreadySeen) {
            return false;
        }
    }
}

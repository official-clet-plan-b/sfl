package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import java.util.UUID;

/**
 * One {@code safety_security.outbox_messages} row, as the transport needs to see it.
 *
 * <p>Read through {@code JdbcTemplate}, like the writer ({@link OutboxEventPublisher}) and like
 * facilities' drainer, because the drainer claims rows with {@code FOR UPDATE SKIP LOCKED} and writes
 * back the V18 delivery-state columns.
 */
public record SafetySecurityOutboxMessage(
        UUID id,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String siteScope,
        String correlationId,
        String causationId,
        String payload) {
}

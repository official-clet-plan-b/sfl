package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.IntegrationEventPublisher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes events to {@code safety_security.outbox_messages} inside the caller's transaction, atomic
 * with the state change. Shared by every module in the schema - see {@code V1__service_foundation}
 * for the table, which was scaffolded before any module used it, and {@code
 * platform.application.port.IntegrationEventPublisher} for why the event type is a plain string here.
 *
 * <p>{@link SafetySecurityOutboxDrainer} delivers what this writes (ADR 0010). Until Phase 2 nothing did:
 * events were recorded and never delivered, which was tolerable only while no other service waited on
 * one. S165's risk-assessment events have consumers in facilities, so the drainer arrived with it.
 */
@Component
public class OutboxEventPublisher implements IntegrationEventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public OutboxEventPublisher(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String eventType, int eventVersion, String aggregateType, String aggregateId,
            String siteScope, ActorContext actor, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO safety_security.outbox_messages (id,event_type,event_version,aggregate_type,
                    aggregate_id,site_scope,correlation_id,causation_id,payload,status,created_at)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,'PENDING',?)
                """, UUID.randomUUID(), eventType, eventVersion, aggregateType, aggregateId, siteScope,
                actor == null ? null : actor.correlationId(), null,
                json.writeValueAsString(payload == null ? Map.of() : payload),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}

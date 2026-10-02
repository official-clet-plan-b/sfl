package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import gh.edu.clet.sfl.safetysecurity.platform.application.integration.InboundIntegrationEvent;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.IntegrationEventHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Every inbound event, one door - the SSEMP twin of facilities' {@code FacilitiesIntegrationListener}.
 *
 * <p>Receives anything the queue is bound to, turns it into an {@link InboundIntegrationEvent}, claims it
 * in the inbox, and gives it to whichever {@link IntegrationEventHandler} beans want it. Adding a reaction
 * is a handler bean and nothing else.
 *
 * <p>The inbox row is written only when a handler wants the event. Recording every overheard message
 * would mean a handler added later found its backlog already marked processed.
 *
 * <p>Runs on a platform thread (see {@code SafetySecurityMessagingConfiguration}), so a handler writing to
 * a policy-carrying table is scoped to {@code *} rather than to nothing.
 */
@Component
@ConditionalOnProperty(name = "sfl.safety-security.messaging.transport", havingValue = "rabbitmq")
public class SafetySecurityIntegrationListener {

    private static final Logger log = LoggerFactory.getLogger(SafetySecurityIntegrationListener.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
    };

    /** One consumer name for the service; the inbox key is the message, not the handler. */
    static final String CONSUMER = "safety-security.integration";

    private final List<IntegrationEventHandler> handlers;
    private final SafetySecurityIntegrationInbox inbox;
    private final ObjectMapper json;

    public SafetySecurityIntegrationListener(List<IntegrationEventHandler> handlers,
            SafetySecurityIntegrationInbox inbox, ObjectMapper json) {
        this.handlers = handlers;
        this.inbox = inbox;
        this.json = json;
    }

    @RabbitListener(queues = SafetySecurityInboundMessaging.INBOUND_QUEUE)
    @Transactional
    public void onIntegrationEvent(Message message) {
        String eventType = header(message, "eventType");
        if (eventType == null) {
            log.error("Discarding an inbound message with no eventType header");
            return;
        }

        List<IntegrationEventHandler> interested = handlers.stream()
                .filter(handler -> handler.handles(eventType))
                .toList();
        if (interested.isEmpty()) {
            log.trace("No handler for {}", eventType);
            return;
        }

        UUID messageId = messageId(message);
        if (messageId == null) {
            log.error("Discarding {} - no usable message id, so it cannot be deduplicated", eventType);
            return;
        }

        String correlationId = message.getMessageProperties().getCorrelationId();
        if (!inbox.claim(messageId, CONSUMER, eventType, correlationId)) {
            log.debug("Already handled {} ({}); acknowledging without acting", messageId, eventType);
            return;
        }

        Map<String, Object> payload;
        try {
            payload = json.readValue(message.getBody(), PAYLOAD);
        } catch (RuntimeException unreadable) {
            // Never retryable. The claim stays committed so this cannot come back around and loop.
            log.error("Unreadable payload for {} ({})", eventType, messageId, unreadable);
            return;
        }

        InboundIntegrationEvent event = new InboundIntegrationEvent(
                messageId,
                eventType,
                header(message, "aggregateType"),
                header(message, "aggregateId"),
                header(message, "siteCode"),
                correlationId,
                header(message, "causationId"),
                payload);

        // Anything thrown propagates: the transaction takes the claim back with it and the broker
        // redelivers, which is what a transient failure needs.
        interested.forEach(handler -> handler.handle(event));
    }

    /** The publisher sets the outbox row id as the message id. */
    private static UUID messageId(Message message) {
        String id = message.getMessageProperties().getMessageId();
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static String header(Message message, String name) {
        Object value = message.getMessageProperties().getHeader(name);
        return value == null ? null : value.toString();
    }
}

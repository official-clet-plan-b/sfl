package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RabbitMQ transport: exchange {@code sfl.events}, routing key {@code {platform}.{event-name}.v{version}},
 * and the same headers facilities' and fleet's transports send, so facilities' listener - which already
 * binds {@code ssemp.#} - reads SSEMP messages with no special case.
 *
 * <p>Waits for the broker, not the socket: with {@code publisher-confirm-type: correlated} and
 * {@code publisher-returns: true} (application.yml), a publish is only treated as delivered once the
 * broker has acked it and has not returned it as unroutable. Anything less is thrown back to the
 * drainer's retry/dead-letter path. See facilities' {@code AmqpFacilitiesEventTransport} for the full
 * reasoning; this is the same mechanism.
 */
final class AmqpSafetySecurityEventTransport implements SafetySecurityEventTransport {

    /** What a consumer sees as the publisher. Matches the catalogue's programme name. */
    static final String SOURCE_MODULE = "SFL.SSEMP";

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final Duration confirmTimeout;

    AmqpSafetySecurityEventTransport(RabbitTemplate rabbitTemplate, String exchange, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void send(SafetySecurityOutboxMessage outboxMessage) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(outboxMessage.id().toString());
        properties.setType(outboxMessage.eventType());
        properties.setCorrelationId(outboxMessage.correlationId());
        properties.setHeader("eventType", outboxMessage.eventType());
        properties.setHeader("eventVersion", outboxMessage.eventVersion());
        properties.setHeader("aggregateType", outboxMessage.aggregateType());
        properties.setHeader("aggregateId", outboxMessage.aggregateId());
        properties.setHeader("siteCode", outboxMessage.siteScope());
        properties.setHeader("causationId", outboxMessage.causationId());
        properties.setHeader("sourceModule", SOURCE_MODULE);

        Message message = MessageBuilder
                .withBody(outboxMessage.payload().getBytes(StandardCharsets.UTF_8))
                .andProperties(properties)
                .build();

        CorrelationData correlationData = new CorrelationData(outboxMessage.id().toString());
        rabbitTemplate.send(exchange, routingKeyOf(outboxMessage.eventType()), message, correlationData);
        awaitConfirmation(outboxMessage, correlationData);
    }

    private void awaitConfirmation(SafetySecurityOutboxMessage outboxMessage, CorrelationData correlationData) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted waiting for broker confirmation of " + outboxMessage.id(), exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Failed waiting for broker confirmation of " + outboxMessage.id(), exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out after " + confirmTimeout
                    + " waiting for broker confirmation of " + outboxMessage.id());
        }

        if (correlationData.getReturned() != null) {
            throw new IllegalStateException("Message " + outboxMessage.id() + " (" + outboxMessage.eventType()
                    + ") was returned as unroutable: " + correlationData.getReturned().getReplyText());
        }
        if (confirm == null || !confirm.isAck()) {
            throw new IllegalStateException("Broker did not acknowledge publish of " + outboxMessage.id() + " ("
                    + outboxMessage.eventType() + "): "
                    + (confirm == null ? "no confirmation received" : confirm.getReason()));
        }
    }

    @Override
    public String name() {
        return "rabbitmq";
    }

    /** {@code sfl.ssemp.risk-assessment-published.v1} routes as {@code ssemp.risk-assessment-published.v1}. */
    static String routingKeyOf(String eventType) {
        return eventType.startsWith("sfl.") ? eventType.substring("sfl.".length()) : eventType;
    }
}

package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Proves {@link AmqpSafetySecurityEventTransport} against a real broker, and proves the one thing the
 * S165 consumers depend on: a message routed as {@code ssemp.*} reaches a queue bound the way facilities
 * binds its inbound queue ({@code ssemp.#}), carrying the headers facilities' listener reads.
 *
 * <p>Skipped, not failed, when no broker is reachable - the same convention and properties as
 * {@code AmqpEmergencyEventTransportBrokerIntegrationTest}.
 */
class AmqpSafetySecurityEventTransportBrokerIntegrationTest {

    private static final String HOST = property("SFL_TEST_RABBITMQ_HOST", "localhost");
    private static final int PORT = Integer.parseInt(property("SFL_TEST_RABBITMQ_PORT", "5672"));
    private static final String USERNAME = property("SFL_TEST_RABBITMQ_USERNAME", "sfl");
    private static final String PASSWORD = property("SFL_TEST_RABBITMQ_PASSWORD", "sfl");

    private CachingConnectionFactory connectionFactory;

    static boolean brokerAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), 500);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    @AfterEach
    void closeConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void an_ssemp_event_reaches_a_queue_bound_the_way_facilities_binds_and_carries_its_headers() {
        assumeTrue(brokerAvailable(), "No RabbitMQ broker reachable at " + HOST + ":" + PORT);

        String exchangeName = "sfl.events.test." + UUID.randomUUID();
        String queueName = "sfl.ifimp.inbound.test." + UUID.randomUUID();
        connectionFactory = realBrokerConnectionFactory();
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(exchangeName, false, true);
        Queue queue = new Queue(queueName, false, false, true);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("ssemp.#"));

        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        SafetySecurityEventTransport transport =
                SafetySecurityEventTransports.rabbitMq(rabbitTemplate, exchangeName, Duration.ofSeconds(5));
        SafetySecurityOutboxMessage outboxMessage = message();

        transport.send(outboxMessage);

        Message received = rabbitTemplate.receive(queueName, 2000);
        assertThat(received).as("ssemp.* routed to a queue bound to ssemp.#").isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(outboxMessage.payload());
        assertThat(received.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo("ssemp.risk-assessment-published.v1");
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(outboxMessage.id().toString());
        assertThat((Object) received.getMessageProperties().getHeader("eventType"))
                .isEqualTo("sfl.ssemp.risk-assessment-published.v1");
        assertThat((Object) received.getMessageProperties().getHeader("siteCode")).isEqualTo("MAIN");
        assertThat((Object) received.getMessageProperties().getHeader("sourceModule")).isEqualTo("SFL.SSEMP");

        admin.deleteQueue(queueName);
        admin.deleteExchange(exchangeName);
    }

    @Test
    void an_unroutable_publish_fails_loudly_rather_than_marking_the_row_published() {
        assumeTrue(brokerAvailable(), "No RabbitMQ broker reachable at " + HOST + ":" + PORT);

        String exchangeName = "sfl.events.test." + UUID.randomUUID();
        connectionFactory = realBrokerConnectionFactory();
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.declareExchange(new TopicExchange(exchangeName, false, true));
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        SafetySecurityEventTransport transport =
                SafetySecurityEventTransports.rabbitMq(rabbitTemplate, exchangeName, Duration.ofSeconds(5));

        // Exchange exists, nothing bound: the broker returns the message, and the drainer must retry it.
        assertThatThrownBy(() -> transport.send(message())).hasMessageContaining("unroutable");

        admin.deleteExchange(exchangeName);
    }

    private static CachingConnectionFactory realBrokerConnectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory(HOST, PORT);
        factory.setUsername(USERNAME);
        factory.setPassword(PASSWORD);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    private static SafetySecurityOutboxMessage message() {
        return new SafetySecurityOutboxMessage(UUID.randomUUID(), "sfl.ssemp.risk-assessment-published.v1", 1,
                "RiskAssessment", "ra-broker-it-1", "MAIN", "corr-broker-it-1", null,
                "{\"assessmentId\":\"ra-broker-it-1\",\"version\":1}");
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}

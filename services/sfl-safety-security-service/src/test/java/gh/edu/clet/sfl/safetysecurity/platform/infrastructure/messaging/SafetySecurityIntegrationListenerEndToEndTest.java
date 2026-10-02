package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.InboundIntegrationEvent;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.IntegrationEventHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The inbound door, against the real {@code safety_security.inbox_messages} - ADR 0010.
 *
 * <p>Drives {@link SafetySecurityIntegrationListener} directly with AMQP {@link Message}s, so what is
 * proved is the listener's own contract (dispatch, claim, deduplicate) without needing a broker. The
 * broker leg is {@code AmqpSafetySecurityEventTransportBrokerIntegrationTest}'s.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class SafetySecurityIntegrationListenerEndToEndTest extends SafetySecurityPostgresSupport {

    private static final String HANDLED = "sfl.ifimp.listener-probe.v1";

    @Autowired private SafetySecurityIntegrationInbox inbox;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<InboundIntegrationEvent> handled = new ArrayList<>();
    private SafetySecurityIntegrationListener listener;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        handled.clear();
        IntegrationEventHandler recording = new IntegrationEventHandler() {
            @Override
            public boolean handles(String eventType) {
                return HANDLED.equals(eventType);
            }

            @Override
            public void handle(InboundIntegrationEvent event) {
                handled.add(event);
            }
        };
        listener = new SafetySecurityIntegrationListener(List.of(recording), inbox, json);
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void a_handled_event_reaches_its_handler_with_headers_and_payload_intact() {
        UUID messageId = UUID.randomUUID();

        deliver(message(messageId, HANDLED, "{\"workTypes\":[\"HOT_WORK\",\"FIT_OUT\"],\"projectId\":\"p-1\"}"));

        assertThat(handled).singleElement().satisfies(event -> {
            assertThat(event.messageId()).isEqualTo(messageId);
            assertThat(event.siteCode()).isEqualTo("E2E-SITE");
            assertThat(event.aggregateType()).isEqualTo("ConstructionProject");
            assertThat(event.text("projectId")).isEqualTo("p-1");
            assertThat(event.texts("workTypes")).containsExactly("HOT_WORK", "FIT_OUT");
        });
        assertThat(claims(messageId)).isEqualTo(1);
    }

    @Test
    void a_redelivered_message_is_acknowledged_without_acting_twice() {
        UUID messageId = UUID.randomUUID();
        Message message = message(messageId, HANDLED, "{}");

        deliver(message);
        deliver(message);

        assertThat(handled).hasSize(1);
        assertThat(claims(messageId)).isEqualTo(1);
    }

    @Test
    void an_overheard_event_is_dropped_without_claiming_it() {
        // Claiming events nobody handles would mark a future handler's backlog as already processed.
        UUID messageId = UUID.randomUUID();

        deliver(message(messageId, "sfl.ifimp.nobody-handles-this.v1", "{}"));

        assertThat(handled).isEmpty();
        assertThat(claims(messageId)).isZero();
    }

    @Test
    void a_handler_that_throws_takes_its_claim_back_so_the_redelivery_is_processed() {
        UUID messageId = UUID.randomUUID();
        IntegrationEventHandler failing = new IntegrationEventHandler() {
            @Override
            public boolean handles(String eventType) {
                return HANDLED.equals(eventType);
            }

            @Override
            public void handle(InboundIntegrationEvent event) {
                throw new IllegalStateException("transient");
            }
        };
        SafetySecurityIntegrationListener failingListener =
                new SafetySecurityIntegrationListener(List.of(failing), inbox, json);

        try {
            transaction.executeWithoutResult(status -> failingListener.onIntegrationEvent(
                    message(messageId, HANDLED, "{}")));
        } catch (IllegalStateException expected) {
            // The broker would redeliver.
        }
        assertThat(claims(messageId)).isZero();

        deliver(message(messageId, HANDLED, "{}"));
        assertThat(handled).hasSize(1);
    }

    private void deliver(Message message) {
        transaction.executeWithoutResult(status -> listener.onIntegrationEvent(message));
    }

    private int claims(UUID messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM safety_security.inbox_messages WHERE message_id = ?", Integer.class,
                messageId);
        return count == null ? 0 : count;
    }

    private static Message message(UUID messageId, String eventType, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId.toString());
        properties.setCorrelationId("corr-" + messageId);
        properties.setHeader("eventType", eventType);
        properties.setHeader("aggregateType", "ConstructionProject");
        properties.setHeader("aggregateId", "p-1");
        properties.setHeader("siteCode", "E2E-SITE");
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
    }
}

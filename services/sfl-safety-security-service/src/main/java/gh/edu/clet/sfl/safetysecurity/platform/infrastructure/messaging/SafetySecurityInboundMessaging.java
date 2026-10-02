package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSEMP's inbound queue. Until Phase 2 the service consumed nothing from any other - every gap report
 * since S156 records "SSEMP has no inbound event consumer anywhere in this codebase".
 *
 * <p>Bound by programme rather than by event name, as facilities is ({@code FacilitiesInboundMessaging}
 * explains why): writing a handler is then the only step to react to a new event, with no broker change.
 * The first handler is S165's coverage projection, which needs the activity types IFIMP systems actually
 * use (SRS-SFL-S165-03), so {@code ifimp.#} is bound. Add a programme here when a handler needs one.
 *
 * <p>Declared only when the transport is {@code rabbitmq}, so the default {@code local} deployment does
 * not try to declare a queue against a broker that is not there.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sfl.safety-security.messaging.transport", havingValue = "rabbitmq")
public class SafetySecurityInboundMessaging {

    /** One inbound queue for the service, named for its owner so the broker is readable. */
    public static final String INBOUND_QUEUE = "sfl.ssemp.inbound";

    private static final List<String> ROUTING_PATTERNS = List.of("ifimp.#");

    @Bean
    Declarables safetySecurityInboundTopology(
            @Value("${sfl.safety-security.messaging.exchange:sfl.events}") String exchangeName) {
        TopicExchange exchange = new TopicExchange(exchangeName, true, false);
        // Durable: an event published while SSEMP is restarting is still true when it comes back.
        Queue queue = QueueBuilder.durable(INBOUND_QUEUE).build();
        Stream<Binding> bindings = ROUTING_PATTERNS.stream()
                .map(pattern -> BindingBuilder.bind(queue).to(exchange).with(pattern));
        return new Declarables(Stream.concat(Stream.of(exchange, queue), bindings).toList());
    }
}

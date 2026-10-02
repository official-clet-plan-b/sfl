package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import java.time.Duration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Factory for the transports, so the implementations stay package-private. */
public final class SafetySecurityEventTransports {

    private SafetySecurityEventTransports() {
    }

    public static SafetySecurityEventTransport local() {
        return new LocalSafetySecurityEventTransport();
    }

    public static SafetySecurityEventTransport rabbitMq(RabbitTemplate rabbitTemplate, String exchange,
            Duration confirmTimeout) {
        return new AmqpSafetySecurityEventTransport(rabbitTemplate, exchange, confirmTimeout);
    }
}

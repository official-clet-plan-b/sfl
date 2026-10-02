package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records delivery without a broker. The development and single-node default.
 *
 * <p>It logs and returns, which marks the row published. That is honest only because it is named
 * {@code local} and selected deliberately: it claims the message left this service, not that anything
 * received it. Any environment where a consumer is expected - facilities' S173 and S176 projections of
 * S165, for one - must select {@code rabbitmq}.
 */
final class LocalSafetySecurityEventTransport implements SafetySecurityEventTransport {

    private static final Logger log = LoggerFactory.getLogger(LocalSafetySecurityEventTransport.class);

    @Override
    public void send(SafetySecurityOutboxMessage message) {
        log.debug("Recorded local delivery of {} for {} {}", message.eventType(), message.aggregateType(),
                message.aggregateId());
    }

    @Override
    public String name() {
        return "local";
    }
}

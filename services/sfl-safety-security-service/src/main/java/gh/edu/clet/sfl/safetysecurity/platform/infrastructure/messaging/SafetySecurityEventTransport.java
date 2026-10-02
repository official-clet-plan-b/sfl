package gh.edu.clet.sfl.safetysecurity.platform.infrastructure.messaging;

/**
 * The wire transport the {@code safety_security} outbox drainer delivers to.
 *
 * <p>Exactly one implementation is active, chosen by {@code sfl.safety-security.messaging.transport}.
 * Resolution is explicit configuration and never a silent fallback: selecting {@code rabbitmq} without a
 * broker fails at startup rather than letting the service run while quietly dropping every event.
 * Mirrors facilities' {@code FacilitiesEventTransport}.
 */
public interface SafetySecurityEventTransport {

    /**
     * Delivers one message. Throwing marks the attempt failed so the drainer retries with backoff and
     * eventually dead-letters - an implementation must never swallow a delivery failure.
     */
    void send(SafetySecurityOutboxMessage message);

    /** Transport name, for the drainer's log line. */
    String name();
}

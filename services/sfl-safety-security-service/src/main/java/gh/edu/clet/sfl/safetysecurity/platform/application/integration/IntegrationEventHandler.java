package gh.edu.clet.sfl.safetysecurity.platform.application.integration;

/**
 * What SSEMP does when another service says something happened.
 *
 * <p>Adding a cross-service reaction is one class implementing this interface. The queue, the
 * bindings, the parsing and the deduplication are generic and already done, once, in
 * {@code SafetySecurityIntegrationListener}. A handler is only the decision.
 *
 * <p>Mirrors facilities' {@code IntegrationEventHandler}, including its contract on failure.
 */
public interface IntegrationEventHandler {

    /** Whether this handler wants the event. A predicate, so one handler can take a family. */
    boolean handles(String eventType);

    /**
     * Acts on the event.
     *
     * <p>Called inside the listener's transaction, after the inbox has confirmed this is the first
     * delivery. Throw for anything worth retrying: the transaction rolls the inbox claim back with it and
     * the broker redelivers. Do not throw for a payload that can never be handled - log it and return,
     * or it will loop.
     */
    void handle(InboundIntegrationEvent event);
}

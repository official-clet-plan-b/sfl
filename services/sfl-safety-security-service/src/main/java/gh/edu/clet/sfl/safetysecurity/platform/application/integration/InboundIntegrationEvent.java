package gh.edu.clet.sfl.safetysecurity.platform.application.integration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An event that arrived from another SFL service, in the only shape a handler should need.
 *
 * <p>The SSEMP twin of facilities' {@code InboundIntegrationEvent}. Framework-free on purpose: nothing
 * here names a broker, so a handler compiles unchanged when the transport moves to the enterprise
 * integration layer (S217, Phase 2 SRS Section 3.5).
 *
 * @param messageId the publisher's outbox row id, and the key the inbox deduplicates on
 * @param eventType canonical name, e.g. {@code sfl.ifimp.construction-project-created.v1}
 * @param aggregateType the kind of record the event is about
 * @param aggregateId that record's id in the publishing service - held by value, never resolved
 * @param siteCode the site the event belongs to, so a handler can scope without parsing the payload
 * @param correlationId links this back to whatever started it, across services
 * @param payload the event body
 */
public record InboundIntegrationEvent(
        UUID messageId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String siteCode,
        String correlationId,
        String causationId,
        Map<String, Object> payload) {

    /** A payload field as text, or null. Absent and null read the same, which is what callers want. */
    public String text(String field) {
        Object value = payload == null ? null : payload.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * A payload field that is a JSON array, as its elements' text. A single scalar reads as a one-element
     * list, and anything absent as empty - a handler wanting "the work types on this event" should not
     * have to care which of those the publisher sent.
     */
    public List<String> texts(String field) {
        Object value = payload == null ? null : payload.get(field);
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> values) {
            return values.stream().filter(v -> v != null).map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }
}

package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.permit.domain.event.PermitEventType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.platform.application.port.IntegrationEventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The one place S164's event payloads are built. The first four carry exactly what S176's start gate reads - {@code permitId,
 * permitReference, siteCode, workType, validFrom, validTo, contractorReference, originReference, occurredAt} - and nothing about
 * the people on the permit.
 */
@Component
public class PermitEvents {

    private final IntegrationEventPublisher events;

    public PermitEvents(IntegrationEventPublisher events) {
        this.events = events;
    }

    public void issued(Permit permit, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_ISSUED, permit, at, actor, Map.of());
    }

    public void suspended(Permit permit, String reason, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_SUSPENDED, permit, at, actor, Map.of("reason", reason));
    }

    public void extended(Permit permit, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_EXTENDED, permit, at, actor, Map.of());
    }

    public void closed(Permit permit, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_CLOSED, permit, at, actor, Map.of());
    }

    public void expiring(Permit permit, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_EXPIRING, permit, at, actor, Map.of());
    }

    public void overdue(Permit permit, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_OVERDUE, permit, at, actor, Map.of());
    }

    public void flagged(Permit permit, String flagType, String reference, Instant at, ActorContext actor) {
        publish(PermitEventType.PERMIT_FLAGGED, permit, at, actor, Map.of("flagType", flagType, "flagReference", reference));
    }

    private void publish(PermitEventType type, Permit permit, Instant at, ActorContext actor, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("permitId", permit.id().toString());
        payload.put("permitReference", permit.reference());
        payload.put("siteCode", permit.siteCode());
        payload.put("workType", permit.workType());
        payload.put("validFrom", iso(permit.startsAt()));
        payload.put("validTo", iso(permit.endsAt()));
        payload.put("contractorReference", permit.contractorReference());
        payload.put("originReference", permit.originReference());
        payload.put("occurredAt", iso(at));
        payload.putAll(extra);
        events.publish(type.eventType(), type.version(), PermitEventType.AGGREGATE_TYPE, permit.id().toString(), permit.siteCode(), actor,
                payload);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}

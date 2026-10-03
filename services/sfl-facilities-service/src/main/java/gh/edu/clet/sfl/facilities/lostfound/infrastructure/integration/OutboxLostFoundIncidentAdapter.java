package gh.edu.clet.sfl.facilities.lostfound.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.lostfound.application.LostFoundEvents;
import gh.edu.clet.sfl.facilities.lostfound.application.ports.LostFoundIncidentPort;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The S163 incident request, by event. Recorded, not enforced: no S163 consumer exists yet. */
@Component
public class OutboxLostFoundIncidentAdapter implements LostFoundIncidentPort {

    static final String PROVIDER = "SSEMP-S163-OUTBOX-NO-CONSUMER";

    private static final Logger log = LoggerFactory.getLogger(OutboxLostFoundIncidentAdapter.class);

    private final ServiceOutbox outbox;

    public OutboxLostFoundIncidentAdapter(ServiceOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public Dispatch request(FoundItem item, String reason, ActorContext actor) {
        outbox.record(LostFoundEvents.INCIDENT_REQUESTED, 1, "FoundItem", item.id(), item.siteCode(),
                actor.correlationId(), actor.actorId(), LostFoundEvents.payload("itemId", item.id(), "reference",
                        item.reference(), "category", item.category()));
        log.warn("Found item {} at {} needs an incident ({}). RECORDED, NOT ENFORCED: no S163 consumer exists; link "
                + "the incident reference by hand.", item.reference(), item.siteCode(), reason);
        return new Dispatch(PROVIDER, false);
    }
}

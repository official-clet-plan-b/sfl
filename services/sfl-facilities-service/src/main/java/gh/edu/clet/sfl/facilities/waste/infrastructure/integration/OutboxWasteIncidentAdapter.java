package gh.edu.clet.sfl.facilities.waste.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import gh.edu.clet.sfl.facilities.waste.application.WasteEvents;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteIncidentPort;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The S163 incident request, by event. Recorded, not enforced: no S163 consumer exists yet. */
@Component
public class OutboxWasteIncidentAdapter implements WasteIncidentPort {

    static final String PROVIDER = "SSEMP-S163-OUTBOX-NO-CONSUMER";

    private static final Logger log = LoggerFactory.getLogger(OutboxWasteIncidentAdapter.class);

    private final ServiceOutbox outbox;

    public OutboxWasteIncidentAdapter(ServiceOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public Dispatch request(WasteException exception, ActorContext actor) {
        outbox.record(WasteEvents.INCIDENT_REQUESTED, 1, "WasteException", exception.id(), exception.siteCode(),
                actor.correlationId(), actor.actorId(), WasteEvents.payload("exceptionId", exception.id(),
                        "reference", exception.reference(), "type", exception.exceptionType(), "collectionId",
                        exception.collectionId()));
        log.warn("Waste exception {} at {} needs an incident. RECORDED, NOT ENFORCED: no S163 consumer exists; "
                + "link the incident reference by hand.", exception.reference(), exception.siteCode());
        return new Dispatch(PROVIDER, false);
    }
}

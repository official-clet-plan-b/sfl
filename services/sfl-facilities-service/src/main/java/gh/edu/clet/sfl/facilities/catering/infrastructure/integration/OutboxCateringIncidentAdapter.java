package gh.edu.clet.sfl.facilities.catering.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.catering.application.CateringEvents;
import gh.edu.clet.sfl.facilities.catering.application.ports.CateringIncidentPort;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The S163 incident request, by event. Recorded, not enforced: no S163 consumer exists yet. */
@Component
public class OutboxCateringIncidentAdapter implements CateringIncidentPort {

    static final String PROVIDER = "SSEMP-S163-OUTBOX-NO-CONSUMER";

    private static final Logger log = LoggerFactory.getLogger(OutboxCateringIncidentAdapter.class);

    private final ServiceOutbox outbox;

    public OutboxCateringIncidentAdapter(ServiceOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public Dispatch request(CateringException exception, ActorContext actor) {
        outbox.record(CateringEvents.INCIDENT_REQUESTED, 1, "CateringException", exception.id(), exception.siteCode(),
                actor.correlationId(), actor.actorId(), CateringEvents.payload("exceptionId", exception.id(),
                        "reference", exception.reference(), "type", exception.exceptionType(), "serviceId",
                        exception.serviceId()));
        log.warn("Catering exception {} at {} is a food-safety event. RECORDED, NOT ENFORCED: no S163 consumer exists;"
                + " link the incident reference by hand.", exception.reference(), exception.siteCode());
        return new Dispatch(PROVIDER, false);
    }
}

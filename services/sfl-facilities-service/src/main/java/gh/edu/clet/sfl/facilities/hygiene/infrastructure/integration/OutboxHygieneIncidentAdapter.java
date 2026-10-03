package gh.edu.clet.sfl.facilities.hygiene.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneEvents;
import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneIncidentPort;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The incident request to S163, by event. Recorded, and honest about it: the event goes to the outbox,
 * <strong>no S163 consumer exists yet</strong>, so no incident is created because of it and
 * {@code enforced} is always {@code false}. When S163 consumes the event the change is an acknowledgement
 * path that links the reference - not a change to this contract.
 */
@Component
public class OutboxHygieneIncidentAdapter implements HygieneIncidentPort {

    static final String PROVIDER = "SSEMP-S163-OUTBOX-NO-CONSUMER";

    private static final Logger log = LoggerFactory.getLogger(OutboxHygieneIncidentAdapter.class);

    private final ServiceOutbox outbox;

    public OutboxHygieneIncidentAdapter(ServiceOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public Dispatch request(HygieneFinding finding, ActorContext actor) {
        outbox.record(HygieneEvents.INCIDENT_REQUESTED, 1, "HygieneFinding", finding.id(), finding.siteCode(),
                actor.correlationId(), actor.actorId(), HygieneEvents.payload("findingId", finding.id(),
                        "findingReference", finding.reference(), "severity", finding.severity(), "category",
                        finding.category(), "roomId", finding.roomId()));
        log.warn("Hygiene finding {} at {} needs an incident. RECORDED, NOT ENFORCED: no S163 consumer exists; "
                + "link the incident reference by hand.", finding.reference(), finding.siteCode());
        return new Dispatch(PROVIDER, false);
    }
}

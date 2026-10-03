package gh.edu.clet.sfl.facilities.lostfound.application.ports;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;

/**
 * A request to Incident Reporting (S163) for an unsafe or suspicious item. S163 runs in another deployable, so
 * the request is recorded and {@code enforced} is false until a consumer exists; the escalation is left
 * pending-manual until someone links the incident reference. It never claims an incident that was not created.
 */
public interface LostFoundIncidentPort {

    Dispatch request(FoundItem item, String reason, ActorContext actor);

    record Dispatch(String provider, boolean enforced) {
    }
}

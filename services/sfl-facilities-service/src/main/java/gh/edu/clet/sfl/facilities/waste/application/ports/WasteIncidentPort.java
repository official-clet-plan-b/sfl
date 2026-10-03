package gh.edu.clet.sfl.facilities.waste.application.ports;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;

/**
 * A request to Incident Reporting (S163) for a spill. S163 runs in another deployable, so the request is
 * recorded and {@code enforced} is false until a consumer exists: the exception is left pending-manual
 * and someone links the incident reference by hand. Never reports an incident that was not created.
 */
public interface WasteIncidentPort {

    Dispatch request(WasteException exception, ActorContext actor);

    record Dispatch(String provider, boolean enforced) {
    }
}

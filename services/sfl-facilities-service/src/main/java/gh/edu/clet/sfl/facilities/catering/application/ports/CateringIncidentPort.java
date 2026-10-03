package gh.edu.clet.sfl.facilities.catering.application.ports;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;

/**
 * A request to Incident Reporting (S163) for a food-safety event. S163 runs in another deployable, so the
 * request is recorded and {@code enforced} is false until a consumer exists; the exception stays pending-manual
 * until someone links the incident reference.
 */
public interface CateringIncidentPort {

    Dispatch request(CateringException exception, ActorContext actor);

    record Dispatch(String provider, boolean enforced) {
    }
}

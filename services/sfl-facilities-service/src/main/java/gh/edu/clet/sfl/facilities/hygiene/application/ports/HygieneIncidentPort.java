package gh.edu.clet.sfl.facilities.hygiene.application.ports;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;

/**
 * A request to the Incident Reporting system (S163, SSEMP) for a finding that is also a safety incident -
 * SRS-SFL-S170-03.
 *
 * <p>S163 runs in another deployable, so S170 cannot create the incident and read its number back in one
 * transaction. The port says so in its return type: {@code enforced=false} means the request was
 * recorded for S163 and no incident exists yet, and the finding is left {@code PENDING_MANUAL} until
 * someone links the incident reference by hand.
 */
public interface HygieneIncidentPort {

    Dispatch request(HygieneFinding finding, ActorContext actor);

    record Dispatch(String provider, boolean enforced) {
    }
}

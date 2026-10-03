package gh.edu.clet.sfl.facilities.hygiene.application.ports;

import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import java.util.UUID;

/**
 * S153 as S170 needs it: a work order for a finding that needs physical correction - SRS-SFL-S170-03.
 *
 * <p>Implemented over {@code AutomatedWorkOrderIntake} by the one adapter allowed to name S153, so the
 * category, origin and idempotency key are decided in a single place and the finding rules can be tested
 * with a double that fails on demand.
 */
public interface HygieneWorkOrderPort {

    RaisedWorkOrder raise(HygieneFinding finding, HygieneControl control, String requestedBy, String correlationId);

    record RaisedWorkOrder(UUID workOrderId, String workOrderNumber) {
    }
}

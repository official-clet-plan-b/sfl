package gh.edu.clet.sfl.facilities.lease.application.ports;

import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import java.util.UUID;

/**
 * S153 as S177 needs it: corrective work for a lease exception. Implemented over the S153 intake by the one adapter
 * allowed to name it, so the category and idempotency key are decided once.
 */
public interface LeaseWorkOrderPort {

    RaisedWorkOrder raise(LeaseWorkOrder request, Agreement agreement, String requestedBy, String correlationId);

    record RaisedWorkOrder(UUID workOrderId, String workOrderNumber) {
    }
}

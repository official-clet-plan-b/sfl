package gh.edu.clet.sfl.facilities.waste.application.ports;

import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import java.util.UUID;

/**
 * S153 as S178 needs it: corrective work for a spill or a contaminated stream. Implemented over the S153
 * intake by the one adapter allowed to name it, so the category and idempotency key are decided once.
 */
public interface WasteWorkOrderPort {

    /**
     * @param roomId the S152 room of the collection point, if it has one
     * @param location where to send someone when there is no room - S153 refuses a fault with neither
     */
    RaisedWorkOrder raise(WasteException exception, UUID roomId, String location, String requestedBy,
            String correlationId);

    record RaisedWorkOrder(UUID workOrderId, String workOrderNumber) {
    }
}

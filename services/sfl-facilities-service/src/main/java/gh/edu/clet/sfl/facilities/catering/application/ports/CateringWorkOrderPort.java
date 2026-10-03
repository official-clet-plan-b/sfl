package gh.edu.clet.sfl.facilities.catering.application.ports;

import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import java.util.UUID;

/** S153 as S172 needs it: corrective work for a shortage, a service exception or a food-safety incident. */
public interface CateringWorkOrderPort {

    /** @param roomId the S152 room of the venue, if it has one; @param location where to send someone when there is no room */
    RaisedWorkOrder raise(CateringException exception, UUID roomId, String location, String requestedBy, String correlationId);

    record RaisedWorkOrder(UUID workOrderId, String workOrderNumber) {
    }
}

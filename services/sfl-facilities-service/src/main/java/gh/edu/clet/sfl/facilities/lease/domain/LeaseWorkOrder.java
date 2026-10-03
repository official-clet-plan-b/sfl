package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Corrective work raised in S153 because of a lease exception. {@code state} is RAISED only once S153 has answered
 * with an order number; until then it is PENDING_MANUAL and can be retried - never shown as raised.
 */
public record LeaseWorkOrder(UUID id, String siteCode, UUID agreementId, UUID obligationId, WorkOrderTrigger trigger,
        String description, String state, UUID workOrderId, String workOrderNumber, String requestedBy, Instant createdAt,
        Instant updatedAt, long version) {

    public boolean raised() {
        return "RAISED".equals(state);
    }

    public LeaseWorkOrder linked(UUID orderId, String number, Instant at) {
        return new LeaseWorkOrder(id, siteCode, agreementId, obligationId, trigger, description, "RAISED", orderId, number,
                requestedBy, createdAt, at, version);
    }
}

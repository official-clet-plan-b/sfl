package gh.edu.clet.sfl.facilities.lease.infrastructure.integration;

import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseWorkOrderPort;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import gh.edu.clet.sfl.facilities.maintenance.application.AutomatedWorkOrderIntake;
import gh.edu.clet.sfl.facilities.maintenance.domain.FaultPriority;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import org.springframework.stereotype.Component;

/**
 * Lease exceptions as S153 work orders - the one class in S177 that names S153. Category {@value #CATEGORY};
 * idempotent on {@code lease-work-order:<id>} so a retry returns the same order.
 */
@Component
public class S153LeaseWorkOrderAdapter implements LeaseWorkOrderPort {

    static final String CATEGORY = "LEASE_EXCEPTION";

    private final AutomatedWorkOrderIntake intake;

    public S153LeaseWorkOrderAdapter(AutomatedWorkOrderIntake intake) {
        this.intake = intake;
    }

    @Override
    public RaisedWorkOrder raise(LeaseWorkOrder request, Agreement agreement, String requestedBy, String correlationId) {
        // S153 refuses a fault with neither a room nor a location, so an agreement with no room names the property.
        AutomatedWorkOrderIntake.RaisedWorkOrder order = intake.raise(new AutomatedWorkOrderIntake.AutomatedWorkOrderRequest(
                agreement.siteCode(), agreement.roomId(), agreement.roomId() == null ? agreement.propertyReference() : null, null,
                "Lease " + agreement.reference() + " - " + request.trigger(), request.description(), CATEGORY, FaultPriority.HIGH,
                "S177", "agreement:" + agreement.reference(), null, requestedBy, null, null, SourceChannel.SYSTEM, correlationId,
                "lease-work-order:" + request.id()));
        return new RaisedWorkOrder(order.workOrderId(), order.workOrderNumber());
    }
}

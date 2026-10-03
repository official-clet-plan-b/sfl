package gh.edu.clet.sfl.facilities.waste.infrastructure.integration;

import gh.edu.clet.sfl.facilities.maintenance.application.AutomatedWorkOrderIntake;
import gh.edu.clet.sfl.facilities.maintenance.domain.FaultPriority;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteWorkOrderPort;
import gh.edu.clet.sfl.facilities.waste.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.waste.domain.WasteException;
import org.springframework.stereotype.Component;

/**
 * Waste exceptions as S153 work orders - the one class in S178 that names S153. Category
 * {@value #CATEGORY}; idempotent on {@code waste-exception:<id>} so a retry returns the same order.
 */
@Component
public class S153WasteWorkOrderAdapter implements WasteWorkOrderPort {

    static final String CATEGORY = "WASTE_EXCEPTION";

    private final AutomatedWorkOrderIntake intake;

    public S153WasteWorkOrderAdapter(AutomatedWorkOrderIntake intake) {
        this.intake = intake;
    }

    @Override
    public RaisedWorkOrder raise(WasteException exception, java.util.UUID roomId, String location, String requestedBy,
            String correlationId) {
        AutomatedWorkOrderIntake.RaisedWorkOrder order = intake.raise(new AutomatedWorkOrderIntake.AutomatedWorkOrderRequest(
                exception.siteCode(), roomId, roomId == null ? location : null, null,
                "Waste " + exception.reference() + " - " + exception.exceptionType(), exception.description(),
                CATEGORY, exception.exceptionType() == ExceptionType.SPILL ? FaultPriority.CRITICAL : FaultPriority.HIGH,
                "S178", "exception:" + exception.reference(), null, requestedBy, null, null, SourceChannel.SYSTEM,
                correlationId, "waste-exception:" + exception.id()));
        return new RaisedWorkOrder(order.workOrderId(), order.workOrderNumber());
    }
}

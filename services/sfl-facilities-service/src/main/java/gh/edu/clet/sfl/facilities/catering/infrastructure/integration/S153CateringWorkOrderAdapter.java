package gh.edu.clet.sfl.facilities.catering.infrastructure.integration;

import gh.edu.clet.sfl.facilities.catering.application.ports.CateringWorkOrderPort;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.maintenance.application.AutomatedWorkOrderIntake;
import gh.edu.clet.sfl.facilities.maintenance.domain.FaultPriority;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Catering exceptions as S153 work orders - the one class in S172 that names S153. Idempotent on {@code catering-exception:<id>}. */
@Component
public class S153CateringWorkOrderAdapter implements CateringWorkOrderPort {

    static final String CATEGORY = "CATERING_EXCEPTION";

    private final AutomatedWorkOrderIntake intake;

    public S153CateringWorkOrderAdapter(AutomatedWorkOrderIntake intake) {
        this.intake = intake;
    }

    @Override
    public RaisedWorkOrder raise(CateringException exception, UUID roomId, String location, String requestedBy, String correlationId) {
        AutomatedWorkOrderIntake.RaisedWorkOrder order = intake.raise(new AutomatedWorkOrderIntake.AutomatedWorkOrderRequest(
                exception.siteCode(), roomId, roomId == null ? location : null, null,
                "Catering " + exception.reference() + " - " + exception.exceptionType(), exception.description(), CATEGORY,
                exception.exceptionType() == ExceptionType.FOOD_SAFETY_INCIDENT ? FaultPriority.CRITICAL : FaultPriority.MEDIUM,
                "S172", "exception:" + exception.reference(), null, requestedBy, null, null, SourceChannel.SYSTEM, correlationId,
                "catering-exception:" + exception.id()));
        return new RaisedWorkOrder(order.workOrderId(), order.workOrderNumber());
    }
}

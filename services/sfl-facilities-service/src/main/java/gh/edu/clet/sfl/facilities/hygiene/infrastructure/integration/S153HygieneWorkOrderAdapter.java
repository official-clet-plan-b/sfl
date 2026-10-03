package gh.edu.clet.sfl.facilities.hygiene.infrastructure.integration;

import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneWorkOrderPort;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.maintenance.application.AutomatedWorkOrderIntake;
import gh.edu.clet.sfl.facilities.maintenance.domain.FaultPriority;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import org.springframework.stereotype.Component;

/**
 * Hygiene findings as S153 work orders - the one class in S170 that names S153.
 *
 * <p>Category {@value #CATEGORY} is what an S153 reader filters on to tell a hygiene correction from
 * ordinary maintenance. Idempotent on {@code hygiene-finding:<id>}, so a retried raise returns the same
 * order instead of a second one.
 */
@Component
public class S153HygieneWorkOrderAdapter implements HygieneWorkOrderPort {

    static final String CATEGORY = "HYGIENE_FINDING";

    private final AutomatedWorkOrderIntake intake;

    public S153HygieneWorkOrderAdapter(AutomatedWorkOrderIntake intake) {
        this.intake = intake;
    }

    @Override
    public RaisedWorkOrder raise(HygieneFinding finding, HygieneControl control, String requestedBy,
            String correlationId) {
        AutomatedWorkOrderIntake.RaisedWorkOrder order = intake.raise(new AutomatedWorkOrderIntake.AutomatedWorkOrderRequest(
                finding.siteCode(), finding.roomId(), location(control, finding), null,
                "Hygiene " + finding.reference() + " - " + finding.title(), finding.description(), CATEGORY,
                FaultPriority.valueOf(finding.severity().name()), "S170",
                "control:" + control.reference() + "/finding:" + finding.reference(), null, requestedBy, null, null,
                SourceChannel.SYSTEM, correlationId, "hygiene-finding:" + finding.id()));
        return new RaisedWorkOrder(order.workOrderId(), order.workOrderNumber());
    }

    /** S153 will not take a fault with no place to send anyone, so a finding with neither room nor label is placed at its site. */
    private static String location(HygieneControl control, HygieneFinding finding) {
        if (control.locationLabel() != null) {
            return control.locationLabel();
        }
        return finding.roomId() != null ? null : "SITE " + finding.siteCode();
    }
}

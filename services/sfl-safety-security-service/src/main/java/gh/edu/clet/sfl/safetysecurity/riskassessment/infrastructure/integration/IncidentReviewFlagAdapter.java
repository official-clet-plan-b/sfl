package gh.edu.clet.sfl.safetysecurity.riskassessment.infrastructure.integration;

import gh.edu.clet.sfl.safetysecurity.incident.application.contract.IncidentRiskObserver;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.ReviewFlagService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import org.springframework.stereotype.Component;

/**
 * S163 -> S165, SRS-SFL-S165-04: an incident's saved risk context flags the assessment(s) it identifies.
 *
 * <p>The only class in S165 allowed to import the incident module (asserted by
 * {@code RiskAssessmentArchitectureTest}). Same deployable, so this is a real in-process call inside the
 * incident's transaction, not a recorded stand-in: the flag commits with the incident, and a link naming no
 * assessment at the incident's site refuses the incident save rather than leaving a dangling reference.
 */
@Component
public class IncidentReviewFlagAdapter implements IncidentRiskObserver {

    private final ReviewFlagService flags;

    public IncidentReviewFlagAdapter(ReviewFlagService flags) {
        this.flags = flags;
    }

    /**
     * A link naming no assessment at the incident's site becomes an {@link IllegalArgumentException} with
     * S165's own wording: the incident API reports that as a 400 on the field the reporter got wrong, and it
     * has no reason to know S165's exception type.
     */
    @Override
    public void riskContextRecorded(IncidentRiskObserver.IncidentRiskContext context) {
        try {
            flags.flagFromIncident(new ReviewFlagService.IncidentRiskContext(context.incidentId(),
                    context.incidentReference(), context.siteCode(), context.riskAssessmentId(), context.activityType(),
                    context.actor()));
        } catch (RiskAssessmentException refused) {
            if (refused.errorCode() == RiskAssessmentErrorCode.RISK_ASSESSMENT_LINK_NOT_FOUND) {
                throw new IllegalArgumentException(refused.getMessage(), refused);
            }
            throw refused;
        }
    }
}

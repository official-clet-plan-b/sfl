package gh.edu.clet.sfl.safetysecurity.permit.application.port;

import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import java.time.Instant;
import java.util.UUID;

/**
 * What S164 needs from S165: whether an assessment may be relied on right now. Implemented over S165's published
 * {@code RiskAssessmentDirectory} by the one adapter allowed to name it. The verdict is S165's own currency rule - the same
 * logic S165-02 applies, not a second copy.
 */
public interface PermitRiskAssessmentPort {

    RiskAssessmentStanding standing(UUID assessmentId, String siteCode, Instant at);
}

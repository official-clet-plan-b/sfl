package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What S165 says about an assessment right now, in this module's own words. {@code current} is S165's verdict by the
 * shared currency rule; {@code reason} says why when it is not (the rule's own name for it).
 */
public record RiskAssessmentStanding(UUID assessmentId, boolean found, String reference, Integer version, String activityType,
        PermitRiskLevel riskLevel, Instant reviewDueAt, boolean current, String reason) {

    public static RiskAssessmentStanding notLinked() {
        return new RiskAssessmentStanding(null, false, null, null, null, null, null, false, "NONE_LINKED");
    }
}

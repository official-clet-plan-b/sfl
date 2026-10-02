package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SRS-SFL-S165-01 validation: "An assessment cannot be published without at least one control measure
 * per identified hazard."
 *
 * <p>Names every uncontrolled hazard, not just the first, so the author fixes them in one pass rather
 * than discovering them one refusal at a time. An assessment with no hazards at all is refused too: it
 * would satisfy "a control per hazard" vacuously and say nothing about the work.
 */
public final class PublishPolicy {

    private PublishPolicy() {
    }

    public static void requirePublishable(AssessmentContent content) {
        if (content.hazards().isEmpty()) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_NO_HAZARDS);
        }
        List<String> uncontrolled = uncontrolledHazards(content);
        if (!uncontrolled.isEmpty()) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_HAZARD_WITHOUT_CONTROL,
                    Map.of("hazardsWithoutControl", uncontrolled));
        }
    }

    /** "#2 Ladder access to roof" for each hazard with no control, 1-based as an author numbers them. */
    public static List<String> uncontrolledHazards(AssessmentContent content) {
        List<String> uncontrolled = new ArrayList<>();
        List<Hazard> hazards = content.hazards();
        for (int index = 0; index < hazards.size(); index++) {
            if (!hazards.get(index).hasControl()) {
                uncontrolled.add("#" + (index + 1) + " " + hazards.get(index).description());
            }
        }
        return uncontrolled;
    }
}

package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SignOff;
import java.util.List;

/**
 * Everything the detail screen shows about one assessment, in one read: the summary, every version
 * (newest first, superseded ones included and marked), the sign-off history and the review flags.
 */
public record AssessmentDetail(AssessmentSummary assessment, List<AssessmentVersion> versions,
        List<SignOff> signOffs, List<ReviewFlag> reviewFlags) {
}

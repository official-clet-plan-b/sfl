package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import java.util.Locale;
import java.util.Map;

/**
 * SRS-SFL-S165-02 validation: "A review sign-off must be by a named competent reviewer, not the original
 * author acting alone, for higher-risk assessments."
 *
 * <p>"Higher-risk" is whatever {@link RiskLevel#requiresIndependentReviewer()} says - the shared currency
 * rule's definition - so a sign-off this policy accepts is always one the currency check, here and in
 * every consumer's projection, will treat as independent. Two definitions would let an assessment be
 * signed off here and still read as not current in S173.
 */
public final class SignOffPolicy {

    private SignOffPolicy() {
    }

    public static void requireAcceptable(RiskLevel level, String authorId, String reviewerId) {
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("A sign-off needs a named reviewer");
        }
        if (level != null && level.requiresIndependentReviewer() && sameActor(authorId, reviewerId)) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_INDEPENDENT_REVIEW_REQUIRED,
                    Map.of("riskLevel", level.name()));
        }
    }

    public static boolean independent(String authorId, String reviewerId) {
        return !sameActor(authorId, reviewerId);
    }

    private static boolean sameActor(String authorId, String reviewerId) {
        return authorId != null && reviewerId != null
                && authorId.strip().toLowerCase(Locale.ROOT).equals(reviewerId.strip().toLowerCase(Locale.ROOT));
    }
}

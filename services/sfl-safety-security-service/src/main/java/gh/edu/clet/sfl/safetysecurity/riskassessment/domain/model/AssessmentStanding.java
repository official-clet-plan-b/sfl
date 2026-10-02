package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;

/**
 * Where an assessment stands for anyone deciding whether to rely on it - the dashboard's vocabulary for
 * {@link RiskAssessmentCurrency.Verdict}.
 *
 * <p>Derived, never stored: it is the currency rule's answer for the current version right now, and a
 * stored copy would be wrong the moment a review date passed between writes.
 */
public enum AssessmentStanding {
    /** Never published - only a draft exists. Nothing may link to it. */
    NO_PUBLISHED_VERSION,
    /** Published, in date, and independently signed off where its level requires it. */
    CURRENT,
    /** Published at HIGH or CRITICAL but not yet signed off by someone other than the author. */
    AWAITING_INDEPENDENT_SIGN_OFF,
    /** S165-02 "Lapsed Assessment": the review date passed without a renewed sign-off. */
    LAPSED;

    public static AssessmentStanding of(RiskAssessmentCurrency.Verdict verdict) {
        if (verdict.current()) {
            return CURRENT;
        }
        return switch (verdict.reason()) {
            case REVIEW_LAPSED -> LAPSED;
            case NOT_INDEPENDENTLY_SIGNED_OFF -> AWAITING_INDEPENDENT_SIGN_OFF;
            case NONE_LINKED, NOT_PUBLISHED, SUPERSEDED -> NO_PUBLISHED_VERSION;
        };
    }
}

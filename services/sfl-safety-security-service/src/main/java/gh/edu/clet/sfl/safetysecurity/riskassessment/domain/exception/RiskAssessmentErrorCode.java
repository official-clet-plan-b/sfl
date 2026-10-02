package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception;

/**
 * S165 error catalog, following {@code LifeSafetyErrorCode}'s shape. Where the SRS names an error state,
 * the message carries its wording - it is already written for the person who will read it.
 */
public enum RiskAssessmentErrorCode {

    RISK_ASSESSMENT_MISSING_SITE_SCOPE(400, "Select a valid CLET site before saving this risk assessment."),
    RISK_ASSESSMENT_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),
    RISK_ASSESSMENT_VALIDATION_FAILED(400, "The request failed validation."),
    RISK_ASSESSMENT_RECORD_NOT_FOUND(404, "The requested risk assessment record was not found."),
    RISK_ASSESSMENT_INVALID_STATE_TRANSITION(409, "The requested change is not allowed from the current state."),
    RISK_ASSESSMENT_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry."),

    /** SRS-SFL-S165-01: an assessment is scoped to an activity type and/or a specific S152 location. */
    RISK_ASSESSMENT_SCOPE_REQUIRED(400,
            "Scope the assessment to an activity type, an S152 location, or both."),
    /** SRS-SFL-S165-01 error state "Hazard Without Control". */
    RISK_ASSESSMENT_HAZARD_WITHOUT_CONTROL(409,
            "Hazard Without Control - every identified hazard needs at least one control measure before "
                    + "this assessment can be published."),
    RISK_ASSESSMENT_NO_HAZARDS(409, "Record at least one hazard before publishing this assessment."),
    RISK_ASSESSMENT_DRAFT_ALREADY_OPEN(409,
            "A draft revision of this assessment is already open. Edit that draft rather than starting another."),

    /** SRS-SFL-S165-02 error state "Lapsed Assessment". */
    RISK_ASSESSMENT_LAPSED(409,
            "Lapsed Assessment - the review date passed without a renewed sign-off. The assessment is not "
                    + "current and cannot be newly linked."),
    RISK_ASSESSMENT_NOT_CURRENT(409, "This risk assessment is not current and cannot be newly linked."),
    /** SRS-SFL-S165-02 validation: a higher-risk sign-off is by a named competent reviewer, not the author alone. */
    RISK_ASSESSMENT_INDEPENDENT_REVIEW_REQUIRED(409,
            "A higher-risk assessment must be signed off by a named competent reviewer, not by its author "
                    + "acting alone."),

    /** SRS-SFL-S165-04 error state "Flag Dismissed Without Review". */
    RISK_ASSESSMENT_REVIEW_FINDINGS_REQUIRED(400,
            "Flag Dismissed Without Review - a review flag is cleared only by a completed review with recorded "
                    + "findings."),
    RISK_ASSESSMENT_DEFERRAL_INVALID(400, "A deferral needs a named reason and a date in the future."),
    RISK_ASSESSMENT_LINK_NOT_FOUND(400, "The linked risk assessment was not found at this site."),
    RISK_ASSESSMENT_IDEMPOTENCY_KEY_CONFLICT(409,
            "This Idempotency-Key was already used with a different request payload.");

    private final int httpStatus;
    private final String message;

    RiskAssessmentErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }
}

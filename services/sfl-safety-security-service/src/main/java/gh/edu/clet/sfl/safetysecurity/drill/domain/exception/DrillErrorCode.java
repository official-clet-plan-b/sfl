package gh.edu.clet.sfl.safetysecurity.drill.domain.exception;

/**
 * S175 error catalog, following {@code RiskAssessmentErrorCode}'s shape. Where the SRS names an error state,
 * the message carries its wording - it is already written for the person who will read it.
 */
public enum DrillErrorCode {

    DRILL_MISSING_SITE_SCOPE(400, "Select a valid CLET site before saving this drill."),
    DRILL_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),
    DRILL_VALIDATION_FAILED(400, "The request failed validation."),
    DRILL_RECORD_NOT_FOUND(404, "The requested drill record was not found."),
    DRILL_INVALID_STATE_TRANSITION(409, "The requested change is not allowed from the drill's current state."),
    DRILL_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry."),
    DRILL_IDEMPOTENCY_KEY_CONFLICT(409, "This Idempotency-Key was already used with a different request payload."),
    DRILL_REASON_REQUIRED(400, "Give a reason - a postponed or cancelled drill keeps its plan and the reason why."),

    /** SRS-SFL-S175-01 error state "Test/Real Ambiguity". */
    DRILL_TEST_REAL_AMBIGUITY(409,
            "Test/Real Ambiguity - a drill is sent only with an S174 drill template that carries the drill "
                    + "marker; a configuration that could look like a real alert is refused at setup."),
    /** SRS-SFL-S175-01/-02: what a drill needs before it can be scheduled. */
    DRILL_NOT_READY(409, "This drill plan is not ready to schedule."),
    /** SRS-SFL-S175-05 validation: per-module expectations explicit in the plan before execution. */
    DRILL_EXPECTATIONS_REQUIRED(409,
            "A combined drill must name each participating module, what it is expected to do and how success "
                    + "is measured, before it runs."),

    /** SRS-SFL-S175-02: the gap list is carried into the after-action report, each gap followed up. */
    DRILL_GAPS_UNRESOLVED(409, "Every roll-call gap needs a follow-up outcome before the review is submitted."),
    /** SRS-SFL-S175-05: the review records whether each expectation was met. */
    DRILL_EXPECTATION_OUTCOME_REQUIRED(409,
            "Record whether each module expectation was met before the review is submitted."),
    DRILL_REVIEW_SUMMARY_REQUIRED(400, "Record the after-action review's summary before submitting it."),
    /** SRS-SFL-S175-03 error state "Unactioned Finding". */
    DRILL_UNACTIONED_FINDING(409,
            "Unactioned Finding - every finding needs a corrective action or an explicit no-action-required "
                    + "justification."),
    /** SRS-SFL-S175-03 validation. */
    DRILL_OVERDUE_ACTIONS_OPEN(409,
            "This drill has open, overdue corrective actions. Close them, or give an explicit deferral reason."),
    DRILL_FINDING_ACTIONED(409, "This finding already has a corrective action; it cannot also be marked no-action.");

    private final int httpStatus;
    private final String message;

    DrillErrorCode(int httpStatus, String message) {
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

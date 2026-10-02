package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.event;

/**
 * S165 integration events, canonical {@code sfl.ssemp.{name}.v1}.
 *
 * <p>The first four were reserved before S165 existed: facilities' S173 projection consumes them, and
 * their payloads are fixed by {@code docs/facilities/S173_Event_Contracts.md}. They are reused verbatim
 * rather than renamed. The last two are new, and catalogued alongside them.
 */
public enum RiskAssessmentEventType {

    RISK_ASSESSMENT_PUBLISHED("sfl.ssemp.risk-assessment-published.v1"),
    RISK_ASSESSMENT_SUPERSEDED("sfl.ssemp.risk-assessment-superseded.v1"),
    RISK_ASSESSMENT_REVIEW_LAPSED("sfl.ssemp.risk-assessment-review-lapsed.v1"),
    RISK_ASSESSMENT_SIGNED_OFF("sfl.ssemp.risk-assessment-signed-off.v1"),
    RISK_ASSESSMENT_REVIEW_DUE("sfl.ssemp.risk-assessment-review-due.v1"),
    RISK_ASSESSMENT_REVIEW_FLAGGED("sfl.ssemp.risk-assessment-review-flagged.v1");

    /** Every S165 event is about one assessment, so the aggregate type is shared. */
    public static final String AGGREGATE_TYPE = "RiskAssessment";

    private final String eventType;

    RiskAssessmentEventType(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    public int version() {
        return Integer.parseInt(eventType.substring(eventType.lastIndexOf(".v") + 2));
    }
}

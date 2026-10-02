package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception;

import java.util.Map;
import java.util.UUID;

/** The single domain/application exception for S165, framework-free - mirrors {@code LifeSafetyException}. */
public class RiskAssessmentException extends RuntimeException {

    private final RiskAssessmentErrorCode errorCode;
    private final transient Map<String, Object> details;

    public RiskAssessmentException(RiskAssessmentErrorCode errorCode, Map<String, Object> details) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public RiskAssessmentException(RiskAssessmentErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public RiskAssessmentErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static RiskAssessmentException notFound(String type, UUID id) {
        return new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_RECORD_NOT_FOUND,
                Map.of("resourceType", type, "resourceId", id == null ? "" : id.toString()));
    }

    public static RiskAssessmentException unauthorizedScope(String site, String resource, String id) {
        return new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_UNAUTHORIZED_SCOPE,
                Map.of("siteCode", site == null ? "" : site, "resourceType", resource,
                        "resourceId", id == null ? "" : id));
    }

    public static RiskAssessmentException invalidState(String reason) {
        return new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_INVALID_STATE_TRANSITION,
                Map.of("reason", reason));
    }
}

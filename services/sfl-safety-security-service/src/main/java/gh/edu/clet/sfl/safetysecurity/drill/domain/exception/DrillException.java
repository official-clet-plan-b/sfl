package gh.edu.clet.sfl.safetysecurity.drill.domain.exception;

import java.util.Map;
import java.util.UUID;

/** The single domain/application exception for S175, framework-free - mirrors {@code RiskAssessmentException}. */
public class DrillException extends RuntimeException {

    private final DrillErrorCode errorCode;
    private final transient Map<String, Object> details;

    public DrillException(DrillErrorCode errorCode, Map<String, Object> details) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public DrillException(DrillErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public DrillErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static DrillException notFound(String type, UUID id) {
        return new DrillException(DrillErrorCode.DRILL_RECORD_NOT_FOUND,
                Map.of("resourceType", type, "resourceId", id == null ? "" : id.toString()));
    }

    public static DrillException unauthorizedScope(String site, String resource, String id) {
        return new DrillException(DrillErrorCode.DRILL_UNAUTHORIZED_SCOPE,
                Map.of("siteCode", site == null ? "" : site, "resourceType", resource,
                        "resourceId", id == null ? "" : id));
    }

    public static DrillException invalidState(String from, String to) {
        return new DrillException(DrillErrorCode.DRILL_INVALID_STATE_TRANSITION, Map.of("from", from, "to", to));
    }
}

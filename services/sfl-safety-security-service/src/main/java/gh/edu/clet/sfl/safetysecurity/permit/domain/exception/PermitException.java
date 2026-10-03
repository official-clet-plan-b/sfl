package gh.edu.clet.sfl.safetysecurity.permit.domain.exception;

import java.util.Map;
import java.util.UUID;

/** The single domain/application exception for S164, framework-free - mirrors {@code DrillException}. */
public class PermitException extends RuntimeException {

    private final PermitErrorCode errorCode;
    private final transient Map<String, Object> details;

    public PermitException(PermitErrorCode errorCode, Map<String, Object> details) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public PermitException(PermitErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public PermitErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static PermitException notFound(String type, UUID id) {
        return new PermitException(PermitErrorCode.PERMIT_RECORD_NOT_FOUND,
                Map.of("resourceType", type, "resourceId", id == null ? "" : id.toString()));
    }

    public static PermitException unauthorizedScope(String site, String resource, String id) {
        return new PermitException(PermitErrorCode.PERMIT_UNAUTHORIZED_SCOPE, Map.of("siteCode", site == null ? "" : site,
                "resourceType", resource, "resourceId", id == null ? "" : id));
    }

    public static PermitException invalidState(String from, String to) {
        return new PermitException(PermitErrorCode.PERMIT_INVALID_STATE_TRANSITION, Map.of("from", from, "to", to));
    }

    public static PermitException conflict() {
        return new PermitException(PermitErrorCode.PERMIT_RECORD_VERSION_CONFLICT);
    }

    public static PermitException invalid(String message) {
        return new PermitException(PermitErrorCode.PERMIT_VALIDATION_FAILED, Map.of("reason", message));
    }
}

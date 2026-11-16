package gh.edu.clet.sfl.safetysecurity.lifesafety.domain.exception;

/** S162a error catalog, following S163's {@code IncidentErrorCode} shape. */
public enum LifeSafetyErrorCode {

    LIFESAFETY_MISSING_SITE_SCOPE(400, "Select a valid GSL site before saving this life-safety record."),
    LIFESAFETY_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),
    LIFESAFETY_SOURCE_NOT_ALLOWED(401, "This source system is not allowed to send life-safety events."),
    LIFESAFETY_INVALID_SIGNATURE(401, "The inbound life-safety message failed authentication."),
    LIFESAFETY_SCHEMA_VALIDATION_FAILED(400, "The inbound life-safety message failed schema validation."),
    LIFESAFETY_DUPLICATE_MESSAGE(409, "This life-safety message has already been processed."),
    LIFESAFETY_VALIDATION_FAILED(400, "The request failed validation."),
    LIFESAFETY_RECORD_NOT_FOUND(404, "The requested life-safety record was not found."),
    LIFESAFETY_INVALID_STATE_TRANSITION(409, "The requested transition is not allowed from the current state."),
    LIFESAFETY_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry.");

    private final int httpStatus;
    private final String message;

    LifeSafetyErrorCode(int httpStatus, String message) {
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

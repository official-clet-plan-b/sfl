package gh.edu.clet.sfl.safetysecurity.visitor.domain.exception;

/**
 * S160 error catalog, following S174's {@code EmergencyErrorCode} shape: the numeric HTTP status is
 * carried here as a plain int so the domain stays framework-free; the API layer translates it.
 */
public enum VisitorErrorCode {

    // Operational records
    VISITOR_MISSING_SITE_SCOPE(400, "Select a valid GSL site before saving this visit."),
    VISITOR_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),

    // Workflow
    VISITOR_UNAUTHORIZED_APPROVAL(403, "You do not have permission to approve this visit."),
    VISITOR_SELF_APPROVAL_NOT_ALLOWED(403, "You may not decide on your own visit request."),
    VISITOR_WATCHLIST_MATCH(403,
            "This visitor matched a watchlist or restriction check and requires an override to proceed."),
    VISITOR_BADGE_NOT_ASSIGNED(409, "A badge must be assigned before check-in."),

    // Generic operational codes
    VISITOR_VALIDATION_FAILED(400, "The request failed validation."),
    VISITOR_RECORD_NOT_FOUND(404, "The requested visitor record was not found."),
    VISITOR_INVALID_STATE_TRANSITION(409, "The requested transition is not allowed from the current state."),
    VISITOR_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry."),
    VISITOR_IDEMPOTENCY_KEY_CONFLICT(409, "This Idempotency-Key was already used with a different request payload.");

    private final int httpStatus;
    private final String message;

    VisitorErrorCode(int httpStatus, String message) {
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

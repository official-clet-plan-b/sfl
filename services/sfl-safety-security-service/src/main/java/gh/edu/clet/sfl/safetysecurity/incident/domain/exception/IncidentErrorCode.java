package gh.edu.clet.sfl.safetysecurity.incident.domain.exception;

/**
 * S163 error catalog, following S174's {@code EmergencyErrorCode} shape: the numeric HTTP status is
 * carried here as a plain int so the domain stays framework-free; the API layer translates it.
 */
public enum IncidentErrorCode {

    // Operational records
    INCIDENT_MISSING_SITE_SCOPE(400, "Select a valid GSL site before saving this incident."),
    INCIDENT_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),

    // Workflow
    INCIDENT_TRIAGE_REQUIRED(409, "Triage must be completed before an investigation can be opened."),
    INCIDENT_MANDATORY_CAPA_OPEN(409,
            "The incident cannot be closed while a mandatory corrective action remains open - SRS-SFL-S163 hard rule 1."),

    // Generic operational codes
    INCIDENT_VALIDATION_FAILED(400, "The request failed validation."),
    INCIDENT_RECORD_NOT_FOUND(404, "The requested incident record was not found."),
    INCIDENT_CAPA_NOT_FOUND(404, "The requested corrective action was not found."),
    INCIDENT_INVALID_STATE_TRANSITION(409, "The requested transition is not allowed from the current state."),
    INCIDENT_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry.");

    private final int httpStatus;
    private final String message;

    IncidentErrorCode(int httpStatus, String message) {
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

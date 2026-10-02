package gh.edu.clet.sfl.safetysecurity.emergency.domain.exception;

/**
 * S174 error catalog. The messages for SRS-defined states are the SRS <em>Error States</em> wording,
 * verbatim - that text is contract and is asserted in tests. The numeric HTTP status is carried here (as a
 * plain int) so the domain stays framework-free; the API layer translates it to a response status.
 */
public enum EmergencyErrorCode {

    // SRS-SFL-S174-01 operational records
    EMERGENCY_DUPLICATE_IDENTIFIER(409, "An active record with this identifier already exists for this site."),
    EMERGENCY_MISSING_SITE_SCOPE(400, "Select a valid CLET site before saving this record."),
    EMERGENCY_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),

    // SRS-SFL-S174-02 workflow
    EMERGENCY_CLOSURE_EVIDENCE_MISSING(422, "Required evidence must be attached before closure."),
    EMERGENCY_SLA_BREACH(409, "This item has breached its configured SLA and has been escalated."),
    EMERGENCY_UNAUTHORIZED_APPROVAL(403, "You do not have permission to approve this workflow transition."),

    // SRS-SFL-S174-03 evidence and audit
    EMERGENCY_EXPORT_NOT_APPROVED(403, "Evidence export requires approval and a recorded reason."),
    EMERGENCY_RETENTION_CLASS_MISSING(400, "Select a retention class before saving this evidence."),
    EMERGENCY_AUDIT_CHAIN_FAILURE(500, "Audit integrity check failed. Escalate to compliance and security."),

    // SRS-SFL-S174-04 integrations
    EMERGENCY_INVALID_SIGNATURE(401, "Integration message rejected: signature verification failed."),
    EMERGENCY_SCHEMA_VALIDATION_FAILED(400, "Integration message rejected: payload does not match registered schema."),
    EMERGENCY_DUPLICATE_MESSAGE(200, "Duplicate integration message received and safely ignored."),
    EMERGENCY_SOURCE_NOT_ALLOWED(403, "Integration message rejected: source system is not allowlisted."),
    EMERGENCY_INTEGRATION_NOT_CONFIGURED(503, "Integration is not configured for this source and site."),

    // SRS-SFL-S174-05 dashboards and reports
    EMERGENCY_DATA_STALE(200, "Dashboard data is older than the configured freshness threshold."),
    EMERGENCY_NO_SCOPE(403, "No site scope is assigned to your user profile."),
    EMERGENCY_RESTRICTED_DRILLDOWN(403, "You do not have permission to view the underlying record."),

    // Phase 2 SRS-SFL-S175-01: drills reach recipients through S174 in test mode
    EMERGENCY_TEST_REAL_AMBIGUITY(409,
            "Test/Real Ambiguity - a drill notification must never be indistinguishable from a real alert. A drill"
                    + " template must open with the drill marker, and a real template or activation may not carry it."),

    // Generic operational codes
    EMERGENCY_VALIDATION_FAILED(400, "The request failed validation."),
    EMERGENCY_RECORD_NOT_FOUND(404, "The requested emergency notification record was not found."),
    EMERGENCY_INVALID_STATE_TRANSITION(409, "The requested transition is not allowed from the current state."),
    EMERGENCY_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry."),
    EMERGENCY_IDEMPOTENCY_KEY_CONFLICT(409,
            "This Idempotency-Key was already used with a different request payload.");

    private final int httpStatus;
    private final String message;

    EmergencyErrorCode(int httpStatus, String message) {
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

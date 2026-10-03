package gh.edu.clet.sfl.safetysecurity.permit.domain.exception;

/**
 * S164 error catalog, following {@code DrillErrorCode}'s shape. Where the SRS names an error state the name is in the
 * message - it is already written for the person who will read it.
 */
public enum PermitErrorCode {

    PERMIT_VALIDATION_FAILED(400, "The request failed validation."),
    PERMIT_UNAUTHORIZED_SCOPE(403, "You are not authorised to access this site or record."),
    PERMIT_RECORD_NOT_FOUND(404, "The requested permit record was not found."),
    PERMIT_INVALID_STATE_TRANSITION(409, "The requested change is not allowed from the permit's current state."),
    PERMIT_RECORD_VERSION_CONFLICT(409, "The record was modified concurrently; reload and retry."),
    PERMIT_REASON_REQUIRED(400, "Give a reason: it is kept with the permit."),
    PERMIT_TYPE_INACTIVE(409, "This permit type is no longer in use."),
    PERMIT_TYPE_INVALID(400, "A permit type of high or critical risk must use two-stage approval."),

    /** SRS-SFL-S164-01 error state "No Risk Assessment Linked". */
    PERMIT_NO_RISK_ASSESSMENT(422, "No Risk Assessment Linked - a current risk assessment is required for this work type; "
            + "the request is refused."),
    /** SRS-SFL-S164-01 error state "Risk Assessment Not Current". */
    PERMIT_RISK_ASSESSMENT_NOT_CURRENT(422, "Risk Assessment Not Current - the linked assessment is past its review date; "
            + "the request is refused until it is renewed."),
    PERMIT_RISK_ASSESSMENT_MISMATCH(422, "The linked risk assessment covers a different kind of work than this permit type."),
    PERMIT_WORKERS_REQUIRED(422, "Name the workers involved: a permit cannot be requested without them."),
    PERMIT_ISOLATIONS_REQUIRED(422, "This permit type requires isolations: list each one before the request is submitted."),
    PERMIT_WINDOW_INVALID(422, "The validity window is not allowed for this permit type."),
    PERMIT_ZONE_NOT_FOUND(422, "That access-control zone does not exist at this site."),

    /** SRS-SFL-S164-02 error state "Self-Approval Attempted". */
    PERMIT_SELF_APPROVAL(403, "Self-Approval Attempted - the person who requested a permit cannot approve it."),
    /** SRS-SFL-S164-02 error state "Isolation Not Verified". */
    PERMIT_ISOLATION_NOT_VERIFIED(422, "Isolation Not Verified - approval is refused until every required isolation has a "
            + "recorded verification."),
    PERMIT_VERIFIER_NOT_INDEPENDENT(403, "The verifier must be someone other than the person who requested the permit."),
    PERMIT_COMPETENCY_EXCEPTION(422, "A worker named on the permit has no current competence check for what this work needs."),
    PERMIT_STAGE_NOT_INDEPENDENT(403, "The safety sign-off must come from someone other than the issuing authority."),
    PERMIT_STAGE_OUT_OF_ORDER(409, "This approval stage is not next: the issuing authority approves first, then the safety sign-off."),

    PERMIT_EVIDENCE_REQUIRED(422, "Close-out needs evidence that the work is complete."),
    PERMIT_COMPLETION_STATEMENT_REQUIRED(422, "Close-out needs a completion statement."),
    /** SRS-SFL-S164-04 error state "Isolation Removal Not Recorded". */
    PERMIT_ISOLATION_REMOVAL_NOT_RECORDED(422, "Isolation Removal Not Recorded - the permit cannot be closed until every "
            + "isolation has a recorded removal."),
    PERMIT_REMOVER_NOT_INDEPENDENT(403, "Isolation removal must be recorded by someone other than the person who requested the permit."),
    PERMIT_EXTENSION_PENDING(409, "This permit already has an extension waiting for a decision."),
    PERMIT_EXTENSION_INVALID(422, "The extension is not allowed: it must end later than the permit does, within the type's limit."),
    PERMIT_EXPORT_REASON_REQUIRED(400, "An export needs a reason of at least 10 characters.");

    private final int httpStatus;
    private final String message;

    PermitErrorCode(int httpStatus, String message) {
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

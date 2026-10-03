package gh.edu.clet.sfl.facilities.shared.api;

import gh.edu.clet.sfl.common.api.ApiError;
import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.AuthorizationException;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps every failure to the platform envelope and the status its SRS error state implies.
 *
 * <p>The envelope is {@code ApiResponse<T>} - {@code {data, error}} - because that is what the other
 * 35 controllers in this platform emit and what the dashboard's single API client parses. S152
 * originally returned bare payloads with its own error shape; the client returns {@code envelope.data}
 * and would have read every successful response as {@code undefined}. Thirty-five controllers set the
 * convention, so the five here were changed rather than the shared client taught a per-service policy.
 *
 * <p>Field errors travel in {@code data}, which is where {@code FleetApiError.fromEnvelope} looks for
 * them - an array there becomes the form's per-field messages.
 *
 * <p>The status table this encodes:
 *
 * <ul>
 *   <li>Unauthorised scope, restricted drilldown, no scope - <strong>403</strong></li>
 *   <li>Record not found - <strong>404</strong></li>
 *   <li>Duplicate identifier, version conflict, idempotency key conflict, booking conflict, resource
 *       unavailable - <strong>409</strong></li>
 *   <li>Invalid transition, readiness blocked, readiness locked, mode transition, space not bookable
 *       - <strong>422</strong></li>
 *   <li>Audit chain failure - <strong>500</strong></li>
 *   <li>Everything else - <strong>400</strong></li>
 * </ul>
 *
 * <p>422 rather than 400 for the domain-rule refusals is deliberate: the request was well-formed and
 * the server understood it - it is the estate's current state that forbids it. A client that retried a
 * 400 after fixing its payload would retry a 422 forever.
 */
@RestControllerAdvice
class FacilitiesApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FacilitiesApiExceptionHandler.class);

    private static final Map<FacilitiesErrorCode, HttpStatus> STATUSES = Map.ofEntries(
            Map.entry(FacilitiesErrorCode.UNAUTHORIZED_SCOPE, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.UNAUTHORIZED_APPROVAL, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.RESTRICTED_DRILLDOWN, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.NO_SCOPE, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.RECORD_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(FacilitiesErrorCode.DUPLICATE_IDENTIFIER, HttpStatus.CONFLICT),
            Map.entry(FacilitiesErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(FacilitiesErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT),
            // BOOKING_CONFLICT and RESOURCE_UNAVAILABLE are the same "somebody else has it" shape as
            // VERSION_CONFLICT above - see BookingController's own Javadoc, which has promised 409 for
            // these since S159 shipped, and JpaBookingRepositoryAdapter, which translates the exclusion
            // constraint violation into the same exception precisely so a caller cannot tell whether
            // they lost a race or simply asked late.
            Map.entry(FacilitiesErrorCode.BOOKING_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(FacilitiesErrorCode.RESOURCE_UNAVAILABLE, HttpStatus.CONFLICT),
            Map.entry(FacilitiesErrorCode.SPACE_NOT_BOOKABLE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.INVALID_STATE_TRANSITION, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.READINESS_BLOCKED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.READINESS_LOCKED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.OPERATING_MODE_TRANSITION_INVALID, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CLOSURE_EVIDENCE_MISSING, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.INVALID_PARENT_REFERENCE, HttpStatus.BAD_REQUEST),
            Map.entry(FacilitiesErrorCode.AUDIT_CHAIN_FAILURE, HttpStatus.INTERNAL_SERVER_ERROR),
            // Phase 2. A forged or malformed vendor message is an authentication failure, not a bad
            // request: the sender must not be told which check it failed, only that it failed.
            Map.entry(FacilitiesErrorCode.VENDOR_MESSAGE_REJECTED, HttpStatus.UNAUTHORIZED),
            Map.entry(FacilitiesErrorCode.BMS_TELEMETRY_REJECTED, HttpStatus.UNAUTHORIZED),
            // Quarantine is acceptance for review, not refusal - see S156-01 - hence 202.
            Map.entry(FacilitiesErrorCode.BMS_LOCATION_UNRESOLVABLE, HttpStatus.ACCEPTED),
            Map.entry(FacilitiesErrorCode.BMS_DEVICE_UNREGISTERED, HttpStatus.ACCEPTED),
            Map.entry(FacilitiesErrorCode.BMS_RULE_OVERRIDE_REQUIRED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.ENERGY_READING_IMPLAUSIBLE, HttpStatus.ACCEPTED),
            Map.entry(FacilitiesErrorCode.ENERGY_TARIFF_MISSING, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.ENERGY_DEVICE_DOUBLE_REGISTERED, HttpStatus.CONFLICT),
            Map.entry(FacilitiesErrorCode.ENERGY_SELF_VERIFICATION, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.SPACE_SCENARIO_UNCOMMITTED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.SPACE_OVERRIDE_INCOMPLETE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.SPACE_CHANGE_UNLINKED_RESOLUTION, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CLEANING_BOOKING_UNLINKED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CLEANING_CHECKLIST_INCOMPLETE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CLEANING_RESOURCING_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(FacilitiesErrorCode.CLEANING_VENDOR_NOT_FOUND, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.EVENT_REFERENCE_UNRESOLVABLE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.EVENT_RISK_ASSESSMENT_NOT_CURRENT, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.EVENT_UNRESOLVED_RESOURCE_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.PROJECT_APPROVAL_MISSING, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.PROJECT_PERMIT_MISSING, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CONTRACTOR_COMPLIANCE_LAPSED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.VARIATION_NOT_APPROVED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.VARIATION_ESCALATION_REQUIRED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.PROJECT_HANDOVER_INCOMPLETE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.PROJECT_DEFECTS_OPEN, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.RISK_ASSESSMENT_NOT_CURRENT, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.RISK_ASSESSMENT_NOT_LINKED, HttpStatus.UNPROCESSABLE_ENTITY),
            // Added at merge - flagged by the S176 build as outside its own file boundary.
            Map.entry(FacilitiesErrorCode.PROJECT_CONTRACTOR_UNASSIGNED, HttpStatus.UNPROCESSABLE_ENTITY),
            // Added at merge - flagged by the S158 build as outside its own file boundary. Both name an
            // unresolved cross-module dependency rather than a bad request from the caller, so 503
            // rather than 422/400.
            Map.entry(FacilitiesErrorCode.SPACE_CONSTRUCTION_INTAKE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(FacilitiesErrorCode.SPACE_UTILISATION_SOURCE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(FacilitiesErrorCode.HYGIENE_SELF_VERIFICATION, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.HYGIENE_PROVIDER_UNCONFIRMED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.HYGIENE_CLOSURE_BLOCKED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CAT_ALLERGEN_CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.CAT_SELF_APPROVAL, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.CAT_CUTOFF_PASSED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.LF_RELEASE_BLOCKED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.LF_COMPETING_CLAIMS, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.LF_ITEM_ISOLATED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.LF_RETENTION_NOT_EXPIRED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.LF_SELF_APPROVAL, HttpStatus.FORBIDDEN),
            Map.entry(FacilitiesErrorCode.LF_STORAGE_NOT_SECURE, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.WASTE_CARRIER_UNAPPROVED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.WASTE_DESTINATION_UNAPPROVED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.WASTE_CHAIN_OPEN, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(FacilitiesErrorCode.WASTE_SELF_VERIFICATION, HttpStatus.FORBIDDEN));

    /** One field's rejection, shaped as the dashboard's form binding expects. */
    public record FieldErrorResponse(String field, String message, Object rejectedValue) {
    }

    @ExceptionHandler(FacilitiesException.class)
    ResponseEntity<ApiResponse<Object>> facilitiesFailure(FacilitiesException exception,
            HttpServletRequest request) {
        HttpStatus status = STATUSES.getOrDefault(exception.code(), HttpStatus.BAD_REQUEST);
        if (status.is5xxServerError()) {
            log.error("S152 failure {}: {}", exception.code(), exception.getMessage(), exception);
        }
        return respond(status, exception.code(), exception.getMessage(), null, request);
    }

    /**
     * The pre-S152 authorisation exception from {@code sfl-service-common}.
     *
     * <p>Nothing in this module throws it any more: {@code WorkOrderService} and its direct use of
     * {@code AuthorizationPolicy} were replaced by {@code WorkOrderApplicationService} authorising
     * through {@link gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization}, which
     * raises {@code FacilitiesException} subtypes exclusively - see {@code facilitiesFailure} above.
     * Kept rather than removed because {@code AuthorizationException} is a shared-library type another
     * component in the request path could still raise; mapping it to the same envelope and the same
     * {@code UNAUTHORIZED_SCOPE} code means a client cannot tell which module refused it, and costs
     * nothing while it stays unreachable.
     */
    @ExceptionHandler(AuthorizationException.class)
    ResponseEntity<ApiResponse<Object>> forbidden(AuthorizationException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, FacilitiesErrorCode.UNAUTHORIZED_SCOPE, exception.getMessage(),
                null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Object>> validationFailure(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(error.getField(), error.getDefaultMessage(),
                        error.getRejectedValue()))
                .toList();
        String message = fieldErrors.isEmpty()
                ? FacilitiesErrorCode.VALIDATION_FAILED.defaultMessage()
                : fieldErrors.get(0).field() + ": " + fieldErrors.get(0).message();
        return respond(HttpStatus.BAD_REQUEST, FacilitiesErrorCode.VALIDATION_FAILED, message, fieldErrors,
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Object>> typeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, FacilitiesErrorCode.VALIDATION_FAILED,
                exception.getName() + " is not a valid value", null, request);
    }

    /**
     * A required query parameter that never arrived.
     *
     * <p>Unhandled, this is the one failure that escapes the envelope entirely. Spring answers with its
     * own body - {@code {timestamp, status, error, path}} - which carries no error code, so the
     * dashboard's client cannot recognise it and falls back to reporting the raw HTTP status and the
     * request path to whoever is looking at the screen. An operator asking "what is free on Tuesday"
     * was shown "The service could not complete /api/v1/facilities/booking-availability/spaces: Bad
     * Request", which names neither the problem nor anything they can do about it.
     *
     * <p>The parameter is named because that is the actionable part: the caller omitted something, and
     * which something is the whole answer.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiResponse<Object>> missingParameter(MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, FacilitiesErrorCode.VALIDATION_FAILED,
                exception.getParameterName() + " is required", null, request);
    }

    /**
     * Anything else Spring refused while binding the request.
     *
     * <p>A catch-all beneath the two specific handlers above, and deliberately last of the binding
     * family: whatever new shape of malformed request arrives, it leaves through the envelope rather
     * than as a bare Spring error the client has to guess at.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    ResponseEntity<ApiResponse<Object>> bindingFailure(ServletRequestBindingException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, FacilitiesErrorCode.VALIDATION_FAILED,
                "The request could not be read: " + exception.getMessage(), null, request);
    }

    /**
     * A database constraint the application-level check did not catch first.
     *
     * <p>Two very different failures arrive as one exception type, and reporting them alike sends a
     * caller down the wrong path entirely. Discriminated on the SQL state rather than the message text,
     * so it does not depend on a driver's wording: 23503 is a foreign-key violation - a reference to
     * something that does not exist - and anything else here is a uniqueness race the pre-write check
     * lost.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Object>> integrityViolation(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        Throwable cause = exception.getMostSpecificCause();
        log.warn("Database constraint rejected a write: {}", cause.getMessage());

        String sqlState = cause instanceof java.sql.SQLException sqlException ? sqlException.getSQLState() : null;
        FacilitiesErrorCode code = "23503".equals(sqlState)
                ? FacilitiesErrorCode.INVALID_PARENT_REFERENCE
                : FacilitiesErrorCode.DUPLICATE_IDENTIFIER;
        return respond(STATUSES.getOrDefault(code, HttpStatus.CONFLICT), code, code.defaultMessage(), null,
                request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Object>> optimisticLock(OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, FacilitiesErrorCode.VERSION_CONFLICT,
                FacilitiesErrorCode.VERSION_CONFLICT.defaultMessage(), null, request);
    }

    /**
     * Legacy argument failures.
     *
     * <p>The pre-existing {@code masterdata} and {@code maintenance} code signals invalid input with
     * {@link IllegalArgumentException}. Kept on 400 so this change does not alter the status of an
     * endpoint that already shipped.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Object>> invalidRequest(IllegalArgumentException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, FacilitiesErrorCode.VALIDATION_FAILED, exception.getMessage(),
                null, request);
    }

    /** A body that is not JSON, or names an enum value that does not exist, gets the same envelope as any other 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Object>> unreadableBody(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, FacilitiesErrorCode.VALIDATION_FAILED,
                "The request body is missing or malformed.", null, request);
    }

    /** Legacy state failures from {@code maintenance}, which predate the typed exceptions. */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiResponse<Object>> invalidState(IllegalStateException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, FacilitiesErrorCode.INVALID_STATE_TRANSITION,
                exception.getMessage(), null, request);
    }

    private ResponseEntity<ApiResponse<Object>> respond(HttpStatus status, FacilitiesErrorCode code,
            String message, Object data, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.currentCorrelationId(request);
        ApiError error = ApiError.of(code.name(), message, correlationId);
        return ResponseEntity.status(status)
                .header(FacilitiesActorResolver.HEADER_CORRELATION_ID, correlationId)
                .body(new ApiResponse<>(data, error));
    }
}

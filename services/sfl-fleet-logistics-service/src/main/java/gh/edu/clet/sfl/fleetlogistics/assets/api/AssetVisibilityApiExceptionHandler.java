package gh.edu.clet.sfl.fleetlogistics.assets.api;

import gh.edu.clet.sfl.fleetlogistics.assets.application.AssetVisibilityAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.assets.application.DuplicateAssetTagException;
import gh.edu.clet.sfl.common.api.ApiError;
import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.AuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps every AVAMP failure to the platform envelope.
 *
 * <h2>Why this was rewritten on 1 August 2026</h2>
 *
 * <p>It handled two exception types and emitted a shape of its own -
 * {@code {status, error, message, timestamp}} - while the rest of the platform emits
 * {@code {data, error}}. The dashboard's single API client reads {@code envelope.error.code}, so an
 * AVAMP failure reached it as an error with no code at all.
 *
 * <p>The more pressing gap: {@link AssetVisibilityAuthorizationException} did not exist when this was
 * written and nothing mapped it. A refusal from the new access policy would have fallen through to
 * Spring's default handler and surfaced as <strong>500</strong>. A permission check that reports its
 * refusals as server faults is worse than none - it is a check that trains whoever reads the logs to
 * treat a real denial as a bug in the service.
 *
 * <h2>403, not 401</h2>
 *
 * The same distinction A1 established platform-wide. 401 tells a client to authenticate again; for an
 * actor who authenticated correctly and simply lacks {@code ASSET_REFERENCE_MANAGE} that is an
 * instruction to loop through sign-in forever.
 *
 * <h2>Why it names a base package</h2>
 *
 * <p>AVAMP used to be its own deployable, so an unqualified {@code @RestControllerAdvice} was the only
 * advice in its context. In the merged FTLMP service it is not. This one covers {@code ..assets..} and
 * {@code FleetApiExceptionHandler} covers the fleet, fuel, dispatch and system packages; the two sets
 * are disjoint, which is what makes the answer to "which handler maps this exception" a fact about the
 * source rather than about scanning order. Neither needs an {@code @Order}.
 */
@RestControllerAdvice(basePackages = "gh.edu.clet.sfl.fleetlogistics.assets")
class AssetVisibilityApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssetVisibilityApiExceptionHandler.class);

    private static final String VALIDATION_FAILED = "ASSETVIS_VALIDATION_FAILED";
    private static final String RECORD_NOT_FOUND = "ASSETVIS_RECORD_NOT_FOUND";
    private static final String DUPLICATE_IDENTIFIER = "ASSETVIS_DUPLICATE_IDENTIFIER";
    private static final String UNAUTHORIZED_SCOPE = "ASSETVIS_UNAUTHORIZED_SCOPE";

    /** One field's rejection, shaped as the dashboard's form binding expects. */
    record FieldErrorResponse(String field, String message, Object rejectedValue) {
    }

    /**
     * The refusal from {@code AssetVisibilityAccessPolicy}.
     *
     * <p>The structured details travel in {@code data} - the permission required, the resource, and
     * the site when the refusal was a scope one. A caller told only "forbidden" cannot tell whether to
     * request a role or a site scope, and those go to different people.
     */
    @ExceptionHandler(AssetVisibilityAuthorizationException.class)
    ResponseEntity<ApiResponse<Object>> refused(AssetVisibilityAuthorizationException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, exception.code(), exception.getMessage(), exception.details(),
                request);
    }

    /** The shared-kernel authorisation exception, mapped to the same code so callers see one contract. */
    @ExceptionHandler(AuthorizationException.class)
    ResponseEntity<ApiResponse<Object>> forbidden(AuthorizationException exception, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, UNAUTHORIZED_SCOPE, exception.getMessage(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Object>> validationFailure(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldErrorResponse> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorResponse(error.getField(), error.getDefaultMessage(),
                        error.getRejectedValue()))
                .toList();
        String message = fieldErrors.isEmpty()
                ? "Request validation failed"
                : fieldErrors.get(0).field() + ": " + fieldErrors.get(0).message();
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, message, fieldErrors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Object>> typeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED,
                exception.getName() + " is not a valid value", null, request);
    }

    /**
     * A read for an asset that is not there.
     *
     * <p>404 rather than 400: the request was well-formed. Note that the controller checks site scope
     * <em>after</em> loading the record, so an out-of-scope asset answers 403 and a genuinely absent
     * one answers 404. That does leak existence to a caller who holds the read permission but not the
     * site - an acceptable trade here, because the alternative (404 for both) makes a scope
     * misconfiguration indistinguishable from a bad asset id for the integrations team that has to
     * diagnose it.
     */
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ApiResponse<Object>> notFound(NoSuchElementException exception, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, RECORD_NOT_FOUND, exception.getMessage(), null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Object>> integrityViolation(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        log.warn("Database constraint rejected a write: {}", exception.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, DUPLICATE_IDENTIFIER,
                "An asset with this code or tag is already registered.", null, request);
    }

    /** A tag identifies one asset; asking for it to identify a second is a conflict, not a bad request. */
    @ExceptionHandler(DuplicateAssetTagException.class)
    ResponseEntity<ApiResponse<Object>> duplicateTag(DuplicateAssetTagException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, DUPLICATE_IDENTIFIER, exception.getMessage(), null, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Object>> invalidRequest(IllegalArgumentException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, exception.getMessage(), null, request);
    }

    private ResponseEntity<ApiResponse<Object>> respond(HttpStatus status, String code, String message,
            Object data, HttpServletRequest request) {
        String correlationId = request.getHeader(AssetVisibilityActorResolver.HEADER_CORRELATION_ID);
        ApiError error = ApiError.of(code, message, correlationId);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (correlationId != null && !correlationId.isBlank()) {
            response = response.header(AssetVisibilityActorResolver.HEADER_CORRELATION_ID, correlationId);
        }
        return response.body(new ApiResponse<>(data, error));
    }
}

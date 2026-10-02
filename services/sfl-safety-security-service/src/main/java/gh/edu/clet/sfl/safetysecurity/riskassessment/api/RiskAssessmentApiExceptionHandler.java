package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.common.api.ApiError;
import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.AuthorizationException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Translates S165 failures into the shared {@code ApiResponse}/{@code ApiError} envelope. Mirrors {@code IncidentApiExceptionHandler}. */
@RestControllerAdvice(basePackages = "gh.edu.clet.sfl.safetysecurity.riskassessment.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
class RiskAssessmentApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RiskAssessmentApiExceptionHandler.class);
    private final RiskAssessmentActorResolver actors;

    RiskAssessmentApiExceptionHandler(RiskAssessmentActorResolver actors) {
        this.actors = actors;
    }

    @ExceptionHandler(RiskAssessmentException.class)
    ResponseEntity<ApiResponse<Object>> domain(RiskAssessmentException exception, HttpServletRequest request) {
        RiskAssessmentErrorCode code = exception.errorCode();
        HttpStatus status = HttpStatus.valueOf(code.httpStatus());
        if (status.is5xxServerError()) {
            log.error("Risk-assessment request failed: {} {}", code, exception.getMessage(), exception);
        }
        Object details = exception.details().isEmpty() ? null : exception.details();
        return respond(status, code.code(), exception.getMessage(), details, request);
    }

    @ExceptionHandler(AuthorizationException.class)
    ResponseEntity<ApiResponse<Object>> authorization(AuthorizationException exception, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, RiskAssessmentErrorCode.RISK_ASSESSMENT_UNAUTHORIZED_SCOPE.code(),
                RiskAssessmentErrorCode.RISK_ASSESSMENT_UNAUTHORIZED_SCOPE.message(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Object>> beanValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<Map<String, Object>> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.<String, Object>of("field", e.getField(), "message",
                        String.valueOf(e.getDefaultMessage())))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, RiskAssessmentErrorCode.RISK_ASSESSMENT_VALIDATION_FAILED.code(),
                RiskAssessmentErrorCode.RISK_ASSESSMENT_VALIDATION_FAILED.message(), fields, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ApiResponse<Object>> malformed(Exception exception, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, RiskAssessmentErrorCode.RISK_ASSESSMENT_VALIDATION_FAILED.code(),
                exception.getMessage(), null, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Object>> optimisticLock(OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, RiskAssessmentErrorCode.RISK_ASSESSMENT_RECORD_VERSION_CONFLICT.code(),
                RiskAssessmentErrorCode.RISK_ASSESSMENT_RECORD_VERSION_CONFLICT.message(), null, request);
    }

    /**
     * A database constraint lost a race the service's own checks could not see - two drafts opened at once
     * (one-draft partial index), or two flags for one incident. A conflict to retry, never a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Object>> constraint(DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, RiskAssessmentErrorCode.RISK_ASSESSMENT_INVALID_STATE_TRANSITION.code(),
                "Another change to this record was saved first; reload and retry.", null, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Object>> illegalArgument(IllegalArgumentException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, RiskAssessmentErrorCode.RISK_ASSESSMENT_VALIDATION_FAILED.code(),
                exception.getMessage(), null, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiResponse<Object>> illegalState(IllegalStateException exception, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, RiskAssessmentErrorCode.RISK_ASSESSMENT_INVALID_STATE_TRANSITION.code(),
                exception.getMessage(), null, request);
    }

    private ResponseEntity<ApiResponse<Object>> respond(HttpStatus status, String code, String message, Object data,
            HttpServletRequest request) {
        String correlationId = actors.resolveCorrelationId(request);
        ApiError error = ApiError.of(code, message, correlationId);
        return ResponseEntity.status(status)
                .header(RiskAssessmentActorResolver.HEADER_CORRELATION_ID, correlationId)
                .body(new ApiResponse<>(data, error));
    }
}

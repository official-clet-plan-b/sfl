package gh.edu.clet.sfl.common.features;

import gh.edu.clet.sfl.common.api.ApiError;
import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.features.SflFeatureGate.SystemState;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The feature-flag endpoints' behaviour, shared by the three services.
 *
 * <p>Each service exposes it through a small controller that resolves the actor its own way. Anyone
 * signed in may read the flags (the dashboard needs them to draw its menu); only a platform
 * administrator may change them.
 */
public final class SflFeatureFlagsApi {

    public static final Set<SflRole> ADMINISTRATORS = EnumSet.of(SflRole.SFL_ADMIN, SflRole.DTI_ADMIN);
    public static final String FORBIDDEN = "SFL_FEATURE_FLAGS_FORBIDDEN";
    public static final String UNKNOWN_SYSTEM = "SFL_FEATURE_FLAGS_UNKNOWN_SYSTEM";
    public static final String INVALID_REQUEST = "SFL_FEATURE_FLAGS_INVALID_REQUEST";

    private final SflFeatureGate gate;

    public SflFeatureFlagsApi(SflFeatureGate gate) {
        this.gate = gate;
    }

    /** Request body for switching a system on or off. */
    public record UpdateRequest(Boolean enabled) {
    }

    public ApiResponse<List<SystemState>> list() {
        return ApiResponse.ok(gate.details());
    }

    public ResponseEntity<ApiResponse<List<SystemState>>> update(String systemCode, UpdateRequest request,
            ActorContext actor) {
        ResponseEntity<ApiResponse<List<SystemState>>> refused = refuse(systemCode, actor);
        if (refused != null) {
            return refused;
        }
        if (request == null || request.enabled() == null) {
            return failure(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Say whether the system is enabled.", actor);
        }
        gate.set(systemCode, request.enabled(), changedBy(actor));
        return ResponseEntity.ok(ApiResponse.ok(gate.details()));
    }

    public ResponseEntity<ApiResponse<List<SystemState>>> reset(String systemCode, ActorContext actor) {
        ResponseEntity<ApiResponse<List<SystemState>>> refused = refuse(systemCode, actor);
        if (refused != null) {
            return refused;
        }
        gate.reset(systemCode, changedBy(actor));
        return ResponseEntity.ok(ApiResponse.ok(gate.details()));
    }

    /**
     * Who made the change, as a person reads it and as the identity provider knows them: with an
     * identity provider the subject alone is an opaque id, so the name leads and the subject follows.
     */
    static String changedBy(ActorContext actor) {
        String subject = actor.principal().subjectId();
        String name = actor.principal().displayName();
        String value = name == null || name.equals(subject) ? subject : name + " (" + subject + ")";
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private ResponseEntity<ApiResponse<List<SystemState>>> refuse(String systemCode, ActorContext actor) {
        if (!actor.principal().hasAnyRole(ADMINISTRATORS)) {
            return failure(HttpStatus.FORBIDDEN, FORBIDDEN,
                    "Only a platform administrator can switch modules on or off.", actor);
        }
        if (!gate.hosts(systemCode)) {
            return failure(HttpStatus.NOT_FOUND, UNKNOWN_SYSTEM,
                    "This service does not host system " + systemCode + ".", actor);
        }
        return null;
    }

    private static ResponseEntity<ApiResponse<List<SystemState>>> failure(HttpStatus status, String code,
            String message, ActorContext actor) {
        return ResponseEntity.status(status).body(ApiResponse.failed(ApiError.of(code, message, actor.correlationId())));
    }
}

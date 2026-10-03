package gh.edu.clet.sfl.safetysecurity.permit.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.TypeBody;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.Caller;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitTypeService;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SRS-SFL-S164-01: permit types are configurable, each with its own approvals and validity rules. */
@RestController
@RequestMapping("/api/v1/permit-types")
@Tag(name = "S164 Permit-to-Work")
public class PermitTypeController {

    private final PermitTypeService types;
    private final PermitActorResolver actors;

    public PermitTypeController(PermitTypeService types, PermitActorResolver actors) {
        this.types = types;
        this.actors = actors;
    }

    private Caller caller(HttpServletRequest http) {
        return new Caller(actors.resolve(http), actors.resolveSourceChannel(http));
    }

    private static PermitTypeService.TypeDefinition definition(TypeBody b) {
        return new PermitTypeService.TypeDefinition(b.code(), b.name(), b.description(), b.riskLevel(), b.activityType(), b.riskAssessmentRequired(), b.twoStage(),
                b.requiresIsolation(), b.maxValidityHours(), b.requiredCompetencies(), b.active());
    }

    @GetMapping
    @Operation(summary = "The permit types in use, or all of them")
    public ApiResponse<List<PermitType>> list(@RequestParam(defaultValue = "true") boolean activeOnly, HttpServletRequest http) {
        return ApiResponse.ok(types.list(activeOnly, caller(http)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PermitType>> create(@Valid @RequestBody TypeBody body, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(types.create(definition(body), caller(http))));
    }

    @PutMapping("/{id}")
    public ApiResponse<PermitType> update(@PathVariable UUID id, @Valid @RequestBody TypeBody body, HttpServletRequest http) {
        return ApiResponse.ok(types.update(id, definition(body), body.version(), caller(http)));
    }
}

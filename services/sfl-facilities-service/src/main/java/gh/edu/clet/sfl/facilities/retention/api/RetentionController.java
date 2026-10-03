package gh.edu.clet.sfl.facilities.retention.api;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.retention.application.RetentionService;
import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** How long each class of record is kept, and what has outlived it. Needs the retention grant. */
@RestController
@RequestMapping("/api/v1/facilities/retention")
public class RetentionController {

    private final RetentionService retention;

    public RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    public record PolicyRequest(@NotNull Integer retentionDays, @NotBlank @Size(max = 240) String basis) {
    }

    @GetMapping("/policies")
    public ApiResponse<List<RetentionService.Policy>> policies(ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(retention.policies(new Caller(actor, channel)));
    }

    @PutMapping("/policies/{system}/{recordClass}")
    public ApiResponse<RetentionService.Policy> set(@PathVariable String system, @PathVariable String recordClass, @Valid @RequestBody PolicyRequest r,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(retention.set(system, recordClass, r.retentionDays(), r.basis(), new Caller(actor, channel)));
    }

    @GetMapping("/due")
    public ApiResponse<List<RetentionService.Due>> due(@RequestParam(required = false) String system, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(retention.due(system, new Caller(actor, channel)));
    }
}

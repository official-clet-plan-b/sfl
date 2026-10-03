package gh.edu.clet.sfl.facilities.registers.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.registers.application.RegisterRecordService;
import gh.edu.clet.sfl.facilities.registers.domain.RegisterRecord;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST contract for S172, S177, S178 and S179 operational records. */
@RestController
@RequestMapping("/api/v1/facilities/registers")
public class RegisterRecordController {

    private final RegisterRecordService records;

    public RegisterRecordController(RegisterRecordService records) {
        this.records = records;
    }

    @GetMapping("/{systemCode}/records")
    public ApiResponse<List<RegisterRecord>> list(@PathVariable String systemCode, @RequestParam String siteCode,
            @RequestParam(required = false) String recordType,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(records.list(systemCode, siteCode, recordType, actor, channel));
    }

    @PostMapping("/{systemCode}/records")
    public ResponseEntity<ApiResponse<RegisterRecord>> create(@PathVariable String systemCode,
            @Valid @RequestBody CreateRequest request, ActorContext actor, SourceChannel channel) {
        RegisterRecord created = records.create(new RegisterRecordService.CreateCommand(systemCode, request.siteCode(),
                request.recordType(), request.title(), request.status(), request.ownerReference(), request.dueAt(),
                request.severity(), request.details(), actor, channel));
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PatchMapping("/records/{id}/status")
    public ApiResponse<RegisterRecord> updateStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(records.updateStatus(id, request.status(), actor, channel));
    }

    public record CreateRequest(@NotBlank @Size(max = 40) String siteCode,
            @NotBlank @Size(max = 60) String recordType, @NotBlank @Size(max = 240) String title,
            @NotBlank @Size(max = 40) String status, @Size(max = 160) String ownerReference, Instant dueAt,
            @Size(max = 30) String severity, @Size(max = 4000) String details) {
    }

    public record StatusRequest(@NotBlank @Size(max = 40) String status) {
    }
}

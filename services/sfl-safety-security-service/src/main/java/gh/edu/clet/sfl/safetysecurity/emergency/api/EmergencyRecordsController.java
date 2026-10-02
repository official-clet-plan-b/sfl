package gh.edu.clet.sfl.safetysecurity.emergency.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.emergency.application.service.EmergencyRecordsService;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.AudienceGroup;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.EmergencyScenario;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.Priority;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecipientZone;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.RecordLifecycle;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** SRS-SFL-S174-01: operational records - templates, scenarios, audience groups and recipient zones. */
@RestController
@RequestMapping("/api/v1/emergency")
public class EmergencyRecordsController {

    private final EmergencyRecordsService service;
    private final EmergencyActorResolver actors;

    public EmergencyRecordsController(EmergencyRecordsService service, EmergencyActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/templates")
    @Tag(name = "Emergency Templates")
    public ResponseEntity<ApiResponse<NotificationTemplate>> createTemplate(@Valid @RequestBody TemplateRequest r,
            HttpServletRequest h) {
        var t = service.createTemplate(new EmergencyRecordsService.CreateTemplate(r.siteCode(), r.templateCode(),
                r.title(), r.body(), r.channels(), r.breakGlassEligible(), actors.resolve(h),
                actors.resolveSourceChannel(h), Boolean.TRUE.equals(r.drill())));
        return ResponseEntity.created(URI.create("/api/v1/emergency/templates/" + t.id())).body(ApiResponse.ok(t));
    }

    @GetMapping("/templates")
    @Tag(name = "Emergency Templates")
    public ApiResponse<EmergencyPageResponse<NotificationTemplate>> templates(@RequestParam String siteCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RecordLifecycle lifecycle,
            @RequestParam(required = false) Boolean breakGlassEligible,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            HttpServletRequest h) {
        return ApiResponse.ok(EmergencyPageResponse.of(service.templates(siteCode, search, lifecycle,
                breakGlassEligible, EmergencyPageResponse.paging(page, size, sort), actors.resolve(h))));
    }

    @GetMapping("/templates/{id}")
    @Tag(name = "Emergency Templates")
    public ApiResponse<NotificationTemplate> template(@PathVariable UUID id, HttpServletRequest h) {
        return ApiResponse.ok(service.template(id, actors.resolve(h)));
    }

    @PostMapping("/scenarios")
    @Tag(name = "Emergency Scenarios")
    public ResponseEntity<ApiResponse<EmergencyScenario>> createScenario(@Valid @RequestBody ScenarioRequest r,
            HttpServletRequest h) {
        var s = service.createScenario(new EmergencyRecordsService.CreateScenario(r.siteCode(), r.scenarioCode(),
                r.name(), r.priority(), r.defaultTemplateId(), r.breakGlassEligible(), actors.resolve(h),
                actors.resolveSourceChannel(h)));
        return ResponseEntity.created(URI.create("/api/v1/emergency/scenarios/" + s.id())).body(ApiResponse.ok(s));
    }

    @GetMapping("/scenarios")
    @Tag(name = "Emergency Scenarios")
    public ApiResponse<EmergencyPageResponse<EmergencyScenario>> scenarios(@RequestParam String siteCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RecordLifecycle lifecycle,
            @RequestParam(required = false) Boolean breakGlassEligible,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            HttpServletRequest h) {
        return ApiResponse.ok(EmergencyPageResponse.of(service.scenarios(siteCode, search, lifecycle,
                breakGlassEligible, EmergencyPageResponse.paging(page, size, sort), actors.resolve(h))));
    }

    /** Scenario detail. Closes half of gap 5 - only templates had a detail endpoint. */
    @GetMapping("/scenarios/{id}")
    @Tag(name = "Emergency Scenarios")
    public ApiResponse<EmergencyScenario> scenario(@PathVariable UUID id, HttpServletRequest h) {
        return ApiResponse.ok(service.scenario(id, actors.resolve(h)));
    }

    @PostMapping("/audience-groups")
    @Tag(name = "Audience Groups")
    public ResponseEntity<ApiResponse<AudienceGroup>> createAudience(@Valid @RequestBody AudienceRequest r,
            HttpServletRequest h) {
        var a = service.createAudienceGroup(new EmergencyRecordsService.CreateAudienceGroup(r.siteCode(), r.groupCode(),
                r.name(), r.directoryReference(), r.recipientCount(), actors.resolve(h), actors.resolveSourceChannel(h)));
        return ResponseEntity.created(URI.create("/api/v1/emergency/audience-groups/" + a.id()))
                .body(ApiResponse.ok(a));
    }

    @GetMapping("/audience-groups")
    @Tag(name = "Audience Groups")
    public ApiResponse<EmergencyPageResponse<AudienceGroup>> audiences(@RequestParam String siteCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RecordLifecycle lifecycle,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            HttpServletRequest h) {
        return ApiResponse.ok(EmergencyPageResponse.of(service.audienceGroups(siteCode, search, lifecycle,
                EmergencyPageResponse.paging(page, size, sort), actors.resolve(h))));
    }

    @GetMapping("/audience-groups/{id}")
    @Tag(name = "Audience Groups")
    public ApiResponse<AudienceGroup> audience(@PathVariable UUID id, HttpServletRequest h) {
        return ApiResponse.ok(service.audienceGroup(id, actors.resolve(h)));
    }

    /**
     * Corrects an audience group's size and directory pointer.
     *
     * <p>The sharp edge in gap 6: {@code recipientCount} is what the service fans out to and the
     * denominator every delivery percentage is read against, and it could not be corrected. A group
     * sized at zero sent to nobody and reported a completely successful broadcast. The name is
     * deliberately not editable - closed activations cite this group, and renaming it would rewrite
     * what they say they were sent to.
     */
    @PatchMapping("/audience-groups/{id}")
    @Tag(name = "Audience Groups")
    public ApiResponse<AudienceGroup> updateAudience(@PathVariable UUID id,
            @Valid @RequestBody UpdateAudienceRequest r, HttpServletRequest h) {
        return ApiResponse.ok(service.updateAudienceGroup(id, r.directoryReference(), r.recipientCount(),
                actors.resolve(h), actors.resolveSourceChannel(h)));
    }

    @PostMapping("/recipient-zones")
    @Tag(name = "Recipient Zones")
    public ResponseEntity<ApiResponse<RecipientZone>> createZone(@Valid @RequestBody ZoneRequest r,
            HttpServletRequest h) {
        var z = service.createRecipientZone(new EmergencyRecordsService.CreateRecipientZone(r.siteCode(), r.zoneCode(),
                r.name(), r.locationReference(), actors.resolve(h), actors.resolveSourceChannel(h)));
        return ResponseEntity.created(URI.create("/api/v1/emergency/recipient-zones/" + z.id()))
                .body(ApiResponse.ok(z));
    }

    @GetMapping("/recipient-zones")
    @Tag(name = "Recipient Zones")
    public ApiResponse<EmergencyPageResponse<RecipientZone>> zones(@RequestParam String siteCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RecordLifecycle lifecycle,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            HttpServletRequest h) {
        return ApiResponse.ok(EmergencyPageResponse.of(service.recipientZones(siteCode, search, lifecycle,
                EmergencyPageResponse.paging(page, size, sort), actors.resolve(h))));
    }

    @GetMapping("/recipient-zones/{id}")
    @Tag(name = "Recipient Zones")
    public ApiResponse<RecipientZone> zone(@PathVariable UUID id, HttpServletRequest h) {
        return ApiResponse.ok(service.recipientZone(id, actors.resolve(h)));
    }

    /**
     * Retires or reinstates a master-data record.
     *
     * <p>The other half of gap 6. Every one of these records carries {@code withLifecycle} on its
     * domain type and nothing called it, so an obsolete template stayed selectable forever.
     * Archiving is not deletion: activations citing the record still resolve it.
     */
    @PatchMapping("/{resourceType}/{id}/lifecycle")
    public ApiResponse<Object> setLifecycle(@PathVariable String resourceType, @PathVariable UUID id,
            @Valid @RequestBody LifecycleRequest r, HttpServletRequest h) {
        String type = switch (resourceType) {
            case "templates" -> "NotificationTemplate";
            case "scenarios" -> "EmergencyScenario";
            case "audience-groups" -> "AudienceGroup";
            case "recipient-zones" -> "RecipientZone";
            default -> throw new IllegalArgumentException("Unknown emergency record type: " + resourceType);
        };
        return ApiResponse.ok(service.setLifecycle(type, id, r.lifecycle(), actors.resolve(h),
                actors.resolveSourceChannel(h)));
    }

    public record UpdateAudienceRequest(String directoryReference, Integer recipientCount) {}

    public record LifecycleRequest(@NotNull RecordLifecycle lifecycle) {}

    /**
     * @param drill a drill-only template for S175 exercises; its title and body must both open with
     *        "DRILL - THIS IS AN EXERCISE", and a real template may not contain that phrase at all
     */
    public record TemplateRequest(@NotBlank String siteCode, String templateCode, @NotBlank String title,
            @NotBlank String body, @NotNull List<ChannelType> channels, boolean breakGlassEligible, Boolean drill) {}

    public record ScenarioRequest(@NotBlank String siteCode, String scenarioCode, @NotBlank String name,
            @NotNull Priority priority, UUID defaultTemplateId, boolean breakGlassEligible) {}

    public record AudienceRequest(@NotBlank String siteCode, String groupCode, @NotBlank String name,
            String directoryReference, int recipientCount) {}

    public record ZoneRequest(@NotBlank String siteCode, String zoneCode, @NotBlank String name,
            String locationReference) {}
}

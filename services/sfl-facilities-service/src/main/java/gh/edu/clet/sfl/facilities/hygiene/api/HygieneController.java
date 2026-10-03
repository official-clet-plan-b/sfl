package gh.edu.clet.sfl.facilities.hygiene.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.Caller;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneControlService;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneFindingService;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneStore;
import gh.edu.clet.sfl.facilities.hygiene.domain.ActionStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosureMode;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlType;
import gh.edu.clet.sfl.facilities.hygiene.domain.Frequency;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEscalation;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEvidence;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.hygiene.domain.RiskCategory;
import gh.edu.clet.sfl.facilities.hygiene.domain.Severity;
import gh.edu.clet.sfl.facilities.shared.api.PageResponse;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S170 Hygiene &amp; Pest-Control Audit Tracker. Creates answer 201; an unknown id answers 404; a transition
 * the record is not in a position to make answers 422; and every transition accepts the version the caller
 * last saw, answering 409 if someone else got there first.
 */
@RestController
@RequestMapping("/api/v1/facilities/hygiene")
public class HygieneController {

    private final HygieneControlService controls;
    private final HygieneFindingService findings;

    public HygieneController(HygieneControlService controls, HygieneFindingService findings) {
        this.controls = controls;
        this.findings = findings;
    }

    // ---- dashboard and escalations

    @GetMapping("/dashboard")
    public ApiResponse<HygieneControlService.Dashboard> dashboard(@RequestParam String siteCode,
            @RequestParam(defaultValue = "90") int periodDays, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(controls.dashboard(siteCode, periodDays, new Caller(actor, channel)));
    }

    @GetMapping("/escalations")
    public ApiResponse<PageResponse<HygieneEscalation>> escalations(@RequestParam String siteCode,
            @RequestParam(defaultValue = "false") boolean openOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(controls.escalations(siteCode, openOnly, page, size, new Caller(actor, channel))));
    }

    @PostMapping("/escalations/{id}/acknowledge")
    public ApiResponse<HygieneEscalation> acknowledge(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(controls.acknowledge(id, new Caller(actor, channel)));
    }

    // ---- controls

    @GetMapping("/controls")
    public ApiResponse<PageResponse<ControlResponse>> listControls(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(required = false) String controlType,
            @RequestParam(defaultValue = "false") boolean overdueOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, ActorContext actor, SourceChannel channel) {
        LocalDate today = LocalDate.now();
        return ApiResponse.ok(paged(controls.list(siteCode, status, controlType, overdueOnly, page, size,
                new Caller(actor, channel)), control -> ControlResponse.of(control, today)));
    }

    @PostMapping("/controls")
    public ResponseEntity<ApiResponse<ControlResponse>> createControl(@Valid @RequestBody CreateControlRequest request,
            ActorContext actor, SourceChannel channel) {
        HygieneControl created = controls.create(new HygieneCommands.CreateControl(request.siteCode(),
                request.roomId(), request.locationLabel(), request.controlType(), request.riskCategory(),
                request.title(), request.ownerReference(), request.frequency(), request.dueOn(),
                request.providerReference(), request.notes(), new Caller(actor, channel)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ControlResponse.of(created, LocalDate.now())));
    }

    @GetMapping("/controls/{id}")
    public ApiResponse<HygieneControlService.ControlDetail> control(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(controls.get(id, new Caller(actor, channel)));
    }

    @PostMapping("/controls/{id}/start")
    public ApiResponse<HygieneControl> start(@PathVariable UUID id, @RequestBody(required = false) VersionRequest body,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(controls.start(id, version(body), new Caller(actor, channel)));
    }

    @PostMapping("/controls/{id}/complete")
    public ApiResponse<HygieneControlService.Completion> complete(@PathVariable UUID id,
            @Valid @RequestBody(required = false) CompleteRequest body, ActorContext actor, SourceChannel channel) {
        CompleteRequest request = body == null ? new CompleteRequest(null, null, null) : body;
        return ApiResponse.ok(controls.complete(new HygieneCommands.CompleteControl(id, request.completedOn(),
                request.notes(), request.version(), new Caller(actor, channel))));
    }

    @PostMapping("/controls/{id}/missed")
    public ApiResponse<HygieneControlService.Completion> missed(@PathVariable UUID id,
            @Valid @RequestBody ReasonRequest body, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(controls.markMissed(id, body.reason(), body.version(), new Caller(actor, channel)));
    }

    @PostMapping("/controls/{id}/cancel")
    public ApiResponse<HygieneControl> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(controls.cancel(id, body.reason(), body.version(), new Caller(actor, channel)));
    }

    @PostMapping("/controls/{id}/confirm-provider")
    public ApiResponse<HygieneControl> confirmProvider(@PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(controls.confirmProvider(id, version(body), new Caller(actor, channel)));
    }

    // ---- findings

    @GetMapping("/findings")
    public ApiResponse<PageResponse<HygieneFinding>> listFindings(@RequestParam String siteCode,
            @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) UUID controlId, @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(paged(findings.list(siteCode, status, severity, controlId, overdueOnly, page, size,
                new Caller(actor, channel))));
    }

    @PostMapping("/controls/{controlId}/findings")
    public ResponseEntity<ApiResponse<HygieneFinding>> createFinding(@PathVariable UUID controlId,
            @Valid @RequestBody CreateFindingRequest request, ActorContext actor, SourceChannel channel) {
        HygieneFinding created = findings.create(new HygieneCommands.CreateFinding(controlId, request.roomId(),
                request.category(), request.title(), request.description(), request.severity(),
                request.ownerReference(), request.targetDate(), Boolean.TRUE.equals(request.requiresIncident()),
                new Caller(actor, channel)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/findings/{id}")
    public ApiResponse<HygieneFindingService.FindingDetail> finding(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(findings.get(id, new Caller(actor, channel)));
    }

    @PostMapping("/findings/{id}/update")
    public ApiResponse<HygieneFinding> updateFinding(@PathVariable UUID id,
            @Valid @RequestBody UpdateFindingRequest body, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.update(new HygieneCommands.UpdateFinding(id, body.title(), body.description(),
                body.ownerReference(), body.targetDate(), body.version(), new Caller(actor, channel))));
    }

    @PostMapping("/findings/{id}/start")
    public ApiResponse<HygieneFinding> startFinding(@PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.start(id, version(body), new Caller(actor, channel)));
    }

    @PostMapping("/findings/{id}/link-incident")
    public ApiResponse<HygieneFinding> linkIncident(@PathVariable UUID id,
            @Valid @RequestBody LinkIncidentRequest body, ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.linkIncident(id, body.incidentReference(), new Caller(actor, channel)));
    }

    @PostMapping("/findings/{id}/retry-work-order")
    public ApiResponse<HygieneFinding> retryWorkOrder(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(findings.retryWorkOrder(id, new Caller(actor, channel)));
    }

    @PostMapping("/findings/{id}/close")
    public ApiResponse<HygieneFinding> close(@PathVariable UUID id, @Valid @RequestBody CloseRequest body,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.close(new HygieneCommands.CloseFinding(id, body.mode(), body.reason(),
                body.version(), new Caller(actor, channel))));
    }

    @PostMapping("/findings/{id}/reopen")
    public ApiResponse<HygieneFinding> reopen(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.reopen(id, body.reason(), new Caller(actor, channel)));
    }

    // ---- actions

    @PostMapping("/findings/{id}/actions")
    public ResponseEntity<ApiResponse<HygieneAction>> addAction(@PathVariable UUID id,
            @Valid @RequestBody AddActionRequest body, ActorContext actor, SourceChannel channel) {
        HygieneAction created = findings.addAction(new HygieneCommands.AddAction(id, body.description(),
                body.ownerReference(), body.dueOn(), new Caller(actor, channel)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PostMapping("/actions/{id}/transition")
    public ApiResponse<HygieneAction> moveAction(@PathVariable UUID id, @Valid @RequestBody ActionMoveRequest body,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.moveAction(id, body.status(), body.reason(), body.version(),
                new Caller(actor, channel)));
    }

    // ---- evidence

    @GetMapping("/findings/{id}/evidence")
    public ApiResponse<List<HygieneEvidence>> evidence(@PathVariable UUID id, ActorContext actor,
            SourceChannel channel) {
        return ApiResponse.ok(findings.evidence(id, new Caller(actor, channel)));
    }

    @PostMapping("/findings/{id}/evidence")
    public ResponseEntity<ApiResponse<HygieneEvidence>> submitEvidence(@PathVariable UUID id,
            @Valid @RequestBody EvidenceRequest body, ActorContext actor, SourceChannel channel) {
        HygieneEvidence created = findings.submitEvidence(new HygieneCommands.SubmitEvidence(id, body.actionId(),
                body.reference(), body.fileName(), body.mediaType(), body.sizeBytes(), body.contentHash(),
                body.retentionClass(), body.notes(), new Caller(actor, channel)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PostMapping("/evidence/{id}/review")
    public ApiResponse<HygieneEvidence> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest body,
            ActorContext actor, SourceChannel channel) {
        return ApiResponse.ok(findings.reviewEvidence(id, body.accept(), body.reason(), new Caller(actor, channel)));
    }

    // ---- shapes

    private static <T> PageResponse<T> paged(HygieneStore.Page<T> page) {
        return paged(page, Function.identity());
    }

    private static <D, R> PageResponse<R> paged(HygieneStore.Page<D> page, Function<D, R> mapper) {
        int size = Math.max(1, page.size());
        return new PageResponse<>(page.items().stream().map(mapper).toList(), page.total(),
                (int) Math.ceil(page.total() / (double) size), page.page(), page.size());
    }

    private static Long version(VersionRequest body) {
        return body == null ? null : body.version();
    }

    /** A control as a reader sees it: the stored record plus DUE / OVERDUE worked out for today. */
    public record ControlResponse(HygieneControl control, String effectiveStatus) {
        static ControlResponse of(HygieneControl control, LocalDate today) {
            return new ControlResponse(control, control.effectiveStatus(today));
        }
    }

    public record CreateControlRequest(@NotBlank @Size(max = 40) String siteCode, UUID roomId,
            @Size(max = 160) String locationLabel, @NotNull ControlType controlType,
            @NotNull RiskCategory riskCategory, @NotBlank @Size(max = 240) String title,
            @NotBlank @Size(max = 160) String ownerReference, @NotNull Frequency frequency, @NotNull LocalDate dueOn,
            @Size(max = 160) String providerReference, @Size(max = 2000) String notes) {
    }

    public record VersionRequest(Long version) {
    }

    public record CompleteRequest(LocalDate completedOn, @Size(max = 2000) String notes, Long version) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason, Long version) {
    }

    public record CreateFindingRequest(UUID roomId, RiskCategory category, @NotBlank @Size(max = 240) String title,
            @Size(max = 4000) String description, @NotNull Severity severity, @Size(max = 160) String ownerReference,
            LocalDate targetDate, Boolean requiresIncident) {
    }

    public record UpdateFindingRequest(@Size(max = 240) String title, @Size(max = 4000) String description,
            @Size(max = 160) String ownerReference, LocalDate targetDate, Long version) {
    }

    public record LinkIncidentRequest(@NotBlank @Size(max = 80) String incidentReference) {
    }

    public record CloseRequest(@NotNull ClosureMode mode, @Size(max = 2000) String reason, Long version) {
    }

    public record AddActionRequest(@NotBlank @Size(max = 2000) String description,
            @NotBlank @Size(max = 160) String ownerReference, @NotNull LocalDate dueOn) {
    }

    public record ActionMoveRequest(@NotNull ActionStatus status, @Size(max = 2000) String reason, Long version) {
    }

    public record EvidenceRequest(UUID actionId, @NotBlank @Size(max = 240) String reference,
            @NotBlank @Size(max = 240) String fileName, @NotBlank @Size(max = 120) String mediaType,
            @Positive long sizeBytes, @NotBlank @Size(max = 128) String contentHash,
            @Size(max = 20) String retentionClass, @Size(max = 2000) String notes) {
    }

    public record ReviewRequest(boolean accept, @Size(max = 2000) String reason) {
    }
}

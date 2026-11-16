package gh.edu.clet.sfl.safetysecurity.cctv.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.cctv.application.service.AnalyticsAlertService;
import gh.edu.clet.sfl.safetysecurity.cctv.application.service.CameraService;
import gh.edu.clet.sfl.safetysecurity.cctv.application.service.DisclosureService;
import gh.edu.clet.sfl.safetysecurity.cctv.application.service.EvidenceRequestService;
import gh.edu.clet.sfl.safetysecurity.cctv.application.service.LiveViewService;
import gh.edu.clet.sfl.safetysecurity.cctv.application.service.RetentionService;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.AlertStatus;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.AnalyticsAlert;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.Camera;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.Disclosure;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.EvidenceAccessAction;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.EvidenceAccessLog;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.EvidenceItem;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.EvidenceRequest;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.LiveViewSession;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.RetentionPolicy;
import gh.edu.clet.sfl.safetysecurity.cctv.domain.model.RetentionScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
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

/**
 * SRS-SFL-S161-01..05: the operational surface over cameras, evidence requests/items, the SOC
 * analytics-alert queue, disclosures, live-view sessions and retention - everything that is not raw
 * integration ingestion (see {@code CctvIntegrationController}). One controller, following {@code
 * AccessControlController}'s precedent of grouping a module's several sub-resources together.
 */
@RestController
@RequestMapping("/api/v1/cctv")
@Tag(name = "S161 CCTV / Video Management System")
public class CctvController {

    private final CameraService cameras;
    private final EvidenceRequestService evidenceRequests;
    private final AnalyticsAlertService alerts;
    private final DisclosureService disclosures;
    private final LiveViewService liveView;
    private final RetentionService retention;
    private final CctvActorResolver actors;

    public CctvController(CameraService cameras, EvidenceRequestService evidenceRequests,
            AnalyticsAlertService alerts, DisclosureService disclosures, LiveViewService liveView,
            RetentionService retention, CctvActorResolver actors) {
        this.cameras = cameras;
        this.evidenceRequests = evidenceRequests;
        this.alerts = alerts;
        this.disclosures = disclosures;
        this.liveView = liveView;
        this.retention = retention;
        this.actors = actors;
    }

    // ---- Cameras (S161-01) ----

    @PostMapping("/cameras")
    @Operation(summary = "Register a camera in the inventory reference")
    public ResponseEntity<ApiResponse<Camera>> registerCamera(@Valid @RequestBody RegisterCameraRequest request,
            HttpServletRequest http) {
        Camera camera = cameras.register(new CameraService.RegisterCamera(request.siteCode(), request.cameraId(),
                request.name(), request.locationRef(), request.coverageArea(), request.examinationArea(),
                actors.resolve(http)));
        return ResponseEntity.created(URI.create("/api/v1/cctv/cameras/" + camera.id())).body(ApiResponse.ok(camera));
    }

    @GetMapping("/cameras")
    @Operation(summary = "List a site's cameras and their current health")
    public ApiResponse<List<Camera>> camerasForSite(@RequestParam String siteCode, HttpServletRequest http) {
        return ApiResponse.ok(cameras.forSite(siteCode, actors.resolve(http)));
    }

    // ---- Evidence requests and items (S161-02/03) ----

    @PostMapping("/evidence-requests")
    @Operation(summary = "Request evidence for one or more cameras and a time window",
            description = "SRS-SFL-S161-02: requires approval by an authorised security role before any "
                    + "retrieval.")
    public ResponseEntity<ApiResponse<EvidenceRequest>> requestEvidence(
            @Valid @RequestBody RequestEvidenceRequest request, HttpServletRequest http) {
        EvidenceRequest saved = evidenceRequests.create(new EvidenceRequestService.CreateRequest(request.siteCode(),
                request.cameraIds(), request.locationRef(), request.windowStart(), request.windowEnd(),
                request.purpose(), request.caseRef(), actors.resolve(http)));
        return ResponseEntity.created(URI.create("/api/v1/cctv/evidence-requests/" + saved.id()))
                .body(ApiResponse.ok(saved));
    }

    @PatchMapping("/evidence-requests/{id}/approval")
    @Operation(summary = "Approve an evidence request")
    public ApiResponse<EvidenceRequest> approveEvidenceRequest(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(evidenceRequests.approve(new EvidenceRequestService.Decide(id, null,
                actors.resolve(http))));
    }

    @PatchMapping("/evidence-requests/{id}/rejection")
    @Operation(summary = "Reject an evidence request")
    public ApiResponse<EvidenceRequest> rejectEvidenceRequest(@PathVariable UUID id,
            @Valid @RequestBody RejectEvidenceRequestRequest request, HttpServletRequest http) {
        return ApiResponse.ok(evidenceRequests.reject(new EvidenceRequestService.Decide(id, request.reason(),
                actors.resolve(http))));
    }

    @GetMapping("/evidence-requests")
    @Operation(summary = "List a site's evidence requests")
    public ApiResponse<List<EvidenceRequest>> evidenceRequestsForSite(@RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(evidenceRequests.forSite(siteCode, actors.resolve(http)));
    }

    @PostMapping("/evidence-requests/{id}/items")
    @Operation(summary = "Retrieve footage for one camera from an approved request",
            description = "SRS-SFL-S161-03: recorded by reference with a hash and provenance; blocked "
                    + "unless the request is approved.")
    public ResponseEntity<ApiResponse<EvidenceItem>> retrieveEvidence(@PathVariable UUID id,
            @Valid @RequestBody RetrieveEvidenceRequest request, HttpServletRequest http) {
        EvidenceItem item = evidenceRequests.retrieve(new EvidenceRequestService.Retrieve(id, request.cameraId(),
                actors.resolve(http)));
        return ResponseEntity.created(URI.create("/api/v1/cctv/evidence-items/" + item.id()))
                .body(ApiResponse.ok(item));
    }

    @GetMapping("/evidence-requests/{id}/items")
    @Operation(summary = "List the evidence items retrieved for a request")
    public ApiResponse<List<EvidenceItem>> itemsForRequest(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(evidenceRequests.itemsForRequest(id, actors.resolve(http)));
    }

    @PostMapping("/evidence-items/{id}/access")
    @Operation(summary = "Log a view, download or export of an evidence item")
    public ApiResponse<EvidenceAccessLog> recordEvidenceAccess(@PathVariable UUID id,
            @Valid @RequestBody RecordAccessRequest request, HttpServletRequest http) {
        return ApiResponse.ok(evidenceRequests.recordAccess(new EvidenceRequestService.RecordAccess(id,
                request.action(), actors.resolve(http))));
    }

    @GetMapping("/evidence-items/{id}/access")
    @Operation(summary = "The full access log for one evidence item")
    public ApiResponse<List<EvidenceAccessLog>> evidenceAccessLog(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(evidenceRequests.accessLogFor(id, actors.resolve(http)));
    }

    // ---- SOC analytics-alert queue (S161-04) ----

    @PatchMapping("/analytics-alerts/{id}/acknowledgement")
    @Operation(summary = "Acknowledge a video-analytics alert in the SOC queue")
    public ApiResponse<AnalyticsAlert> acknowledgeAlert(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(alerts.acknowledge(id, actors.resolve(http)));
    }

    @PatchMapping("/analytics-alerts/{id}/resolution")
    @Operation(summary = "Resolve a video-analytics alert in the SOC queue")
    public ApiResponse<AnalyticsAlert> resolveAlert(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(alerts.resolve(id, actors.resolve(http)));
    }

    @GetMapping("/analytics-alerts")
    @Operation(summary = "The SOC analytics-alert queue for a site")
    public ApiResponse<List<AnalyticsAlert>> alertQueue(@RequestParam String siteCode,
            @RequestParam(defaultValue = "OPEN") AlertStatus status, HttpServletRequest http) {
        return ApiResponse.ok(alerts.queue(siteCode, status, actors.resolve(http)));
    }

    // ---- Live view (S161-04) ----

    @PostMapping("/live-view")
    @Operation(summary = "Start a role-restricted live-view session on one or more cameras")
    public ResponseEntity<ApiResponse<LiveViewSession>> startLiveView(@Valid @RequestBody StartLiveViewRequest request,
            HttpServletRequest http) {
        LiveViewSession session = liveView.start(new LiveViewService.StartSession(request.siteCode(),
                request.cameraIds(), actors.resolve(http)));
        return ResponseEntity.created(URI.create("/api/v1/cctv/live-view/" + session.id()))
                .body(ApiResponse.ok(session));
    }

    @PatchMapping("/live-view/{id}/end")
    @Operation(summary = "End a live-view session")
    public ApiResponse<LiveViewSession> endLiveView(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(liveView.end(id, actors.resolve(http)));
    }

    // ---- Disclosures (S161-05) ----

    @PostMapping("/disclosures")
    @Operation(summary = "Request disclosure of an evidence item outside GSL")
    public ResponseEntity<ApiResponse<Disclosure>> requestDisclosure(@Valid @RequestBody RequestDisclosureRequest
            request, HttpServletRequest http) {
        Disclosure disclosure = disclosures.request(new DisclosureService.RequestDisclosure(request.evidenceItemId(),
                request.purpose(), request.recipient(), actors.resolve(http)));
        return ResponseEntity.created(URI.create("/api/v1/cctv/disclosures/" + disclosure.id()))
                .body(ApiResponse.ok(disclosure));
    }

    @PatchMapping("/disclosures/{id}/approval")
    @Operation(summary = "Approve a disclosure - the release event itself")
    public ApiResponse<Disclosure> approveDisclosure(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(disclosures.approve(id, actors.resolve(http)));
    }

    @PatchMapping("/disclosures/{id}/rejection")
    @Operation(summary = "Reject a disclosure")
    public ApiResponse<Disclosure> rejectDisclosure(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(disclosures.reject(id, actors.resolve(http)));
    }

    @GetMapping("/disclosures")
    @Operation(summary = "List a site's disclosures")
    public ApiResponse<List<Disclosure>> disclosuresForSite(@RequestParam String siteCode, HttpServletRequest http) {
        return ApiResponse.ok(disclosures.forSite(siteCode, actors.resolve(http)));
    }

    // ---- Retention (S161-05) ----

    @PostMapping("/retention-policies")
    @Operation(summary = "Define a retention schedule for one camera or zone")
    public ResponseEntity<ApiResponse<RetentionPolicy>> defineRetentionPolicy(
            @Valid @RequestBody DefineRetentionPolicyRequest request, HttpServletRequest http) {
        RetentionPolicy policy = retention.define(new RetentionService.DefinePolicy(request.siteCode(),
                request.scope(), request.scopeRef(), request.retentionDays(), actors.resolve(http)));
        return ResponseEntity.created(URI.create("/api/v1/cctv/retention-policies/" + policy.id()))
                .body(ApiResponse.ok(policy));
    }

    @PatchMapping("/retention-policies/legal-hold")
    @Operation(summary = "Place a legal hold on a camera or zone's retention policy")
    public ApiResponse<RetentionPolicy> placeLegalHold(@Valid @RequestBody LegalHoldRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(retention.placeLegalHold(request.siteCode(), request.scope(), request.scopeRef(),
                request.reason(), actors.resolve(http)));
    }

    @PatchMapping("/retention-policies/legal-hold/release")
    @Operation(summary = "Release a legal hold on a camera or zone's retention policy")
    public ApiResponse<RetentionPolicy> releaseLegalHold(@Valid @RequestBody ReleaseLegalHoldRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(retention.releaseLegalHold(request.siteCode(), request.scope(), request.scopeRef(),
                actors.resolve(http)));
    }

    public record RegisterCameraRequest(@NotBlank String siteCode, @NotBlank String cameraId, @NotBlank String name,
            String locationRef, String coverageArea, boolean examinationArea) {
    }

    public record RequestEvidenceRequest(@NotBlank String siteCode, @NotNull List<String> cameraIds,
            String locationRef, @NotNull Instant windowStart, @NotNull Instant windowEnd, @NotBlank String purpose,
            UUID caseRef) {
    }

    public record RejectEvidenceRequestRequest(@NotBlank String reason) {
    }

    public record RetrieveEvidenceRequest(@NotBlank String cameraId) {
    }

    public record RecordAccessRequest(@NotNull EvidenceAccessAction action) {
    }

    public record StartLiveViewRequest(@NotBlank String siteCode, @NotNull List<String> cameraIds) {
    }

    public record RequestDisclosureRequest(@NotNull UUID evidenceItemId, @NotBlank String purpose,
            @NotBlank String recipient) {
    }

    public record DefineRetentionPolicyRequest(@NotBlank String siteCode, @NotNull RetentionScope scope,
            @NotBlank String scopeRef, int retentionDays) {
    }

    public record LegalHoldRequest(@NotBlank String siteCode, @NotNull RetentionScope scope, @NotBlank String scopeRef,
            @NotBlank String reason) {
    }

    public record ReleaseLegalHoldRequest(@NotBlank String siteCode, @NotNull RetentionScope scope,
            @NotBlank String scopeRef) {
    }
}

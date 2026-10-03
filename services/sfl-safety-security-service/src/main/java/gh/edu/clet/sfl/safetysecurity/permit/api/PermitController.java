package gh.edu.clet.sfl.safetysecurity.permit.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.ApproveBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.CompetencyBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.CompletionBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.CreateBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.DecisionBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.EvidenceBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.ExtensionBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.IncidentLinkBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.IsolationBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.NoteBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.PermitBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.ReasonBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.ReviseBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.VerifyIsolationBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.VersionBody;
import gh.edu.clet.sfl.safetysecurity.permit.api.PermitRequests.WorkerBody;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.Caller;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitApprovalService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitDetail;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitLifecycleService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitQueryService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitRequestService;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S164 Permit-to-Work / Hot-Work Authorisation. Creates answer 201; an unknown id 404; a refusal the SRS names (no risk assessment, assessment
 * not current, isolation not verified, competence exception, isolation removal not recorded) 422 with the reason in the body; self-approval and
 * a non-independent verifier 403; and every transition accepts the version the caller last saw, answering 409 if someone got there first.
 */
@RestController
@RequestMapping("/api/v1/permits")
@Tag(name = "S164 Permit-to-Work")
public class PermitController {

    private final PermitRequestService requests;
    private final PermitApprovalService approvals;
    private final PermitLifecycleService lifecycle;
    private final PermitQueryService queries;
    private final PermitActorResolver actors;

    public PermitController(PermitRequestService requests, PermitApprovalService approvals, PermitLifecycleService lifecycle, PermitQueryService queries,
            PermitActorResolver actors) {
        this.requests = requests;
        this.approvals = approvals;
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.actors = actors;
    }

    private Caller caller(HttpServletRequest http) {
        return new Caller(actors.resolve(http), actors.resolveSourceChannel(http));
    }

    private static PermitRequestService.PermitInput input(PermitBody b) {
        return new PermitRequestService.PermitInput(b.permitTypeId(), b.title(), b.workDescription(), b.locationCode(), b.zoneId(), b.startsAt(), b.endsAt(),
                b.riskAssessmentId(), b.contractorReference(), b.supervisorReference(), b.supervisorContact(), b.originSystem(), b.originReference());
    }

    // ---- register and request (S164-01)

    @GetMapping
    @Operation(summary = "Search the permit register",
            description = "Without siteCode, every site the caller is scoped to. from/to bound startsAt. openOnly lists issued, unclosed permits; overdueOnly those past "
                    + "their validity window. sort: startsAt (default, newest first), endsAt, reference, status, title or updatedAt, with ',asc' to reverse.")
    public ApiResponse<PermitPageResponse<Permit>> search(@RequestParam(required = false) String siteCode, @RequestParam(required = false) UUID permitTypeId,
            @RequestParam(required = false) PermitStatus status, @RequestParam(required = false) String contractor, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "false") boolean openOnly, @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort, HttpServletRequest http) {
        return ApiResponse.ok(PermitPageResponse.of(queries.search(new PermitQueryService.Search(siteCode, permitTypeId, status, contractor, from, to, openOnly,
                overdueOnly, q, new Paging(page, size, sort)), caller(http))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One permit with its complete record and what stands between it and its next step")
    public ApiResponse<PermitDetail> get(@PathVariable UUID id, HttpServletRequest http) {
        return ApiResponse.ok(queries.get(id, caller(http)));
    }

    @PostMapping
    @Operation(summary = "Start a permit request as a draft")
    public ResponseEntity<ApiResponse<PermitDetail>> create(@Valid @RequestBody CreateBody body, HttpServletRequest http) {
        List<PermitRequestService.WorkerInput> workers = body.workers() == null ? List.of() : body.workers().stream()
                .map(w -> new PermitRequestService.WorkerInput(w.personReference(), w.displayName(), w.workRole())).toList();
        List<PermitRequestService.IsolationInput> isolations = body.isolations() == null ? List.of() : body.isolations().stream()
                .map(i -> new PermitRequestService.IsolationInput(i.kind(), i.description(), i.tagReference())).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(requests.create(body.siteCode(), input(body.permit()), workers, isolations, caller(http))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Revise a draft - only its requester, only before submission")
    public ApiResponse<PermitDetail> revise(@PathVariable UUID id, @Valid @RequestBody ReviseBody body, HttpServletRequest http) {
        return ApiResponse.ok(requests.reviseDraft(id, input(body.permit()), body.version(), caller(http)));
    }

    @PostMapping("/{id}/workers")
    public ApiResponse<PermitDetail> addWorker(@PathVariable UUID id, @Valid @RequestBody WorkerBody body, HttpServletRequest http) {
        return ApiResponse.ok(requests.addWorker(id, new PermitRequestService.WorkerInput(body.personReference(), body.displayName(), body.workRole()), caller(http)));
    }

    @DeleteMapping("/{id}/workers/{workerId}")
    public ApiResponse<PermitDetail> removeWorker(@PathVariable UUID id, @PathVariable UUID workerId, HttpServletRequest http) {
        return ApiResponse.ok(requests.removeWorker(id, workerId, caller(http)));
    }

    @PostMapping("/{id}/isolations")
    public ApiResponse<PermitDetail> addIsolation(@PathVariable UUID id, @Valid @RequestBody IsolationBody body, HttpServletRequest http) {
        return ApiResponse.ok(requests.addIsolation(id, new PermitRequestService.IsolationInput(body.kind(), body.description(), body.tagReference()), caller(http)));
    }

    @DeleteMapping("/{id}/isolations/{isolationId}")
    public ApiResponse<PermitDetail> removeIsolationFromDraft(@PathVariable UUID id, @PathVariable UUID isolationId, HttpServletRequest http) {
        return ApiResponse.ok(requests.removeIsolation(id, isolationId, caller(http)));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit for approval", description = "Refused with PERMIT_NO_RISK_ASSESSMENT or PERMIT_RISK_ASSESSMENT_NOT_CURRENT when S165 does not stand behind the linked assessment.")
    public ApiResponse<PermitDetail> submit(@PathVariable UUID id, @RequestBody(required = false) VersionBody body, HttpServletRequest http) {
        return ApiResponse.ok(requests.submit(id, body == null ? null : body.version(), caller(http)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Withdraw a permit that has not been issued")
    public ApiResponse<PermitDetail> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonBody body, HttpServletRequest http) {
        return ApiResponse.ok(requests.cancel(id, body.reason(), body.version(), caller(http)));
    }

    // ---- verification and approval (S164-02)

    @PostMapping("/{id}/isolations/{isolationId}/verify")
    @Operation(summary = "Verify one isolation - by a competent person who is not the requester")
    public ApiResponse<PermitDetail> verifyIsolation(@PathVariable UUID id, @PathVariable UUID isolationId, @Valid @RequestBody VerifyIsolationBody body, HttpServletRequest http) {
        return ApiResponse.ok(approvals.verifyIsolation(id, isolationId, body.tagReference(), body.note(), caller(http)));
    }

    @PostMapping("/{id}/verification/complete")
    @Operation(summary = "Record that isolation verification is complete", description = "The explicit step approval needs; a permit whose type needs no isolation is verified by the same step.")
    public ApiResponse<PermitDetail> completeVerification(@PathVariable UUID id, @RequestBody(required = false) NoteBody body, HttpServletRequest http) {
        return ApiResponse.ok(approvals.completeVerification(id, body == null ? null : body.note(), body == null ? null : body.version(), caller(http)));
    }

    @PostMapping("/{id}/workers/{workerId}/competency")
    @Operation(summary = "Record a competence check for a worker")
    public ApiResponse<PermitDetail> competency(@PathVariable UUID id, @PathVariable UUID workerId, @Valid @RequestBody CompetencyBody body, HttpServletRequest http) {
        return ApiResponse.ok(approvals.recordCompetency(id, workerId, body.competencyCode(), body.competent(), body.evidenceReference(), body.validUntil(), body.note(),
                caller(http)));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve the stage the permit is waiting on",
            description = "Issuing authority first, then - for a higher-risk type - an independent safety sign-off. The permit becomes active when the last stage approves.")
    public ApiResponse<PermitDetail> approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveBody body, HttpServletRequest http) {
        ApproveBody b = body == null ? new ApproveBody(null, null, null) : body;
        return ApiResponse.ok(approvals.approve(id, b.version(), b.conditions(), b.comment(), caller(http)));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<PermitDetail> reject(@PathVariable UUID id, @Valid @RequestBody ReasonBody body, HttpServletRequest http) {
        return ApiResponse.ok(approvals.reject(id, body.reason(), body.version(), caller(http)));
    }

    // ---- monitoring, suspension, extension (S164-03)

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Suspend an active permit", description = "The supervisor and every worker on the permit are queued for notification in the same transaction.")
    public ApiResponse<PermitDetail> suspend(@PathVariable UUID id, @Valid @RequestBody ReasonBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.suspend(id, body.reason(), body.version(), caller(http)));
    }

    @PostMapping("/{id}/resume-request")
    @Operation(summary = "Ask to resume a suspended permit", description = "Opens a fresh round of approvals; there is no un-suspend.")
    public ApiResponse<PermitDetail> resumeRequest(@PathVariable UUID id, @RequestBody(required = false) VersionBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.requestResumption(id, body == null ? null : body.version(), caller(http)));
    }

    @PostMapping("/{id}/extensions")
    public ApiResponse<PermitDetail> requestExtension(@PathVariable UUID id, @Valid @RequestBody ExtensionBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.requestExtension(id, body.newEndsAt(), body.reason(), body.version(), caller(http)));
    }

    @PostMapping("/{id}/extensions/{extensionId}/decide")
    public ApiResponse<PermitDetail> decideExtension(@PathVariable UUID id, @PathVariable UUID extensionId, @Valid @RequestBody DecisionBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.decideExtension(id, extensionId, body.approve(), body.note(), body.version(), caller(http)));
    }

    @PostMapping("/{id}/flags/{flagId}/review")
    public ApiResponse<PermitDetail> reviewFlag(@PathVariable UUID id, @PathVariable UUID flagId, @Valid @RequestBody ReasonBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.reviewFlag(id, flagId, body.reason(), caller(http)));
    }

    @PostMapping("/{id}/incident-links")
    @Operation(summary = "Link the permit to an S163 incident by hand")
    public ApiResponse<PermitDetail> linkIncident(@PathVariable UUID id, @Valid @RequestBody IncidentLinkBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.linkIncident(id, body.incidentReference(), body.detail(), caller(http)));
    }

    // ---- close-out (S164-04)

    @PostMapping("/{id}/evidence")
    @Operation(summary = "File close-out evidence by reference")
    public ApiResponse<PermitDetail> evidence(@PathVariable UUID id, @Valid @RequestBody EvidenceBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.addEvidence(id, body.kind(), body.reference(), body.fileName(), body.mediaType(), body.sizeBytes(), body.contentHash(), caller(http)));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Record that the work is complete", description = "Needs a completion statement and evidence. Isolation removal is the next, separate act.")
    public ApiResponse<PermitDetail> complete(@PathVariable UUID id, @Valid @RequestBody CompletionBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.completeWork(id, body.statement(), body.version(), caller(http)));
    }

    @PostMapping("/{id}/isolations/{isolationId}/remove")
    @Operation(summary = "Record the removal of one isolation - by a competent person who is not the requester")
    public ApiResponse<PermitDetail> removeIsolation(@PathVariable UUID id, @PathVariable UUID isolationId, @RequestBody(required = false) NoteBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.removeIsolation(id, isolationId, body == null ? null : body.note(), caller(http)));
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close the permit", description = "Refused with PERMIT_ISOLATION_REMOVAL_NOT_RECORDED until every isolation has a recorded removal.")
    public ApiResponse<PermitDetail> close(@PathVariable UUID id, @RequestBody(required = false) VersionBody body, HttpServletRequest http) {
        return ApiResponse.ok(lifecycle.close(id, body == null ? null : body.version(), caller(http)));
    }

    // ---- dashboard, analytics, statutory export (S164-05)

    @GetMapping("/dashboard")
    @Operation(summary = "Open permits by type and risk level, nearing expiry, overdue close-outs, isolation status per zone, competence exceptions")
    public ApiResponse<PermitQueryService.Dashboard> dashboard(@RequestParam(required = false) String siteCode, HttpServletRequest http) {
        return ApiResponse.ok(queries.dashboard(siteCode, caller(http)));
    }

    @GetMapping("/analytics")
    @Operation(summary = "Permit volume, type, duration and incident correlation")
    public ApiResponse<PermitQueryService.Analytics> analytics(@RequestParam(required = false) String siteCode, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, HttpServletRequest http) {
        return ApiResponse.ok(queries.analytics(siteCode, from, to, caller(http)));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @Operation(summary = "Statutory evidence: the register with each permit's complete lifecycle",
            description = "Needs PERMIT_EXPORT and a reason. A permit that has not closed says so on its row; the file is never presented as complete when it is not.")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String siteCode, @RequestParam(required = false) UUID permitTypeId,
            @RequestParam(required = false) PermitStatus status, @RequestParam(required = false) String contractor, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam String reason, HttpServletRequest http) {
        PermitQueryService.ExportFile file = queries.export(new PermitQueryService.Search(siteCode, permitTypeId, status, contractor, from, to, false, false, null,
                new Paging(0, 100, null)), reason, caller(http));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(file.csv().getBytes(StandardCharsets.UTF_8));
    }
}

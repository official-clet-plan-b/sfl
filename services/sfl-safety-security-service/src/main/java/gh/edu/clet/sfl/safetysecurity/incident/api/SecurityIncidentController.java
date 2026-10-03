package gh.edu.clet.sfl.safetysecurity.incident.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.emergency.api.EmergencyPageResponse;
import gh.edu.clet.sfl.safetysecurity.incident.application.port.SecurityIncidentRepository;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.CorrectiveActionService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentClosureService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentInvestigationService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentReportingService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentTriageService;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.CorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Impact;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentEvidence;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentSource;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentStatus;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.RetentionClass;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.RiskRating;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.SecurityIncident;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Severity;
import gh.edu.clet.sfl.safetysecurity.incident.domain.exception.IncidentErrorCode;
import gh.edu.clet.sfl.safetysecurity.incident.domain.exception.IncidentException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
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
 * SRS §D.9: HSE incident/near-miss reporting, triage, investigation, CAPA and closure.
 *
 * <p>{@code Idempotency-Key} is honoured on the one state-creating POST that opens a new case
 * ({@code POST /incidents}) and nowhere else - every other mutation is a PATCH guarded by the
 * record's version and its state machine, the same reasoning {@code VisitorVisitController} and
 * {@code BookingController} document. Evidence and CAPA creation are POSTs too but create child rows
 * under an existing incident rather than a new top-level case, so they do not carry a key either -
 * same distinction {@code WorkOrderController} draws for its own sub-resource POSTs.
 */
@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "S163 HSE Incidents")
public class SecurityIncidentController {

    private final IncidentReportingService reporting;
    private final IncidentTriageService triage;
    private final IncidentInvestigationService investigation;
    private final CorrectiveActionService correctiveActions;
    private final IncidentClosureService closure;
    private final IncidentActorResolver actors;

    public SecurityIncidentController(IncidentReportingService reporting, IncidentTriageService triage,
            IncidentInvestigationService investigation, CorrectiveActionService correctiveActions,
            IncidentClosureService closure, IncidentActorResolver actors) {
        this.reporting = reporting;
        this.triage = triage;
        this.investigation = investigation;
        this.correctiveActions = correctiveActions;
        this.closure = closure;
        this.actors = actors;
    }

    @PostMapping
    @Operation(summary = "Report an incident or near-miss", description = "SRS-SFL-S163-01: staff, "
            + "student, inspector, or seeded from another SSEMP module. Anonymous near-miss reporting "
            + "is supported (Q-163-3: whether policy permits it is decided by the reporting channel, "
            + "not enforced here).")
    public ResponseEntity<ApiResponse<SecurityIncident>> report(@Valid @RequestBody ReportRequest request,
            HttpServletRequest http) {
        SecurityIncident incident = reporting.report(new IncidentReportingService.ReportIncident(
                request.siteCode(), request.source() == null ? IncidentSource.REPORTED : request.source(),
                request.anonymous(), request.reporterId(), request.reporterContact(), request.description(),
                request.nearMiss(), actors.resolve(http), actors.resolveSourceChannel(http), request.riskAssessmentId(),
                request.activityType()));
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + incident.id()))
                .body(ApiResponse.ok(incident));
    }

    @PatchMapping("/{incidentId}/risk-context")
    @Operation(summary = "Record the risk assessment or activity the incident happened under",
            description = "Phase 2 SRS S165-04: saving a linked assessment, or an activity type with published "
                    + "assessments at the site, flags each for out-of-cycle review in the same transaction. A "
                    + "link naming no assessment at this site is refused. Requires triage or investigation authority.")
    public ApiResponse<SecurityIncident> recordRiskContext(@PathVariable UUID incidentId,
            @Valid @RequestBody RiskContextRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reporting.recordRiskContext(incidentId, request.riskAssessmentId(), request.activityType(),
                request.expectedVersion(), actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PatchMapping("/{incidentId}/triage")
    @Operation(summary = "Rate or re-rate severity and risk",
            description = "SRS-SFL-S163-02: revisable during investigation. An EMERGENCY severity "
                    + "escalates the case automatically (hard rule 2).")
    public ApiResponse<SecurityIncident> triage(@PathVariable UUID incidentId,
            @Valid @RequestBody TriageRequest request, HttpServletRequest http) {
        RiskRating rating = new RiskRating(request.likelihood(), request.impact());
        return ApiResponse.ok(triage.triage(new IncidentTriageService.Triage(incidentId, request.severity(), rating,
                request.reportable(), request.reportabilityNotes(), request.expectedVersion(), actors.resolve(http),
                actors.resolveSourceChannel(http))));
    }

    @PatchMapping("/{incidentId}/investigation")
    @Operation(summary = "Open or update the investigation",
            description = "Opens the investigation (requires triage to be completed first) if the case "
                    + "is still in TRIAGE, or updates the investigation notes in place if it is already "
                    + "INVESTIGATING.")
    public ApiResponse<SecurityIncident> investigation(@PathVariable UUID incidentId,
            @Valid @RequestBody InvestigationRequest request, HttpServletRequest http) {
        return ApiResponse.ok(investigation.openOrUpdateInvestigation(
                new IncidentInvestigationService.OpenOrUpdateInvestigation(incidentId, request.investigatorId(),
                        request.investigationNotes(), request.expectedVersion(), actors.resolve(http),
                        actors.resolveSourceChannel(http))));
    }

    @PostMapping("/{incidentId}/evidence")
    @Operation(summary = "Attach evidence", description = "SRS-SFL-S163-05: by reference, with a "
            + "provenance hash. Large files are held by reference to S003, never as bytes here.")
    public ResponseEntity<ApiResponse<IncidentEvidence>> attachEvidence(@PathVariable UUID incidentId,
            @Valid @RequestBody AttachEvidenceRequest request, HttpServletRequest http) {
        IncidentEvidence saved = investigation.attachEvidence(new IncidentInvestigationService.AttachEvidence(
                incidentId, request.fileReference(), request.fileName(), request.mediaType(), request.sizeBytes(),
                request.contentHash(), request.retentionClass(), request.notes(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + incidentId + "/evidence/" + saved.id()))
                .body(ApiResponse.ok(saved));
    }

    @GetMapping("/{incidentId}/evidence")
    @Operation(summary = "List the evidence references attached to a case")
    public ApiResponse<List<IncidentEvidence>> evidence(@PathVariable UUID incidentId, HttpServletRequest http) {
        return ApiResponse.ok(investigation.evidence(incidentId, actors.resolve(http)));
    }

    @PostMapping("/{incidentId}/corrective-actions")
    @Operation(summary = "Open a CAPA item", description = "SRS-SFL-S163-04: owner, due date, whether "
            + "it is mandatory for closure.")
    public ResponseEntity<ApiResponse<CorrectiveAction>> openCorrectiveAction(@PathVariable UUID incidentId,
            @Valid @RequestBody OpenCorrectiveActionRequest request, HttpServletRequest http) {
        CorrectiveAction saved = correctiveActions.open(new CorrectiveActionService.OpenCorrectiveAction(incidentId,
                request.description(), request.ownerId(), request.dueDate(), request.mandatory(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
        return ResponseEntity
                .created(URI.create("/api/v1/incidents/" + incidentId + "/corrective-actions/" + saved.id()))
                .body(ApiResponse.ok(saved));
    }

    @GetMapping("/{incidentId}/corrective-actions")
    @Operation(summary = "List corrective and preventive actions for a case")
    public ApiResponse<List<CorrectiveAction>> correctiveActions(@PathVariable UUID incidentId,
            HttpServletRequest http) {
        return ApiResponse.ok(correctiveActions.list(incidentId, actors.resolve(http)));
    }

    @PatchMapping("/{incidentId}/corrective-actions/{correctiveActionId}")
    @Operation(summary = "Move a CAPA item forward",
            description = "One endpoint for all three CAPA transitions (start progress, verify "
                    + "effectiveness, cancel), discriminated by the request body - mirrors how "
                    + "MaintenanceRequests.TransitionWorkOrder funnels several simple transitions "
                    + "through one shape.")
    public ApiResponse<CorrectiveAction> transitionCorrectiveAction(@PathVariable UUID incidentId,
            @PathVariable UUID correctiveActionId, @Valid @RequestBody TransitionCorrectiveActionRequest request,
            HttpServletRequest http) {
        var command = new CorrectiveActionService.TransitionCorrectiveAction(correctiveActionId, request.notes(),
                actors.resolve(http), actors.resolveSourceChannel(http));
        CorrectiveAction result = switch (request.transition()) {
            case IN_PROGRESS -> correctiveActions.startProgress(command);
            case VERIFY -> correctiveActions.verify(command);
            case CANCEL -> correctiveActions.cancel(command);
        };
        return ApiResponse.ok(result);
    }

    @PatchMapping("/{incidentId}/closure")
    @Operation(summary = "Close the case",
            description = "SRS-SFL-S163 hard rule 1: refused while a mandatory corrective action "
                    + "remains open.")
    public ApiResponse<SecurityIncident> close(@PathVariable UUID incidentId,
            @Valid @RequestBody CloseRequest request, HttpServletRequest http) {
        return ApiResponse.ok(closure.close(new IncidentClosureService.Close(incidentId, request.closureNotes(),
                request.expectedVersion(), actors.resolve(http), actors.resolveSourceChannel(http))));
    }

    @GetMapping
    @Operation(summary = "Search incidents",
            description = "Paginated the same way every other SFL collection is - see EmergencyPageResponse. "
                    + "sort accepts \"createdAt\" or \"createdAt,desc\" (the default); any other value falls "
                    + "back to the default rather than being rejected.")
    public ApiResponse<EmergencyPageResponse<SecurityIncident>> search(
            @RequestParam(required = false) String siteCode, @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) Severity severity, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String sort,
            HttpServletRequest http) {
        return ApiResponse.ok(EmergencyPageResponse.of(reporting.searchPage(siteCode, status, severity,
                EmergencyPageResponse.paging(page, size, sort), actors.resolve(http))));
    }

    @GetMapping("/{incidentId}")
    @Operation(summary = "Read one incident")
    public ApiResponse<SecurityIncident> findById(@PathVariable UUID incidentId, HttpServletRequest http) {
        return ApiResponse.ok(reporting.get(incidentId, actors.resolve(http)));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Counts by status and severity", description = "SRS-SFL-S163-08: the HSE dashboard view.")
    public ApiResponse<IncidentReportingService.Dashboard> dashboard(@RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(reporting.dashboard(siteCode, actors.resolve(http)));
    }

    @GetMapping("/corrective-actions/summary")
    @Operation(summary = "Corrective-action counts", description = "Open, overdue, verified, cancelled and the "
            + "ageing of open actions - the incident half of the HSE dashboard's shared corrective-action panel.")
    public ApiResponse<SecurityIncidentRepository.CapaCounts> correctiveActionSummary(@RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(reporting.correctiveActionCounts(siteCode, actors.resolve(http)));
    }

    /**
     * @param riskAssessmentId optional S165 assessment the incident happened under (Phase 2 SRS S165-04)
     * @param activityType optional activity the incident happened during, e.g. {@code HOT_WORK}
     */
    public record ReportRequest(@NotBlank String siteCode, IncidentSource source, boolean anonymous,
            String reporterId, String reporterContact, @NotBlank String description, boolean nearMiss,
            UUID riskAssessmentId, @Size(max = 80) String activityType) {

        public ReportRequest {
            if (anonymous && reporterId != null && !reporterId.isBlank()) {
                throw new IncidentException(IncidentErrorCode.INCIDENT_VALIDATION_FAILED,
                        java.util.Map.of("field", "reporterId", "message", "An anonymous report must not name a reporter."));
            }
        }
    }

    public record RiskContextRequest(UUID riskAssessmentId, @Size(max = 80) String activityType,
            Long expectedVersion) {
    }

    public record TriageRequest(@NotNull Severity severity, @NotNull Likelihood likelihood, @NotNull Impact impact,
            boolean reportable, String reportabilityNotes, Long expectedVersion) {
    }

    public record InvestigationRequest(@NotBlank String investigatorId, String investigationNotes,
            Long expectedVersion) {
    }

    public record AttachEvidenceRequest(@NotBlank String fileReference, String fileName, String mediaType,
            Long sizeBytes, @NotBlank String contentHash, @NotNull RetentionClass retentionClass, String notes) {
    }

    public record OpenCorrectiveActionRequest(@NotBlank String description, @NotBlank String ownerId,
            @NotNull LocalDate dueDate, boolean mandatory) {
    }

    public record TransitionCorrectiveActionRequest(@NotNull CapaTransition transition, String notes) {
    }

    public enum CapaTransition {
        IN_PROGRESS, VERIFY, CANCEL
    }

    public record CloseRequest(@NotBlank String closureNotes, Long expectedVersion) {
    }
}

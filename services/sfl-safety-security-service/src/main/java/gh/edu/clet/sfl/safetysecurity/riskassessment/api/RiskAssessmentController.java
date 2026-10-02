package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.CreateRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.DraftRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.SignOffRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.VersionedRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.contract.RiskAssessmentDirectory.LinkCheck;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.RiskAssessmentRepository.StandingFilter;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.AssessmentDetail;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.AssessmentSummary;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentAuthoringService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentReviewService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SRS-SFL-S165-01/02: the register, authoring, publishing, sign-off and the link check.
 *
 * <p>{@code Idempotency-Key} is honoured on the one state-creating POST, creating an assessment. Every
 * other change is guarded by the record's version and its lifecycle instead.
 */
@RestController
@RequestMapping("/api/v1/risk-assessments")
@Tag(name = "S165 Risk Assessment Library")
public class RiskAssessmentController {

    private final RiskAssessmentAuthoringService authoring;
    private final RiskAssessmentReviewService reviews;
    private final RiskAssessmentActorResolver actors;

    public RiskAssessmentController(RiskAssessmentAuthoringService authoring, RiskAssessmentReviewService reviews,
            RiskAssessmentActorResolver actors) {
        this.authoring = authoring;
        this.reviews = reviews;
        this.actors = actors;
    }

    @GetMapping
    @Operation(summary = "Search the register for a site",
            description = "standing pre-filters: DRAFT_ONLY, PUBLISHED, LAPSED, AWAITING_INDEPENDENT_SIGN_OFF. Each "
                    + "row carries its exact standing from the shared currency rule. sort: currentReviewDueAt "
                    + "(default, soonest first), reference, title or lastModifiedAt, with ',desc' to reverse.")
    public ApiResponse<RiskAssessmentPageResponse<AssessmentSummary>> search(@RequestParam String siteCode,
            @RequestParam(required = false) String activityType, @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(required = false) StandingFilter standing, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort, HttpServletRequest http) {
        return ApiResponse.ok(RiskAssessmentPageResponse.of(authoring.search(
                new RiskAssessmentAuthoringService.SearchAssessments(siteCode, activityType, riskLevel, standing, q,
                        new Paging(page, size, sort), actors.resolve(http)))));
    }

    @PostMapping
    @Operation(summary = "Create an assessment and its first draft",
            description = "SRS-SFL-S165-01. Scoped to an activity type, an S152 location, or both. With a "
                    + "templateId and no hazards, the template's hazards and controls are copied.")
    public ResponseEntity<ApiResponse<AssessmentDetail>> create(@Valid @RequestBody CreateRequest request,
            HttpServletRequest http) {
        AssessmentDetail created = authoring.create(new RiskAssessmentAuthoringService.CreateAssessment(
                request.siteCode(), request.activityType(), request.locationCode(), request.title(), request.summary(),
                RiskAssessmentRequests.hazards(request.hazards()), request.templateId(),
                actors.resolveIdempotencyKey(http), actors.resolve(http), actors.resolveSourceChannel(http)));
        return ResponseEntity.created(URI.create("/api/v1/risk-assessments/" + created.assessment().id()))
                .body(ApiResponse.ok(created));
    }

    @GetMapping("/{assessmentId}")
    @Operation(summary = "Read one assessment with every version, its sign-offs and review flags")
    public ApiResponse<AssessmentDetail> get(@PathVariable UUID assessmentId, HttpServletRequest http) {
        return ApiResponse.ok(authoring.get(assessmentId, actors.resolve(http)));
    }

    @GetMapping("/{assessmentId}/versions/{versionNumber}")
    @Operation(summary = "Read one version, superseded or not", description = "SRS-SFL-S165-01: a superseded "
            + "version remains retrievable for audit, clearly marked as not current.")
    public ApiResponse<AssessmentVersion> version(@PathVariable UUID assessmentId, @PathVariable int versionNumber,
            HttpServletRequest http) {
        return ApiResponse.ok(authoring.version(assessmentId, versionNumber, actors.resolve(http)));
    }

    @PutMapping("/{assessmentId}/draft")
    @Operation(summary = "Replace the open draft's title, summary, hazards and controls",
            description = "expectedVersion is the draft version's recordVersion.")
    public ApiResponse<AssessmentDetail> editDraft(@PathVariable UUID assessmentId,
            @Valid @RequestBody DraftRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authoring.editDraft(assessmentId, request.toContent(), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{assessmentId}/revisions")
    @Operation(summary = "Open a new draft from the current version",
            description = "The only way to change a published assessment. Refused while a draft is already open.")
    public ApiResponse<AssessmentDetail> openRevision(@PathVariable UUID assessmentId, HttpServletRequest http) {
        return ApiResponse.ok(authoring.openRevision(assessmentId, actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{assessmentId}/publish")
    @Operation(summary = "Publish the open draft as the current version",
            description = "SRS-SFL-S165-01: refused with 'Hazard Without Control' naming every uncontrolled hazard. "
                    + "The previous version is superseded in the same transaction. The review date is set from the "
                    + "interval for the computed risk level. expectedVersion is the draft's recordVersion.")
    public ApiResponse<AssessmentDetail> publish(@PathVariable UUID assessmentId,
            @RequestBody(required = false) VersionedRequest request, HttpServletRequest http) {
        return ApiResponse.ok(authoring.publish(assessmentId, request == null ? null : request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{assessmentId}/sign-off")
    @Operation(summary = "Sign off the current version and renew its review date",
            description = "SRS-SFL-S165-02: for HIGH and CRITICAL the reviewer must not be the version's author. "
                    + "Signing off a lapsed assessment makes it current again. expectedVersion is the current "
                    + "version's recordVersion.")
    public ApiResponse<RiskAssessmentReviewService.AssessmentDetailSignOff> signOff(@PathVariable UUID assessmentId,
            @Valid @RequestBody(required = false) SignOffRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reviews.signOff(assessmentId, request == null ? null : request.notes(),
                request == null ? null : request.expectedVersion(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    @GetMapping("/{assessmentId}/link-check")
    @Operation(summary = "May this assessment be newly linked right now?",
            description = "SRS-SFL-S165-02: the shared currency rule's verdict - the same answer S164, S173 and S176 "
                    + "get. An assessment at a different site is reported as not found.")
    public ApiResponse<LinkCheck> linkCheck(@PathVariable UUID assessmentId, @RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(reviews.checkLink(assessmentId, siteCode, actors.resolve(http)));
    }
}

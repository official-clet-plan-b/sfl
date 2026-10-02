package gh.edu.clet.sfl.safetysecurity.drill.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.CloseRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.CorrectiveActionRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.FindingRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.JudgementRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.JudgementsRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.NoActionRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.NotesRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.ReasonRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.ReviewRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.VersionedRequest;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillCorrectiveActionService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillDetail;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillReviewService;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SRS-SFL-S175-03/-05: the after-action review, its findings and their corrective actions, and closing the drill. */
@RestController
@RequestMapping("/api/v1/drills/{drillId}")
@Tag(name = "S175 Crisis & Evacuation Drills")
public class DrillReviewController {

    private final DrillReviewService reviews;
    private final DrillCorrectiveActionService actions;
    private final DrillActorResolver actors;

    public DrillReviewController(DrillReviewService reviews, DrillCorrectiveActionService actions,
            DrillActorResolver actors) {
        this.reviews = reviews;
        this.actors = actors;
        this.actions = actions;
    }

    @PutMapping("/review")
    @Operation(summary = "Write the after-action review's narrative", description = "SRS-SFL-S175-03. Timing and "
            + "participation gaps are measured and already on the drill; this is what people concluded.")
    public ApiResponse<DrillDetail> review(@PathVariable UUID drillId, @Valid @RequestBody ReviewRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(reviews.recordReview(drillId, request.summary(), request.timingNotes(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/findings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a finding")
    public ApiResponse<DrillFinding> addFinding(@PathVariable UUID drillId, @Valid @RequestBody FindingRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(reviews.addFinding(drillId, request.description(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    @PostMapping("/findings/{findingId}/no-action")
    @Operation(summary = "Record that a finding needs no corrective action, and why",
            description = "SRS-SFL-S175-03: the only alternative to a corrective action. Refused once it has one.")
    public ApiResponse<DrillFinding> noAction(@PathVariable UUID drillId, @PathVariable UUID findingId,
            @Valid @RequestBody NoActionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reviews.justifyNoAction(drillId, findingId, request.justification(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PutMapping("/expectations")
    @Operation(summary = "Record whether each module met its expectation",
            description = "SRS-SFL-S175-05. index is the expectation's position in the plan, from 0.")
    public ApiResponse<DrillDetail> judge(@PathVariable UUID drillId, @Valid @RequestBody JudgementsRequest request,
            HttpServletRequest http) {
        List<DrillReviewService.ExpectationJudgement> judgements = request.judgements() == null ? List.of()
                : request.judgements().stream().map(JudgementRequest::toJudgement).toList();
        return ApiResponse.ok(reviews.judgeExpectations(drillId, judgements, request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/review/submit")
    @Operation(summary = "Submit the review and mark the drill reviewed", description = "Refused until every gap is "
            + "followed up, every expectation judged, and every finding actioned or justified ('Unactioned "
            + "Finding'). From here the drill counts toward frequency compliance.")
    public ApiResponse<DrillDetail> submit(@PathVariable UUID drillId,
            @RequestBody(required = false) VersionedRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reviews.submit(drillId, request == null ? null : request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/close")
    @Operation(summary = "Close the drill", description = "SRS-SFL-S175-03: refused while corrective actions are open "
            + "and overdue, unless a deferral reason is given.")
    public ApiResponse<DrillDetail> close(@PathVariable UUID drillId,
            @Valid @RequestBody(required = false) CloseRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reviews.close(drillId, request == null ? null : request.deferralReason(),
                request == null ? null : request.expectedVersion(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    // ---- corrective actions ---------------------------------------------------------------------

    @PostMapping("/corrective-actions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Raise a corrective action from a finding", description = "SRS-SFL-S175-03: S163's CAPA "
            + "pattern - an owner and a due date.")
    public ApiResponse<DrillCorrectiveAction> openAction(@PathVariable UUID drillId,
            @Valid @RequestBody CorrectiveActionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(actions.open(drillId, request.findingId(), request.description(), request.ownerId(),
                request.dueDate(), actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/corrective-actions/{actionId}/start")
    @Operation(summary = "Mark a corrective action in progress")
    public ApiResponse<DrillCorrectiveAction> startAction(@PathVariable UUID drillId, @PathVariable UUID actionId,
            HttpServletRequest http) {
        return ApiResponse.ok(actions.startProgress(drillId, actionId, actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    @PostMapping("/corrective-actions/{actionId}/verify")
    @Operation(summary = "Verify a corrective action's effectiveness", description = "The notes say how.")
    public ApiResponse<DrillCorrectiveAction> verifyAction(@PathVariable UUID drillId, @PathVariable UUID actionId,
            @Valid @RequestBody NotesRequest request, HttpServletRequest http) {
        return ApiResponse.ok(actions.verify(drillId, actionId, request.notes(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    @PostMapping("/corrective-actions/{actionId}/cancel")
    @Operation(summary = "Cancel a corrective action with a reason")
    public ApiResponse<DrillCorrectiveAction> cancelAction(@PathVariable UUID drillId, @PathVariable UUID actionId,
            @Valid @RequestBody ReasonRequest request, HttpServletRequest http) {
        return ApiResponse.ok(actions.cancel(drillId, actionId, request.reason(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }
}

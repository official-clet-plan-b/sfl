package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.CompleteReviewRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.DeferRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.ReviewFlagService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SRS-SFL-S165-04: the out-of-cycle review queue. Flags are raised by S163 incidents, never through this
 * API; this is where they are worked. Two ways out - defer (reason and date) or complete (findings) - and
 * deliberately no third.
 */
@RestController
@RequestMapping("/api/v1/risk-assessments/review-flags")
@Tag(name = "S165 Risk Assessment Library")
public class ReviewFlagController {

    private final ReviewFlagService flags;
    private final RiskAssessmentActorResolver actors;

    public ReviewFlagController(ReviewFlagService flags, RiskAssessmentActorResolver actors) {
        this.flags = flags;
        this.actors = actors;
    }

    @GetMapping
    @Operation(summary = "The review-flag queue for a site, oldest first")
    public ApiResponse<RiskAssessmentPageResponse<ReviewFlag>> search(@RequestParam String siteCode,
            @RequestParam(required = false) ReviewFlagStatus status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, HttpServletRequest http) {
        return ApiResponse.ok(RiskAssessmentPageResponse.of(flags.search(siteCode, status, new Paging(page, size, null),
                actors.resolve(http))));
    }

    @GetMapping("/{flagId}")
    @Operation(summary = "Read one review flag")
    public ApiResponse<ReviewFlag> get(@PathVariable UUID flagId, HttpServletRequest http) {
        return ApiResponse.ok(flags.get(flagId, actors.resolve(http)));
    }

    @PostMapping("/{flagId}/defer")
    @Operation(summary = "Defer a review flag to a named date, with a reason",
            description = "SRS-SFL-S165-04. The date must be in the future; when it passes the flag reopens.")
    public ApiResponse<ReviewFlag> defer(@PathVariable UUID flagId, @Valid @RequestBody DeferRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(flags.defer(flagId, request.reason(), request.until(), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{flagId}/complete")
    @Operation(summary = "Complete the review and clear the flag",
            description = "SRS-SFL-S165-04: 'Flag Dismissed Without Review' is not a permitted action - findings "
                    + "are required.")
    public ApiResponse<ReviewFlag> complete(@PathVariable UUID flagId, @Valid @RequestBody CompleteReviewRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(flags.complete(flagId, request.findings(), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }
}

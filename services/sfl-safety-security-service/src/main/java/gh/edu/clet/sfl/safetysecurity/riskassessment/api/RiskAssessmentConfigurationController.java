package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.IntervalsRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.api.RiskAssessmentRequests.TemplateRequest;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.AssessmentTemplateService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.ReviewIntervalService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentTemplate;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewInterval;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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

/** The template library and the review cycle - S165's configuration, global rather than per site. */
@RestController
@RequestMapping("/api/v1/risk-assessments")
@Tag(name = "S165 Risk Assessment Library")
public class RiskAssessmentConfigurationController {

    private final AssessmentTemplateService templates;
    private final ReviewIntervalService intervals;
    private final RiskAssessmentActorResolver actors;

    public RiskAssessmentConfigurationController(AssessmentTemplateService templates, ReviewIntervalService intervals,
            RiskAssessmentActorResolver actors) {
        this.templates = templates;
        this.intervals = intervals;
        this.actors = actors;
    }

    @GetMapping("/templates")
    @Operation(summary = "The hazard/control template library")
    public ApiResponse<List<AssessmentTemplate>> templates(@RequestParam(defaultValue = "true") boolean activeOnly,
            HttpServletRequest http) {
        return ApiResponse.ok(templates.list(activeOnly, actors.resolve(http)));
    }

    @GetMapping("/templates/{templateId}")
    @Operation(summary = "Read one template")
    public ApiResponse<AssessmentTemplate> template(@PathVariable UUID templateId, HttpServletRequest http) {
        return ApiResponse.ok(templates.get(templateId, actors.resolve(http)));
    }

    @PostMapping("/templates")
    @Operation(summary = "Add a template to the library")
    public ResponseEntity<ApiResponse<AssessmentTemplate>> createTemplate(@Valid @RequestBody TemplateRequest request,
            HttpServletRequest http) {
        AssessmentTemplate created = templates.create(content(request), actors.resolve(http),
                actors.resolveSourceChannel(http));
        return ResponseEntity.created(URI.create("/api/v1/risk-assessments/templates/" + created.id()))
                .body(ApiResponse.ok(created));
    }

    @PutMapping("/templates/{templateId}")
    @Operation(summary = "Revise or retire a template", description = "Assessments already copied from it are "
            + "untouched.")
    public ApiResponse<AssessmentTemplate> updateTemplate(@PathVariable UUID templateId,
            @Valid @RequestBody TemplateRequest request, HttpServletRequest http) {
        return ApiResponse.ok(templates.update(templateId, content(request), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @GetMapping("/configuration/review-intervals")
    @Operation(summary = "The review interval and reminder lead per risk level")
    public ApiResponse<List<ReviewInterval>> reviewIntervals(HttpServletRequest http) {
        return ApiResponse.ok(intervals.list(actors.resolve(http)));
    }

    @PutMapping("/configuration/review-intervals")
    @Operation(summary = "Change the review cycle", description = "SRS-SFL-S165-02: a higher risk level must be "
            + "reviewed at least as often as a lower one. Applies from the next publish or sign-off.")
    public ApiResponse<List<ReviewInterval>> updateReviewIntervals(@Valid @RequestBody IntervalsRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(intervals.update(request.intervals().stream()
                .map(i -> new ReviewIntervalService.IntervalChange(i.riskLevel(), i.intervalDays(),
                        i.reminderLeadDays()))
                .toList(), actors.resolve(http)));
    }

    private static AssessmentTemplateService.TemplateContent content(TemplateRequest request) {
        return new AssessmentTemplateService.TemplateContent(request.name(), request.activityType(),
                request.description(), request.active() == null || request.active(),
                RiskAssessmentRequests.hazards(request.hazards()));
    }
}

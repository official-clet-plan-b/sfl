package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.CoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SRS-SFL-S165-03 and the S165 dashboard: every figure published by the service, none derived by a client. */
@RestController
@RequestMapping("/api/v1/risk-assessments")
@Tag(name = "S165 Risk Assessment Library")
public class RiskAssessmentAnalyticsController {

    private final CoverageService coverage;
    private final RiskAssessmentActorResolver actors;

    public RiskAssessmentAnalyticsController(CoverageService coverage, RiskAssessmentActorResolver actors) {
        this.coverage = coverage;
        this.actors = actors;
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Standing, review queue, coverage-gap and hazard figures for a site")
    public ApiResponse<CoverageService.Dashboard> dashboard(@RequestParam String siteCode, HttpServletRequest http) {
        return ApiResponse.ok(coverage.dashboard(siteCode, actors.resolve(http)));
    }

    @GetMapping("/analytics/hazards")
    @Operation(summary = "Hazard-type frequency across current assessments, most frequent first")
    public ApiResponse<List<CoverageService.HazardFrequency>> hazards(@RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(coverage.hazardFrequency(siteCode, actors.resolve(http)));
    }

    @GetMapping("/analytics/coverage")
    @Operation(summary = "Activity types observed in use, and which have no current assessment",
            description = "SRS-SFL-S165-03: observed from S176 construction work types and S163 incident activities, "
                    + "never a static list.")
    public ApiResponse<CoverageService.CoverageReport> coverage(@RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(coverage.coverage(siteCode, actors.resolve(http)));
    }

    @GetMapping("/activity-types")
    @Operation(summary = "Activity types known at a site, for pickers")
    public ApiResponse<List<String>> activityTypes(@RequestParam String siteCode, HttpServletRequest http) {
        return ApiResponse.ok(coverage.activityTypes(siteCode, actors.resolve(http)));
    }
}

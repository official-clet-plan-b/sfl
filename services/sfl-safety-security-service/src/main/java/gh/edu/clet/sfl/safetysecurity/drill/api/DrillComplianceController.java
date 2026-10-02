package gh.edu.clet.sfl.safetysecurity.drill.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.RequirementRequest;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillRepository.CapaCounts;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillComplianceService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillComplianceService.ComplianceRow;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillCorrectiveActionService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** SRS-SFL-S175-04/-05: frequency compliance and its configuration, the drill dashboard, and the CAPA counts. */
@RestController
@RequestMapping("/api/v1/drills")
@Tag(name = "S175 Crisis & Evacuation Drills")
public class DrillComplianceController {

    private final DrillComplianceService compliance;
    private final DrillDashboardService dashboard;
    private final DrillCorrectiveActionService actions;
    private final DrillActorResolver actors;

    public DrillComplianceController(DrillComplianceService compliance, DrillDashboardService dashboard,
            DrillCorrectiveActionService actions, DrillActorResolver actors) {
        this.compliance = compliance;
        this.dashboard = dashboard;
        this.actions = actions;
        this.actors = actors;
    }

    @GetMapping("/compliance")
    @Operation(summary = "Where each site stands against its required drill frequencies",
            description = "SRS-SFL-S175-04: COMPLIANT, DUE_SOON or COMPLIANCE_GAP per site and drill type, most urgent "
                    + "first. Only a completed, reviewed drill counts.")
    public ApiResponse<List<ComplianceRow>> compliance(@RequestParam(required = false) String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(compliance.compliance(siteCode, actors.resolve(http)));
    }

    @PutMapping("/compliance/requirements")
    @Operation(summary = "Set the required frequency for a site and drill type",
            description = "Creates or revises it. A revision keeps the original effective date.")
    public ApiResponse<ComplianceRow> setRequirement(@Valid @RequestBody RequirementRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(compliance.setRequirement(new DrillComplianceService.SetRequirement(request.siteCode(),
                request.drillType(), request.intervalDays(), request.warningDays(), request.effectiveFrom(),
                actors.resolve(http), actors.resolveSourceChannel(http))));
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Per-site completion, participation, compliance and open corrective actions",
            description = "days: how far back the window looks (default 365).")
    public ApiResponse<DrillDashboardService.Dashboard> dashboard(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "365") int days, HttpServletRequest http) {
        return ApiResponse.ok(dashboard.dashboard(siteCode, days, actors.resolve(http)));
    }

    @GetMapping("/corrective-actions/summary")
    @Operation(summary = "Drill corrective-action counts", description = "The drill half of the HSE dashboard's "
            + "shared incidents-and-drills corrective-action panel.")
    public ApiResponse<CapaCounts> correctiveActionSummary(@RequestParam(required = false) String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(actions.counts(siteCode, actors.resolve(http)));
    }
}

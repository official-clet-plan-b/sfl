package gh.edu.clet.sfl.safetysecurity.drill.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.CheckInRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.CreateRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.GapFollowUpRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.ReasonRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.ReviseRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.ScheduleRequest;
import gh.edu.clet.sfl.safetysecurity.drill.api.DrillRequests.VersionedRequest;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillMusterPort.CheckIn;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillNotificationPort;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillDetail;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillExecutionService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillPlanningService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.RollCallView;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RollCallGap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
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

/**
 * SRS-SFL-S175-01/-02: the drill register and calendar, planning and scheduling, and running a drill on the day.
 *
 * <p>{@code Idempotency-Key} is honoured on the one state-creating POST, planning a drill. Every other change is
 * guarded by the drill's version and its lifecycle.
 */
@RestController
@RequestMapping("/api/v1/drills")
@Tag(name = "S175 Crisis & Evacuation Drills")
public class DrillRegisterController {

    private final DrillPlanningService planning;
    private final DrillExecutionService execution;
    private final DrillActorResolver actors;

    public DrillRegisterController(DrillPlanningService planning, DrillExecutionService execution,
            DrillActorResolver actors) {
        this.planning = planning;
        this.execution = execution;
        this.actors = actors;
    }

    @GetMapping
    @Operation(summary = "Search the drill register, or read the calendar",
            description = "Without siteCode, every site the caller is scoped to. from/to bound scheduledFor - the "
                    + "calendar's window. sort: scheduledFor (default), reference, title, status or lastModifiedAt, "
                    + "with ',desc' to reverse.")
    public ApiResponse<DrillPageResponse<Drill>> search(@RequestParam(required = false) String siteCode,
            @RequestParam(required = false) DrillType drillType, @RequestParam(required = false) DrillStatus status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String sort,
            HttpServletRequest http) {
        return ApiResponse.ok(DrillPageResponse.of(planning.search(new DrillPlanningService.SearchDrills(siteCode,
                drillType, status, from, to, q, new Paging(page, size, sort), actors.resolve(http)))));
    }

    @GetMapping("/notification-templates")
    @Operation(summary = "The S174 drill templates a plan at this site may name",
            description = "SRS-SFL-S175-01: only templates carrying the drill marker are listed.")
    public ApiResponse<List<DrillNotificationPort.Template>> templates(@RequestParam String siteCode,
            HttpServletRequest http) {
        return ApiResponse.ok(planning.drillTemplates(siteCode, actors.resolve(http)));
    }

    @PostMapping
    @Operation(summary = "Plan a drill", description = "SRS-SFL-S175-01: site, type, scenario and expected "
            + "participants. Saved as PLANNED; scheduling checks it is ready.")
    public ResponseEntity<ApiResponse<DrillDetail>> create(@Valid @RequestBody CreateRequest request,
            HttpServletRequest http) {
        DrillDetail created = planning.create(new DrillPlanningService.CreateDrill(request.siteCode(),
                request.plan().toPlan(), actors.resolveIdempotencyKey(http), actors.resolve(http),
                actors.resolveSourceChannel(http)));
        return ResponseEntity.created(URI.create("/api/v1/drills/" + created.drill().id()))
                .body(ApiResponse.ok(created));
    }

    @GetMapping("/{drillId}")
    @Operation(summary = "Read one drill with its execution, gaps, review, findings and corrective actions")
    public ApiResponse<DrillDetail> get(@PathVariable UUID drillId, HttpServletRequest http) {
        return ApiResponse.ok(planning.get(drillId, actors.resolve(http)));
    }

    @PutMapping("/{drillId}/plan")
    @Operation(summary = "Revise the plan", description = "Until the drill starts. A scheduled drill stays "
            + "scheduled only if the revised plan is still ready.")
    public ApiResponse<DrillDetail> revise(@PathVariable UUID drillId, @Valid @RequestBody ReviseRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(planning.revise(drillId, request.plan().toPlan(), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{drillId}/schedule")
    @Operation(summary = "Put the drill on the calendar", description = "SRS-SFL-S175-01/-05: refused unless the plan "
            + "is ready - a combined drill's expectations explicit - and with 'Test/Real Ambiguity' unless S174 "
            + "confirms the template is a drill template. Also reschedules a postponed drill.")
    public ApiResponse<DrillDetail> schedule(@PathVariable UUID drillId,
            @RequestBody(required = false) ScheduleRequest request, HttpServletRequest http) {
        return ApiResponse.ok(planning.schedule(drillId, request == null ? null : request.scheduledFor(),
                request == null ? null : request.expectedVersion(), actors.resolve(http),
                actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{drillId}/postpone")
    @Operation(summary = "Postpone with a reason", description = "The plan is kept for rescheduling.")
    public ApiResponse<DrillDetail> postpone(@PathVariable UUID drillId, @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(planning.postpone(drillId, request.reason(), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{drillId}/cancel")
    @Operation(summary = "Cancel with a reason", description = "A cancelled drill never counts toward compliance.")
    public ApiResponse<DrillDetail> cancel(@PathVariable UUID drillId, @Valid @RequestBody ReasonRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(planning.cancel(drillId, request.reason(), request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{drillId}/start")
    @Operation(summary = "Start the drill", description = "SRS-SFL-S175-01/-02: takes the S160/S160a occupancy "
            + "baseline, opens the S162a muster at the assembly point and sends the S174 notification in drill mode, "
            + "in one transaction. A baseline built on old access data is flagged 'Stale Baseline'.")
    public ApiResponse<DrillDetail> start(@PathVariable UUID drillId,
            @RequestBody(required = false) VersionedRequest request, HttpServletRequest http) {
        return ApiResponse.ok(execution.start(drillId, request == null ? null : request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{drillId}/check-ins")
    @Operation(summary = "Check someone in at the assembly point", description = "Through S162a's muster - the "
            + "same check-in a real evacuation uses.")
    public ApiResponse<CheckIn> checkIn(@PathVariable UUID drillId, @Valid @RequestBody CheckInRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(execution.checkIn(drillId, request.personRef(), actors.resolve(http)));
    }

    @GetMapping("/{drillId}/roll-call")
    @Operation(summary = "The live roll-call", description = "SRS-SFL-S175-02: who is still outstanding against the "
            + "baseline, who checked in unexpectedly, and where the drill notification's delivery stands.")
    public ApiResponse<RollCallView> rollCall(@PathVariable UUID drillId, HttpServletRequest http) {
        return ApiResponse.ok(execution.rollCall(drillId, actors.resolve(http)));
    }

    @PostMapping("/{drillId}/roll-call/close")
    @Operation(summary = "Close the roll-call and complete the drill", description = "SRS-SFL-S175-02: everyone in "
            + "the baseline who did not check in is written to the gap list before the drill is complete.")
    public ApiResponse<DrillDetail> closeRollCall(@PathVariable UUID drillId,
            @RequestBody(required = false) VersionedRequest request, HttpServletRequest http) {
        return ApiResponse.ok(execution.closeRollCall(drillId, request == null ? null : request.expectedVersion(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }

    @PostMapping("/{drillId}/gaps/{gapId}/follow-up")
    @Operation(summary = "Record how a roll-call gap was followed up")
    public ApiResponse<RollCallGap> followUp(@PathVariable UUID drillId, @PathVariable UUID gapId,
            @Valid @RequestBody GapFollowUpRequest request, HttpServletRequest http) {
        return ApiResponse.ok(execution.followUpGap(drillId, gapId, request.followUp(), request.notes(),
                actors.resolve(http), actors.resolveSourceChannel(http)));
    }
}

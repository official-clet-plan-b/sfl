package gh.edu.clet.sfl.fleetlogistics.fleet.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.mapper.FleetAssessmentMapper;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.mapper.FleetWorkflowMapper;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.request.FleetTripRequests;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.response.FleetWorkflowResponses.InspectionResponse;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.response.FleetWorkflowResponses.TripResponse;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.response.FleetIntegrationResponses.VehicleLocationResponse;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.response.PageResponse;
import gh.edu.clet.sfl.fleetlogistics.fleet.api.response.ReadinessResponse;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.AcknowledgeTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.AssignTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CancelTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CloseTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CreateTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.HoldTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RecordInspectionCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.ReportTripLocationCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.StartTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.query.TripQueryService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.TripApplicationService;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleInspection;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Trip and assignment endpoints (SRS-SFL-S166-02). */
@RestController
@RequestMapping("/api/v1/fleet/trips")
@io.swagger.v3.oas.annotations.tags.Tag(name = "Trips")
class TripController {

    private final TripApplicationService tripService;
    private final TripQueryService tripQueries;
    private final FleetWorkflowMapper mapper;
    private final FleetAssessmentMapper assessmentMapper;
    private final FleetActorResolver actorResolver;

    TripController(TripApplicationService tripService, TripQueryService tripQueries, FleetWorkflowMapper mapper,
            FleetAssessmentMapper assessmentMapper, FleetActorResolver actorResolver) {
        this.tripService = tripService;
        this.tripQueries = tripQueries;
        this.mapper = mapper;
        this.assessmentMapper = assessmentMapper;
        this.actorResolver = actorResolver;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Creates a trip, optionally pre-assigned to a vehicle and driver")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks the required trip permission for the site")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The vehicle or driver is already assigned during the requested period")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "The vehicle is not ready, or the driver is not eligible, for this assignment")
    @PostMapping
    ResponseEntity<ApiResponse<TripResponse>> create(@Valid @RequestBody FleetTripRequests.CreateTrip request,
            HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        Trip trip = tripService.create(new CreateTripCommand(request.vehicleId(), request.driverId(),
                request.siteCode(), request.purpose(), request.origin(), request.destination(),
                request.operatingMode(), request.plannedStart(), request.plannedEnd(), actor,
                actorResolver.resolveSourceChannel(httpRequest),
                actorResolver.resolveIdempotencyKey(httpRequest)));

        return ResponseEntity
                .created(URI.create("/api/v1/fleet/trips/" + trip.id()))
                .body(ApiResponse.ok(mapper.toResponse(trip)));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Searches trips, narrowed to the actor's own trips if they are a driver")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "A named site is outside the actor's scope")
    @GetMapping
    ApiResponse<PageResponse<TripResponse>> search(
            @RequestParam(required = false) String siteCode,
            @RequestParam(required = false) TripStatus status,
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) OperatingMode operatingMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        // The driverId the caller supplied is a filter, not an authorisation input: for a driver-only
        // actor the query service overrides it with their own. See TripQueryService.search.
        TripQueryService.ScopedTrips scoped = tripQueries.search(new TripRepository.TripSearchCriteria(siteCode,
                status, vehicleId, driverId, operatingMode, from, to, page, size, sort), actor);
        TripRepository.TripPage result = scoped.page();

        return ApiResponse.ok(new PageResponse<>(
                result.content().stream().map(mapper::toResponse).toList(), result.page(), result.size(),
                result.totalElements(), result.totalPages(), result.page() == 0,
                result.page() >= result.totalPages() - 1, result.sort(), scoped.scopeNotice()));
    }

    /**
     * The assigned driver confirms the trip, or defers it with a reason (SRS-SFL-S166-02).
     *
     * <p>The only write on this controller a {@code FLEET_DRIVER} can reach. It is a PATCH on the trip
     * rather than a POST to a sub-resource because it amends the assignment record rather than
     * creating anything, and it is deliberately not part of {@code /assignment} - that endpoint
     * requires {@code FLEET_TRIP_ASSIGN} and changes who the trip belongs to, which is the opposite of
     * what this does.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "The assigned driver confirms or defers a trip")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor is not the trip's assigned driver")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The trip was changed by another user, or is not in a state that can be acknowledged")
    @PatchMapping("/{tripId}/acknowledgement")
    ApiResponse<TripResponse> acknowledge(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.AcknowledgeTrip request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        return ApiResponse.ok(mapper.toResponse(tripService.acknowledge(new AcknowledgeTripCommand(tripId,
                request.answer(), request.reason(), request.expectedVersion(), actor,
                actorResolver.resolveSourceChannel(httpRequest)))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Reads one trip by id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "The trip's site is outside the actor's scope, or a driver may not read another driver's trip")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @GetMapping("/{tripId}")
    ApiResponse<TripResponse> findById(@PathVariable UUID tripId, HttpServletRequest httpRequest) {
        return ApiResponse.ok(mapper.toResponse(
                tripQueries.findById(tripId, actorResolver.resolve(httpRequest))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Assigns or reassigns a trip's vehicle and driver")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks FLEET_TRIP_ASSIGN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The trip was changed by another user, or the vehicle/driver is already assigned in this period")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "The vehicle is not ready, or the driver is not eligible, for this assignment")
    @PatchMapping("/{tripId}/assignment")
    ApiResponse<TripResponse> assign(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.AssignTrip request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        return ApiResponse.ok(mapper.toResponse(tripService.assign(new AssignTripCommand(tripId,
                request.vehicleId(), request.driverId(), request.reason(), request.expectedVersion(), actor,
                actorResolver.resolveSourceChannel(httpRequest)))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Starts a trip, recording the starting odometer")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks the required trip permission")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The trip was changed by another user, or is not in a startable status")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "The odometer reading is lower than the vehicle's last recorded reading")
    @PatchMapping("/{tripId}/start")
    ApiResponse<TripResponse> start(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.StartTrip request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        return ApiResponse.ok(mapper.toResponse(tripService.start(new StartTripCommand(tripId,
                request.startOdometer(), request.expectedVersion(), actor,
                actorResolver.resolveSourceChannel(httpRequest)))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Holds or resumes a trip")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks the required trip permission")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The trip was changed by another user, or the transition is not permitted from its current status")
    @PatchMapping("/{tripId}/hold")
    ApiResponse<TripResponse> holdOrResume(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.HoldTrip request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        HoldTripCommand.HoldAction action =
                request.action() == FleetTripRequests.HoldTrip.HoldAction.HOLD
                        ? HoldTripCommand.HoldAction.HOLD
                        : HoldTripCommand.HoldAction.RESUME;
        return ApiResponse.ok(mapper.toResponse(tripService.holdOrResume(new HoldTripCommand(tripId, action,
                request.reason(), request.expectedVersion(), actor,
                actorResolver.resolveSourceChannel(httpRequest)))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Cancels a trip")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks the required trip permission")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The trip was changed by another user, or is not in a cancellable status")
    @PatchMapping("/{tripId}/cancel")
    ApiResponse<TripResponse> cancel(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.CancelTrip request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        return ApiResponse.ok(mapper.toResponse(tripService.cancel(new CancelTripCommand(tripId,
                request.reason(), request.expectedVersion(), actor,
                actorResolver.resolveSourceChannel(httpRequest)))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Closes a trip, recording the ending odometer and closure evidence")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks the required trip permission")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The trip was changed by another user, or is not in a closable status")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Required closure evidence is missing, or the odometer reading regresses")
    @PatchMapping("/{tripId}/closure")
    ApiResponse<TripResponse> close(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.CloseTrip request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        return ApiResponse.ok(mapper.toResponse(tripService.close(new CloseTripCommand(tripId,
                request.closureReason(), request.closureEvidenceId(), request.endOdometer(),
                request.expectedVersion(), actor, actorResolver.resolveSourceChannel(httpRequest)))));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Records a vehicle inspection against a trip")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Actor lacks the required trip/inspection permission")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @PostMapping("/{tripId}/inspections")
    ResponseEntity<ApiResponse<InspectionResponse>> recordInspection(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.RecordInspection request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        List<RecordInspectionCommand.Finding> findings = request.findings() == null
                ? List.of()
                : request.findings().stream()
                        .map(finding -> new RecordInspectionCommand.Finding(finding.checkCode(),
                                finding.description(), finding.severity()))
                        .toList();

        VehicleInspection inspection = tripService.recordInspection(new RecordInspectionCommand(tripId, null,
                request.inspectionType(), request.odometerReading(), request.evidenceId(), findings,
                request.notes(), actor, actorResolver.resolveSourceChannel(httpRequest),
                actorResolver.resolveIdempotencyKey(httpRequest)));

        return ResponseEntity
                .created(URI.create("/api/v1/fleet/trips/" + tripId + "/inspections/" + inspection.id()))
                .body(ApiResponse.ok(mapper.toResponse(inspection)));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lists inspections recorded against a trip")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "The trip's site is outside the actor's scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @GetMapping("/{tripId}/inspections")
    ApiResponse<List<InspectionResponse>> inspections(@PathVariable UUID tripId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(tripQueries.findInspections(tripId, actorResolver.resolve(httpRequest)).stream()
                .map(mapper::toResponse)
                .toList());
    }

    /**
     * The assigned driver reports the vehicle's current position while the trip is in progress.
     *
     * <p>Without a connected telematics vendor (S167, Phase 2), this is the live feed: the driver's
     * own device, on the same mobile-friendly web workflow every other field role here already uses.
     */
    @io.swagger.v3.oas.annotations.Operation(
            summary = "Reports the vehicle's current position - the assigned driver's own trip, in progress, only")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Request failed bean validation")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not the driver assigned to this trip, or the trip is not in progress")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @PostMapping("/{tripId}/location")
    ApiResponse<VehicleLocationResponse> reportLocation(@PathVariable UUID tripId,
            @Valid @RequestBody FleetTripRequests.ReportLocation request, HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        return ApiResponse.ok(VehicleLocationResponse.from(tripService.reportOwnLocation(
                new ReportTripLocationCommand(tripId, request.latitude(), request.longitude(), actor,
                        actorResolver.resolveSourceChannel(httpRequest)))));
    }

    /**
     * The vehicle's latest known position for this trip, for a live map - driver-reported or, once a
     * telematics vendor is connected (S167), vendor-fed; the reader cannot tell which from this alone.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "The trip's vehicle's latest known position, if any has been reported")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "The trip's site is outside the actor's scope")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No trip exists with this id")
    @GetMapping("/{tripId}/location")
    ApiResponse<VehicleLocationResponse> latestLocation(@PathVariable UUID tripId, HttpServletRequest httpRequest) {
        return ApiResponse.ok(tripQueries.latestLocation(tripId, actorResolver.resolve(httpRequest))
                .map(VehicleLocationResponse::from)
                .orElse(null));
    }

    /**
     * Readiness preview before committing to an assignment.
     *
     * <p>Deliberately the same policy and inputs the assignment itself will use, so the preview and the
     * outcome cannot disagree.
     */
    @io.swagger.v3.oas.annotations.Operation(summary = "Previews vehicle/driver readiness for an assignment before committing to it")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "The vehicle's site is outside the actor's scope")
    @GetMapping("/assignment-preview")
    ApiResponse<ReadinessResponse> previewAssignment(
            @RequestParam UUID vehicleId,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) OperatingMode operatingMode,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(assessmentMapper.toResponse(tripQueries.previewAssignment(vehicleId, driverId,
                from, to, operatingMode, actorResolver.resolve(httpRequest))));
    }
}

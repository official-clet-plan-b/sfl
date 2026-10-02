package gh.edu.clet.sfl.fleetlogistics.fleet.api;

import gh.edu.clet.sfl.common.api.ApiResponse;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.query.VehicleTrackingQueryService;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLifecycleStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the fleet is, in one request (S167).
 *
 * <p>Only vehicles in the operational fleet are listed. A retired vehicle with no tracker is not a
 * tracking gap, and counting it as one would bury the vehicles that are.
 */
@RestController
@RequestMapping("/api/v1/fleet/vehicles/tracking")
class VehicleTrackingController {

    private final VehicleTrackingQueryService tracking;
    private final FleetActorResolver actorResolver;

    VehicleTrackingController(VehicleTrackingQueryService tracking, FleetActorResolver actorResolver) {
        this.tracking = tracking;
        this.actorResolver = actorResolver;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Lists active vehicles with their latest position and whether it is current, stale or absent")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "A named site is outside the actor's scope")
    @GetMapping
    ApiResponse<TrackingPageResponse> overview(@RequestParam(required = false) String siteCode,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size,
            HttpServletRequest httpRequest) {
        ActorContext actor = actorResolver.resolve(httpRequest);
        VehicleTrackingQueryService.TrackingPage result = tracking.overview(
                new VehicleRepository.VehicleSearchCriteria(siteCode, VehicleLifecycleStatus.ACTIVE, null, null,
                        null, null, null, page, Math.min(Math.max(size, 1), 200), "registrationNumber"),
                actor);
        return ApiResponse.ok(TrackingPageResponse.from(result));
    }

    record TrackingPageResponse(List<TrackedVehicleResponse> content, int page, int size, long totalElements,
            int totalPages, long staleAfterSeconds) {

        static TrackingPageResponse from(VehicleTrackingQueryService.TrackingPage page) {
            return new TrackingPageResponse(page.content().stream().map(TrackedVehicleResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages(),
                    page.staleAfter().toSeconds());
        }
    }

    /** Position and age are null for a vehicle that has never reported. */
    record TrackedVehicleResponse(UUID vehicleId, String registrationNumber, String make, String model,
            String siteCode, String trackingStatus, BigDecimal latitude, BigDecimal longitude,
            Instant recordedAt, String sourceSystem) {

        static TrackedVehicleResponse from(VehicleTrackingQueryService.VehicleTracking row) {
            var vehicle = row.vehicle();
            var report = row.latest();
            return new TrackedVehicleResponse(vehicle.id(), vehicle.registrationNumber().value(),
                    vehicle.specification().make(), vehicle.specification().model(), vehicle.siteCode().value(),
                    row.status().name(), report == null ? null : report.latitude(),
                    report == null ? null : report.longitude(), report == null ? null : report.recordedAt(),
                    report == null ? null : report.sourceSystem());
        }
    }
}

package gh.edu.clet.sfl.fleetlogistics.fleet.application.query;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleTrackingStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.policy.VehicleTrackingPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where every vehicle in scope is, and whether that can be trusted.
 *
 * <p>One page of vehicles and one query for their latest reports, in place of a request per vehicle.
 * The screen used to ask each vehicle for its movement history one at a time and quietly dropped
 * everything past the thirtieth, so a fleet of forty showed thirty vehicles and said nothing about
 * the rest.
 *
 * <p>The scope filter is applied to the vehicle search, so a vehicle the actor may not see never
 * reaches the location lookup either.
 */
@Service
public class VehicleTrackingQueryService {

    private static final String RESOURCE_TYPE = "Vehicle";

    private final VehicleRepository vehicles;
    private final VehicleLocationRepository locations;
    private final FleetAccessPolicy accessPolicy;
    private final Clock clock;
    private final Duration staleAfter;

    public VehicleTrackingQueryService(VehicleRepository vehicles, VehicleLocationRepository locations,
            FleetAccessPolicy accessPolicy, Clock clock,
            @Value("${sfl.fleet.tracking.stale-after:PT15M}") Duration staleAfter) {
        this.vehicles = vehicles;
        this.locations = locations;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @Transactional(readOnly = true)
    public TrackingPage overview(VehicleRepository.VehicleSearchCriteria criteria, ActorContext actor) {
        accessPolicy.requirePermission(actor, SflPermission.FLEET_VEHICLE_READ, RESOURCE_TYPE);
        SiteScopeFilter scope = accessPolicy.requireSiteScopeFilter(actor);

        VehicleRepository.VehiclePage page = vehicles.search(criteria, scope);
        Map<UUID, VehicleLocationSnapshot> latest = locations.findLatestByVehicles(
                page.content().stream().map(Vehicle::id).toList());

        List<VehicleTracking> rows = page.content().stream().map(vehicle -> {
            VehicleLocationSnapshot report = latest.get(vehicle.id());
            VehicleTrackingStatus status = VehicleTrackingPolicy.classify(
                    report == null ? null : report.recordedAt(), clock.instant(), staleAfter);
            return new VehicleTracking(vehicle, report, status);
        }).toList();
        return new TrackingPage(rows, page.page(), page.size(), page.totalElements(), page.totalPages(),
                page.sort(), staleAfter);
    }

    public record VehicleTracking(Vehicle vehicle, VehicleLocationSnapshot latest, VehicleTrackingStatus status) {
    }

    public record TrackingPage(List<VehicleTracking> content, int page, int size, long totalElements,
            int totalPages, String sort, Duration staleAfter) {
    }
}

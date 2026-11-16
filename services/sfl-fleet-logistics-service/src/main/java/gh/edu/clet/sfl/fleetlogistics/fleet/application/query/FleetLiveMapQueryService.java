package gh.edu.clet.sfl.fleetlogistics.fleet.application.query;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.DriverProfileRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverProfileReference;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLifecycleStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleTrackingStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.policy.VehicleTrackingPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live fleet map: every active vehicle in scope, where it is, whether it is moving, which way,
 * how fast, the path it took recently, and the trip it is on.
 *
 * <p>Speed and heading are derived from the two most recent reports in the trail window. No
 * telematics vendor is connected yet, so positions come from the driver's phone during a trip or from
 * the integrations inbox; when a vendor feed arrives this needs no change.
 */
@Service
public class FleetLiveMapQueryService {

    /** Below this, a vehicle is treated as stationary - GPS jitter on a parked phone is a few km/h. */
    static final double MOVING_THRESHOLD_KPH = 3.0;
    static final int MAX_VEHICLES = 200;
    static final int MAX_TRAIL_POINTS = 30;
    static final Duration MAX_TRAIL_WINDOW = Duration.ofHours(6);

    private final VehicleRepository vehicles;
    private final VehicleLocationRepository locations;
    private final TripRepository trips;
    private final DriverProfileRepository drivers;
    private final FleetAccessPolicy accessPolicy;
    private final Clock clock;
    private final Duration staleAfter;

    public FleetLiveMapQueryService(VehicleRepository vehicles, VehicleLocationRepository locations,
            TripRepository trips, DriverProfileRepository drivers, FleetAccessPolicy accessPolicy, Clock clock,
            @Value("${sfl.fleet.tracking.stale-after:PT15M}") Duration staleAfter) {
        this.vehicles = vehicles;
        this.locations = locations;
        this.trips = trips;
        this.drivers = drivers;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @Transactional(readOnly = true)
    public LiveMap live(String siteCode, Duration trailWindow, ActorContext actor) {
        accessPolicy.requirePermission(actor, SflPermission.FLEET_VEHICLE_READ, "Vehicle");
        SiteScopeFilter scope = accessPolicy.requireSiteScopeFilter(actor);
        Instant now = clock.instant();
        Duration window = trailWindow == null || trailWindow.isNegative() || trailWindow.isZero()
                ? Duration.ofMinutes(60)
                : (trailWindow.compareTo(MAX_TRAIL_WINDOW) > 0 ? MAX_TRAIL_WINDOW : trailWindow);

        VehicleRepository.VehiclePage page = vehicles.search(new VehicleRepository.VehicleSearchCriteria(siteCode,
                VehicleLifecycleStatus.ACTIVE, null, null, null, null, null, 0, MAX_VEHICLES, "registrationNumber"),
                scope);
        List<UUID> ids = page.content().stream().map(Vehicle::id).toList();

        Map<UUID, VehicleLocationSnapshot> latest = locations.findLatestByVehicles(ids);
        Map<UUID, List<VehicleLocationSnapshot>> trails = new HashMap<>();
        locations.findSinceByVehicles(ids, now.minus(window), ids.size() * MAX_TRAIL_POINTS)
                .forEach(point -> trails.computeIfAbsent(point.vehicleId(), id -> new ArrayList<>()).add(point));

        Map<UUID, Trip> activeTrips = new HashMap<>();
        trips.search(new TripRepository.TripSearchCriteria(siteCode, TripStatus.IN_PROGRESS, null, null, null, null,
                null, 0, MAX_VEHICLES, null), scope).content().forEach(trip -> {
                    if (trip.vehicleId() != null) {
                        activeTrips.put(trip.vehicleId(), trip);
                    }
                });
        Map<UUID, Optional<DriverProfileReference>> driverCache = new HashMap<>();

        List<LiveVehicle> rows = page.content().stream().map(vehicle -> {
            VehicleLocationSnapshot report = latest.get(vehicle.id());
            VehicleTrackingStatus status = VehicleTrackingPolicy.classify(
                    report == null ? null : report.recordedAt(), now, staleAfter);
            List<VehicleLocationSnapshot> trail = trails.getOrDefault(vehicle.id(), List.of()).stream()
                    .sorted(Comparator.comparing(VehicleLocationSnapshot::recordedAt))
                    .toList();
            if (trail.size() > MAX_TRAIL_POINTS) {
                trail = trail.subList(trail.size() - MAX_TRAIL_POINTS, trail.size());
            }
            Motion motion = motion(trail, status);
            Trip trip = activeTrips.get(vehicle.id());
            ActiveTrip active = null;
            if (trip != null) {
                String driverName = trip.driverId() == null ? null
                        : driverCache.computeIfAbsent(trip.driverId(), drivers::findById)
                                .map(DriverProfileReference::displayName).orElse(null);
                active = new ActiveTrip(trip.id(), trip.tripNumber(), trip.origin(), trip.destination(),
                        trip.driverId(), driverName);
            }
            return new LiveVehicle(vehicle, report, status, motion, trail, active);
        }).toList();

        return new LiveMap(rows, page.totalElements(), staleAfter, window, now);
    }

    /** Speed and heading from the two newest trail points; movement from those and the tracking state. */
    static Motion motion(List<VehicleLocationSnapshot> trail, VehicleTrackingStatus status) {
        if (status != VehicleTrackingStatus.TRACKED) {
            return new Motion(Movement.OFFLINE, null, null);
        }
        if (trail.size() < 2) {
            return new Motion(Movement.IDLE, null, null);
        }
        VehicleLocationSnapshot previous = trail.get(trail.size() - 2);
        VehicleLocationSnapshot last = trail.get(trail.size() - 1);
        double seconds = Duration.between(previous.recordedAt(), last.recordedAt()).toMillis() / 1000.0;
        double metres = distanceMetres(previous, last);
        if (seconds <= 0) {
            return new Motion(Movement.IDLE, null, null);
        }
        double kph = metres / seconds * 3.6;
        Double heading = metres < 1 ? null : bearingDegrees(previous, last);
        return new Motion(kph >= MOVING_THRESHOLD_KPH ? Movement.MOVING : Movement.IDLE,
                Math.round(kph * 10) / 10.0, heading == null ? null : (double) Math.round(heading));
    }

    static double distanceMetres(VehicleLocationSnapshot from, VehicleLocationSnapshot to) {
        double lat1 = Math.toRadians(from.latitude().doubleValue());
        double lat2 = Math.toRadians(to.latitude().doubleValue());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.longitude().doubleValue() - from.longitude().doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double bearingDegrees(VehicleLocationSnapshot from, VehicleLocationSnapshot to) {
        double lat1 = Math.toRadians(from.latitude().doubleValue());
        double lat2 = Math.toRadians(to.latitude().doubleValue());
        double dLon = Math.toRadians(to.longitude().doubleValue() - from.longitude().doubleValue());
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    public enum Movement { MOVING, IDLE, OFFLINE }

    public record Motion(Movement movement, Double speedKph, Double headingDegrees) {
    }

    public record ActiveTrip(UUID tripId, String tripNumber, String origin, String destination, UUID driverId,
            String driverName) {
    }

    public record LiveVehicle(Vehicle vehicle, VehicleLocationSnapshot latest, VehicleTrackingStatus status,
            Motion motion, List<VehicleLocationSnapshot> trail, ActiveTrip trip) {
    }

    public record LiveMap(List<LiveVehicle> vehicles, long totalVehicles, Duration staleAfter, Duration trailWindow,
            Instant generatedAt) {
    }
}

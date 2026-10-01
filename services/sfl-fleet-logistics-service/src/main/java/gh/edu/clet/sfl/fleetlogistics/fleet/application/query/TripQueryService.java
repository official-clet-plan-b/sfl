package gh.edu.clet.sfl.fleetlogistics.fleet.application.query;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.DriverProfileRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleInspectionRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverScope;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverScopeResolver;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetReadinessService;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.RecordNotFoundException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DateTimeRange;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.ReadinessAssessment;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleInspection;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read use cases for trips, inspections and the readiness preview shown before an assignment. */
@Service
public class TripQueryService {

    private static final String RESOURCE_TYPE = "Trip";

    private final TripRepository trips;
    private final VehicleRepository vehicles;
    private final VehicleInspectionRepository inspections;
    private final FleetReadinessService readinessService;
    private final FleetAccessPolicy accessPolicy;
    private final DriverScopeResolver driverScopes;
    private final VehicleLocationRepository locations;

    public TripQueryService(TripRepository trips, VehicleRepository vehicles,
            VehicleInspectionRepository inspections,
            FleetReadinessService readinessService,
            FleetAccessPolicy accessPolicy, DriverScopeResolver driverScopes,
            VehicleLocationRepository locations) {
        this.trips = trips;
        this.vehicles = vehicles;
        this.inspections = inspections;
        this.readinessService = readinessService;
        this.accessPolicy = accessPolicy;
        this.driverScopes = driverScopes;
        this.locations = locations;
    }

    /**
     * The vehicle's latest known position for this trip, for a live map.
     *
     * <p>Goes through {@link #findById} first and only - every authorisation and narrowing rule a
     * trip-detail read already enforces applies identically here, because seeing a live position is
     * strictly less sensitive than seeing the trip it belongs to. {@code Optional.empty()} is the
     * honest answer before anything has reported a position; the caller does not get to tell that
     * apart from "the trip does not exist", because it already could not by the time {@link #findById}
     * returned.
     */
    @Transactional(readOnly = true)
    public Optional<VehicleLocationSnapshot> latestLocation(UUID tripId, ActorContext actor) {
        Trip trip = findById(tripId, actor);
        if (trip.vehicleId() == null) {
            return Optional.empty();
        }
        return locations.findLatestByVehicle(trip.vehicleId());
    }

    /**
     * Site scope is the wrong boundary for a driver, and it was the only one applied here.
     *
     * <p>{@link FleetAccessPolicy#requireRecordScope} is documented as "what keeps the limited
     * driver/mobile user class to their own trips and inspections", and it is unit-tested - but until
     * now no read called it, so a {@code FLEET_DRIVER} holding any trip id read that trip in full:
     * route, purpose, operating mode and the driver it belongs to. Applying it to the collection and
     * not to the record would be decorative, so it is applied here, where the record is returned.
     *
     * <p>The comparison itself is now the {@code principal_subject} binding rather than a staff
     * reference matched against the token subject - see {@link DriverScopeResolver} for why the old
     * form could never be true once authentication was on.
     *
     * <p>A supervising {@code FLEET_TRIP_MANAGE} passes through, which is what keeps an officer or a
     * manager unaffected by the narrowing.
     */
    @Transactional(readOnly = true)
    public Trip findById(UUID tripId, ActorContext actor) {
        accessPolicy.requirePermission(actor, SflPermission.FLEET_TRIP_READ, RESOURCE_TYPE);
        Trip trip = trips.findById(tripId)
                .orElseThrow(() -> RecordNotFoundException.of(RESOURCE_TYPE, tripId));
        accessPolicy.requireSiteAccess(actor, trip.siteCode(), RESOURCE_TYPE, tripId.toString());
        requireOwnTrip(trip, actor);
        return trip;
    }

    /**
     * Refuses a narrowed actor a trip that is not theirs.
     *
     * <p>An unassigned trip - {@code driverId} null - is refused to a narrowed actor rather than
     * allowed. The old {@code requireRecordScope} returned early when the owner reference was blank,
     * which is right for a record that genuinely has no owner but wrong here: an unassigned trip is
     * nobody's, and "nobody's" must not read as "everybody's" for the one role class this narrowing
     * exists to contain.
     */
    private void requireOwnTrip(Trip trip, ActorContext actor) {
        DriverScope scope = driverScopes.resolve(actor, SflPermission.FLEET_TRIP_MANAGE);

        if (scope instanceof DriverScope.Everything) {
            // Supervising actor: site scope, already checked, is the whole boundary.
            return;
        }
        if (scope instanceof DriverScope.Own own && own.driverId().equals(trip.driverId())) {
            return;
        }

        String reason = scope instanceof DriverScope.Nothing nothing
                ? nothing.reason()
                : "This trip is assigned to another driver";
        throw new FleetAuthorizationException(Map.of(
                "requiredPermission", SflPermission.FLEET_TRIP_MANAGE.name(),
                "resourceType", RESOURCE_TYPE,
                "resourceId", trip.id().toString(),
                "reason", reason));
    }

    /**
     * The trip list, narrowed to the actor's own trips when they are a driver.
     *
     * <p>This was the unnarrowed read: permission plus site scope and nothing else, so a driver's trip
     * list was every trip at their site. The driver filter is <strong>overridden</strong>, never merged
     * with the caller's - a narrowed actor who passes {@code ?driverId=<somebody else>} gets their own
     * trips, not that driver's. Merging (honouring the caller's value when present) is the version of
     * this that looks correct and hands the whole register to anyone who reads the query string.
     *
     * <p>An unbound driver gets an empty page without the query running at all.
     */
    @Transactional(readOnly = true)
    public ScopedTrips search(TripRepository.TripSearchCriteria criteria, ActorContext actor) {
        accessPolicy.requirePermission(actor, SflPermission.FLEET_TRIP_READ, RESOURCE_TYPE);
        SiteScopeFilter scope = accessPolicy.requireSiteScopeFilter(actor);
        DriverScope driverScope = driverScopes.resolve(actor, SflPermission.FLEET_TRIP_MANAGE);

        if (driverScope instanceof DriverScope.Nothing nothing) {
            return new ScopedTrips(emptyPage(criteria), nothing.reason());
        }

        TripRepository.TripSearchCriteria effective = driverScope instanceof DriverScope.Own own
                ? withDriver(criteria, own.driverId())
                : criteria;
        return new ScopedTrips(trips.search(effective, scope), narrowingNotice(driverScope));
    }

    /**
     * A page of trips and, when the list was narrowed, why.
     *
     * <p>The reason travels with the data rather than being inferred by the client from the roles it
     * happens to hold. A client that has to work out for itself why a list is short will eventually
     * work it out differently from the server.
     */
    public record ScopedTrips(TripRepository.TripPage page, String scopeNotice) {
    }

    private static TripRepository.TripPage emptyPage(TripRepository.TripSearchCriteria criteria) {
        return new TripRepository.TripPage(List.of(), criteria.page(), criteria.size(), 0L, 0, criteria.sort());
    }

    private static TripRepository.TripSearchCriteria withDriver(TripRepository.TripSearchCriteria criteria,
            UUID driverId) {
        return new TripRepository.TripSearchCriteria(criteria.siteCode(), criteria.status(), criteria.vehicleId(),
                driverId, criteria.operatingMode(), criteria.from(), criteria.to(), criteria.page(),
                criteria.size(), criteria.sort());
    }

    /**
     * Why a list is shorter than the site's.
     *
     * <p>Carried to the client so the interface can say "showing your assigned trips" instead of
     * presenting a silently filtered list as if it were the whole one. A narrowing the user cannot see
     * is one they will report as missing data.
     */
    private static String narrowingNotice(DriverScope scope) {
        return scope instanceof DriverScope.Own own
                ? "Showing trips assigned to you (" + own.staffReference() + ")."
                : null;
    }

    @Transactional(readOnly = true)
    public List<VehicleInspection> findInspections(UUID tripId, ActorContext actor) {
        Trip trip = findById(tripId, actor);
        return inspections.findByTrip(trip.id());
    }

    /**
     * The readiness preview the dashboard shows before an officer commits to an assignment.
     *
     * <p>Answering the same question the assignment path will ask - with the same policy and the same
     * inputs - is what stops the preview and the outcome disagreeing.
     */
    @Transactional(readOnly = true)
    public ReadinessAssessment previewAssignment(UUID vehicleId, UUID driverId, Instant from, Instant to,
            OperatingMode operatingMode, ActorContext actor) {
        accessPolicy.requirePermission(actor, SflPermission.FLEET_VEHICLE_READ, "Vehicle");
        Vehicle vehicle = vehicles.findById(vehicleId)
                .orElseThrow(() -> RecordNotFoundException.of("Vehicle", vehicleId));
        accessPolicy.requireSiteAccess(actor, vehicle.siteCode(), "Vehicle", vehicleId.toString());

        if (from == null || to == null) {
            return readinessService.assessVehicle(vehicle);
        }
        return readinessService.assessForAssignment(vehicle, driverId, DateTimeRange.of(from, to),
                operatingMode == null ? OperatingMode.ROUTINE : operatingMode, vehicle.siteCode(), null, false);
    }
}

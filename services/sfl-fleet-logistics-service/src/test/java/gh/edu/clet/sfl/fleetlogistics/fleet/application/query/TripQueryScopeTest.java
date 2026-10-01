package gh.edu.clet.sfl.fleetlogistics.fleet.application.query;

import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.ACCRA;
import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.NOW;
import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.TODAY;
import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.metadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.DriverScopeResolver;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetReadinessService;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DateTimeRange;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverProfileReference;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.LicenceClass;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.LicenceDetails;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetTestDoubles;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetWorkflowTestDoubles;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A driver sees their own trips and nobody else's.
 *
 * <h2>What was actually wrong</h2>
 *
 * <p>{@code TripQueryService.search} applied a permission check and a site filter, and stopped. A
 * {@code FLEET_DRIVER} therefore opened their trip screen and got every trip at their site - every
 * colleague's route, purpose, operating mode and timing. The by-id read did call a record-scope check,
 * but that check compared the driver's staff reference against the token subject, which cannot match
 * once authentication is on, so it refused drivers their own trips while the list showed them
 * everybody's. Both halves are covered here.
 */
class TripQueryScopeTest {

    private static final UUID VEHICLE_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final DateTimeRange PERIOD =
            DateTimeRange.of(NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(6)));

    private FleetWorkflowTestDoubles.InMemoryTripRepository trips;
    private FleetTestDoubles.InMemoryDriverProfileRepository drivers;
    private TripQueryService queries;
    private FleetTestDoubles.InMemoryLocationRepository locations;

    private DriverProfileReference kwame;
    private DriverProfileReference ama;
    private Trip kwamesTrip;
    private Trip amasTrip;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        trips = new FleetWorkflowTestDoubles.InMemoryTripRepository();
        drivers = new FleetTestDoubles.InMemoryDriverProfileRepository();
        FleetTestDoubles.InMemoryVehicleRepository vehicles = new FleetTestDoubles.InMemoryVehicleRepository();
        FleetWorkflowTestDoubles.InMemoryInspectionRepository inspections =
                new FleetWorkflowTestDoubles.InMemoryInspectionRepository();

        FleetAccessPolicy accessPolicy = new FleetAccessPolicy();
        FleetReadinessService readiness = new FleetReadinessService(
                new FleetTestDoubles.InMemoryComplianceDocumentRepository(), inspections, trips, drivers,
                new FleetTestDoubles.FixedRuntimeConfiguration(), clock);
        locations = new FleetTestDoubles.InMemoryLocationRepository();
        queries = new TripQueryService(trips, vehicles, inspections, readiness, accessPolicy,
                new DriverScopeResolver(drivers, accessPolicy), locations);

        kwame = drivers.save(driver("11111111-1111-1111-1111-111111111111", "CLET/HR/00123", "Kwame Mensah",
                "GHA-DL-4477201", "CLET/HR/00123"));
        ama = drivers.save(driver("22222222-2222-2222-2222-222222222222", "CLET/HR/00456", "Ama Owusu",
                "GHA-DL-1122334", "CLET/HR/00456"));

        kwamesTrip = trips.save(assignedTrip("33333333-3333-3333-3333-333333333333", "TRP-1", kwame.id()));
        amasTrip = trips.save(assignedTrip("44444444-4444-4444-4444-444444444444", "TRP-2", ama.id()));
    }

    @Test
    @DisplayName("an officer sees every trip in their site scope")
    void a_supervisor_is_not_narrowed() {
        var result = queries.search(criteria(null), FleetTestDoubles.fleetOfficer("ACCRA"));

        assertThat(result.page().content()).hasSize(2);
        assertThat(result.scopeNotice()).isNull();
    }

    @Test
    @DisplayName("a driver sees only the trips assigned to them")
    void a_driver_sees_only_their_own() {
        var result = queries.search(criteria(null), driverActor("CLET/HR/00123"));

        assertThat(result.page().content()).extracting(Trip::id).containsExactly(kwamesTrip.id());
        assertThat(result.scopeNotice()).contains("CLET/HR/00123");
    }

    /**
     * The filter is overridden, not merged.
     *
     * <p>A narrowing that honours a caller-supplied {@code driverId} when one is present looks correct
     * and hands the whole register to anyone who edits the query string. This is the test that would
     * have caught it.
     */
    @Test
    @DisplayName("a driver asking for another driver's trips still gets their own")
    void the_caller_supplied_driver_filter_cannot_widen_the_scope() {
        var result = queries.search(criteria(ama.id()), driverActor("CLET/HR/00123"));

        assertThat(result.page().content()).extracting(Trip::id).containsExactly(kwamesTrip.id());
    }

    /** The decision this pass was given, stated as a test: an unbound driver sees no trips. */
    @Test
    @DisplayName("a driver bound to no profile sees nothing, and is told why")
    void an_unbound_driver_sees_nothing() {
        var result = queries.search(criteria(null), driverActor("no-profile-names-this-subject"));

        assertThat(result.page().content()).isEmpty();
        assertThat(result.page().totalElements()).isZero();
        assertThat(result.scopeNotice()).contains("not linked to a driver profile");
    }

    @Test
    @DisplayName("a driver reading another driver's trip by id is refused")
    void a_driver_cannot_read_another_drivers_trip_by_id() {
        assertThatThrownBy(() -> queries.findById(amasTrip.id(), driverActor("CLET/HR/00123")))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("a driver reads their own trip by id")
    void a_driver_reads_their_own_trip_by_id() {
        assertThat(queries.findById(kwamesTrip.id(), driverActor("CLET/HR/00123")).id())
                .isEqualTo(kwamesTrip.id());
    }

    /**
     * An unassigned trip belongs to nobody, and nobody is not everybody.
     *
     * <p>The record-scope check this replaced returned early when the owner reference was blank, so a
     * planned trip with no driver was readable by any driver holding its id.
     */
    @Test
    @DisplayName("a driver cannot read an unassigned trip")
    void a_driver_cannot_read_an_unassigned_trip() {
        Trip unassigned = trips.save(Trip.plan(UUID.fromString("55555555-5555-5555-5555-555555555555"),
                "TRP-3", ACCRA, "Deliver examination materials", "Accra HQ", "Kumasi Centre",
                OperatingMode.EXAMINATION, PERIOD, metadata()));

        assertThatThrownBy(() -> queries.findById(unassigned.id(), driverActor("CLET/HR/00123")))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("a reported position is visible through the same narrowing as the trip itself")
    void latest_location_is_narrowed_like_the_trip_it_belongs_to() {
        locations.save(new VehicleLocationSnapshot(UUID.randomUUID(), VEHICLE_ID, ACCRA,
                new BigDecimal("5.6037"), new BigDecimal("-0.186964"), null, NOW, "DRIVER_MOBILE",
                UUID.randomUUID(), null));

        assertThat(queries.latestLocation(kwamesTrip.id(), driverActor("CLET/HR/00123")))
                .isPresent()
                .get()
                .extracting(VehicleLocationSnapshot::vehicleId)
                .isEqualTo(VEHICLE_ID);

        // Ama's driver scope cannot reach Kwame's trip, so it cannot reach its position either -
        // the same refusal findById already gives, not a second rule to keep in step with it.
        assertThatThrownBy(() -> queries.latestLocation(kwamesTrip.id(), driverActor("CLET/HR/00456")))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("a trip nobody has reported a position for answers empty, not an error")
    void latest_location_is_empty_before_anything_is_reported() {
        assertThat(queries.latestLocation(kwamesTrip.id(), driverActor("CLET/HR/00123"))).isEmpty();
    }

    private static TripRepository.TripSearchCriteria criteria(UUID driverId) {
        return new TripRepository.TripSearchCriteria(null, null, null, driverId, null, null, null, 0, 20, null);
    }

    private static ActorContext driverActor(String subject) {
        return FleetTestDoubles.driver(subject, "ACCRA");
    }

    private static Trip assignedTrip(String id, String tripNumber, UUID driverId) {
        return Trip.plan(UUID.fromString(id), tripNumber, ACCRA, "Deliver examination materials", "Accra HQ",
                        "Kumasi Centre", OperatingMode.EXAMINATION, PERIOD, metadata())
                .assign(VEHICLE_ID, driverId, metadata());
    }

    private static DriverProfileReference driver(String id, String staffReference, String displayName,
            String licenceNumber, String principalSubject) {
        return DriverProfileReference.register(UUID.fromString(id), staffReference, displayName,
                new LicenceDetails(licenceNumber, LicenceClass.D, TODAY.plusYears(1)), TODAY.plusYears(1),
                ACCRA, "Transportation & Logistics Unit", principalSubject, metadata());
    }
}

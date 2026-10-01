package gh.edu.clet.sfl.fleetlogistics.fleet.application.service;

import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.ACCRA;
import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.NOW;
import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.TODAY;
import static gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures.metadata;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.AcknowledgeTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.AssignTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CancelTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CloseTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CreateTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.HoldTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RecordInspectionCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.ReportTripLocationCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.StartTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.event.FleetEventType;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.AssignmentConflictException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.ClosureEvidenceMissingException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.DriverIneligibleException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetErrorCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.InvalidStateTransitionException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.PreTripInspectionMissingException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.ReadinessBlockedException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.AuditAction;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.ComplianceDocument;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.ComplianceDocumentType;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DefectSeverity;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverProfileReference;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.InspectionType;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.LicenceClass;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.LicenceDetails;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripAcknowledgementState;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleAvailabilityStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleInspection;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleServiceStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetTestDoubles;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetWorkflowTestDoubles;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Traces: SRS-SFL-S166-02 assignment, hold, cancellation and closure, and the readiness gating that
 * makes an unfit assignment impossible.
 */
class TripApplicationServiceTest {

    private static final Instant PERIOD_START = NOW.plus(Duration.ofHours(2));
    private static final Instant PERIOD_END = NOW.plus(Duration.ofHours(6));
    private static final UUID EVIDENCE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private FleetTestDoubles.InMemoryVehicleRepository vehicles;
    private FleetTestDoubles.InMemoryDriverProfileRepository drivers;
    private FleetTestDoubles.InMemoryComplianceDocumentRepository complianceDocuments;
    private FleetWorkflowTestDoubles.InMemoryTripRepository trips;
    private FleetWorkflowTestDoubles.InMemoryInspectionRepository inspections;
    private FleetWorkflowTestDoubles.RecordingWorkflowRaiser workflowRaiser;
    private FleetTestDoubles.RecordingAuditPort audit;
    private FleetTestDoubles.RecordingEventPublisher events;
    private TripApplicationService service;

    private Vehicle vehicle;
    private DriverProfileReference driver;

    @BeforeEach
    void setUp() {
        vehicles = new FleetTestDoubles.InMemoryVehicleRepository();
        drivers = new FleetTestDoubles.InMemoryDriverProfileRepository();
        complianceDocuments = new FleetTestDoubles.InMemoryComplianceDocumentRepository();
        trips = new FleetWorkflowTestDoubles.InMemoryTripRepository();
        inspections = new FleetWorkflowTestDoubles.InMemoryInspectionRepository();
        workflowRaiser = new FleetWorkflowTestDoubles.RecordingWorkflowRaiser();

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        audit = new FleetTestDoubles.RecordingAuditPort(clock);
        events = new FleetTestDoubles.RecordingEventPublisher();

        FleetReadinessService readiness = new FleetReadinessService(complianceDocuments, inspections, trips,
                drivers, new FleetTestDoubles.FixedRuntimeConfiguration(), clock);
        FleetAccessPolicy accessPolicy = new FleetAccessPolicy();
        service = new TripApplicationService(trips, vehicles, inspections, drivers, readiness, workflowRaiser,
                accessPolicy, audit, events, new FleetTestDoubles.InMemoryIdempotencyPort(),
                new DriverScopeResolver(drivers, accessPolicy), new FleetTestDoubles.InMemoryLocationRepository(),
                clock);

        vehicle = vehicles.save(FleetFixtures.vehicle());
        driver = drivers.save(eligibleDriver());
        giveVehicleFullCompliance();
    }

    // --- creation and assignment --------------------------------------------------------

    @Test
    @DisplayName("a compliant vehicle and eligible driver are assigned, audited and published")
    void valid_assignment_succeeds() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        assertThat(trip.status()).isEqualTo(TripStatus.ASSIGNED);
        assertThat(trip.vehicleId()).isEqualTo(vehicle.id());
        assertThat(trip.driverId()).isEqualTo(driver.id());
        assertThat(vehicles.findById(vehicle.id()).orElseThrow().availabilityStatus())
                .isEqualTo(VehicleAvailabilityStatus.ASSIGNED);
        assertThat(audit.hasRecord(AuditAction.CREATE, "Trip")).isTrue();
        assertThat(events.types()).contains(FleetEventType.VEHICLE_ASSIGNED);
    }

    @Test
    @DisplayName("a trip may be planned without a vehicle or driver")
    void a_trip_may_be_planned_first() {
        Trip trip = service.create(createCommand(null, null, "idem-1"));

        assertThat(trip.status()).isEqualTo(TripStatus.PLANNED);
        assertThat(events.types()).doesNotContain(FleetEventType.VEHICLE_ASSIGNED);
    }

    @Test
    @DisplayName("expired compliance blocks the assignment and names the document")
    void expired_compliance_blocks_assignment() {
        Vehicle vehicleWithExpiredInsurance = vehicles.save(FleetFixtures.vehicle(
                UUID.fromString("77777777-7777-7777-7777-777777777777"), "GT-7777-26", ACCRA));
        giveComplianceExcept(vehicleWithExpiredInsurance.id(), ComplianceDocumentType.INSURANCE_CERTIFICATE);
        complianceDocuments.save(FleetFixtures.complianceDocument(vehicleWithExpiredInsurance.id(),
                ComplianceDocumentType.INSURANCE_CERTIFICATE, TODAY.minusYears(1), TODAY.minusDays(1)));

        assertThatThrownBy(() -> service.create(createCommand(vehicleWithExpiredInsurance.id(), driver.id(),
                "idem-1")))
                .isInstanceOf(ReadinessBlockedException.class)
                .extracting(exception -> ((ReadinessBlockedException) exception).details())
                .satisfies(details -> assertThat(details.get("blockerCodes").toString())
                        .contains("COMPLIANCE_DOCUMENT_EXPIRED"));

        assertThat(trips.size()).isZero();
    }

    @Test
    @DisplayName("an ineligible driver blocks the assignment with the driver-specific error")
    void ineligible_driver_blocks_assignment() {
        DriverProfileReference expired = drivers.save(driverWithLicenceExpiring(TODAY.minusDays(1)));

        assertThatThrownBy(() -> service.create(createCommand(vehicle.id(), expired.id(), "idem-1")))
                .isInstanceOf(DriverIneligibleException.class)
                .hasMessage(FleetErrorCode.FLEET_DRIVER_INELIGIBLE.message());
    }

    @Test
    @DisplayName("an overlapping vehicle assignment is refused as a conflict")
    void overlapping_vehicle_assignment_is_refused() {
        service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        DriverProfileReference otherDriver = drivers.save(anotherEligibleDriver());

        assertThatThrownBy(() -> service.create(createCommand(vehicle.id(), otherDriver.id(), "idem-2")))
                .isInstanceOf(AssignmentConflictException.class)
                .hasMessage(FleetErrorCode.FLEET_ASSIGNMENT_CONFLICT.message());
    }

    @Test
    @DisplayName("an overlapping driver assignment is refused as a conflict")
    void overlapping_driver_assignment_is_refused() {
        service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        Vehicle otherVehicle = vehicles.save(FleetFixtures.vehicle(
                UUID.fromString("55555555-5555-5555-5555-555555555555"), "GT-9999-26", ACCRA));
        giveCompliance(otherVehicle.id());

        assertThatThrownBy(() -> service.create(createCommand(otherVehicle.id(), driver.id(), "idem-2")))
                .isInstanceOf(AssignmentConflictException.class);
    }

    @Test
    @DisplayName("back-to-back trips on the same vehicle do not conflict")
    void back_to_back_trips_do_not_conflict() {
        service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        Trip second = service.create(new CreateTripCommand(vehicle.id(), driver.id(), "ACCRA",
                "Return leg", "Kumasi Centre", "Accra HQ", OperatingMode.EXAMINATION, PERIOD_END,
                PERIOD_END.plus(Duration.ofHours(4)), FleetTestDoubles.fleetOfficer("ACCRA"),
                SourceChannel.WEB, "idem-2"));

        assertThat(second.status()).isEqualTo(TripStatus.ASSIGNED);
        assertThat(trips.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("an emergency-only vehicle is refused for a routine trip")
    void emergency_only_vehicle_is_refused_for_routine_work() {
        Vehicle ambulance = vehicles.save(FleetFixtures.emergencyOnlyVehicle());
        giveCompliance(ambulance.id());

        assertThatThrownBy(() -> service.create(new CreateTripCommand(ambulance.id(), driver.id(), "ACCRA",
                "Campus shuttle", "Accra HQ", "Legon", OperatingMode.ROUTINE, PERIOD_START, PERIOD_END,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB, "idem-1")))
                .isInstanceOf(ReadinessBlockedException.class)
                .extracting(exception -> ((ReadinessBlockedException) exception).details())
                .satisfies(details -> assertThat(details.get("blockerCodes").toString())
                        .contains("EMERGENCY_ONLY_RESTRICTION"));
    }

    @Test
    @DisplayName("a viewer cannot create a trip")
    void viewer_cannot_create_a_trip() {
        assertThatThrownBy(() -> service.create(new CreateTripCommand(vehicle.id(), driver.id(), "ACCRA",
                "Shuttle", "A", "B", OperatingMode.ROUTINE, PERIOD_START, PERIOD_END,
                FleetTestDoubles.reportingViewer("ACCRA"), SourceChannel.WEB, "idem-1")))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("reassignment needs a reason and releases the previous vehicle")
    void reassignment_requires_a_reason_and_releases_the_previous_vehicle() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        Vehicle replacement = vehicles.save(FleetFixtures.vehicle(
                UUID.fromString("55555555-5555-5555-5555-555555555555"), "GT-9999-26", ACCRA));
        giveCompliance(replacement.id());

        assertThatThrownBy(() -> service.assign(new AssignTripCommand(trip.id(), replacement.id(), driver.id(),
                null, null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason is required");

        Trip reassigned = service.assign(new AssignTripCommand(trip.id(), replacement.id(), driver.id(),
                "Original vehicle grounded", null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThat(reassigned.vehicleId()).isEqualTo(replacement.id());
        assertThat(vehicles.findById(vehicle.id()).orElseThrow().currentTripId()).isNull();
        assertThat(events.types()).contains(FleetEventType.TRIP_REASSIGNED);
    }

    // --- start, hold, cancel, close ------------------------------------------------------

    @Test
    @DisplayName("starting without a valid inspection is blocked")
    void start_requires_an_inspection() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        assertThatThrownBy(() -> service.start(new StartTripCommand(trip.id(), 42_000L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB)))
                .isInstanceOf(PreTripInspectionMissingException.class)
                .extracting(exception -> ((PreTripInspectionMissingException) exception).details())
                .satisfies(details -> assertThat(details.get("blockerCodes").toString())
                        .contains("MANDATORY_INSPECTION_MISSING"));
    }

    @Test
    @DisplayName("a clean pre-trip inspection lets the trip start and marks the vehicle in use")
    void clean_inspection_allows_the_trip_to_start() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        service.recordInspection(inspectionCommand(trip.id(), List.of(), "idem-insp-1"));

        Trip started = service.start(new StartTripCommand(trip.id(), 42_100L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThat(started.status()).isEqualTo(TripStatus.IN_PROGRESS);
        assertThat(vehicles.findById(vehicle.id()).orElseThrow().availabilityStatus())
                .isEqualTo(VehicleAvailabilityStatus.IN_USE);
    }

    @Test
    @DisplayName("a critical inspection defect grounds the vehicle and raises a defect workflow item")
    void critical_defect_takes_vehicle_out_of_service() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        VehicleInspection inspection = service.recordInspection(inspectionCommand(trip.id(),
                List.of(new RecordInspectionCommand.Finding("BRAKES", "Brake failure",
                        DefectSeverity.CRITICAL)), "idem-insp-1"));

        assertThat(inspection.permitsUse()).isFalse();
        assertThat(vehicles.findById(vehicle.id()).orElseThrow().serviceStatus())
                .isEqualTo(VehicleServiceStatus.OUT_OF_SERVICE);
        assertThat(events.types()).contains(FleetEventType.VEHICLE_INSPECTION_FAILED);
        assertThat(workflowRaiser.raised()).anySatisfy(entry ->
                assertThat(entry).startsWith("INSPECTION_DEFECT"));

        // And the trip can no longer start.
        assertThatThrownBy(() -> service.start(new StartTripCommand(trip.id(), 42_100L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB)))
                .isInstanceOf(ReadinessBlockedException.class);
    }

    @Test
    @DisplayName("hold and resume return the trip to what it was doing")
    void hold_and_resume() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        Trip held = service.holdOrResume(new HoldTripCommand(trip.id(), HoldTripCommand.HoldAction.HOLD,
                "Awaiting fuel card", null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));
        assertThat(held.status()).isEqualTo(TripStatus.ON_HOLD);

        Trip resumed = service.holdOrResume(new HoldTripCommand(trip.id(), HoldTripCommand.HoldAction.RESUME,
                null, null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));
        assertThat(resumed.status()).isEqualTo(TripStatus.ASSIGNED);
    }

    @Test
    @DisplayName("only a privileged role may cancel, and the vehicle is released")
    void cancellation_is_privileged_and_releases_the_vehicle() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        assertThatThrownBy(() -> service.cancel(new CancelTripCommand(trip.id(), "No longer needed", null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB)))
                .isInstanceOf(gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception
                        .UnauthorizedApprovalException.class);

        Trip cancelled = service.cancel(new CancelTripCommand(trip.id(), "No longer needed", null,
                FleetTestDoubles.fleetManager("ACCRA"), SourceChannel.WEB));

        assertThat(cancelled.status()).isEqualTo(TripStatus.CANCELLED);
        assertThat(vehicles.findById(vehicle.id()).orElseThrow().availabilityStatus())
                .isEqualTo(VehicleAvailabilityStatus.AVAILABLE);
        assertThat(events.types()).contains(FleetEventType.TRIP_CANCELLED);
    }

    /**
     * The driver finishes their own journey, and only their own.
     *
     * <p>They hold {@code FLEET_TRIP_CLOSE_OWN} rather than {@code FLEET_TRIP_CLOSE}, so the
     * permission alone decides nothing - every driver holds it. What decides is the binding, exactly
     * as it does for acknowledgement, and these three cases are the whole rule: your trip, somebody
     * else's, and a sign-in bound to no driver at all.
     */
    @Test
    @DisplayName("the assigned driver can complete their own trip")
    void a_driver_closes_their_own_trip() {
        Trip inProgress = startedTrip();

        Trip closed = service.close(new CloseTripCommand(inProgress.id(), "Delivered and signed for",
                UUID.randomUUID(), 42_500L, null, boundDriverActor(), SourceChannel.MOBILE));

        assertThat(closed.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(closed.endOdometer()).isEqualTo(42_500L);
        // Named on the record, not only in the audit trail. Matching the driver's own staff
        // reference is what tells this apart from an officer closing it on their behalf, and the
        // two used to look identical.
        assertThat(closed.closedBy()).isEqualTo(driver.staffReference());
        // The fleet office sees it the moment it is written - closure is a record, not a request.
        assertThat(events.types()).contains(FleetEventType.TRIP_COMPLETED);
    }

    @Test
    @DisplayName("a driver cannot complete a trip assigned to somebody else")
    void a_driver_cannot_close_another_drivers_trip() {
        DriverProfileReference other = drivers.save(anotherEligibleDriver());
        Trip trip = service.create(createCommand(vehicle.id(), other.id(), "idem-other"));
        // The pre-trip inspection is what clears the readiness blocker; without it `start` refuses
        // and this would prove nothing about who may close.
        service.recordInspection(inspectionCommand(trip.id(), List.of(), "idem-insp-other"));
        service.start(new StartTripCommand(trip.id(), 42_100L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThatThrownBy(() -> service.close(new CloseTripCommand(trip.id(), "Not mine to finish",
                UUID.randomUUID(), 42_500L, null, boundDriverActor(), SourceChannel.MOBILE)))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("a driver bound to no profile cannot complete anything")
    void an_unbound_driver_cannot_close() {
        Trip inProgress = startedTrip();

        // Fail-closed, as with acknowledgement: "we do not know which driver you are" must not read
        // as "you may be any of them".
        assertThatThrownBy(() -> service.close(new CloseTripCommand(inProgress.id(), "Delivered",
                UUID.randomUUID(), 42_500L, null,
                FleetTestDoubles.driver("nobody-is-bound-to-this", "ACCRA"), SourceChannel.MOBILE)))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    // --- the driver's own live position, without a telematics vendor (S167 seam) ----------

    @Test
    @DisplayName("the assigned driver reports their own trip's position while it is in progress")
    void a_driver_reports_their_own_trips_position() {
        Trip inProgress = startedTrip();

        VehicleLocationSnapshot reported = service.reportOwnLocation(new ReportTripLocationCommand(
                inProgress.id(), new BigDecimal("5.603700"), new BigDecimal("-0.186964"), boundDriverActor(),
                SourceChannel.MOBILE));

        assertThat(reported.vehicleId()).isEqualTo(inProgress.vehicleId());
        assertThat(reported.sourceSystem()).isEqualTo("DRIVER_MOBILE");
        assertThat(reported.latitude()).isEqualByComparingTo("5.603700");
        assertThat(reported.longitude()).isEqualByComparingTo("-0.186964");
    }

    @Test
    @DisplayName("a driver cannot report the position of a trip assigned to somebody else")
    void a_driver_cannot_report_another_drivers_trip_position() {
        DriverProfileReference other = drivers.save(anotherEligibleDriver());
        Trip trip = service.create(createCommand(vehicle.id(), other.id(), "idem-other-loc"));
        service.recordInspection(inspectionCommand(trip.id(), List.of(), "idem-insp-other-loc"));
        service.start(new StartTripCommand(trip.id(), 42_100L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThatThrownBy(() -> service.reportOwnLocation(new ReportTripLocationCommand(trip.id(),
                new BigDecimal("5.6"), new BigDecimal("-0.2"), boundDriverActor(), SourceChannel.MOBILE)))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("a position cannot be reported before the trip is in progress")
    void location_cannot_be_reported_before_the_trip_is_in_progress() {
        Trip assigned = service.create(createCommand(vehicle.id(), driver.id(), "idem-not-started"));

        // ASSIGNED, not IN_PROGRESS: nothing about "the driver is at the wheel right now" is true yet.
        assertThatThrownBy(() -> service.reportOwnLocation(new ReportTripLocationCommand(assigned.id(),
                new BigDecimal("5.6"), new BigDecimal("-0.2"), boundDriverActor(), SourceChannel.MOBILE)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("closure without evidence is blocked with the SRS wording")
    void closure_requires_evidence() {
        Trip inProgress = startedTrip();

        assertThatThrownBy(() -> service.close(new CloseTripCommand(inProgress.id(), "Delivered", null,
                42_500L, null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB)))
                .isInstanceOf(ClosureEvidenceMissingException.class)
                .hasMessage(FleetErrorCode.FLEET_CLOSURE_EVIDENCE_MISSING.message());
    }

    @Test
    @DisplayName("an officer closing on a driver's behalf is recorded as the officer, not the driver")
    void closing_on_behalf_names_the_officer() {
        Trip inProgress = startedTrip();

        Trip closed = service.close(new CloseTripCommand(inProgress.id(), "Driver unreachable",
                EVIDENCE_ID, 42_500L, null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        // The recovery path stays open - a trip nobody can close holds its vehicle forever - but it
        // no longer looks like the driver ended the journey at the vehicle.
        assertThat(closed.closedBy()).isNotEqualTo(driver.staffReference());
        assertThat(closed.status()).isEqualTo(TripStatus.COMPLETED);
    }

    @Test
    @DisplayName("a valid closure completes the trip, releases the vehicle and publishes completion")
    void valid_closure_completes_the_trip() {
        Trip inProgress = startedTrip();

        Trip closed = service.close(new CloseTripCommand(inProgress.id(), "Materials delivered and signed for",
                EVIDENCE_ID, 42_480L, null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThat(closed.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(closed.distanceCovered()).isEqualTo(380L);
        Vehicle released = vehicles.findById(vehicle.id()).orElseThrow();
        assertThat(released.availabilityStatus()).isEqualTo(VehicleAvailabilityStatus.AVAILABLE);
        assertThat(released.odometer().value()).isEqualTo(42_480L);
        assertThat(events.types()).contains(FleetEventType.TRIP_COMPLETED);
        assertThat(audit.hasRecord(AuditAction.CLOSE, "Trip")).isTrue();
    }

    @Test
    @DisplayName("the audit chain stays intact across a full trip lifecycle")
    void audit_chain_stays_intact_across_the_lifecycle() {
        Trip inProgress = startedTrip();
        service.close(new CloseTripCommand(inProgress.id(), "Delivered", EVIDENCE_ID, 42_480L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThat(audit.verifyChain().intact()).isTrue();
    }

    @Test
    @DisplayName("a replayed create returns the original trip rather than double-booking the vehicle")
    void replayed_create_returns_the_original() {
        Trip first = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        Trip replay = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(trips.size()).isEqualTo(1);
    }

    // --- fixtures ------------------------------------------------------------------------

    private Trip startedTrip() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        service.recordInspection(inspectionCommand(trip.id(), List.of(), "idem-insp-1"));
        return service.start(new StartTripCommand(trip.id(), 42_100L, null,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));
    }

    private CreateTripCommand createCommand(UUID vehicleId, UUID driverId, String idempotencyKey) {
        return new CreateTripCommand(vehicleId, driverId, "ACCRA", "Deliver examination materials",
                "Accra HQ", "Kumasi Centre", OperatingMode.EXAMINATION, PERIOD_START, PERIOD_END,
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB, idempotencyKey);
    }

    private RecordInspectionCommand inspectionCommand(UUID tripId,
            List<RecordInspectionCommand.Finding> findings, String idempotencyKey) {
        return new RecordInspectionCommand(tripId, null, InspectionType.PRE_TRIP, 42_050L,
                findings.isEmpty() ? null : EVIDENCE_ID, findings, "Pre-trip check",
                FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.MOBILE, idempotencyKey);
    }

    // --- the driver's own answer (SRS-SFL-S166-02) ---------------------------------------

    @Test
    @DisplayName("the assigned driver confirms their trip without changing its status")
    void assigned_driver_confirms() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        Trip confirmed = service.acknowledge(new AcknowledgeTripCommand(trip.id(),
                TripAcknowledgementState.CONFIRMED, null, null, boundDriverActor(), SourceChannel.MOBILE));

        assertThat(confirmed.acknowledgement().state()).isEqualTo(TripAcknowledgementState.CONFIRMED);
        assertThat(confirmed.acknowledgement().answeredBy()).isEqualTo(driver.staffReference());
        // The point of a separate axis: confirming does not advance the lifecycle.
        assertThat(confirmed.status()).isEqualTo(TripStatus.ASSIGNED);
        assertThat(audit.hasRecord(AuditAction.ACKNOWLEDGE, "Trip")).isTrue();
        assertThat(events.types()).contains(FleetEventType.TRIP_ACKNOWLEDGED);
    }

    @Test
    @DisplayName("a deferral carries its reason, keeps the assignment, and is published separately")
    void assigned_driver_defers_with_a_reason() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        Trip deferred = service.acknowledge(new AcknowledgeTripCommand(trip.id(),
                TripAcknowledgementState.DEFERRED, "Called to an examination centre that morning", null,
                boundDriverActor(), SourceChannel.MOBILE));

        assertThat(deferred.acknowledgement().state()).isEqualTo(TripAcknowledgementState.DEFERRED);
        assertThat(deferred.acknowledgement().reason()).isEqualTo("Called to an examination centre that morning");
        // Still theirs, still holding the vehicle. Deferring is a signal to a dispatcher, not a release.
        assertThat(deferred.status()).isEqualTo(TripStatus.ASSIGNED);
        assertThat(deferred.driverId()).isEqualTo(driver.id());
        assertThat(events.types()).contains(FleetEventType.TRIP_DEFERRED);
    }

    @Test
    @DisplayName("a deferral without a reason is refused")
    void deferral_requires_a_reason() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        assertThatThrownBy(() -> service.acknowledge(new AcknowledgeTripCommand(trip.id(),
                TripAcknowledgementState.DEFERRED, "   ", null, boundDriverActor(), SourceChannel.MOBILE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deferral reason is required");
    }

    @Test
    @DisplayName("a driver cannot answer for a trip assigned to somebody else")
    void another_drivers_trip_cannot_be_acknowledged() {
        DriverProfileReference other = drivers.save(anotherEligibleDriver());
        Trip trip = service.create(createCommand(vehicle.id(), other.id(), "idem-1"));

        assertThatThrownBy(() -> service.acknowledge(new AcknowledgeTripCommand(trip.id(),
                TripAcknowledgementState.CONFIRMED, null, null, boundDriverActor(), SourceChannel.MOBILE)))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    /**
     * The decision this pass was given: an unbound driver sees - and answers for - nothing.
     *
     * <p>The actor holds {@code FLEET_TRIP_ACKNOWLEDGE} and the trip's site, and is still refused,
     * because no driver profile names their sign-in. Fail-closed: the alternative reading of "we do
     * not know which driver you are" is "you may be any of them".
     */
    @Test
    @DisplayName("a driver whose sign-in is bound to no profile cannot acknowledge at all")
    void an_unbound_driver_is_refused() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        assertThatThrownBy(() -> service.acknowledge(new AcknowledgeTripCommand(trip.id(),
                TripAcknowledgementState.CONFIRMED, null, null,
                FleetTestDoubles.driver("nobody-is-bound-to-this", "ACCRA"), SourceChannel.MOBILE)))
                .isInstanceOf(FleetAuthorizationException.class)
                .hasMessageContaining("not authorised");
    }

    @Test
    @DisplayName("a fleet officer cannot answer on a driver's behalf")
    void a_supervisor_cannot_acknowledge_for_a_driver() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));

        // Holds FLEET_TRIP_MANAGE and every other trip power, and still may not produce a record
        // saying the driver confirmed - which is the one fact a dispatcher relies on being true.
        assertThatThrownBy(() -> service.acknowledge(new AcknowledgeTripCommand(trip.id(),
                TripAcknowledgementState.CONFIRMED, null, null, FleetTestDoubles.fleetOfficer("ACCRA"),
                SourceChannel.WEB)))
                .isInstanceOf(FleetAuthorizationException.class);
    }

    @Test
    @DisplayName("reassignment to a different driver clears the previous driver's answer")
    void reassignment_resets_the_acknowledgement() {
        Trip trip = service.create(createCommand(vehicle.id(), driver.id(), "idem-1"));
        service.acknowledge(new AcknowledgeTripCommand(trip.id(), TripAcknowledgementState.CONFIRMED, null,
                null, boundDriverActor(), SourceChannel.MOBILE));

        DriverProfileReference replacement = drivers.save(anotherEligibleDriver());
        Trip reassigned = service.assign(new AssignTripCommand(trip.id(), vehicle.id(), replacement.id(),
                "Original driver unavailable", null, FleetTestDoubles.fleetOfficer("ACCRA"), SourceChannel.WEB));

        assertThat(reassigned.acknowledgement().state()).isEqualTo(TripAcknowledgementState.PENDING);
        assertThat(reassigned.acknowledgement().answeredBy()).isNull();
    }

    /** The driver fixture is bound to its own staff reference, which is what a header actor presents. */
    private ActorContext boundDriverActor() {
        return FleetTestDoubles.driver(driver.staffReference(), "ACCRA");
    }

    private void giveVehicleFullCompliance() {
        giveCompliance(vehicle.id());
    }

    private void giveCompliance(UUID vehicleId) {
        for (ComplianceDocumentType type : ComplianceDocumentType.values()) {
            if (type.isMandatory()) {
                complianceDocuments.save(FleetFixtures.complianceDocument(vehicleId, type,
                        TODAY.minusMonths(6), TODAY.plusYears(1)));
            }
        }
    }

    private void giveComplianceExcept(UUID vehicleId, ComplianceDocumentType excludedType) {
        for (ComplianceDocumentType type : ComplianceDocumentType.values()) {
            if (type.isMandatory() && type != excludedType) {
                complianceDocuments.save(FleetFixtures.complianceDocument(vehicleId, type,
                        TODAY.minusMonths(6), TODAY.plusYears(1)));
            }
        }
    }

    private static DriverProfileReference eligibleDriver() {
        return driverWithLicenceExpiring(TODAY.plusYears(1));
    }

    private static DriverProfileReference anotherEligibleDriver() {
        return DriverProfileReference.register(UUID.fromString("66666666-6666-6666-6666-666666666666"),
                "CLET/HR/00456", "Ama Owusu",
                new LicenceDetails("GHA-DL-1122334", LicenceClass.D, TODAY.plusYears(1)), TODAY.plusYears(1),
                ACCRA, "Transportation & Logistics Unit", "CLET/HR/00456", metadata());
    }

    private static DriverProfileReference driverWithLicenceExpiring(LocalDate expiry) {
        return DriverProfileReference.register(UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "CLET/HR/00123", "Kwame Mensah",
                new LicenceDetails("GHA-DL-4477201", LicenceClass.D, expiry), TODAY.plusYears(1), ACCRA,
                // Bound to its own staff reference, which is what the header-authenticated actors in
                // these tests present as their subject.
                "Transportation & Logistics Unit", "CLET/HR/00123", metadata());
    }
}

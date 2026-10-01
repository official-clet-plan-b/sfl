package gh.edu.clet.sfl.fleetlogistics.fleet.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.AcknowledgeTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.AssignTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CancelTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CloseTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.CreateTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.HoldTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.RecordInspectionCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.ReportTripLocationCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.command.StartTripCommand;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.AuditPort;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.DriverProfileRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.IdempotencyPort;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.IntegrationEventPublisher;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleInspectionRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.workflow.FleetWorkflowRaiser;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.event.FleetEventType;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.AssignmentConflictException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.FleetAuthorizationException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.OptimisticLockConflictException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.PreTripInspectionMissingException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.ReadinessBlockedException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.RecordNotFoundException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.AuditAction;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DateTimeRange;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverProfileReference;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.InspectionFinding;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OdometerSource;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.ReadinessAssessment;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.ReadinessBlockerCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.RecordMetadata;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripAcknowledgement;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripAcknowledgementState;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleInspection;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleServiceStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vehicle/driver assignment and trip workflow (SRS-SFL-S166-02).
 *
 * <p>Assignment is the operation with the most ways to go wrong, so it is guarded twice: the readiness
 * policy refuses an unfit vehicle or driver with named blockers, and the vehicle row is locked before
 * the overlap check so two dispatchers booking the same vehicle at the same moment serialise instead of
 * both passing. The database exclusion constraint is the third line of defence.
 */
@Service
public class TripApplicationService {

    private static final String RESOURCE_TYPE = "Trip";
    private static final String INSPECTION_RESOURCE_TYPE = "VehicleInspection";

    private final TripRepository trips;
    private final VehicleRepository vehicles;
    private final VehicleInspectionRepository inspections;
    private final DriverProfileRepository driverProfiles;
    private final FleetReadinessService readinessService;
    private final FleetWorkflowRaiser workflowRaiser;
    private final FleetAccessPolicy accessPolicy;
    private final AuditPort auditPort;
    private final IntegrationEventPublisher eventPublisher;
    private final IdempotencyPort idempotency;
    private final DriverScopeResolver driverScopes;
    private final VehicleLocationRepository locations;
    private final Clock clock;

    public TripApplicationService(TripRepository trips, VehicleRepository vehicles,
            VehicleInspectionRepository inspections, DriverProfileRepository driverProfiles,
            FleetReadinessService readinessService,
            FleetWorkflowRaiser workflowRaiser, FleetAccessPolicy accessPolicy, AuditPort auditPort,
            IntegrationEventPublisher eventPublisher, IdempotencyPort idempotency,
            DriverScopeResolver driverScopes, VehicleLocationRepository locations, Clock clock) {
        this.trips = trips;
        this.vehicles = vehicles;
        this.inspections = inspections;
        this.driverProfiles = driverProfiles;
        this.readinessService = readinessService;
        this.workflowRaiser = workflowRaiser;
        this.accessPolicy = accessPolicy;
        this.auditPort = auditPort;
        this.eventPublisher = eventPublisher;
        this.idempotency = idempotency;
        this.driverScopes = driverScopes;
        this.locations = locations;
        this.clock = clock;
    }

    /**
     * The owner reference {@link FleetAccessPolicy#requireRecordScope} compares against, resolved from
     * the trip's driver.
     *
     * <p>{@code Trip} carries {@code driverId}, a register key, and the record-scope rule is written in
     * terms of the actor's own id - so the two are joined here through the driver's
     * {@code staffReference}, which is the value the actor authenticates as. Fuel already relies on
     * that same equivalence when it refuses a driver a logbook opened for somebody else.
     *
     * <p>Returns {@code null} for an unresolvable driver, which {@code requireRecordScope} treats as
     * "no owner, no narrowing". That is the pre-existing behaviour for an unassigned trip and is not
     * widened here: a trip with no driver has no owner to be scoped to.
     */
    private String driverOwnerReference(UUID driverId) {
        return driverId == null ? null
                : driverProfiles.findById(driverId).map(DriverProfileReference::staffReference).orElse(null);
    }

    /** SRS-SFL-S166-02: create a trip, optionally assigning vehicle and driver immediately. */
    @Transactional
    public Trip create(CreateTripCommand command) {
        SiteCode site = SiteCode.of(command.siteCode());
        accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_MANAGE, site, RESOURCE_TYPE, null);

        String fingerprint = idempotency.fingerprint(command.idempotencyPayload());
        Optional<UUID> replayed = idempotency.findExistingResult("create-trip", command.idempotencyKey(),
                fingerprint);
        if (replayed.isPresent()) {
            return trips.findById(replayed.get())
                    .orElseThrow(() -> RecordNotFoundException.of(RESOURCE_TYPE, replayed.get()));
        }

        Instant now = clock.instant();
        DateTimeRange period = DateTimeRange.of(command.plannedStart(), command.plannedEnd());
        UUID tripId = UUID.randomUUID();
        RecordMetadata metadata = RecordMetadata.createdBy(command.actor().actorId(), now, command.sourceChannel(),
                command.actor().correlationId());

        Trip trip = Trip.plan(tripId, tripNumber(tripId), site, command.purpose(), command.origin(),
                command.destination(), command.operatingMode(), period, metadata);

        if (command.vehicleId() != null && command.driverId() != null) {
            accessPolicy.requirePermission(command.actor(), SflPermission.FLEET_TRIP_ASSIGN, RESOURCE_TYPE);
            Vehicle vehicle = lockVehicle(command.vehicleId());
            requireReady(readinessService.assessForAssignment(vehicle, command.driverId(), period,
                    command.operatingMode(), site, tripId, false));
            trip = trip.assign(command.vehicleId(), command.driverId(), metadata);
            vehicles.save(vehicle.assignToTrip(tripId, vehicle.metadata().modifiedBy(command.actor().actorId(),
                    now, command.sourceChannel(), command.actor().correlationId())));
        }

        Trip saved = trips.save(trip);
        auditPort.record(command.actor(), command.sourceChannel(), site, AuditAction.CREATE, RESOURCE_TYPE,
                saved.id().toString(), null, auditImage(saved));
        if (saved.status() == TripStatus.ASSIGNED) {
            publishAssigned(command.actor(), saved);
        }
        idempotency.recordResult("create-trip", command.idempotencyKey(), fingerprint, saved.id(), site.value(),
                command.actor().actorId());
        return saved;
    }

    /** SRS-SFL-S166-02: assign or reassign the vehicle and driver. */
    @Transactional
    public Trip assign(AssignTripCommand command) {
        Trip existing = requireTrip(command.tripId());
        accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_ASSIGN, existing.siteCode(), RESOURCE_TYPE,
                existing.id().toString());
        requireExpectedVersion(existing, command.expectedVersion());

        boolean isReassignment = existing.vehicleId() != null;
        if (isReassignment && (command.reason() == null || command.reason().isBlank())) {
            throw new IllegalArgumentException("A reason is required when reassigning a trip");
        }

        Instant now = clock.instant();
        RecordMetadata metadata = existing.metadata().modifiedBy(command.actor().actorId(), now,
                command.sourceChannel(), command.actor().correlationId());

        Vehicle vehicle = lockVehicle(command.vehicleId());
        requireReady(readinessService.assessForAssignment(vehicle, command.driverId(), existing.plannedPeriod(),
                existing.operatingMode(), existing.siteCode(), existing.id(), false));

        // Release the previously held vehicle before taking the new one, so a reassignment cannot leave
        // two vehicles both believing they are on this trip.
        if (isReassignment && !command.vehicleId().equals(existing.vehicleId())) {
            vehicles.findById(existing.vehicleId()).ifPresent(previous ->
                    vehicles.save(previous.releaseFromTrip(previous.metadata().modifiedBy(
                            command.actor().actorId(), now, command.sourceChannel(),
                            command.actor().correlationId()))));
        }

        Trip assigned = trips.save(existing.assign(command.vehicleId(), command.driverId(), metadata));
        vehicles.save(vehicle.assignToTrip(assigned.id(), vehicle.metadata().modifiedBy(command.actor().actorId(),
                now, command.sourceChannel(), command.actor().correlationId())));

        Map<String, Object> after = new LinkedHashMap<>(auditImage(assigned));
        after.put("reason", command.reason());
        auditPort.record(command.actor(), command.sourceChannel(), assigned.siteCode(),
                isReassignment ? AuditAction.REASSIGN : AuditAction.ASSIGN, RESOURCE_TYPE,
                assigned.id().toString(), auditImage(existing), after);

        if (isReassignment) {
            eventPublisher.publish(FleetEventType.TRIP_REASSIGNED, RESOURCE_TYPE, assigned.id().toString(),
                    assigned.siteCode(), command.actor(), Map.of(
                            "tripId", assigned.id().toString(),
                            "previousVehicleId", String.valueOf(existing.vehicleId()),
                            "previousDriverId", String.valueOf(existing.driverId()),
                            "vehicleId", String.valueOf(assigned.vehicleId()),
                            "driverId", String.valueOf(assigned.driverId()),
                            "reason", String.valueOf(command.reason())));
        } else {
            publishAssigned(command.actor(), assigned);
        }
        return assigned;
    }

    /**
     * SRS-SFL-S166-02: start an assigned trip, gated on a valid pre-trip inspection.
     *
     * <h2>Two ways to be allowed, and they are not the same permission</h2>
     *
     * <p>A dispatcher holds {@link SflPermission#FLEET_TRIP_MANAGE} and may start any trip at a site
     * they are scoped to. A driver holds {@link SflPermission#FLEET_TRIP_START_OWN} and may start
     * exactly one: the trip they are assigned to. See {@link #close(CloseTripCommand)} for why the
     * binding is checked as well as the permission, and why the supervising permission is tried first.
     */
    @Transactional
    public Trip start(StartTripCommand command) {
        Trip existing = requireTrip(command.tripId());
        if (accessPolicy.has(command.actor(), SflPermission.FLEET_TRIP_MANAGE)) {
            accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_MANAGE, existing.siteCode(),
                    RESOURCE_TYPE, existing.id().toString());
        } else {
            accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_START_OWN, existing.siteCode(),
                    RESOURCE_TYPE, existing.id().toString());
            requireOwnAssignment(existing, command.actor(), SflPermission.FLEET_TRIP_START_OWN,
                    "Only the driver assigned to this trip can start it");
        }
        requireExpectedVersion(existing, command.expectedVersion());

        Vehicle vehicle = lockVehicle(existing.vehicleId());
        // inspectionRequired = true: this is the moment the pre-trip check has to exist and pass.
        requireReady(readinessService.assessForAssignment(vehicle, existing.driverId(), existing.plannedPeriod(),
                existing.operatingMode(), existing.siteCode(), existing.id(), true));

        Instant now = clock.instant();
        RecordMetadata metadata = existing.metadata().modifiedBy(command.actor().actorId(), now,
                command.sourceChannel(), command.actor().correlationId());

        Trip started = trips.save(existing.start(now, command.startOdometer(), metadata));
        vehicles.save(vehicle
                .recordOdometer(command.startOdometer(), OdometerSource.MANUAL_ENTRY, now,
                        vehicle.metadata().modifiedBy(command.actor().actorId(), now, command.sourceChannel(),
                                command.actor().correlationId()))
                .markInUse(vehicle.metadata().modifiedBy(command.actor().actorId(), now, command.sourceChannel(),
                        command.actor().correlationId())));

        auditPort.record(command.actor(), command.sourceChannel(), started.siteCode(),
                AuditAction.STATE_TRANSITION, RESOURCE_TYPE, started.id().toString(), auditImage(existing),
                auditImage(started));
        return started;
    }

    /**
     * The assigned driver reports where the vehicle is right now, while the trip is in progress.
     *
     * <h2>Not an audited state change, on purpose</h2>
     *
     * <p>Every other write in this class moves the trip through its state machine and records a
     * before/after audit entry. This does neither: the trip's own status is untouched, and a position
     * report every few seconds would turn the audit chain - which exists to answer "who changed what,
     * and when" - into a GPS log nobody reading it for that purpose wants to wade through. The
     * telemetry itself is still kept, in {@link VehicleLocationSnapshot}, which is where a reading
     * belongs and where the vendor-fed readings already live.
     *
     * <h2>Why {@code IN_PROGRESS} is enforced here and not just assumed</h2>
     *
     * <p>A stale tab left open after a trip closes, or opened before it starts, must not be able to
     * keep writing positions against a vehicle that is no longer - or not yet - that driver's to
     * report on. {@link TripStatus#IN_PROGRESS} is the one status for which "the driver is at the
     * wheel, right now" is actually true.
     *
     * <p>Shares {@link #requireOwnAssignment} with {@link #start} and {@link #close}: the permission
     * says a driver may report their own trip's position; the binding check says which trip is theirs.
     */
    @Transactional
    public VehicleLocationSnapshot reportOwnLocation(ReportTripLocationCommand command) {
        Trip existing = requireTrip(command.tripId());
        accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_LOCATION_REPORT_OWN, existing.siteCode(),
                RESOURCE_TYPE, existing.id().toString());
        requireOwnAssignment(existing, command.actor(), SflPermission.FLEET_TRIP_LOCATION_REPORT_OWN,
                "Only the driver assigned to this trip can report its position");
        if (existing.status() != TripStatus.IN_PROGRESS) {
            throw InvalidStateTransitionException.of(RESOURCE_TYPE, existing.status(), "location report");
        }

        VehicleLocationSnapshot snapshot = new VehicleLocationSnapshot(UUID.randomUUID(), existing.vehicleId(),
                existing.siteCode(), command.latitude(), command.longitude(), null, clock.instant(),
                "DRIVER_MOBILE", UUID.randomUUID(), command.actor().correlationId());
        return locations.save(snapshot);
    }

    /**
     * SRS-SFL-S166-02: the assigned driver confirms the trip, or defers it with a reason.
     *
     * <h2>The record check is the whole point</h2>
     *
     * <p>{@code FLEET_TRIP_ACKNOWLEDGE} is held by every driver, so the permission alone would let any
     * driver confirm or defer any trip in their site - which is worse than not having the feature: a
     * dispatcher would be reading confirmations from people who are not driving. The permission says
     * "you may answer for a trip"; {@link DriverScopeResolver} says <em>which</em> trip, and both have
     * to pass.
     *
     * <p>A supervising actor is refused too, and deliberately. {@link DriverScope.Everything} means "no
     * narrowing on reads", not "may answer on a driver's behalf" - a fleet manager confirming a trip
     * for a driver produces a record that says the driver confirmed it, which is exactly the thing the
     * dispatcher is relying on being true. If answering on behalf is ever needed it wants its own
     * permission and its own wording in the audit trail.
     */
    @Transactional
    public Trip acknowledge(AcknowledgeTripCommand command) {
        Trip existing = requireTrip(command.tripId());
        accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_ACKNOWLEDGE, existing.siteCode(),
                RESOURCE_TYPE, existing.id().toString());
        requireOwnAssignment(existing, command.actor());
        requireExpectedVersion(existing, command.expectedVersion());

        Instant now = clock.instant();
        RecordMetadata metadata = existing.metadata().modifiedBy(command.actor().actorId(), now,
                command.sourceChannel(), command.actor().correlationId());

        TripAcknowledgement answer = command.answer() == TripAcknowledgementState.DEFERRED
                ? TripAcknowledgement.deferredBy(command.actor().actorId(), command.reason(), now)
                : TripAcknowledgement.confirmedBy(command.actor().actorId(), now);

        Trip acknowledged = trips.save(existing.acknowledge(answer, metadata));

        auditPort.record(command.actor(), command.sourceChannel(), acknowledged.siteCode(),
                AuditAction.ACKNOWLEDGE, RESOURCE_TYPE, acknowledged.id().toString(), auditImage(existing),
                auditImage(acknowledged));

        FleetEventType eventType = command.answer() == TripAcknowledgementState.DEFERRED
                ? FleetEventType.TRIP_DEFERRED
                : FleetEventType.TRIP_ACKNOWLEDGED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", acknowledged.id().toString());
        payload.put("tripNumber", acknowledged.tripNumber());
        payload.put("driverId", String.valueOf(acknowledged.driverId()));
        payload.put("answer", acknowledged.acknowledgement().state().name());
        payload.put("answeredBy", command.actor().actorId());
        if (acknowledged.acknowledgement().reason() != null) {
            payload.put("reason", acknowledged.acknowledgement().reason());
        }
        eventPublisher.publish(eventType, RESOURCE_TYPE, acknowledged.id().toString(), acknowledged.siteCode(),
                command.actor(), payload);
        return acknowledged;
    }

    /**
     * Refuses an actor a trip that is not assigned to them.
     *
     * <p>Resolved through the {@code principal_subject} binding rather than by comparing the driver's
     * staff reference against the token subject - the comparison that could never be true once
     * authentication was switched on. See {@link DriverScopeResolver}.
     */
    private void requireOwnAssignment(Trip trip, ActorContext actor) {
        requireOwnAssignment(trip, actor, SflPermission.FLEET_TRIP_ACKNOWLEDGE,
                "Only the driver assigned to this trip can confirm or defer it");
    }

    /**
     * Refuses unless the actor is the driver bound to this trip.
     *
     * <p>Shared by acknowledgement and by a driver's own start and closure. The permission and the
     * refusal wording differ between them - one is about answering for a trip, the others about
     * starting or finishing it - so both are passed in rather than the message being written three
     * times and drifting.
     */
    private void requireOwnAssignment(Trip trip, ActorContext actor, SflPermission permission, String refusal) {
        /*
          Resolved as always-narrowed, deliberately. The permission-driven overload would ask "does this
          actor hold a supervising permission?" - and there is no supervising permission for answering
          on somebody's behalf, because nobody may. Passing FLEET_TRIP_ACKNOWLEDGE there would be worse
          than useless: every driver holds it, so every driver would resolve as a supervisor and be
          waved through with no binding at all, which is the exact opposite of the rule. The same is
          true of FLEET_TRIP_START_OWN and FLEET_TRIP_CLOSE_OWN.
        */
        UUID boundDriverId = driverScopes.resolve(actor, true) instanceof DriverScope.Own own
                ? own.driverId()
                : null;
        if (boundDriverId != null && boundDriverId.equals(trip.driverId())) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requiredPermission", permission.name());
        details.put("siteCode", trip.siteCode().value());
        details.put("resourceType", RESOURCE_TYPE);
        details.put("resourceId", trip.id().toString());
        details.put("reason", boundDriverId == null
                ? "Your sign-in is not linked to a driver profile, so you cannot answer for a trip"
                : refusal);
        throw new FleetAuthorizationException(details);
    }

    /** SRS-SFL-S166-02: hold or resume. */
    @Transactional
    public Trip holdOrResume(HoldTripCommand command) {
        Trip existing = requireTrip(command.tripId());
        accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_MANAGE, existing.siteCode(), RESOURCE_TYPE,
                existing.id().toString());
        requireExpectedVersion(existing, command.expectedVersion());

        Instant now = clock.instant();
        RecordMetadata metadata = existing.metadata().modifiedBy(command.actor().actorId(), now,
                command.sourceChannel(), command.actor().correlationId());

        Trip updated = command.action() == HoldTripCommand.HoldAction.HOLD
                ? existing.hold(command.reason(), metadata)
                : existing.resume(metadata);
        Trip saved = trips.save(updated);

        auditPort.record(command.actor(), command.sourceChannel(), saved.siteCode(),
                command.action() == HoldTripCommand.HoldAction.HOLD ? AuditAction.HOLD : AuditAction.RESUME,
                RESOURCE_TYPE, saved.id().toString(), auditImage(existing), auditImage(saved));
        return saved;
    }

    /**
     * SRS-SFL-S166-02: cancel. Privileged, and the reason is mandatory.
     *
     * <p>Deliberately one path, not two: unlike {@link #start(StartTripCommand)} and
     * {@link #close(CloseTripCommand)}, there is no driver-scoped "own" variant here. A driver who
     * cannot take an assigned trip defers it ({@link #acknowledge(AcknowledgeTripCommand)}); calling
     * the trip off entirely is the fleet office's decision, made with a view of the wider schedule a
     * driver does not have.
     */
    @Transactional
    public Trip cancel(CancelTripCommand command) {
        Trip existing = requireTrip(command.tripId());
        accessPolicy.requirePrivilegedTransition(command.actor(), SflPermission.FLEET_TRIP_CANCEL,
                existing.siteCode(), RESOURCE_TYPE, existing.id().toString());
        requireExpectedVersion(existing, command.expectedVersion());

        Instant now = clock.instant();
        RecordMetadata metadata = existing.metadata().modifiedBy(command.actor().actorId(), now,
                command.sourceChannel(), command.actor().correlationId());

        Trip cancelled = trips.save(existing.cancel(command.reason(), now, metadata));
        releaseVehicle(existing, command.actor(), command.sourceChannel(), now);

        auditPort.record(command.actor(), command.sourceChannel(), cancelled.siteCode(), AuditAction.CANCEL,
                RESOURCE_TYPE, cancelled.id().toString(), auditImage(existing), auditImage(cancelled));
        eventPublisher.publish(FleetEventType.TRIP_CANCELLED, RESOURCE_TYPE, cancelled.id().toString(),
                cancelled.siteCode(), command.actor(), Map.of(
                        "tripId", cancelled.id().toString(),
                        "reason", String.valueOf(command.reason()),
                        "cancelledBy", command.actor().actorId()));
        return cancelled;
    }

    /**
     * SRS-SFL-S166-02: close with the required reason, evidence and end odometer.
     *
     * <h2>Two ways to be allowed, and they are not the same permission</h2>
     *
     * <p>A dispatcher holds {@link SflPermission#FLEET_TRIP_CLOSE} and may close any trip at a site
     * they are scoped to. A driver holds {@link SflPermission#FLEET_TRIP_CLOSE_OWN} and may close
     * exactly one: the trip they are assigned to.
     *
     * <p>The driver route needs the binding checked as well as the permission, for the reason
     * {@link #requireOwnAssignment} sets out - every driver holds the permission, so a permission
     * check on its own would let any of them close anybody's journey. The order matters too: the
     * supervising permission is tried first, so a dispatcher who is also a driver is not refused for
     * closing a trip that is not personally theirs.
     */
    @Transactional
    public Trip close(CloseTripCommand command) {
        Trip existing = requireTrip(command.tripId());
        if (accessPolicy.has(command.actor(), SflPermission.FLEET_TRIP_CLOSE)) {
            accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_CLOSE, existing.siteCode(), RESOURCE_TYPE,
                    existing.id().toString());
        } else {
            accessPolicy.require(command.actor(), SflPermission.FLEET_TRIP_CLOSE_OWN, existing.siteCode(),
                    RESOURCE_TYPE, existing.id().toString());
            requireOwnAssignment(existing, command.actor(), SflPermission.FLEET_TRIP_CLOSE_OWN,
                    "Only the driver assigned to this trip can complete it");
        }
        requireExpectedVersion(existing, command.expectedVersion());

        Instant now = clock.instant();
        RecordMetadata metadata = existing.metadata().modifiedBy(command.actor().actorId(), now,
                command.sourceChannel(), command.actor().correlationId());

        Trip closed = trips.save(existing.close(command.closureReason(), command.closureEvidenceId(),
                command.endOdometer(), now, metadata));

        vehicles.findById(existing.vehicleId()).ifPresent(vehicle -> vehicles.save(vehicle
                .recordOdometer(command.endOdometer(), OdometerSource.MANUAL_ENTRY, now,
                        vehicle.metadata().modifiedBy(command.actor().actorId(), now, command.sourceChannel(),
                                command.actor().correlationId()))
                .releaseFromTrip(vehicle.metadata().modifiedBy(command.actor().actorId(), now,
                        command.sourceChannel(), command.actor().correlationId()))));

        auditPort.record(command.actor(), command.sourceChannel(), closed.siteCode(), AuditAction.CLOSE,
                RESOURCE_TYPE, closed.id().toString(), auditImage(existing), auditImage(closed));
        eventPublisher.publish(FleetEventType.TRIP_COMPLETED, RESOURCE_TYPE, closed.id().toString(),
                closed.siteCode(), command.actor(), Map.of(
                        "tripId", closed.id().toString(),
                        "endOdometer", command.endOdometer(),
                        "distanceCovered", String.valueOf(closed.distanceCovered()),
                        "closureReason", String.valueOf(closed.closureReason()),
                        "closureEvidenceId", String.valueOf(closed.closureEvidenceId())));
        return closed;
    }

    /**
     * Records an inspection against a trip.
     *
     * <p>A critical defect does three things at once: it fails the inspection, takes the vehicle out of
     * service, and opens a defect workflow item so somebody owns the rectification.
     */
    @Transactional
    public VehicleInspection recordInspection(RecordInspectionCommand command) {
        Trip trip = command.tripId() == null ? null : requireTrip(command.tripId());
        UUID vehicleId = trip != null ? trip.vehicleId() : command.vehicleId();
        if (vehicleId == null) {
            throw new IllegalArgumentException("An inspection needs either a trip with an assigned vehicle "
                    + "or an explicit vehicleId");
        }
        Vehicle vehicle = vehicles.findById(vehicleId)
                .orElseThrow(() -> RecordNotFoundException.of("Vehicle", vehicleId));

        accessPolicy.require(command.actor(), SflPermission.FLEET_INSPECTION_RECORD, vehicle.siteCode(),
                INSPECTION_RESOURCE_TYPE, vehicleId.toString());
        // A driver may only inspect the vehicle on their own trip. The owner reference used to be
        // passed as null, which `requireRecordScope` returns on immediately - so the rule this comment
        // describes has never once been enforced, at the only call site the policy has.
        if (trip != null && trip.driverId() != null) {
            accessPolicy.requireRecordScope(command.actor(), driverOwnerReference(trip.driverId()),
                    SflPermission.FLEET_TRIP_MANAGE, RESOURCE_TYPE, trip.id().toString());
        }

        String fingerprint = idempotency.fingerprint(command.idempotencyPayload());
        Optional<UUID> replayed = idempotency.findExistingResult("record-inspection", command.idempotencyKey(),
                fingerprint);
        if (replayed.isPresent()) {
            return inspections.findById(replayed.get())
                    .orElseThrow(() -> RecordNotFoundException.of(INSPECTION_RESOURCE_TYPE, replayed.get()));
        }

        Instant now = clock.instant();
        RecordMetadata metadata = RecordMetadata.createdBy(command.actor().actorId(), now, command.sourceChannel(),
                command.actor().correlationId());

        List<InspectionFinding> findings = command.findings() == null
                ? List.of()
                : command.findings().stream()
                        .map(finding -> InspectionFinding.of(finding.checkCode(), finding.description(),
                                finding.severity()))
                        .toList();

        VehicleInspection inspection = inspections.save(VehicleInspection.record(UUID.randomUUID(), vehicleId,
                trip == null ? null : trip.id(), vehicle.siteCode(), command.inspectionType(),
                command.actor().actorId(), now, command.odometerReading(), command.evidenceId(), findings,
                command.notes(), metadata));

        Vehicle updatedVehicle = vehicle.recordOdometer(command.odometerReading(), OdometerSource.INSPECTION, now,
                vehicle.metadata().modifiedBy(command.actor().actorId(), now, command.sourceChannel(),
                        command.actor().correlationId()));
        if (!inspection.permitsUse()) {
            updatedVehicle = updatedVehicle.withServiceStatus(VehicleServiceStatus.OUT_OF_SERVICE,
                    updatedVehicle.metadata());
        }
        vehicles.save(updatedVehicle);

        auditPort.record(command.actor(), command.sourceChannel(), vehicle.siteCode(),
                AuditAction.INSPECTION_RECORDED, INSPECTION_RESOURCE_TYPE, inspection.id().toString(), null,
                inspectionImage(inspection));

        if (!inspection.permitsUse()) {
            eventPublisher.publish(FleetEventType.VEHICLE_INSPECTION_FAILED, INSPECTION_RESOURCE_TYPE,
                    inspection.id().toString(), vehicle.siteCode(), command.actor(), Map.of(
                            "inspectionId", inspection.id().toString(),
                            "vehicleId", vehicleId.toString(),
                            "tripId", trip == null ? "" : trip.id().toString(),
                            "result", inspection.result().name(),
                            "defectCodes", inspection.findings().stream()
                                    .map(InspectionFinding::checkCode).toList()));
            workflowRaiser.raiseInspectionDefect(inspection, updatedVehicle, command.actor(),
                    command.sourceChannel());
        }
        idempotency.recordResult("record-inspection", command.idempotencyKey(), fingerprint, inspection.id(),
                vehicle.siteCode().value(), command.actor().actorId());
        return inspection;
    }

    // --- helpers ------------------------------------------------------------------------

    private Trip requireTrip(UUID tripId) {
        return trips.findById(tripId).orElseThrow(() -> RecordNotFoundException.of(RESOURCE_TYPE, tripId));
    }

    /**
     * Loads the vehicle under a row lock.
     *
     * <p>This is what makes concurrent assignment safe: two dispatchers booking the same vehicle
     * serialise here, so the second one sees the first one's trip when it runs the overlap check.
     */
    private Vehicle lockVehicle(UUID vehicleId) {
        if (vehicleId == null) {
            throw new IllegalArgumentException("vehicleId is required");
        }
        return vehicles.findByIdForUpdate(vehicleId)
                .orElseThrow(() -> RecordNotFoundException.of("Vehicle", vehicleId));
    }

    /**
     * Refuses an assignment the readiness policy blocked.
     *
     * <p>Assignment conflicts are reported as a conflict rather than a validation failure, because the
     * caller's request was well formed - the world simply changed underneath it.
     */
    private static void requireReady(ReadinessAssessment assessment) {
        if (assessment.permitsAssignment()) {
            return;
        }
        List<ReadinessBlockerCode> blocking = assessment.blockingCodes();
        Map<String, Object> details = Map.of(
                "vehicleId", String.valueOf(assessment.vehicleId()),
                "driverId", String.valueOf(assessment.driverId()),
                "blockerCodes", blocking.stream().map(Enum::name).toList(),
                "blockers", assessment.blockers().stream()
                        .map(blocker -> blocker.code().name() + ": " + blocker.message())
                        .toList());

        if (blocking.contains(ReadinessBlockerCode.VEHICLE_ASSIGNMENT_CONFLICT)
                || blocking.contains(ReadinessBlockerCode.DRIVER_ASSIGNMENT_CONFLICT)) {
            throw new AssignmentConflictException(details);
        }
        if (blocking.contains(ReadinessBlockerCode.DRIVER_INELIGIBLE)) {
            throw new gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.DriverIneligibleException(details);
        }
        // Distinct from the vehicle's own readiness: this is a fact about the trip (no pre-trip
        // inspection recorded), and only `start()` ever asks for it (inspectionRequired = true) - see
        // its call to assessForAssignment. Reporting it as FLEET_READINESS_BLOCKED sent an operator
        // to fix the vehicle's compliance documents when the vehicle was never the problem.
        if (blocking.contains(ReadinessBlockerCode.MANDATORY_INSPECTION_MISSING)) {
            throw new PreTripInspectionMissingException(details);
        }
        throw new ReadinessBlockedException(details);
    }

    private void releaseVehicle(Trip trip, ActorContext actor, gh.edu.clet.sfl.fleetlogistics.fleet.domain.model
            .SourceChannel channel, Instant now) {
        if (trip.vehicleId() == null) {
            return;
        }
        vehicles.findById(trip.vehicleId()).ifPresent(vehicle -> vehicles.save(vehicle.releaseFromTrip(
                vehicle.metadata().modifiedBy(actor.actorId(), now, channel, actor.correlationId()))));
    }

    private void publishAssigned(ActorContext actor, Trip trip) {
        eventPublisher.publish(FleetEventType.VEHICLE_ASSIGNED, RESOURCE_TYPE, trip.id().toString(),
                trip.siteCode(), actor, Map.of(
                        "tripId", trip.id().toString(),
                        "tripNumber", trip.tripNumber(),
                        "vehicleId", String.valueOf(trip.vehicleId()),
                        "driverId", String.valueOf(trip.driverId()),
                        "plannedStart", trip.plannedPeriod().start().toString(),
                        "plannedEnd", trip.plannedPeriod().end().toString(),
                        "operatingMode", trip.operatingMode().name()));
    }

    private static void requireExpectedVersion(Trip trip, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != trip.metadata().version()) {
            throw new OptimisticLockConflictException(Map.of(
                    "expectedVersion", expectedVersion,
                    "currentVersion", trip.metadata().version()));
        }
    }

    private static String tripNumber(UUID id) {
        return "TRP-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    static Map<String, Object> auditImage(Trip trip) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("tripId", trip.id().toString());
        image.put("tripNumber", trip.tripNumber());
        image.put("vehicleId", trip.vehicleId() == null ? null : trip.vehicleId().toString());
        image.put("driverId", trip.driverId() == null ? null : trip.driverId().toString());
        image.put("siteCode", trip.siteCode().value());
        image.put("operatingMode", trip.operatingMode().name());
        image.put("plannedStart", trip.plannedPeriod().start().toString());
        image.put("plannedEnd", trip.plannedPeriod().end().toString());
        image.put("status", trip.status().name());
        image.put("holdReason", trip.holdReason());
        image.put("cancellationReason", trip.cancellationReason());
        image.put("closureReason", trip.closureReason());
        image.put("closureEvidenceId", trip.closureEvidenceId() == null
                ? null
                : trip.closureEvidenceId().toString());
        image.put("startOdometer", trip.startOdometer());
        image.put("endOdometer", trip.endOdometer());
        image.put("version", trip.metadata().version());
        return image;
    }

    static Map<String, Object> inspectionImage(VehicleInspection inspection) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("inspectionId", inspection.id().toString());
        image.put("vehicleId", inspection.vehicleId().toString());
        image.put("tripId", inspection.tripId() == null ? null : inspection.tripId().toString());
        image.put("inspectionType", inspection.inspectionType().name());
        image.put("result", inspection.result().name());
        image.put("odometerReading", inspection.odometerReading());
        image.put("evidenceId", inspection.evidenceId() == null ? null : inspection.evidenceId().toString());
        image.put("findings", inspection.findings().stream()
                .map(finding -> finding.checkCode() + "/" + finding.severity())
                .toList());
        return image;
    }

    /** Operating modes a client may request; exposed for the dashboard's assignment form. */
    public static List<OperatingMode> supportedOperatingModes() {
        return List.of(OperatingMode.values());
    }
}

package gh.edu.clet.sfl.fleetlogistics.fuel.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.DriverProfileRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.exception.RecordNotFoundException;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverEligibilityStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DriverLifecycleStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OdometerSource;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SourceChannel;
import gh.edu.clet.sfl.fleetlogistics.fuel.application.port.FuelFleetReferencePort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FleetReferenceAdapter implements FuelFleetReferencePort {
    private final VehicleRepository vehicles;
    private final DriverProfileRepository drivers;
    private final TripRepository trips;

    public FleetReferenceAdapter(VehicleRepository v, DriverProfileRepository d, TripRepository t) {
        vehicles = v;
        drivers = d;
        trips = t;
    }

    @Override
    public Snapshot resolve(UUID vehicleId, UUID driverId, UUID tripId, String site) {
        var v =
                vehicles.findById(vehicleId)
                        .orElseThrow(() -> RecordNotFoundException.of("Vehicle", vehicleId));
        var d =
                drivers.findById(driverId)
                        .orElseThrow(() -> RecordNotFoundException.of("Driver", driverId));
        if (!v.siteCode().value().equals(site) || !d.siteCode().value().equals(site))
            throw RecordNotFoundException.of("Fleet reference", site);
        boolean match =
                tripId == null
                        || trips.findById(tripId)
                                .map(
                                        t ->
                                                vehicleId.equals(t.vehicleId())
                                                        && driverId.equals(t.driverId())
                                                        && site.equals(t.siteCode().value()))
                                .orElse(false);
        return new Snapshot(
                v.odometer().value(),
                v.lifecycleStatus().name(),
                v.availabilityStatus().name(),
                // A driver who is not active cannot drive for CLET, whatever the stored status
                // says.
                d.lifecycleStatus() == DriverLifecycleStatus.ACTIVE
                        ? d.eligibilityStatus().name()
                        : DriverEligibilityStatus.INELIGIBLE.name(),
                match,
                d.staffReference());
    }

    @Override
    @Transactional
    public void acceptOdometer(
            UUID vehicleId, long reading, Instant at, ActorContext actor, SourceChannel channel) {
        var v =
                vehicles.findByIdForUpdate(vehicleId)
                        .orElseThrow(() -> RecordNotFoundException.of("Vehicle", vehicleId));
        if (reading <= v.odometer().value()) return;
        vehicles.save(
                v.recordOdometer(
                        reading,
                        OdometerSource.FUEL_TRANSACTION,
                        at,
                        v.metadata()
                                .modifiedBy(
                                        actor.actorId(),
                                        Instant.now(),
                                        channel,
                                        actor.correlationId())));
    }
}

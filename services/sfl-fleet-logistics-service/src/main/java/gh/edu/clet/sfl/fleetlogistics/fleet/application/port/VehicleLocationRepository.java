package gh.edu.clet.sfl.fleetlogistics.fleet.application.port;

import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Vehicle movement projection persistence port (SRS-SFL-S166-04/05). */
public interface VehicleLocationRepository {

    VehicleLocationSnapshot save(VehicleLocationSnapshot snapshot);

    Optional<VehicleLocationSnapshot> findLatestByVehicle(UUID vehicleId);
    /**
     * One vehicle's movement history, newest first.
     *
     * <p>Closes gap 3. The snapshots have been written on every telematics callback since the
     * service was built and only the latest one was ever readable, so a vehicle detail screen
     * could show where a vehicle is and never where it had been.
     */
    List<VehicleLocationSnapshot> findByVehicle(UUID vehicleId, int limit);

    /**
     * The newest report for each of the given vehicles, in one lookup. A vehicle that has never
     * reported has no entry.
     *
     * <p>The default asks one vehicle at a time so a simple implementation stays correct; the JPA
     * adapter overrides it with a single query, which is the point of the method.
     */
    default java.util.Map<UUID, VehicleLocationSnapshot> findLatestByVehicles(
            java.util.Collection<UUID> vehicleIds) {
        java.util.Map<UUID, VehicleLocationSnapshot> latest = new java.util.HashMap<>();
        for (UUID vehicleId : vehicleIds) {
            findLatestByVehicle(vehicleId).ifPresent(snapshot -> latest.put(vehicleId, snapshot));
        }
        return latest;
    }

    List<VehicleLocationSnapshot> findRecentInScope(SiteScopeFilter scope, int limit);

    /**
     * Reports for these vehicles recorded at or after {@code since}, newest first, at most
     * {@code maxRows} in total - the movement trails on the live fleet map.
     */
    default List<VehicleLocationSnapshot> findSinceByVehicles(java.util.Collection<UUID> vehicleIds,
            java.time.Instant since, int maxRows) {
        return vehicleIds.stream()
                .flatMap(vehicleId -> findByVehicle(vehicleId, maxRows).stream())
                .filter(snapshot -> !snapshot.recordedAt().isBefore(since))
                .sorted(java.util.Comparator.comparing(VehicleLocationSnapshot::recordedAt).reversed())
                .limit(maxRows)
                .toList();
    }
}

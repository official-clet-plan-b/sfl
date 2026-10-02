package gh.edu.clet.sfl.fleetlogistics.fleet.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for vendor-neutral vehicle movement snapshots. */
@Component
class JpaVehicleLocationRepositoryAdapter implements VehicleLocationRepository {

    private final VehicleLocationSnapshotJpaRepository locations;

    JpaVehicleLocationRepositoryAdapter(VehicleLocationSnapshotJpaRepository locations) {
        this.locations = locations;
    }

    @Override
    @Transactional
    public VehicleLocationSnapshot save(VehicleLocationSnapshot snapshot) {
        return locations.save(VehicleLocationSnapshotEntity.from(snapshot)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleLocationSnapshot> findLatestByVehicle(UUID vehicleId) {
        return locations.findFirstByVehicleIdOrderByRecordedAtDescIdDesc(vehicleId)
                .map(VehicleLocationSnapshotEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VehicleLocationSnapshot> findByVehicle(UUID vehicleId, int limit) {
        return locations
                .findByVehicleIdOrderByRecordedAtDescIdDesc(vehicleId,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream()
                .map(VehicleLocationSnapshotEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, VehicleLocationSnapshot> findLatestByVehicles(
            java.util.Collection<UUID> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return java.util.Map.of();
        }
        // Two reports recorded in the same instant tie on time; the larger id wins, the same
        // tiebreak the single-vehicle lookup uses, so the answer cannot flip between requests.
        return locations.findLatestForVehicles(vehicleIds).stream()
                .map(VehicleLocationSnapshotEntity::toDomain)
                .collect(java.util.stream.Collectors.toMap(VehicleLocationSnapshot::vehicleId,
                        snapshot -> snapshot,
                        (first, second) -> first.id().compareTo(second.id()) >= 0 ? first : second));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VehicleLocationSnapshot> findRecentInScope(SiteScopeFilter scope, int limit) {
        return locations.findRecentInScope(scope.allSites(), scope.allSites() ? List.of("*") : List.copyOf(scope.sites()),
                        PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream()
                .map(VehicleLocationSnapshotEntity::toDomain)
                .toList();
    }
}

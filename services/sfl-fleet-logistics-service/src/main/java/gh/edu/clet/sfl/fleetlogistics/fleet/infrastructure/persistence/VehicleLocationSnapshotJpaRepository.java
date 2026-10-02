package gh.edu.clet.sfl.fleetlogistics.fleet.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VehicleLocationSnapshotJpaRepository extends JpaRepository<VehicleLocationSnapshotEntity, UUID> {

    Optional<VehicleLocationSnapshotEntity> findFirstByVehicleIdOrderByRecordedAtDescIdDesc(UUID vehicleId);

    // Ordered by id as well as time so two snapshots recorded in the same instant cannot swap
    // places between requests - the same tiebreak rule the paged collections use.
    List<VehicleLocationSnapshotEntity> findByVehicleIdOrderByRecordedAtDescIdDesc(UUID vehicleId,
            Pageable pageable);

    @Query("""
            select location from VehicleLocationSnapshotEntity location
             where location.vehicleId in :vehicleIds
               and location.recordedAt = (select max(later.recordedAt) from VehicleLocationSnapshotEntity later
                                           where later.vehicleId = location.vehicleId)
            """)
    List<VehicleLocationSnapshotEntity> findLatestForVehicles(
            @Param("vehicleIds") java.util.Collection<UUID> vehicleIds);

    @Query("""
            select location from VehicleLocationSnapshotEntity location
             where (:allSites = true or location.siteCode in :siteScopes)
             order by location.recordedAt desc, location.id desc
            """)
    List<VehicleLocationSnapshotEntity> findRecentInScope(@Param("allSites") boolean allSites,
            @Param("siteScopes") List<String> siteScopes, Pageable pageable);
}

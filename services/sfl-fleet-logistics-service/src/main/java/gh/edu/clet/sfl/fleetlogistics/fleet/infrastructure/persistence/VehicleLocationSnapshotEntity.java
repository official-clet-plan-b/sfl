package gh.edu.clet.sfl.fleetlogistics.fleet.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA image of the vendor-neutral vehicle movement projection. */
@Entity
@Table(name = "fleet_vehicle_locations", schema = "fleet_logistics")
class VehicleLocationSnapshotEntity {

    @Id
    private UUID id;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @Column(name = "site_code", nullable = false, length = 40)
    private String siteCode;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "odometer_value")
    private Long odometerValue;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "source_system", nullable = false, length = 80)
    private String sourceSystem;

    @Column(name = "integration_message_id")
    private UUID integrationMessageId;

    @Column(name = "correlation_id", length = 120)
    private String correlationId;

    protected VehicleLocationSnapshotEntity() {
    }

    static VehicleLocationSnapshotEntity from(VehicleLocationSnapshot snapshot) {
        VehicleLocationSnapshotEntity entity = new VehicleLocationSnapshotEntity();
        entity.id = snapshot.id();
        entity.vehicleId = snapshot.vehicleId();
        entity.siteCode = snapshot.siteCode().value();
        entity.latitude = snapshot.latitude();
        entity.longitude = snapshot.longitude();
        entity.odometerValue = snapshot.odometerValue();
        entity.recordedAt = snapshot.recordedAt();
        entity.sourceSystem = snapshot.sourceSystem();
        entity.integrationMessageId = snapshot.integrationMessageId();
        entity.correlationId = snapshot.correlationId();
        return entity;
    }

    VehicleLocationSnapshot toDomain() {
        return new VehicleLocationSnapshot(id, vehicleId, SiteCode.of(siteCode), latitude, longitude,
                odometerValue, recordedAt, sourceSystem, integrationMessageId, correlationId);
    }
}

package gh.edu.clet.sfl.fleetlogistics.fleet.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.fleetlogistics.assets.application.AssetVisibilityService;
import gh.edu.clet.sfl.fleetlogistics.assets.application.AssignCustodyCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.AssignTagCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.DuplicateAssetTagException;
import gh.edu.clet.sfl.fleetlogistics.assets.application.MoveAssetCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.RecordScanCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.RegisterAssetCommand;
import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetReferenceRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetCategory;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeSource;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeType;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetStatus;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DateTimeRange;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL behind the Phase 2 logistics screens, run against a real PostgreSQL.
 *
 * <p>These are the queries and constraints a mock cannot vouch for: the tag uniqueness index, the
 * history table's foreign key and ordering, the one-lookup latest-position query, and the purpose
 * prefix filter on trip search - which has a documented history of PostgreSQL rejecting bind
 * parameters it cannot type. Each test rolls back, so the database is left as it was found.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.fleet.scheduling.sla.enabled=false",
        "sfl.fleet.scheduling.outbox.enabled=false",
        "sfl.fleet.scheduling.compliance.enabled=false",
        "sfl.fleet.scheduling.dashboard.enabled=false",
        "sfl.fleet.messaging.transport=local"
})
@EnabledIf(value = "gh.edu.clet.sfl.fleetlogistics.fleet.e2e.FleetPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FleetPostgresSupport.unavailableReason()")
@Transactional
class Phase2PersistencePostgresTest extends FleetPostgresSupport {

    private static final SiteCode HQ = FleetFixtures.ACCRA;

    @Autowired private AssetVisibilityService assets;
    @Autowired private AssetReferenceRepository assetRepository;
    @Autowired private VehicleRepository vehicles;
    @Autowired private VehicleLocationRepository locations;
    @Autowired private TripRepository trips;
    @PersistenceContext private EntityManager entityManager;

    private AssetReference register(String code, String tag) {
        return assets.register(new RegisterAssetCommand(code, "Projector", AssetCategory.EQUIPMENT, "ACCRA",
                LocationType.SITE, "ACCRA", null, tag, "operator", "corr"));
    }

    @Test
    @DisplayName("the database refuses a second asset with the same tag, whatever its case")
    void the_tag_index_backs_up_the_service_check() {
        assetRepository.save(new AssetReference(UUID.randomUUID(), "AST-A", "A", AssetCategory.EQUIPMENT,
                AssetStatus.ACTIVE, "ACCRA", LocationType.SITE, "ACCRA", null, "rfid-1", null, Instant.now(), null));
        entityManager.flush();

        assertThatThrownBy(() -> {
            assetRepository.save(new AssetReference(UUID.randomUUID(), "AST-B", "B", AssetCategory.EQUIPMENT,
                    AssetStatus.ACTIVE, "ACCRA", LocationType.SITE, "ACCRA", null, "RFID-1", null, Instant.now(), null));
            entityManager.flush();
        }).rootCause().hasMessageContaining("uq_asset_references_tag");
    }

    @Test
    @DisplayName("the service refuses a duplicate tag with a message that names the holder")
    void the_service_names_who_holds_the_tag() {
        register("AST-1", "RFID-9");

        assertThatThrownBy(() -> register("AST-2", "rfid-9")).isInstanceOf(DuplicateAssetTagException.class)
                .hasMessageContaining("AST-1");
    }

    @Test
    @DisplayName("assets without a tag may share the blank")
    void untagged_assets_do_not_collide() {
        register("AST-1", null);
        register("AST-2", null);
        entityManager.flush();

        assertThat(assetRepository.findByExternalReference(null)).isEmpty();
    }

    @Test
    @DisplayName("history survives the round trip: newest first, with what changed and who did it")
    void history_is_stored_and_read_back_newest_first() throws Exception {
        AssetReference asset = register("AST-1", "RFID-1");
        assets.move(new MoveAssetCommand(asset.id(), LocationType.ROOM, "room 12", "operator", "corr"));
        Thread.sleep(5);
        assets.assignCustody(new AssignCustodyCommand(asset.id(), "A. Mensah", "operator", "corr"));
        Thread.sleep(5);
        assets.recordScan(new RecordScanCommand("rfid-1", LocationType.ZONE, "dock 2", "reader-7", null,
                "svc-readers", "corr"));
        entityManager.flush();
        entityManager.clear();

        List<AssetHistoryEntry> history = assets.history(asset.id());

        assertThat(history).extracting(AssetHistoryEntry::changeType).contains(
                AssetChangeType.REGISTERED, AssetChangeType.TAG_ASSIGNED, AssetChangeType.MOVED,
                AssetChangeType.CUSTODY_CHANGED);
        assertThat(history.get(0).changeType()).isEqualTo(AssetChangeType.MOVED);
        assertThat(history.get(0).source()).isEqualTo(AssetChangeSource.READER);
        assertThat(history.get(0).sourceReference()).isEqualTo("reader-7");
        assertThat(history.get(0).fromValue()).isEqualTo("ROOM:ROOM 12");
        assertThat(history.get(0).toValue()).isEqualTo("ZONE:DOCK 2");
        assertThat(history).extracting(AssetHistoryEntry::occurredAt).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
        assertThat(assets.findByTag("RFID-1").locationReference()).isEqualTo("DOCK 2");
    }

    @Test
    @DisplayName("assigning a tag to an untagged asset is stored and findable")
    void tagging_an_existing_asset_is_findable_by_tag() {
        AssetReference asset = register("AST-1", null);

        assets.assignTag(new AssignTagCommand(asset.id(), "RFID-77", "operator", "corr"));
        entityManager.flush();
        entityManager.clear();

        assertThat(assets.findByTag("rfid-77").id()).isEqualTo(asset.id());
    }

    @Test
    @DisplayName("one lookup returns each vehicle's newest report and nothing for a silent vehicle")
    void latest_positions_come_back_in_one_query() {
        Vehicle moving = vehicles.save(FleetFixtures.vehicle(UUID.randomUUID(), "TRK-1-26", HQ));
        // Never reported, and never needs to exist: the lookup is by id and a silent id has no row.
        UUID silent = UUID.randomUUID();
        Instant base = Instant.parse("2026-10-02T10:00:00Z");
        locations.save(snapshot(moving, base));
        VehicleLocationSnapshot newest = locations.save(snapshot(moving, base.plusSeconds(600)));
        locations.save(snapshot(moving, base.minusSeconds(600)));
        entityManager.flush();
        entityManager.clear();

        Map<UUID, VehicleLocationSnapshot> latest = locations.findLatestByVehicles(List.of(moving.id(), silent));

        assertThat(latest).containsOnlyKeys(moving.id());
        assertThat(latest.get(moving.id()).id()).isEqualTo(newest.id());
        assertThat(locations.findLatestByVehicles(List.of())).isEmpty();
    }

    @Test
    @DisplayName("trip search can be narrowed to a purpose prefix, and a wildcard in it matches only itself")
    void trips_can_be_filtered_by_purpose_prefix() {
        DateTimeRange period = DateTimeRange.of(Instant.parse("2026-10-05T10:00:00Z"),
                Instant.parse("2026-10-05T12:00:00Z"));
        trips.save(trip("TRP-P2-1", "VIP transport: Registrar", period));
        trips.save(trip("TRP-P2-2", "VIP transport: Board chair · chase-car requested", period));
        trips.save(trip("TRP-P2-3", "Courier run to the post office", period));
        trips.save(trip("TRP-P2-4", "100% reliable shuttle", period));
        entityManager.flush();
        entityManager.clear();

        assertThat(purposes(search("VIP transport: ")))
                .containsExactlyInAnyOrder("VIP transport: Registrar",
                        "VIP transport: Board chair · chase-car requested");
        assertThat(search(null)).hasSize(4);
        assertThat(search("  ")).hasSize(4);
        assertThat(purposes(search("100%"))).containsExactly("100% reliable shuttle");
        assertThat(search("%")).isEmpty();
        assertThat(search("_")).isEmpty();
    }

    private List<Trip> search(String prefix) {
        return trips.search(new TripRepository.TripSearchCriteria("ACCRA", null, null, null, null, null, null, 0,
                50, "plannedStart,desc", prefix), SiteScopeFilter.all()).content();
    }

    private static List<String> purposes(List<Trip> found) {
        return found.stream().map(Trip::purpose).toList();
    }

    private static Trip trip(String number, String purpose, DateTimeRange period) {
        return Trip.plan(UUID.randomUUID(), number, HQ, purpose, "Campus", "Airport", OperatingMode.ROUTINE, period,
                FleetFixtures.metadata());
    }

    /** A report must point at the inbox message it arrived in, so each one gets a minimal real row. */
    private VehicleLocationSnapshot snapshot(Vehicle vehicle, Instant at) {
        UUID messageId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                insert into fleet_logistics.fleet_integration_inbox_messages
                    (id, source_system, idempotency_key, event_type, site_code, occurred_at, payload_hash,
                     raw_payload, status, received_at)
                values (:id, 'TRACKER-CO', :key, 'sfl.ftlmp.vehicle-location-received.v1', 'ACCRA', :at,
                        repeat('a', 64), '{}', 'ACCEPTED', :at)
                """)
                .setParameter("id", messageId).setParameter("key", messageId.toString()).setParameter("at", at)
                .executeUpdate();
        return new VehicleLocationSnapshot(UUID.randomUUID(), vehicle.id(), HQ, new BigDecimal("5.60370"),
                new BigDecimal("-0.18700"), null, at, "tracker-co", messageId, "corr");
    }
}

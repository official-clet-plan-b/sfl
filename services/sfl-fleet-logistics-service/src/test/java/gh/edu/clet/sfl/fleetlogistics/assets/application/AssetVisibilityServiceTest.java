package gh.edu.clet.sfl.fleetlogistics.assets.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetHistoryRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetReferenceRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetCategory;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeSource;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeType;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import org.junit.jupiter.api.Test;

class AssetVisibilityServiceTest {

    @Test
    void registers_asset_and_records_outbox_event() {
        InMemoryAssetReferenceRepository repository = new InMemoryAssetReferenceRepository();
        RecordingOutbox outbox = new RecordingOutbox();
        AssetVisibilityService service = new AssetVisibilityService(repository, new InMemoryAssetHistoryRepository(), outbox,
                Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC));

        AssetReference asset = service.register(new RegisterAssetCommand("cam-001", "Main Gate Camera",
                AssetCategory.CCTV_CAMERA, "main", LocationType.ROOM, "room-a", "security", "vms-1001",
                "operator@sfl.local", "corr-1"));

        assertThat(asset.assetCode()).isEqualTo("CAM-001");
        assertThat(repository.findByAssetCode("CAM-001")).isPresent();
        assertThat(outbox.events).hasSize(1);
        assertThat(outbox.events.get(0).eventType).isEqualTo("sfl.avamp.asset-registered.v1");
        assertThat(outbox.events.get(0).siteScope).isEqualTo("MAIN");
    }

    @Test
    void rejects_duplicate_asset_code() {
        InMemoryAssetReferenceRepository repository = new InMemoryAssetReferenceRepository();
        AssetVisibilityService service = new AssetVisibilityService(repository, new InMemoryAssetHistoryRepository(), new RecordingOutbox(),
                Clock.fixed(Instant.parse("2026-07-13T08:00:00Z"), ZoneOffset.UTC));
        service.register(new RegisterAssetCommand("cam-001", "Main Gate Camera", AssetCategory.CCTV_CAMERA,
                "main", LocationType.ROOM, "room-a", null, null, null, null));

        assertThatThrownBy(() -> service.register(new RegisterAssetCommand("CAM-001", "Duplicate Camera",
                AssetCategory.CCTV_CAMERA, "main", LocationType.ROOM, "room-b", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Asset already exists: CAM-001");
    }


    private static final Instant NOW = Instant.parse("2026-07-13T08:00:00Z");

    private final InMemoryAssetReferenceRepository repository = new InMemoryAssetReferenceRepository();
    private final InMemoryAssetHistoryRepository history = new InMemoryAssetHistoryRepository();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final AssetVisibilityService service = new AssetVisibilityService(repository, history, outbox,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private AssetReference registered(String code, String tag) {
        return service.register(new RegisterAssetCommand(code, "Projector", AssetCategory.EQUIPMENT, "main",
                LocationType.SITE, "main", null, tag, "operator", "corr"));
    }

    @Test
    void a_tag_identifies_exactly_one_asset() {
        registered("ast-1", "RFID-001");

        assertThatThrownBy(() -> registered("ast-2", "rfid-001"))
                .isInstanceOf(DuplicateAssetTagException.class)
                .hasMessage("Tag rfid-001 is already assigned to asset AST-1");
        assertThat(repository.findByAssetCode("AST-2")).isEmpty();
    }

    @Test
    void assets_without_a_tag_do_not_collide() {
        registered("ast-1", null);
        registered("ast-2", "  ");

        assertThat(repository.assets).hasSize(2);
    }

    @Test
    void retagging_an_asset_with_its_own_tag_changes_nothing() {
        AssetReference asset = registered("ast-1", "RFID-001");
        int events = outbox.events.size();

        AssetReference same = service.assignTag(new AssignTagCommand(asset.id(), "rfid-001", "operator", "corr"));

        assertThat(same).isEqualTo(asset);
        assertThat(outbox.events).hasSize(events);
    }

    @Test
    void a_tag_held_by_another_asset_cannot_be_taken() {
        registered("ast-1", "RFID-001");
        AssetReference second = registered("ast-2", null);

        assertThatThrownBy(() -> service.assignTag(new AssignTagCommand(second.id(), "RFID-001", "operator", "corr")))
                .isInstanceOf(DuplicateAssetTagException.class);
        assertThat(repository.findById(second.id()).orElseThrow().externalReference()).isNull();
    }

    @Test
    void tagging_an_untagged_asset_is_recorded() {
        AssetReference asset = registered("ast-1", null);

        AssetReference tagged = service.assignTag(new AssignTagCommand(asset.id(), "RFID-009", "operator", "corr"));

        assertThat(tagged.externalReference()).isEqualTo("RFID-009");
        assertThat(service.findByTag("rfid-009").id()).isEqualTo(asset.id());
        assertThat(history.entries(asset.id())).extracting(AssetHistoryEntry::changeType)
                .contains(AssetChangeType.TAG_ASSIGNED);
        assertThat(outbox.events).extracting(e -> e.eventType).contains("sfl.avamp.asset-tagged.v1");
    }

    @Test
    void registration_writes_the_first_history_entries() {
        AssetReference asset = registered("ast-1", "RFID-001");

        assertThat(history.entries(asset.id())).extracting(AssetHistoryEntry::changeType)
                .containsExactlyInAnyOrder(AssetChangeType.REGISTERED, AssetChangeType.TAG_ASSIGNED);
    }

    @Test
    void a_move_and_a_custody_change_each_leave_a_history_entry_with_what_changed() {
        AssetReference asset = registered("ast-1", null);

        service.move(new MoveAssetCommand(asset.id(), LocationType.ROOM, "room 12", "operator", "corr"));
        service.assignCustody(new AssignCustodyCommand(asset.id(), "A. Mensah", "operator", "corr"));
        service.assignCustody(new AssignCustodyCommand(asset.id(), "B. Owusu", "operator", "corr"));

        List<AssetHistoryEntry> entries = history.entries(asset.id());
        AssetHistoryEntry moved = entries.stream().filter(e -> e.changeType() == AssetChangeType.MOVED).findFirst().orElseThrow();
        assertThat(moved.fromValue()).isEqualTo("SITE:MAIN");
        assertThat(moved.toValue()).isEqualTo("ROOM:ROOM 12");
        assertThat(moved.source()).isEqualTo(AssetChangeSource.MANUAL);
        assertThat(entries.stream().filter(e -> e.changeType() == AssetChangeType.CUSTODY_CHANGED))
                .extracting(AssetHistoryEntry::fromValue, AssetHistoryEntry::toValue)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(null, "A. Mensah"),
                        org.assertj.core.groups.Tuple.tuple("A. Mensah", "B. Owusu"));
    }

    @Test
    void naming_the_current_location_or_custodian_again_writes_nothing() {
        AssetReference asset = registered("ast-1", null);
        service.assignCustody(new AssignCustodyCommand(asset.id(), "A. Mensah", "operator", "corr"));
        int entries = history.entries(asset.id()).size();
        int events = outbox.events.size();

        service.move(new MoveAssetCommand(asset.id(), LocationType.SITE, "MAIN", "operator", "corr"));
        service.assignCustody(new AssignCustodyCommand(asset.id(), " A. Mensah ", "operator", "corr"));

        assertThat(history.entries(asset.id())).hasSize(entries);
        assertThat(outbox.events).hasSize(events);
    }

    @Test
    void a_reader_sighting_moves_the_asset_and_is_recorded_as_the_readers_doing() {
        AssetReference asset = registered("ast-1", "RFID-001");

        AssetReference moved = service.recordScan(new RecordScanCommand("rfid-001", LocationType.ROOM, "dock 2",
                "reader-7", NOW.minusSeconds(30), "svc-readers", "corr"));

        assertThat(moved.locationReference()).isEqualTo("DOCK 2");
        AssetHistoryEntry entry = history.entries(asset.id()).stream()
                .filter(e -> e.changeType() == AssetChangeType.MOVED).findFirst().orElseThrow();
        assertThat(entry.source()).isEqualTo(AssetChangeSource.READER);
        assertThat(entry.sourceReference()).isEqualTo("reader-7");
        assertThat(entry.occurredAt()).isEqualTo(NOW.minusSeconds(30));
    }

    @Test
    void seeing_an_asset_where_it_already_is_changes_nothing() {
        AssetReference asset = registered("ast-1", "RFID-001");
        int entries = history.entries(asset.id()).size();

        service.recordScan(new RecordScanCommand("RFID-001", LocationType.SITE, "main", "reader-7", null,
                "svc-readers", "corr"));

        assertThat(history.entries(asset.id())).hasSize(entries);
    }

    @Test
    void a_sighting_of_an_unregistered_tag_is_not_found_and_invents_no_asset() {
        assertThatThrownBy(() -> service.recordScan(new RecordScanCommand("UNKNOWN", LocationType.ROOM, "x",
                "reader-7", null, "svc-readers", "corr")))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(repository.assets).isEmpty();
    }

    @Test
    void a_sighting_stamped_in_the_future_is_refused() {
        registered("ast-1", "RFID-001");

        assertThatThrownBy(() -> service.recordScan(new RecordScanCommand("RFID-001", LocationType.ROOM, "dock 2",
                "reader-7", NOW.plusSeconds(3600), "svc-readers", "corr")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("occurredAt is in the future");
    }

    @Test
    void history_is_newest_first_and_needs_a_real_asset() {
        AssetReference asset = registered("ast-1", null);
        service.move(new MoveAssetCommand(asset.id(), LocationType.ROOM, "room 12", "operator", "corr"));

        assertThat(service.history(asset.id())).isNotEmpty();
        assertThatThrownBy(() -> service.history(UUID.randomUUID())).isInstanceOf(java.util.NoSuchElementException.class);
    }

    private static class InMemoryAssetHistoryRepository implements AssetHistoryRepository {
        private final List<AssetHistoryEntry> saved = new ArrayList<>();

        @Override
        public AssetHistoryEntry save(AssetHistoryEntry entry) {
            saved.add(entry);
            return entry;
        }

        @Override
        public List<AssetHistoryEntry> findByAssetId(UUID assetId) {
            List<AssetHistoryEntry> newestFirst = new ArrayList<>(entries(assetId));
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        }

        List<AssetHistoryEntry> entries(UUID assetId) {
            return saved.stream().filter(entry -> entry.assetId().equals(assetId)).toList();
        }
    }

    private static class InMemoryAssetReferenceRepository implements AssetReferenceRepository {
        private final Map<UUID, AssetReference> assets = new LinkedHashMap<>();

        @Override
        public AssetReference save(AssetReference assetReference) {
            assets.put(assetReference.id(), assetReference);
            return assetReference;
        }

        @Override
        public Optional<AssetReference> findById(UUID id) {
            return Optional.ofNullable(assets.get(id));
        }

        @Override
        public Optional<AssetReference> findByAssetCode(String assetCode) {
            return assets.values().stream().filter(asset -> asset.assetCode().equals(assetCode)).findFirst();
        }

        @Override
        public Optional<AssetReference> findByExternalReference(String tag) {
            return assets.values().stream()
                    .filter(asset -> asset.externalReference() != null && asset.externalReference().equalsIgnoreCase(tag))
                    .findFirst();
        }

        @Override
        public List<AssetReference> findAll(String siteCode) {
            return new ArrayList<>(assets.values());
        }

        @Override
        public List<AssetReference> findAllInScope(Set<String> siteScopes) {
            return assets.values().stream()
                    .filter(asset -> siteScopes.contains(asset.siteCode()))
                    .toList();
        }

        @Override
        public List<AssetReference> findByLocation(String siteCode, LocationType locationType, String locationReference) {
            return assets.values().stream()
                    .filter(asset -> asset.siteCode().equals(siteCode))
                    .filter(asset -> asset.locationType() == locationType)
                    .filter(asset -> asset.locationReference().equals(locationReference))
                    .toList();
        }
    }

    private static class RecordingOutbox implements ServiceOutbox {
        private final List<RecordedEvent> events = new ArrayList<>();

        @Override
        public void record(String eventType, int eventVersion, String aggregateType, UUID aggregateId,
                String siteScope, String correlationId, String causationId, Object payload) {
            events.add(new RecordedEvent(eventType, eventVersion, aggregateType, aggregateId, siteScope,
                    correlationId, causationId, payload));
        }
    }

    private record RecordedEvent(String eventType, int eventVersion, String aggregateType, UUID aggregateId,
            String siteScope, String correlationId, String causationId, Object payload) {
    }
}
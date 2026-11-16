package gh.edu.clet.sfl.facilities.masterdata.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.masterdata.application.ports.FacilitiesRepository;
import gh.edu.clet.sfl.facilities.masterdata.domain.Building;
import gh.edu.clet.sfl.facilities.masterdata.domain.DeviceReference;
import gh.edu.clet.sfl.facilities.masterdata.domain.DeviceReferenceType;
import gh.edu.clet.sfl.facilities.masterdata.domain.FacilityFloor;
import gh.edu.clet.sfl.facilities.masterdata.domain.FacilityRoom;
import gh.edu.clet.sfl.facilities.masterdata.domain.Site;
import gh.edu.clet.sfl.facilities.masterdata.domain.Zone;
import gh.edu.clet.sfl.facilities.masterdata.domain.ZoneMembership;
import gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import gh.edu.clet.sfl.facilities.shared.application.port.AuditPort;
import gh.edu.clet.sfl.facilities.shared.application.port.IdempotencyPort;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import gh.edu.clet.sfl.facilities.shared.domain.model.OperatingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The estate's write and read use cases (SRS-SFL-S152-01, -02).
 *
 * <p>Every command follows the same five steps, in this order, and the order matters:
 * <ol>
 *   <li><strong>Authorise</strong> - permission and site scope, before anything is read or written, so
 *       an unauthorised caller cannot learn from a 404 that a record exists.</li>
 *   <li><strong>Replay check</strong> - an {@code Idempotency-Key} already seen with the same payload
 *       returns the original result rather than creating a second record.</li>
 *   <li><strong>Validate</strong> - parent references resolve, identifiers are free, the caller's
 *       version is current.</li>
 *   <li><strong>Apply</strong> - the domain decides; this layer never encodes a business rule.</li>
 *   <li><strong>Record</strong> - audit and outbox, in the same transaction as the change.</li>
 * </ol>
 */
@Service
public class FacilitiesMasterDataService {

    private final FacilitiesRepository facilities;
    private final ServiceOutbox outbox;
    private final AuditPort audit;
    private final IdempotencyPort idempotency;
    private final FacilitiesAuthorization authorization;
    private final Clock clock;
    private final SiteCommands siteCommands;
    private final BuildingFloorCommands buildingFloorCommands;
    private final RoomCommands roomCommands;
    private final ZoneCommands zoneCommands;
    private final DeviceReferenceCommands deviceReferenceCommands;

    public FacilitiesMasterDataService(FacilitiesRepository facilities, ServiceOutbox outbox, AuditPort audit,
            IdempotencyPort idempotency, FacilitiesAuthorization authorization, Clock clock) {
        this.facilities = facilities;
        this.outbox = outbox;
        this.audit = audit;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.clock = clock;
        this.siteCommands = new SiteCommands(this, facilities, authorization, audit);
        this.buildingFloorCommands = new BuildingFloorCommands(this, facilities, authorization, audit);
        this.roomCommands = new RoomCommands(this, facilities, authorization, audit);
        this.zoneCommands = new ZoneCommands(this, facilities, authorization, audit);
        this.deviceReferenceCommands = new DeviceReferenceCommands(this, facilities, authorization, audit);
    }

    // =========================================================================================
    // Sites, buildings, floors, spaces, zones and device references
    //
    // Each command body lives in a collaborator - SiteCommands, BuildingFloorCommands,
    // RoomCommands, ZoneCommands, DeviceReferenceCommands - split out for the reason given in this
    // class's Javadoc. @Transactional stays here: it is this class's proxy Spring builds the
    // transaction boundary from, and a plain call from an already-transactional method runs in the
    // same transaction with no proxying of its own needed.
    // =========================================================================================

    @Transactional
    public Site createSite(FacilitiesCommands.CreateSite command) {
        return siteCommands.create(command);
    }

    @Transactional
    public Site updateSite(FacilitiesCommands.UpdateSite command) {
        return siteCommands.update(command);
    }

    @Transactional
    public Site changeSiteLifecycle(FacilitiesCommands.ChangeSiteLifecycle command) {
        return siteCommands.changeLifecycle(command);
    }

    /**
     * Declares or stands down examination mode (NFR 23.3).
     *
     * <p>Its own permission, its own audit action and its own event, because the mode change is the
     * decision - every stricter rule that follows is a consequence of it, and burying it inside a
     * general site update would make the one thing a reviewer looks for invisible.
     */
    @Transactional
    public Site changeOperatingMode(FacilitiesCommands.ChangeOperatingMode command) {
        return siteCommands.changeOperatingMode(command);
    }

    @Transactional
    public Building createBuilding(FacilitiesCommands.CreateBuilding command) {
        return buildingFloorCommands.createBuilding(command);
    }

    @Transactional
    public FacilityFloor createFloor(FacilitiesCommands.CreateFloor command) {
        return buildingFloorCommands.createFloor(command);
    }

    @Transactional
    public FacilityRoom createRoom(FacilitiesCommands.CreateRoom command) {
        return roomCommands.create(command);
    }

    @Transactional
    public FacilityRoom updateRoom(FacilitiesCommands.UpdateRoom command) {
        return roomCommands.update(command);
    }

    @Transactional
    public FacilityRoom changeRoomLifecycle(FacilitiesCommands.ChangeRoomLifecycle command) {
        return roomCommands.changeLifecycle(command);
    }

    @Transactional
    public Zone createZone(FacilitiesCommands.CreateZone command) {
        return zoneCommands.create(command);
    }

    /**
     * Adds a record to a zone.
     *
     * <p>The member must belong to the zone's own site. Without that check a zone could reach across
     * sites, and an evacuation broadcast addressed to it would page a building three hundred kilometres
     * from the fire.
     */
    @Transactional
    public ZoneMembership addZoneMember(FacilitiesCommands.AddZoneMember command) {
        return zoneCommands.addMember(command);
    }

    @Transactional
    public void removeZoneMember(FacilitiesCommands.RemoveZoneMember command) {
        zoneCommands.removeMember(command);
    }

    @Transactional
    public DeviceReference registerDeviceReference(FacilitiesCommands.RegisterDeviceReference command) {
        return deviceReferenceCommands.register(command);
    }

    // =========================================================================================
    // Queries
    // =========================================================================================

    @Transactional(readOnly = true)
    public List<Site> sites(ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_SITE_READ, channel, "Site", "list", null);
        return authorization.filterBySite(actor, facilities.findSites(), Site::siteCode);
    }

    @Transactional(readOnly = true)
    public Site site(UUID id, ActorContext actor, SourceChannel channel) {
        Site site = requireSite(id);
        authorization.require(actor, SflPermission.FACILITIES_SITE_READ, site.siteCode(), channel, "Site",
                id.toString());
        return site;
    }

    @Transactional(readOnly = true)
    public List<Building> buildings(String siteCode, ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, channel, "Building", "list", siteCode);
        authorization.requireRequestedSite(actor, siteCode, channel, "Building");
        return authorization.filterBySite(actor, facilities.findBuildings(siteCode), Building::siteCode);
    }

    @Transactional(readOnly = true)
    public Building building(UUID id, ActorContext actor, SourceChannel channel) {
        Building building = facilities.findBuilding(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Building", id));
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, building.siteCode(), channel,
                "Building", id.toString());
        return building;
    }

    @Transactional(readOnly = true)
    public List<FacilityFloor> floors(UUID buildingId, ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, channel, "FacilityFloor", "list", null);
        return authorization.filterBySite(actor, facilities.findFloors(buildingId), FacilityFloor::siteCode);
    }

    @Transactional(readOnly = true)
    public FacilityFloor floor(UUID id, ActorContext actor, SourceChannel channel) {
        FacilityFloor floor = facilities.findFloor(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Floor", id));
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, floor.siteCode(), channel,
                "FacilityFloor", id.toString());
        return floor;
    }

    @Transactional(readOnly = true)
    public List<FacilityRoom> rooms(String siteCode, ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, channel, "FacilityRoom", "list",
                siteCode);
        authorization.requireRequestedSite(actor, siteCode, channel, "FacilityRoom");
        return authorization.filterBySite(actor, facilities.findRooms(siteCode), FacilityRoom::siteCode);
    }

    @Transactional(readOnly = true)
    public FacilitiesRepository.Page<FacilityRoom> searchRooms(FacilitiesRepository.RoomQuery query,
            ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, channel, "FacilityRoom", "search",
                query.siteCode());
        authorization.requireRequestedSite(actor, query.siteCode(), channel, "FacilityRoom");
        FacilitiesRepository.Page<FacilityRoom> page = facilities.searchRooms(query);
        List<FacilityRoom> visible = authorization.filterBySite(actor, page.items(), FacilityRoom::siteCode);
        // When filtering removed rows, the total is reported as what remains: a total counting records
        // the caller may not see would let them infer another site's estate size.
        return visible.size() == page.items().size()
                ? page
                : FacilitiesRepository.Page.of(visible, visible.size(), page.page(), page.size());
    }

    @Transactional(readOnly = true)
    public FacilityRoom room(UUID id, ActorContext actor, SourceChannel channel) {
        FacilityRoom room = requireRoom(id);
        authorization.require(actor, SflPermission.FACILITIES_SPACE_READ, room.siteCode(), channel,
                "FacilityRoom", id.toString());
        return room;
    }

    @Transactional(readOnly = true)
    public List<Zone> zones(String siteCode, ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_ZONE_READ, channel, "Zone", "list", siteCode);
        authorization.requireRequestedSite(actor, siteCode, channel, "Zone");
        return authorization.filterBySite(actor, facilities.findZones(siteCode), Zone::siteCode);
    }

    @Transactional(readOnly = true)
    public Zone zone(UUID id, ActorContext actor, SourceChannel channel) {
        Zone zone = facilities.findZone(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Zone", id));
        authorization.require(actor, SflPermission.FACILITIES_ZONE_READ, zone.siteCode(), channel, "Zone",
                id.toString());
        return zone;
    }

    @Transactional(readOnly = true)
    public List<ZoneMembership> zoneMembers(UUID zoneId, ActorContext actor, SourceChannel channel) {
        Zone zone = facilities.findZone(zoneId)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Zone", zoneId));
        authorization.require(actor, SflPermission.FACILITIES_ZONE_READ, zone.siteCode(), channel, "Zone",
                zoneId.toString());
        return facilities.findZoneMembers(zoneId);
    }

    @Transactional(readOnly = true)
    public List<DeviceReference> deviceReferences(String siteCode, DeviceReferenceType type, UUID roomId,
            ActorContext actor, SourceChannel channel) {
        authorization.require(actor, SflPermission.FACILITIES_DEVICE_REFERENCE_READ, channel, "DeviceReference",
                "list", siteCode);
        authorization.requireRequestedSite(actor, siteCode, channel, "DeviceReference");
        return authorization.filterBySite(actor, facilities.findDeviceReferences(siteCode, type, roomId),
                DeviceReference::siteCode);
    }

    @Transactional(readOnly = true)
    public DeviceReference deviceReference(UUID id, ActorContext actor, SourceChannel channel) {
        DeviceReference device = facilities.findDeviceReference(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Device reference", id));
        authorization.require(actor, SflPermission.FACILITIES_DEVICE_REFERENCE_READ, device.siteCode(), channel,
                "DeviceReference", id.toString());
        return device;
    }


    // =========================================================================================
    // Editing and retiring the rest of the estate
    //
    // Buildings, floors, zones, device references and assets each had a `changeLifecycle` on the
    // domain record and, for most of them, an `update` too - and no endpoint reached either. The
    // gap was recorded in S152_UI_Gap_Report §3 rather than worked around, and this closes it.
    //
    // Every one follows the same four steps as the site equivalents above, in the same order, and
    // the order is the point: load, authorise against the record's *own* site, check the version,
    // then write and audit. Authorising before loading would leak whether an id exists to somebody
    // scoped to another centre; checking the version after writing would not be a check at all.
    // =========================================================================================

    @Transactional
    public Building updateBuilding(FacilitiesCommands.UpdateBuilding command) {
        return buildingFloorCommands.updateBuilding(command);
    }

    @Transactional
    public Building changeBuildingLifecycle(FacilitiesCommands.ChangeBuildingLifecycle command) {
        return buildingFloorCommands.changeBuildingLifecycle(command);
    }

    @Transactional
    public FacilityFloor updateFloor(FacilitiesCommands.UpdateFloor command) {
        return buildingFloorCommands.updateFloor(command);
    }

    @Transactional
    public FacilityFloor changeFloorLifecycle(FacilitiesCommands.ChangeFloorLifecycle command) {
        return buildingFloorCommands.changeFloorLifecycle(command);
    }

    @Transactional
    public Zone changeZoneLifecycle(FacilitiesCommands.ChangeZoneLifecycle command) {
        return zoneCommands.changeLifecycle(command);
    }

    /**
     * Corrects a device reference.
     *
     * <p>Takes the registration permission rather than a new one: whoever may put a device on the
     * estate map is the one who has to fix it when the vendor renames it, and inventing a second
     * grant for the correction would leave the register accumulating wrong names nobody was allowed
     * to touch.
     *
     * <p>The status is not editable here. It belongs to the vendor feed, and a hand-set status would
     * be this service asserting an observation it has not made.
     */
    @Transactional
    public DeviceReference updateDeviceReference(FacilitiesCommands.UpdateDeviceReference command) {
        return deviceReferenceCommands.update(command);
    }

    @Transactional
    public DeviceReference changeDeviceReferenceLifecycle(
            FacilitiesCommands.ChangeDeviceReferenceLifecycle command) {
        return deviceReferenceCommands.changeLifecycle(command);
    }

    Building requireBuilding(UUID id) {
        return facilities.findBuilding(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Building", id));
    }

    FacilityFloor requireFloor(UUID id) {
        return facilities.findFloor(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Floor", id));
    }

    Zone requireZone(UUID id) {
        return facilities.findZone(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Zone", id));
    }

    DeviceReference requireDeviceReference(UUID id) {
        return facilities.findDeviceReference(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("DeviceReference", id));
    }

    // =========================================================================================
    // Internals
    // =========================================================================================

    Site requireSite(UUID id) {
        return facilities.findSite(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Site", id));
    }

    FacilityRoom requireRoom(UUID id) {
        return facilities.findRoom(id)
                .orElseThrow(() -> new FacilitiesException.RecordNotFoundException("Space", id));
    }

    void requireRoomInSite(UUID roomId, String siteCode) {
        FacilityRoom room = requireRoom(roomId);
        if (!room.siteCode().equals(siteCode)) {
            throw new FacilitiesException.ValidationFailedException(
                    "Space " + room.roomCode() + " belongs to site " + room.siteCode() + ", not " + siteCode
                            + ".");
        }
    }

    <T> Optional<T> replay(String operation, String idempotencyKey, Object payload,
            Function<UUID, Optional<T>> lookup) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return idempotency.findExistingResult(operation, idempotencyKey, idempotency.fingerprint(payload))
                .flatMap(lookup);
    }

    void remember(String operation, String idempotencyKey, Object payload, UUID resultId,
            String siteCode, ActorContext actor) {
        idempotency.recordResult(operation, idempotencyKey, idempotency.fingerprint(payload), resultId,
                siteCode, actor.actorId());
    }

    void publish(String eventType, String aggregateType, UUID aggregateId, String siteScope,
            ActorContext actor, Object payload) {
        outbox.record(eventType, 1, aggregateType, aggregateId, siteScope, actor.correlationId(),
                actor.actorId(), payload);
    }

    Instant now() {
        return clock.instant();
    }

    /**
     * Normalises an identifier, refusing a blank one.
     *
     * <p>A blank site code is {@code MISSING_SITE_SCOPE} rather than a validation failure, because
     * that is the SRS's own name for it: "Select a valid GSL site before saving this record."
     */
    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new FacilitiesException.MissingSiteScopeException();
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }
}

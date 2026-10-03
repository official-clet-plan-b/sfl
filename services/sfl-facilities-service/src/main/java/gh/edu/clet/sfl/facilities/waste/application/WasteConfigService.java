package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.waste.domain.ApprovalStatus;
import gh.edu.clet.sfl.facilities.waste.domain.DestinationType;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCarrier;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCategory;
import gh.edu.clet.sfl.facilities.waste.domain.WasteDestination;
import gh.edu.clet.sfl.facilities.waste.domain.WastePoint;
import gh.edu.clet.sfl.facilities.waste.domain.WasteStream;
import gh.edu.clet.sfl.facilities.waste.domain.WasteUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Waste streams, collection points, approved carriers, approved destinations and units - SRS-SFL-S178-01.
 *
 * <p>Streams, carriers, destinations and units are organisation-wide: a carrier's licence is the same
 * licence at every site. Collection points belong to a site. Changing a carrier or destination to
 * SUSPENDED, or letting its licence lapse, takes effect on the next handover - the approval is checked
 * when waste moves, not only when it was scheduled.
 */
@Service
public class WasteConfigService {

    private final WasteStore store;
    private final WasteSupport support;

    public WasteConfigService(WasteStore store, WasteSupport support) {
        this.store = store;
        this.support = support;
    }

    // ---- reads

    @Transactional(readOnly = true)
    public Configuration configuration(String siteCode, Caller caller) {
        String site = WasteSupport.code(siteCode, "siteCode");
        support.require(caller, SflPermission.FACILITIES_WASTE_READ, site, "WasteConfiguration", site);
        return new Configuration(store.streams(), store.points(site), store.carriers(), store.destinations(),
                store.units());
    }

    public record Configuration(List<WasteStream> streams, List<WastePoint> points, List<WasteCarrier> carriers,
            List<WasteDestination> destinations, List<WasteUnit> units) {
    }

    // ---- streams

    @Transactional
    public WasteStream createStream(String code, String name, WasteCategory category, boolean hazardous,
            boolean diverted, String handlingRules, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteStream", "new");
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (hazardous && diverted) {
            throw new IllegalArgumentException("A hazardous stream cannot be counted as diverted.");
        }
        Instant now = support.now();
        WasteStream stream = new WasteStream(UUID.randomUUID(), WasteSupport.code(code, "code"),
                WasteSupport.required(name, "name"), category, hazardous, diverted,
                WasteSupport.blankToNull(handlingRules), true, caller.actor().actorId(), now, now, 0);
        store.insert(stream);
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteStream", stream.id(), "*", null, stream);
        return stream;
    }

    @Transactional
    public WasteStream updateStream(UUID id, String name, Boolean hazardous, Boolean diverted, String handlingRules,
            Boolean active, Long expectedVersion, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteStream", id.toString());
        WasteStream before = store.stream(id).orElseThrow(() -> WasteSupport.notFound("Waste stream", id));
        WasteSupport.checkVersion(expectedVersion, before.version());
        boolean hazard = hazardous == null ? before.hazardous() : hazardous;
        boolean divert = diverted == null ? before.diverted() : diverted;
        if (hazard && divert) {
            throw new IllegalArgumentException("A hazardous stream cannot be counted as diverted.");
        }
        WasteStream after = new WasteStream(id, before.code(), name == null ? before.name() : name.strip(),
                before.category(), hazard, divert, handlingRules == null ? before.handlingRules() : handlingRules,
                active == null ? before.active() : active, before.createdBy(), before.createdAt(), support.now(),
                before.version());
        if (!store.update(after, before.version())) {
            throw WasteSupport.conflict();
        }
        WasteStream saved = store.stream(id).orElseThrow();
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteStream", id, "*", before, saved);
        return saved;
    }

    // ---- points

    @Transactional
    public WastePoint createPoint(String siteCode, String code, String name, UUID roomId, String container,
            Caller caller) {
        String site = support.validateSiteAndRoom(siteCode, roomId);
        support.require(caller, SflPermission.FACILITIES_WASTE_MANAGE, site, "WastePoint", "new");
        Instant now = support.now();
        WastePoint point = new WastePoint(UUID.randomUUID(), site, WasteSupport.code(code, "code"),
                WasteSupport.required(name, "name"), roomId, WasteSupport.blankToNull(container), true,
                caller.actor().actorId(), now, now, 0);
        store.insert(point);
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WastePoint", point.id(), site, null, point);
        return point;
    }

    @Transactional
    public WastePoint updatePoint(UUID id, String name, String container, Boolean active, Long expectedVersion,
            Caller caller) {
        WastePoint before = store.point(id).orElseThrow(() -> WasteSupport.notFound("Collection point", id));
        support.require(caller, SflPermission.FACILITIES_WASTE_MANAGE, before.siteCode(), "WastePoint", id.toString());
        WasteSupport.checkVersion(expectedVersion, before.version());
        WastePoint after = new WastePoint(id, before.siteCode(), before.code(),
                name == null ? before.name() : name.strip(), before.roomId(),
                container == null ? before.containerDescription() : container, active == null ? before.active() : active,
                before.createdBy(), before.createdAt(), support.now(), before.version());
        if (!store.update(after, before.version())) {
            throw WasteSupport.conflict();
        }
        WastePoint saved = store.point(id).orElseThrow();
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WastePoint", id, saved.siteCode(), before, saved);
        return saved;
    }

    // ---- carriers

    @Transactional
    public WasteCarrier createCarrier(String code, String name, String licenceReference, LocalDate licenceExpiresOn,
            boolean hazardousApproved, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteCarrier", "new");
        if (licenceExpiresOn == null) {
            throw new IllegalArgumentException("licenceExpiresOn is required");
        }
        Instant now = support.now();
        WasteCarrier carrier = new WasteCarrier(UUID.randomUUID(), WasteSupport.code(code, "code"),
                WasteSupport.required(name, "name"), WasteSupport.required(licenceReference, "licenceReference"),
                licenceExpiresOn, hazardousApproved, ApprovalStatus.APPROVED, caller.actor().actorId(), now, now, 0);
        store.insert(carrier);
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteCarrier", carrier.id(), "*", null, carrier);
        return carrier;
    }

    @Transactional
    public WasteCarrier updateCarrier(UUID id, String licenceReference, LocalDate licenceExpiresOn,
            Boolean hazardousApproved, ApprovalStatus status, Long expectedVersion, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteCarrier", id.toString());
        WasteCarrier before = store.carrier(id).orElseThrow(() -> WasteSupport.notFound("Carrier", id));
        WasteSupport.checkVersion(expectedVersion, before.version());
        WasteCarrier after = new WasteCarrier(id, before.code(), before.name(),
                licenceReference == null ? before.licenceReference() : licenceReference.strip(),
                licenceExpiresOn == null ? before.licenceExpiresOn() : licenceExpiresOn,
                hazardousApproved == null ? before.hazardousApproved() : hazardousApproved,
                status == null ? before.status() : status, before.createdBy(), before.createdAt(), support.now(),
                before.version());
        if (!store.update(after, before.version())) {
            throw WasteSupport.conflict();
        }
        WasteCarrier saved = store.carrier(id).orElseThrow();
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteCarrier", id, "*", before, saved);
        return saved;
    }

    // ---- destinations

    @Transactional
    public WasteDestination createDestination(String code, String name, DestinationType type, String permitReference,
            LocalDate permitExpiresOn, boolean acceptsHazardous, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteDestination", "new");
        if (type == null || permitExpiresOn == null) {
            throw new IllegalArgumentException("destinationType and permitExpiresOn are required");
        }
        Instant now = support.now();
        WasteDestination destination = new WasteDestination(UUID.randomUUID(), WasteSupport.code(code, "code"),
                WasteSupport.required(name, "name"), type, WasteSupport.required(permitReference, "permitReference"),
                permitExpiresOn, acceptsHazardous, ApprovalStatus.APPROVED, caller.actor().actorId(), now, now, 0);
        store.insert(destination);
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteDestination", destination.id(), "*", null,
                destination);
        return destination;
    }

    @Transactional
    public WasteDestination updateDestination(UUID id, String permitReference, LocalDate permitExpiresOn,
            Boolean acceptsHazardous, ApprovalStatus status, Long expectedVersion, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteDestination", id.toString());
        WasteDestination before = store.destination(id).orElseThrow(() -> WasteSupport.notFound("Destination", id));
        WasteSupport.checkVersion(expectedVersion, before.version());
        WasteDestination after = new WasteDestination(id, before.code(), before.name(), before.destinationType(),
                permitReference == null ? before.permitReference() : permitReference.strip(),
                permitExpiresOn == null ? before.permitExpiresOn() : permitExpiresOn,
                acceptsHazardous == null ? before.acceptsHazardous() : acceptsHazardous,
                status == null ? before.status() : status, before.createdBy(), before.createdAt(), support.now(),
                before.version());
        if (!store.update(after, before.version())) {
            throw WasteSupport.conflict();
        }
        WasteDestination saved = store.destination(id).orElseThrow();
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteDestination", id, "*", before, saved);
        return saved;
    }

    // ---- units

    @Transactional
    public WasteUnit saveUnit(String code, String name, BigDecimal kilogramsPerUnit, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_WASTE_MANAGE, "WasteUnit", "new");
        if (kilogramsPerUnit == null || kilogramsPerUnit.signum() <= 0) {
            throw new IllegalArgumentException("kilogramsPerUnit must be positive");
        }
        WasteUnit unit = new WasteUnit(WasteSupport.code(code, "code"), WasteSupport.required(name, "name"),
                kilogramsPerUnit);
        WasteUnit before = store.unit(unit.code()).orElse(null);
        store.saveUnit(unit);
        support.audit(caller, AuditAction.WASTE_CONFIG_CHANGED, "WasteUnit", UUID.nameUUIDFromBytes(unit.code().getBytes()),
                "*", before, unit);
        return unit;
    }
}

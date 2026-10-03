package gh.edu.clet.sfl.facilities.waste.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.Caller;
import gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import gh.edu.clet.sfl.facilities.shared.application.port.AuditPort;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import gh.edu.clet.sfl.facilities.waste.application.ports.WasteEstatePort;
import gh.edu.clet.sfl.facilities.waste.domain.WasteCollection;
import gh.edu.clet.sfl.facilities.waste.domain.WasteHistoryEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** What every S178 operation needs in the same shape: who may do it, the audit entry, history row and event. */
@Component
public class WasteSupport {

    /** Org-wide configuration (streams, carriers, destinations, units) has no site; it is checked as the organisation. */
    static final String ORGANISATION = "organisation";

    private final WasteStore store;
    private final WasteEstatePort estate;
    private final FacilitiesAuthorization authorization;
    private final AuditPort audit;
    private final ServiceOutbox outbox;
    private final Clock clock;

    public WasteSupport(WasteStore store, WasteEstatePort estate, FacilitiesAuthorization authorization,
            AuditPort audit, ServiceOutbox outbox, Clock clock) {
        this.store = store;
        this.estate = estate;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public void require(Caller caller, SflPermission permission, String siteCode, String type, String id) {
        authorization.require(caller.actor(), permission, siteCode, caller.channel(), type, id);
    }

    public void requireOrganisation(Caller caller, SflPermission permission, String type, String id) {
        authorization.require(caller.actor(), permission, caller.channel(), type, id, ORGANISATION);
    }

    public boolean has(ActorContext actor, SflPermission permission) {
        return authorization.has(actor, permission);
    }

    public WasteCollection collection(UUID id) {
        return store.collection(id).orElseThrow(() -> notFound("Waste collection", id));
    }

    public static FacilitiesException notFound(String what, Object id) {
        return new FacilitiesException(FacilitiesErrorCode.RECORD_NOT_FOUND, what + " " + id + " does not exist.");
    }

    public static FacilitiesException conflict() {
        return new FacilitiesException(FacilitiesErrorCode.VERSION_CONFLICT);
    }

    public static void checkVersion(Long expected, long actual) {
        if (expected != null && expected != actual) {
            throw conflict();
        }
    }

    public static FacilitiesException invalid(String message) {
        return new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION, message);
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    public static String code(String value, String field) {
        return required(value, field).toUpperCase();
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public String validateSiteAndRoom(String siteCode, UUID roomId) {
        String site = code(siteCode, "siteCode");
        if (!estate.siteExists(site)) {
            throw new IllegalArgumentException("Site " + site + " does not exist.");
        }
        if (roomId != null) {
            String roomSite = estate.siteOfRoom(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Room " + roomId + " does not exist."));
            if (!roomSite.equalsIgnoreCase(site)) {
                throw new IllegalArgumentException("Room " + roomId + " is not in site " + site + ".");
            }
        }
        return site;
    }

    public void history(String site, String subjectType, UUID subjectId, String from, String to, String actor,
            String reason) {
        store.record(new WasteHistoryEntry(UUID.randomUUID(), site, subjectType, subjectId, from, to, actor, reason,
                now()));
    }

    public void audit(Caller caller, AuditAction action, String type, UUID id, String site, Object before,
            Object after) {
        audit.record(caller.actor(), caller.channel(), action, type, id.toString(), site, before, after);
    }

    public void publish(String eventType, String aggregateType, UUID id, String site, ActorContext actor,
            Object... payload) {
        outbox.record(eventType, 1, aggregateType, id, site, actor.correlationId(), actor.actorId(),
                WasteEvents.payload(payload));
    }
}

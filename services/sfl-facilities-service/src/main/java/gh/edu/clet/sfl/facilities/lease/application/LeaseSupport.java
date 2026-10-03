package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseEstatePort;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.BusinessCalendar;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseHistoryEntry;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import gh.edu.clet.sfl.facilities.shared.application.port.AuditPort;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What every S177 operation needs in the same shape: who may do it, the audit entry, the history row and the
 * event - and "today", which is the date in the <em>configured timezone</em>, not the server's. A notice date
 * worked out in one zone and compared in another is how a deadline is missed by a day.
 */
@Component
public class LeaseSupport {

    static final String ORGANISATION = "organisation";

    private final LeaseStore store;
    private final LeaseEstatePort estate;
    private final FacilitiesAuthorization authorization;
    private final AuditPort audit;
    private final ServiceOutbox outbox;
    private final Clock clock;

    public LeaseSupport(LeaseStore store, LeaseEstatePort estate, FacilitiesAuthorization authorization, AuditPort audit,
            ServiceOutbox outbox, Clock clock) {
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

    public ZoneId zone() {
        return ZoneId.of(store.settings().timezone());
    }

    /** Today in the configured timezone. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }

    public BusinessCalendar calendar() {
        return store.calendar();
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

    public Agreement agreement(UUID id) {
        return store.agreement(id).orElseThrow(() -> notFound("Agreement", id));
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

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public String validateSite(String siteCode) {
        String site = required(siteCode, "siteCode").toUpperCase();
        if (!estate.siteExists(site)) {
            throw new IllegalArgumentException("Site " + site + " does not exist.");
        }
        return site;
    }

    public void validateRoom(String site, UUID roomId) {
        if (roomId != null) {
            String roomSite = estate.siteOfRoom(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Room " + roomId + " does not exist."));
            if (!roomSite.equalsIgnoreCase(site)) {
                throw new IllegalArgumentException("Room " + roomId + " is not in site " + site + ".");
            }
        }
    }

    public void history(String site, String subjectType, UUID subjectId, String from, String to, String actor, String reason) {
        store.record(new LeaseHistoryEntry(UUID.randomUUID(), site, subjectType, subjectId, from, to, actor, reason, now()));
    }

    public void audit(Caller caller, AuditAction action, String type, UUID id, String site, Object before, Object after) {
        audit.record(caller.actor(), caller.channel(), action, type, id.toString(), site, before, after);
    }

    public void publish(String eventType, String aggregateType, UUID id, String site, ActorContext actor, Object... payload) {
        outbox.record(eventType, 1, aggregateType, id, site, actor.correlationId(), actor.actorId(), LeaseEvents.payload(payload));
    }
}

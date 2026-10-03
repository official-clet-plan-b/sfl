package gh.edu.clet.sfl.facilities.hygiene.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneEstatePort;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationLevel;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEscalation;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneHistoryEntry;
import gh.edu.clet.sfl.facilities.shared.application.FacilitiesAuthorization;
import gh.edu.clet.sfl.facilities.shared.application.ServiceOutbox;
import gh.edu.clet.sfl.facilities.shared.application.port.AuditPort;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What every S170 operation needs in the same shape: who may do it, the audit entry, the history row,
 * the event and the escalation. Held once so a control, a finding and a sweep cannot drift apart on how
 * they record a change.
 */
@Component
public class HygieneSupport {

    private final HygieneStore store;
    private final HygieneEstatePort estate;
    private final FacilitiesAuthorization authorization;
    private final AuditPort audit;
    private final ServiceOutbox outbox;
    private final Clock clock;

    public HygieneSupport(HygieneStore store, HygieneEstatePort estate, FacilitiesAuthorization authorization,
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

    public boolean has(ActorContext actor, SflPermission permission) {
        return authorization.has(actor, permission);
    }

    public HygieneControl control(UUID id) {
        return store.findControl(id).orElseThrow(() -> notFound("Hygiene control", id));
    }

    public HygieneFinding finding(UUID id) {
        return store.findFinding(id).orElseThrow(() -> notFound("Hygiene finding", id));
    }

    public HygieneAction action(UUID id) {
        return store.findAction(id).orElseThrow(() -> notFound("Hygiene action", id));
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

    /** Site and, if given, room must exist in S152 and the room must belong to the site. */
    public String validateSiteAndRoom(String siteCode, UUID roomId) {
        String site = required(siteCode, "siteCode").toUpperCase();
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

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    public void history(String site, String subjectType, UUID subjectId, String from, String to, String actor,
            String reason) {
        store.record(new HygieneHistoryEntry(UUID.randomUUID(), site, subjectType, subjectId, from, to, actor, reason,
                now()));
    }

    public void audit(Caller caller, AuditAction action, String type, UUID id, String site, Object before,
            Object after) {
        audit.record(caller.actor(), caller.channel(), action, type, id.toString(), site, before, after);
    }

    public void publish(String eventType, String aggregateType, UUID id, String site, ActorContext actor,
            Object... payload) {
        outbox.record(eventType, 1, aggregateType, id, site, actor.correlationId(), actor.actorId(),
                HygieneEvents.payload(payload));
    }

    /**
     * Raises an escalation once. A second call for the same subject, level and reason is a no-op and
     * returns false, so a sweep that runs every few minutes cannot notify the same person again and again.
     */
    public boolean escalate(String site, String subjectType, UUID subjectId, String subjectReference,
            EscalationLevel level, EscalationReason reason, String detail, ActorContext actor, SourceChannel channel) {
        HygieneEscalation escalation = new HygieneEscalation(UUID.randomUUID(), site, subjectType, subjectId,
                subjectReference, level, reason, detail, now(), null, null);
        if (!store.insert(escalation)) {
            return false;
        }
        audit.record(actor, channel, AuditAction.HYGIENE_ESCALATED, "HygieneEscalation", escalation.id().toString(),
                site, null, escalation);
        publish(HygieneEvents.ESCALATED, "HygieneEscalation", escalation.id(), site, actor, "subjectType",
                subjectType, "subjectId", subjectId, "subjectReference", subjectReference, "level", level, "reason",
                reason);
        return true;
    }
}

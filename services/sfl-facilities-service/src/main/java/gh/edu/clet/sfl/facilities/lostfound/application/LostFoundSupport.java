package gh.edu.clet.sfl.facilities.lostfound.application;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lostfound.application.ports.LostFoundEstatePort;
import gh.edu.clet.sfl.facilities.lostfound.application.ports.LostFoundIncidentPort;
import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfEscalation;
import gh.edu.clet.sfl.facilities.lostfound.domain.LfHistoryEntry;
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
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What every S179 operation needs in the same shape: who may do it, the audit entry, the history row, the event,
 * and the two things that must survive a refusal - an escalation and the record that a release was refused.
 *
 * <p>Both of those commit in a transaction of their own. A refused release throws, which rolls the caller's
 * transaction back; the SRS says the system must "block release and record the reason", and a reason recorded
 * inside the transaction that was just rolled back is no record at all.
 */
@Component
public class LostFoundSupport {

    private final LostFoundStore store;
    private final LostFoundEstatePort estate;
    private final LostFoundIncidentPort incidents;
    private final FacilitiesAuthorization authorization;
    private final AuditPort audit;
    private final ServiceOutbox outbox;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public LostFoundSupport(LostFoundStore store, LostFoundEstatePort estate, LostFoundIncidentPort incidents,
            FacilitiesAuthorization authorization, AuditPort audit, ServiceOutbox outbox, Clock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.estate = estate;
        this.incidents = incidents;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    public FoundItem item(UUID id) {
        return store.item(id).orElseThrow(() -> notFound("Found item", id));
    }

    public Claim claim(UUID id) {
        return store.claim(id).orElseThrow(() -> notFound("Claim", id));
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

    public void history(String site, String subjectType, UUID subjectId, String from, String to, String actor,
            String reason) {
        store.record(new LfHistoryEntry(UUID.randomUUID(), site, subjectType, subjectId, from, to, actor, reason,
                now()));
    }

    public void audit(Caller caller, AuditAction action, String type, UUID id, String site, Object before,
            Object after) {
        audit.record(caller.actor(), caller.channel(), action, type, id.toString(), site, before, after);
    }

    public void publish(String eventType, String aggregateType, UUID id, String site, ActorContext actor,
            Object... payload) {
        outbox.record(eventType, 1, aggregateType, id, site, actor.correlationId(), actor.actorId(),
                LostFoundEvents.payload(payload));
    }

    /** Raises an escalation once while it is unacknowledged. Commits on its own, so a refusal that follows cannot undo it. */
    public LfEscalation escalate(FoundItem item, UUID claimId, EscalationReason reason, String detail, Caller caller) {
        return requiresNew.execute(tx -> raise(item, claimId, reason, detail, caller));
    }

    /** The same, inside the caller's transaction, for escalations raised on a path that does not throw. */
    public LfEscalation escalateInline(FoundItem item, UUID claimId, EscalationReason reason, String detail,
            Caller caller) {
        return raise(item, claimId, reason, detail, caller);
    }

    private LfEscalation raise(FoundItem item, UUID claimId, EscalationReason reason, String detail, Caller caller) {
        boolean incident = reason == EscalationReason.UNSAFE_ITEM;
        LfEscalation escalation = new LfEscalation(UUID.randomUUID(), item.siteCode(), item.id(), claimId, reason,
                reason.escalatedTo(), detail, incident ? "PENDING_MANUAL" : "NOT_REQUIRED", null, now(), null, null);
        if (!store.insert(escalation)) {
            return null;
        }
        audit(caller, AuditAction.LF_ESCALATED, "LfEscalation", escalation.id(), item.siteCode(), null, escalation);
        publish(LostFoundEvents.ESCALATED, "LfEscalation", escalation.id(), item.siteCode(), caller.actor(),
                "escalationId", escalation.id(), "itemId", item.id(), "reference", item.reference(), "reason",
                reason, "escalatedTo", escalation.escalatedTo());
        if (incident) {
            incidents.request(item, detail, caller.actor());
        }
        return escalation;
    }

    /** Records that a release was refused, and why, in a transaction that survives the refusal. */
    public void recordRefusal(Claim claim, FoundItem item, String reasons, Caller caller) {
        requiresNew.executeWithoutResult(tx -> {
            history(claim.siteCode(), "CLAIM", claim.id(), claim.status().name(), claim.status().name(),
                    caller.actor().actorId(), "Release refused: " + reasons);
            audit(caller, AuditAction.LF_RELEASE_REFUSED, "Claim", claim.id(), claim.siteCode(), null, reasons);
        });
    }
}

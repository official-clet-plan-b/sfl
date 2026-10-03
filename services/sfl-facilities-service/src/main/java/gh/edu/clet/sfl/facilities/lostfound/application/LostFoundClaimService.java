package gh.edu.clet.sfl.facilities.lostfound.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lostfound.domain.Claim;
import gh.edu.clet.sfl.facilities.lostfound.domain.ClaimStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyChain;
import gh.edu.clet.sfl.facilities.lostfound.domain.CustodyEvent;
import gh.edu.clet.sfl.facilities.lostfound.domain.EscalationReason;
import gh.edu.clet.sfl.facilities.lostfound.domain.FoundItem;
import gh.edu.clet.sfl.facilities.lostfound.domain.ItemStatus;
import gh.edu.clet.sfl.facilities.lostfound.domain.ReleasePolicy;
import gh.edu.clet.sfl.facilities.lostfound.domain.VerificationMethod;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims, verification, approval and release - SRS-SFL-S179-03 and -04.
 *
 * <p>The sequence is receive, verify identity, approve, release. Three separations matter. Whoever verifies the
 * claimant does not approve the release; whoever approves does not hand the item over; and while any other
 * claim is open on the item, none of them can be released - the item is held pending investigation and the
 * competing claims are escalated to security. A release that is refused is refused <em>and recorded</em>, with
 * every blocker, in a transaction that survives the refusal.
 */
@Service
public class LostFoundClaimService {

    private final LostFoundStore store;
    private final LostFoundSupport support;
    private final TransactionTemplate inTransaction;

    public LostFoundClaimService(LostFoundStore store, LostFoundSupport support, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    public Claim receive(UUID itemId, String claimantName, String claimantContact, String claimantDescription,
            Caller caller) {
        return inTransaction.execute(tx -> {
            FoundItem item = support.item(itemId);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, item.siteCode(), "Claim", "new");
            if (item.status() == ItemStatus.ISOLATED) {
                throw new FacilitiesException(FacilitiesErrorCode.LF_ITEM_ISOLATED);
            }
            if (item.status() != ItemStatus.STORED && item.status() != ItemStatus.REGISTERED) {
                throw LostFoundSupport.invalid("This item is " + item.status() + "; it can no longer be claimed.");
            }
            Instant now = support.now();
            Claim claim = new Claim(UUID.randomUUID(), String.format("LF-C-%06d", store.nextSequence("lf_claim_seq")),
                    itemId, item.siteCode(), LostFoundSupport.required(claimantName, "claimantName"),
                    LostFoundSupport.required(claimantContact, "claimantContact"),
                    LostFoundSupport.blankToNull(claimantDescription), ClaimStatus.RECEIVED, false, null, null, null,
                    null, null, null, null, null, null, null, null, caller.actor().actorId(), now, now, 0);
            store.insert(claim);
            support.history(item.siteCode(), "CLAIM", claim.id(), null, "RECEIVED", caller.actor().actorId(), null);
            support.audit(caller, AuditAction.LF_CLAIM_RECEIVED, "Claim", claim.id(), item.siteCode(), null, claim.masked());
            if (!store.openClaimsExcept(itemId, claim.id()).isEmpty()) {
                support.escalateInline(item, claim.id(), EscalationReason.COMPETING_CLAIMS,
                        "More than one claim is open on " + item.reference(), caller);
            }
            return claim;
        });
    }

    /** Records that the claimant's identity was checked, how, and where the check is on file. No ID details are stored here. */
    public Claim verifyIdentity(UUID claimId, VerificationMethod method, String verificationReference,
            Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Claim before = manageable(claimId, caller);
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != ClaimStatus.RECEIVED) {
                throw LostFoundSupport.invalid("Only a newly received claim can be verified.");
            }
            if (method == null) {
                throw new IllegalArgumentException("method is required");
            }
            Instant now = support.now();
            Claim after = new Claim(before.id(), before.reference(), before.itemId(), before.siteCode(),
                    before.claimantName(), before.claimantContact(), before.claimantDescription(), ClaimStatus.VERIFIED,
                    true, method, LostFoundSupport.required(verificationReference, "verificationReference"),
                    caller.actor().actorId(), now, null, null, null, null, null, null, null, before.createdBy(),
                    before.createdAt(), now, before.version());
            return save(before, after, caller, "Identity verified by " + method);
        });
    }

    /** Approves the release. Competing claims block it; so does approving a claim you verified yourself. */
    public Claim approve(UUID claimId, String reason, Long expectedVersion, Caller caller) {
        Claim before = inTransaction.execute(tx -> {
            Claim claim = support.claim(claimId);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_APPROVE, claim.siteCode(), "Claim",
                    claimId.toString());
            LostFoundSupport.checkVersion(expectedVersion, claim.version());
            if (claim.status() != ClaimStatus.VERIFIED || !claim.identityVerified()) {
                throw LostFoundSupport.invalid("Only a claim whose identity has been verified can be approved.");
            }
            if (caller.actor().actorId().equals(claim.verifiedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.LF_SELF_APPROVAL, "The person who verified the claimant "
                        + "cannot also approve the release.");
            }
            return claim;
        });
        FoundItem item = support.item(before.itemId());
        if (!store.openClaimsExcept(before.itemId(), claimId).isEmpty()) {
            support.escalate(item, claimId, EscalationReason.COMPETING_CLAIMS,
                    "Approval attempted while other claims are open on " + item.reference(), caller);
            throw new FacilitiesException(FacilitiesErrorCode.LF_COMPETING_CLAIMS);
        }
        return inTransaction.execute(tx -> {
            Claim current = support.claim(claimId);
            Instant now = support.now();
            Claim after = new Claim(current.id(), current.reference(), current.itemId(), current.siteCode(),
                    current.claimantName(), current.claimantContact(), current.claimantDescription(),
                    ClaimStatus.APPROVED, current.identityVerified(), current.verificationMethod(),
                    current.verificationReference(), current.verifiedBy(), current.verifiedAt(),
                    LostFoundSupport.blankToNull(reason), caller.actor().actorId(), now, null, null, null, null,
                    current.createdBy(), current.createdAt(), now, current.version());
            return save(current, after, caller, "Release approved" + (reason == null ? "" : ": " + reason.strip()));
        });
    }

    /** Refuses a claim: verification failed, the description does not match, the claim is not credible. The reason is required and kept. */
    public Claim refuse(UUID claimId, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Claim before = manageable(claimId, caller);
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            if (!before.status().open()) {
                throw LostFoundSupport.invalid("This claim is already " + before.status() + ".");
            }
            return save(before, ItemClose.of(before, ClaimStatus.REFUSED, LostFoundSupport.required(reason, "reason"),
                    caller.actor().actorId(), support.now()), caller, "Refused: " + reason.strip());
        });
    }

    public Claim withdraw(UUID claimId, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Claim before = manageable(claimId, caller);
            LostFoundSupport.checkVersion(expectedVersion, before.version());
            if (!before.status().open()) {
                throw LostFoundSupport.invalid("This claim is already " + before.status() + ".");
            }
            return save(before, ItemClose.of(before, ClaimStatus.WITHDRAWN, LostFoundSupport.required(reason, "reason"),
                    caller.actor().actorId(), support.now()), caller, "Withdrawn: " + reason.strip());
        });
    }

    /**
     * Hands the item over. Every blocker {@link ReleasePolicy} finds is recorded - on the claim's history and
     * in the audit trail, in a transaction that survives the refusal - and the call answers 422. The claimant
     * must accept the item; a refusal at the desk is recorded and closes the claim, leaving the item in store.
     */
    public Claim release(UUID claimId, boolean accepted, String note, Long expectedVersion, Caller caller) {
        Claim claim = support.claim(claimId);
        FoundItem item = support.item(claim.itemId());
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, claim.siteCode(), "Claim", claimId.toString());
        LostFoundSupport.checkVersion(expectedVersion, claim.version());
        List<String> blockers = ReleasePolicy.blockers(item, claim, store.openClaimsExcept(item.id(), claimId),
                store.evidenceOf(item.id()));
        if (caller.actor().actorId().equals(claim.decidedBy()) && blockers.isEmpty()) {
            blockers = List.of("The person who approved the release cannot also hand the item over.");
        }
        if (!blockers.isEmpty()) {
            String reasons = String.join(" ", blockers);
            support.recordRefusal(claim, item, reasons, caller);
            if (!store.openClaimsExcept(item.id(), claimId).isEmpty()) {
                support.escalate(item, claimId, EscalationReason.COMPETING_CLAIMS,
                        "Release attempted while other claims are open on " + item.reference(), caller);
            }
            FacilitiesErrorCode code = !store.openClaimsExcept(item.id(), claimId).isEmpty()
                    ? FacilitiesErrorCode.LF_COMPETING_CLAIMS
                    : item.status() == ItemStatus.ISOLATED ? FacilitiesErrorCode.LF_ITEM_ISOLATED
                            : caller.actor().actorId().equals(claim.decidedBy()) && blockers.size() == 1
                                    && blockers.get(0).startsWith("The person who approved")
                                    ? FacilitiesErrorCode.LF_SELF_APPROVAL : FacilitiesErrorCode.LF_RELEASE_BLOCKED;
            throw new FacilitiesException(code, reasons);
        }
        return inTransaction.execute(tx -> {
            Claim current = support.claim(claimId);
            FoundItem currentItem = support.item(item.id());
            Instant now = support.now();
            if (!accepted) {
                return save(current, ItemClose.of(current, ClaimStatus.REFUSED, "The claimant declined the item at "
                        + "handover" + (note == null || note.isBlank() ? "" : ": " + note.strip()),
                        caller.actor().actorId(), now), caller, "Declined by the claimant at handover");
            }
            Claim released = new Claim(current.id(), current.reference(), current.itemId(), current.siteCode(),
                    current.claimantName(), current.claimantContact(), current.claimantDescription(),
                    ClaimStatus.RELEASED, true, current.verificationMethod(), current.verificationReference(),
                    current.verifiedBy(), current.verifiedAt(), current.decisionReason(), current.decidedBy(),
                    current.decidedAt(), caller.actor().actorId(), now, now, null, current.createdBy(),
                    current.createdAt(), now, current.version());
            Claim saved = save(current, released, caller, "Released to the claimant, who accepted it"
                    + (note == null || note.isBlank() ? "" : ": " + note.strip()));
            FoundItem closed = new FoundItem(currentItem.id(), currentItem.reference(), currentItem.claimReference(),
                    currentItem.siteCode(), currentItem.category(), currentItem.publicDescription(),
                    currentItem.privateDescription(), currentItem.foundLocation(), currentItem.foundAt(),
                    currentItem.finderReference(), currentItem.initialCondition(), ItemStatus.RELEASED,
                    currentItem.unsafe(), currentItem.unsafeReason(), currentItem.storageLocationId(),
                    currentItem.retentionUntil(), now, currentItem.createdBy(), currentItem.createdAt(), now,
                    currentItem.version());
            if (!store.update(closed, currentItem.version())) {
                throw LostFoundSupport.conflict();
            }
            support.history(closed.siteCode(), "ITEM", closed.id(), currentItem.status().name(), "RELEASED",
                    caller.actor().actorId(), "Released against claim " + saved.reference());
            List<CustodyEvent> chain = store.custody(closed.id());
            // Only the claim reference goes in the chain: the claimant's name stays on the claim, where it can be purged.
            store.insert(new CustodyEvent(UUID.randomUUID(), closed.id(), closed.siteCode(),
                    CustodyChain.currentHolder(chain), "Claimant " + saved.reference(), "Handover desk", now,
                    "Released after verification and approval", caller.actor().actorId()));
            support.audit(caller, AuditAction.LF_ITEM_RELEASED, "FoundItem", closed.id(), closed.siteCode(),
                    currentItem.masked(), closed.masked());
            support.publish(LostFoundEvents.ITEM_RELEASED, "FoundItem", closed.id(), closed.siteCode(), caller.actor(),
                    "itemId", closed.id(), "reference", closed.reference(), "claimReference", saved.reference(),
                    "withinRetention", !closed.retentionExpired(support.today()));
            return saved;
        });
    }

    // ---- reads

    public LostFoundStore.Page<Claim> list(String siteCode, String status, int page, int size, Caller caller) {
        String site = LostFoundSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, site, "Claim", "list");
        String statusFilter = LostFoundItemService.enumName(ClaimStatus.class, status);
        boolean privateRead = support.has(caller.actor(), SflPermission.FACILITIES_LOSTFOUND_PRIVATE_READ);
        LostFoundStore.Page<Claim> found = inTransaction.execute(tx -> store.claims(site, statusFilter,
                Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return privateRead ? found : new LostFoundStore.Page<>(found.items().stream().map(Claim::masked).toList(),
                found.total(), found.page(), found.size());
    }

    /**
     * What the claimant may see. The controlled description and the status always; the item's private detail
     * only once their identity has been verified - SRS "a claimant cannot see private identifying details
     * before verification".
     */
    public ClaimantView claimantView(UUID claimId, Caller caller) {
        return inTransaction.execute(tx -> {
            Claim claim = support.claim(claimId);
            support.require(caller, SflPermission.FACILITIES_LOSTFOUND_READ, claim.siteCode(), "Claim", claimId.toString());
            FoundItem item = support.item(claim.itemId());
            return new ClaimantView(claim.reference(), item.claimReference(), item.category(), item.publicDescription(),
                    claim.status(), claim.identityVerified(), claim.identityVerified() ? item.privateDescription() : null);
        });
    }

    public record ClaimantView(String claimRef, String itemClaimReference, gh.edu.clet.sfl.facilities.lostfound.domain.ItemCategory category,
            String publicDescription, ClaimStatus status, boolean identityVerified, String privateDescription) {
    }

    // ---- internals

    private Claim manageable(UUID id, Caller caller) {
        Claim claim = support.claim(id);
        support.require(caller, SflPermission.FACILITIES_LOSTFOUND_MANAGE, claim.siteCode(), "Claim", id.toString());
        return claim;
    }

    private Claim save(Claim before, Claim after, Caller caller, String reason) {
        if (!store.update(after, before.version())) {
            throw LostFoundSupport.conflict();
        }
        Claim saved = support.claim(after.id());
        support.history(saved.siteCode(), "CLAIM", saved.id(), before.status().name(), saved.status().name(),
                caller.actor().actorId(), reason);
        support.audit(caller, AuditAction.LF_CLAIM_UPDATED, "Claim", saved.id(), saved.siteCode(), before.masked(),
                saved.masked());
        return saved;
    }

    /** Builds the closed form of a claim. */
    private static final class ItemClose {
        static Claim of(Claim c, ClaimStatus status, String reason, String actor, Instant now) {
            return new Claim(c.id(), c.reference(), c.itemId(), c.siteCode(), c.claimantName(), c.claimantContact(),
                    c.claimantDescription(), status, c.identityVerified(), c.verificationMethod(),
                    c.verificationReference(), c.verifiedBy(), c.verifiedAt(), reason, actor, now, c.releasedBy(),
                    c.releasedAt(), now, c.personalDataPurgedAt(), c.createdBy(), c.createdAt(), now, c.version());
        }
    }
}

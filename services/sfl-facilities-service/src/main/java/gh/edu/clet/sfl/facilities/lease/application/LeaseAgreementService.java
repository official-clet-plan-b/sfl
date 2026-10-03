package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseOwnerPort;
import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseReferencePort;
import gh.edu.clet.sfl.facilities.lease.domain.ActivationPolicy;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementKind;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementStatus;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementVersion;
import gh.edu.clet.sfl.facilities.lease.domain.AlertLevel;
import gh.edu.clet.sfl.facilities.lease.domain.AlertReason;
import gh.edu.clet.sfl.facilities.lease.domain.Amendment;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentKind;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentPolicy;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentStatus;
import gh.edu.clet.sfl.facilities.lease.domain.CounterpartyState;
import gh.edu.clet.sfl.facilities.lease.domain.Direction;
import gh.edu.clet.sfl.facilities.lease.domain.DocumentKind;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseAlert;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseDocument;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseHistoryEntry;
import gh.edu.clet.sfl.facilities.lease.domain.NoticePolicy;
import gh.edu.clet.sfl.facilities.lease.domain.Obligation;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationKind;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationStatus;
import gh.edu.clet.sfl.facilities.lease.domain.RenewalType;
import gh.edu.clet.sfl.facilities.maintenance.domain.RetentionClass;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agreements, their approval, amendments, obligations and documents - SRS-SFL-S177-01, -02 and -04.
 *
 * <h2>What the workflow guarantees</h2>
 * <ul>
 *   <li><strong>An agreement is active only when it is complete and approved.</strong> Owning site, counterparty
 *       reference, owner, term, notice period and filed approval evidence are all required; approval comes from
 *       someone other than whoever put it forward.</li>
 *   <li><strong>Nothing material changes in place.</strong> Rent, term, renewal and termination are
 *       <em>amendments</em>: proposed, then approved by a third party. Until then the agreement is exactly as it
 *       was. Each approval writes a new version and leaves the old one intact, with who approved it.</li>
 *   <li><strong>A conflicting amendment is held, not applied.</strong> One that touches the same terms as another
 *       open change goes to legal review, and an alert tells legal.</li>
 *   <li><strong>The notice date is calculated, not typed.</strong> End date less notice period, moved back to a
 *       business day on the configured calendar.</li>
 * </ul>
 */
@Service
public class LeaseAgreementService {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final LeaseStore store;
    private final LeaseSupport support;
    private final LeaseReferencePort references;
    private final LeaseOwnerPort owners;
    private final TransactionTemplate inTransaction;

    public LeaseAgreementService(LeaseStore store, LeaseSupport support, LeaseReferencePort references, LeaseOwnerPort owners,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.references = references;
        this.owners = owners;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    public record Register(String siteCode, UUID roomId, String propertyReference, AgreementKind kind, Direction direction,
            String title, String counterpartyReference, String contractReference, String financeReference, String ownerReference,
            LocalDate startDate, LocalDate endDate, RenewalType renewalType, Integer renewalTermMonths, Integer noticeDays,
            LocalDate rentReviewDate, BigDecimal annualRent, BigDecimal depositAmount, String currency, Caller caller) {
    }

    public Agreement register(Register c) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(c.siteCode());
            support.require(c.caller(), SflPermission.FACILITIES_LEASE_MANAGE, site, "Agreement", "new");
            support.validateRoom(site, c.roomId());
            if (c.kind() == null || c.direction() == null || c.renewalType() == null) {
                throw new IllegalArgumentException("kind, direction and renewalType are required");
            }
            requireFinancialGrant(c.caller(), site, c.annualRent(), c.depositAmount(), c.currency());
            validateTerms(c.startDate(), c.endDate(), c.noticeDays(), c.renewalTermMonths(), c.annualRent(), c.depositAmount(), c.currency());
            Instant now = support.now();
            String counterparty = LeaseSupport.blankToNull(c.counterpartyReference());
            Agreement agreement = new Agreement(UUID.randomUUID(),
                    String.format("LS-A-%06d", store.nextSequence("lease_agreement_seq")), site, c.roomId(),
                    LeaseSupport.required(c.propertyReference(), "propertyReference"), c.kind(), c.direction(),
                    LeaseSupport.required(c.title(), "title"), counterparty, counterpartyState(counterparty),
                    LeaseSupport.blankToNull(c.contractReference()), LeaseSupport.blankToNull(c.financeReference()),
                    LeaseSupport.blankToNull(c.ownerReference()), c.startDate(), c.endDate(), c.renewalType(),
                    c.renewalTermMonths(), c.noticeDays(), NoticePolicy.noticeDate(c.endDate(), c.noticeDays(), support.calendar()),
                    c.rentReviewDate(), c.annualRent(), c.depositAmount(), c.currency() == null ? null : c.currency().toUpperCase(),
                    AgreementStatus.DRAFT, 1, null, null, null, null, c.caller().actor().actorId(), now, now, 0);
            store.insert(agreement);
            support.history(site, "AGREEMENT", agreement.id(), null, "DRAFT", c.caller().actor().actorId(), null);
            support.audit(c.caller(), AuditAction.LEASE_REGISTERED, "Agreement", agreement.id(), site, null, agreement.withoutFinancials());
            support.publish(LeaseEvents.REGISTERED, "Agreement", agreement.id(), site, c.caller().actor(), "agreementId",
                    agreement.id(), "reference", agreement.reference(), "kind", agreement.kind(), "endDate", agreement.endDate());
            return agreement;
        });
    }

    public record Update(UUID agreementId, String propertyReference, String title, String counterpartyReference,
            String contractReference, String financeReference, String ownerReference, LocalDate startDate, LocalDate endDate,
            RenewalType renewalType, Integer renewalTermMonths, Integer noticeDays, LocalDate rentReviewDate,
            BigDecimal annualRent, BigDecimal depositAmount, String currency, Long expectedVersion, Caller caller) {
    }

    /** A draft is edited in place; once submitted, a change is an amendment. */
    public Agreement update(Update c) {
        return inTransaction.execute(tx -> {
            Agreement before = manageable(c.agreementId(), c.caller());
            LeaseSupport.checkVersion(c.expectedVersion(), before.version());
            if (!before.status().editable()) {
                throw LeaseSupport.invalid("Only a draft agreement is edited in place; a " + before.status()
                        + " one changes by amendment.");
            }
            requireFinancialGrant(c.caller(), before.siteCode(), c.annualRent(), c.depositAmount(), c.currency());
            LocalDate start = c.startDate() == null ? before.startDate() : c.startDate();
            LocalDate end = c.endDate() == null ? before.endDate() : c.endDate();
            Integer notice = c.noticeDays() == null ? before.noticeDays() : c.noticeDays();
            Integer renewalMonths = c.renewalTermMonths() == null ? before.renewalTermMonths() : c.renewalTermMonths();
            BigDecimal rent = c.annualRent() == null ? before.annualRent() : c.annualRent();
            BigDecimal deposit = c.depositAmount() == null ? before.depositAmount() : c.depositAmount();
            String currency = c.currency() == null ? before.currency() : c.currency().toUpperCase();
            validateTerms(start, end, notice, renewalMonths, rent, deposit, currency);
            String counterparty = c.counterpartyReference() == null ? before.counterpartyReference()
                    : LeaseSupport.blankToNull(c.counterpartyReference());
            Agreement after = new Agreement(before.id(), before.reference(), before.siteCode(), before.roomId(),
                    c.propertyReference() == null ? before.propertyReference() : c.propertyReference().strip(), before.kind(),
                    before.direction(), c.title() == null ? before.title() : c.title().strip(), counterparty,
                    counterpartyState(counterparty), c.contractReference() == null ? before.contractReference() : LeaseSupport.blankToNull(c.contractReference()),
                    c.financeReference() == null ? before.financeReference() : LeaseSupport.blankToNull(c.financeReference()),
                    c.ownerReference() == null ? before.ownerReference() : LeaseSupport.blankToNull(c.ownerReference()), start, end,
                    c.renewalType() == null ? before.renewalType() : c.renewalType(), renewalMonths, notice,
                    NoticePolicy.noticeDate(end, notice, support.calendar()),
                    c.rentReviewDate() == null ? before.rentReviewDate() : c.rentReviewDate(), rent, deposit, currency,
                    before.status(), before.versionNumber(), before.requestedBy(), null, null, null, before.createdBy(),
                    before.createdAt(), support.now(), before.version());
            return save(before, after, c.caller(), "Draft updated", AuditAction.LEASE_UPDATED);
        });
    }

    public Agreement submit(UUID id, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement before = manageable(id, caller);
            LeaseSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, AgreementStatus.IN_REVIEW);
            return save(before, with(before, AgreementStatus.IN_REVIEW, caller.actor().actorId(), null, null, before.terminatedOn()),
                    caller, "Submitted for review", AuditAction.LEASE_UPDATED);
        });
    }

    public Agreement returnToDraft(UUID id, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement before = support.agreement(id);
            support.require(caller, SflPermission.FACILITIES_LEASE_APPROVE, before.siteCode(), "Agreement", id.toString());
            LeaseSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, AgreementStatus.DRAFT);
            return save(before, with(before, AgreementStatus.DRAFT, null, null, null, before.terminatedOn()), caller,
                    "Returned to draft: " + LeaseSupport.required(reason, "reason"), AuditAction.LEASE_UPDATED);
        });
    }

    /** Approval: every blocker named, a different person from whoever submitted it, and obligations generated from the terms. */
    public Agreement approve(UUID id, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement before = support.agreement(id);
            support.require(caller, SflPermission.FACILITIES_LEASE_APPROVE, before.siteCode(), "Agreement", id.toString());
            LeaseSupport.checkVersion(expectedVersion, before.version());
            requireMove(before, AgreementStatus.ACTIVE);
            if (caller.actor().actorId().equals(before.requestedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.LEASE_SELF_APPROVAL);
            }
            ActivationPolicy.Readiness readiness = ActivationPolicy.assess(before, store.documentsOf(id));
            if (!readiness.ready()) {
                throw new FacilitiesException(FacilitiesErrorCode.LEASE_NOT_READY, String.join(" ", readiness.blockers()));
            }
            Instant now = support.now();
            Agreement after = with(before, AgreementStatus.ACTIVE, before.requestedBy(), caller.actor().actorId(), now, before.terminatedOn());
            Agreement saved = save(before, after, caller, "Approved and activated", AuditAction.LEASE_APPROVED);
            snapshot(saved, null, caller);
            generateObligations(saved, caller);
            support.publish(LeaseEvents.APPROVED, "Agreement", id, saved.siteCode(), caller.actor(), "agreementId", id,
                    "reference", saved.reference(), "endDate", saved.endDate(), "noticeDate", saved.noticeDate());
            return saved;
        });
    }

    /** A change of internal owner is administrative, not an amendment: it moves nobody's money or time. It still writes a version. */
    public Agreement reassign(UUID id, String ownerReference, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement before = manageable(id, caller);
            LeaseSupport.checkVersion(expectedVersion, before.version());
            if (AgreementStatus.ENDED.contains(before.status())) {
                throw LeaseSupport.invalid("An ended agreement cannot be reassigned.");
            }
            String owner = LeaseSupport.required(ownerReference, "ownerReference");
            Agreement after = new Agreement(before.id(), before.reference(), before.siteCode(), before.roomId(),
                    before.propertyReference(), before.kind(), before.direction(), before.title(), before.counterpartyReference(),
                    before.counterpartyState(), before.contractReference(), before.financeReference(), owner,
                    before.startDate(), before.endDate(), before.renewalType(), before.renewalTermMonths(), before.noticeDays(),
                    before.noticeDate(), before.rentReviewDate(), before.annualRent(), before.depositAmount(), before.currency(),
                    before.status(), before.status() == AgreementStatus.ACTIVE ? before.versionNumber() + 1 : before.versionNumber(),
                    before.requestedBy(), before.approvedBy(), before.approvedAt(), before.terminatedOn(), before.createdBy(),
                    before.createdAt(), support.now(), before.version());
            Agreement saved = save(before, after, caller, "Owner changed to " + owner, AuditAction.LEASE_UPDATED);
            if (saved.status() == AgreementStatus.ACTIVE) {
                snapshot(saved, null, caller);
            }
            return saved;
        });
    }

    // ---- amendments

    public record Propose(UUID agreementId, AmendmentKind kind, LocalDate newEndDate, BigDecimal newAnnualRent,
            BigDecimal newDepositAmount, Integer newNoticeDays, LocalDate newRentReviewDate, Integer newRenewalTermMonths,
            LocalDate effectiveOn, String reason, Caller caller) {
    }

    public Amendment propose(Propose c) {
        return inTransaction.execute(tx -> {
            Agreement agreement = manageable(c.agreementId(), c.caller());
            if (agreement.status() != AgreementStatus.ACTIVE) {
                throw LeaseSupport.invalid("Only an active agreement can be amended; this one is " + agreement.status() + ".");
            }
            if (c.kind() == null) {
                throw new IllegalArgumentException("kind is required");
            }
            requireFinancialGrant(c.caller(), agreement.siteCode(), c.newAnnualRent(), c.newDepositAmount(), null);
            LocalDate newEnd = c.newEndDate();
            if (c.kind() == AmendmentKind.RENEWAL) {
                if (agreement.renewalType() == RenewalType.NONE) {
                    throw new IllegalArgumentException("This agreement has no renewal right; use a term change.");
                }
                Integer months = c.newRenewalTermMonths() != null ? c.newRenewalTermMonths() : agreement.renewalTermMonths();
                if (newEnd == null && months != null) {
                    newEnd = agreement.endDate().plusMonths(months);
                }
                if (newEnd == null) {
                    throw new IllegalArgumentException("A renewal needs a new end date or a renewal term in months.");
                }
            }
            validateAmendment(c.kind(), agreement, newEnd, c);
            Instant now = support.now();
            Amendment amendment = new Amendment(UUID.randomUUID(),
                    String.format("LS-M-%06d", store.nextSequence("lease_amendment_seq")), agreement.id(), agreement.siteCode(),
                    c.kind(), true, AmendmentStatus.PROPOSED, LeaseSupport.required(c.reason(), "reason"), newEnd,
                    c.newAnnualRent(), c.newDepositAmount(), c.newNoticeDays(), c.newRentReviewDate(),
                    c.newRenewalTermMonths(), c.effectiveOn(), agreement.versionNumber(), c.caller().actor().actorId(), now,
                    null, null, null, null, null, null, 0);
            boolean conflict = AmendmentPolicy.conflicts(amendment, store.openAmendmentsOf(agreement.id()));
            if (conflict) {
                amendment = held(amendment);
            }
            store.insert(amendment);
            support.history(agreement.siteCode(), "AMENDMENT", amendment.id(), null, amendment.status().name(),
                    c.caller().actor().actorId(), amendment.reason());
            support.audit(c.caller(), AuditAction.LEASE_AMENDMENT_PROPOSED, "Agreement", agreement.id(), agreement.siteCode(), null,
                    amendment.withoutFinancials());
            support.publish(LeaseEvents.AMENDMENT_PROPOSED, "Amendment", amendment.id(), agreement.siteCode(), c.caller().actor(),
                    "amendmentId", amendment.id(), "agreementId", agreement.id(), "kind", amendment.kind(), "status", amendment.status());
            if (conflict) {
                raiseAlert(agreement, null, AlertLevel.LEGAL, AlertReason.CONFLICTING_AMENDMENT,
                        "Amendment " + amendment.reference() + " conflicts with another open change", c.caller());
                support.audit(c.caller(), AuditAction.LEASE_AMENDMENT_LEGAL_REVIEW, "Agreement", agreement.id(), agreement.siteCode(),
                        null, amendment.reference());
            }
            return amendment;
        });
    }

    /**
     * Decides a proposed amendment. Approval writes a new version - the prior version stays, with its own approval -
     * applies the change, recomputes the notice date and regenerates the obligations. The proposer cannot approve.
     */
    public Amendment decide(UUID amendmentId, boolean approve, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Amendment before = store.amendment(amendmentId).orElseThrow(() -> LeaseSupport.notFound("Amendment", amendmentId));
            support.require(caller, SflPermission.FACILITIES_LEASE_APPROVE, before.siteCode(), "Amendment", amendmentId.toString());
            LeaseSupport.checkVersion(expectedVersion, before.version());
            if (before.status() == AmendmentStatus.LEGAL_REVIEW) {
                throw new FacilitiesException(FacilitiesErrorCode.LEASE_AMENDMENT_BLOCKED);
            }
            if (before.status() != AmendmentStatus.PROPOSED) {
                throw LeaseSupport.invalid("This amendment is " + before.status() + ".");
            }
            if (caller.actor().actorId().equals(before.proposedBy())) {
                throw new FacilitiesException(FacilitiesErrorCode.LEASE_SELF_APPROVAL);
            }
            if (!approve) {
                LeaseSupport.required(reason, "reason");
            }
            Agreement agreement = support.agreement(before.agreementId());
            if (approve) {
                if (agreement.status() != AgreementStatus.ACTIVE) {
                    throw LeaseSupport.invalid("The agreement is " + agreement.status() + " and can no longer be amended.");
                }
                if (agreement.versionNumber() != before.priorVersion()) {
                    throw new FacilitiesException(FacilitiesErrorCode.LEASE_AMENDMENT_BLOCKED, "The agreement has changed since this "
                            + "amendment was proposed (version " + before.priorVersion() + " to " + agreement.versionNumber()
                            + "); propose it again against the current terms.");
                }
                if (before.kind() == AmendmentKind.TERMINATION && store.documentsOf(agreement.id()).stream()
                        .noneMatch(d -> d.kind() == DocumentKind.TERMINATION_NOTICE)) {
                    throw new FacilitiesException(FacilitiesErrorCode.LEASE_NOT_READY, "A termination needs the termination notice filed first.");
                }
            }
            Instant now = support.now();
            Amendment after = new Amendment(before.id(), before.reference(), before.agreementId(), before.siteCode(), before.kind(),
                    before.material(), approve ? AmendmentStatus.APPROVED : AmendmentStatus.REJECTED, before.reason(),
                    before.newEndDate(), before.newAnnualRent(), before.newDepositAmount(), before.newNoticeDays(),
                    before.newRentReviewDate(), before.newRenewalTermMonths(), before.effectiveOn(), before.priorVersion(),
                    before.proposedBy(), before.proposedAt(), caller.actor().actorId(), now, LeaseSupport.blankToNull(reason),
                    before.legalReviewNote(), before.legalClearedBy(), before.legalClearedAt(), before.version());
            if (!store.update(after, before.version())) {
                throw LeaseSupport.conflict();
            }
            support.history(before.siteCode(), "AMENDMENT", before.id(), "PROPOSED", after.status().name(), caller.actor().actorId(), reason);
            support.audit(caller, AuditAction.LEASE_AMENDMENT_DECIDED, "Agreement", agreement.id(), agreement.siteCode(), before.withoutFinancials(),
                    after.withoutFinancials());
            if (approve) {
                apply(agreement, after, caller);
            }
            return store.amendment(amendmentId).orElseThrow();
        });
    }

    /** Legal clears an amendment that was held for conflicting with another; it returns to the approval queue. */
    public Amendment clearLegalReview(UUID amendmentId, String note, Caller caller) {
        return inTransaction.execute(tx -> {
            Amendment before = store.amendment(amendmentId).orElseThrow(() -> LeaseSupport.notFound("Amendment", amendmentId));
            support.require(caller, SflPermission.FACILITIES_LEASE_LEGAL_REVIEW, before.siteCode(), "Amendment", amendmentId.toString());
            if (before.status() != AmendmentStatus.LEGAL_REVIEW) {
                throw LeaseSupport.invalid("This amendment is not in legal review.");
            }
            Instant now = support.now();
            Amendment after = new Amendment(before.id(), before.reference(), before.agreementId(), before.siteCode(), before.kind(),
                    before.material(), AmendmentStatus.PROPOSED, before.reason(), before.newEndDate(), before.newAnnualRent(),
                    before.newDepositAmount(), before.newNoticeDays(), before.newRentReviewDate(), before.newRenewalTermMonths(),
                    before.effectiveOn(), before.priorVersion(), before.proposedBy(), before.proposedAt(), null, null, null,
                    LeaseSupport.required(note, "note"), caller.actor().actorId(), now, before.version());
            if (!store.update(after, before.version())) {
                throw LeaseSupport.conflict();
            }
            support.history(before.siteCode(), "AMENDMENT", before.id(), "LEGAL_REVIEW", "PROPOSED", caller.actor().actorId(), note);
            support.audit(caller, AuditAction.LEASE_AMENDMENT_LEGAL_REVIEW, "Agreement", before.agreementId(), before.siteCode(),
                    "LEGAL_REVIEW", "PROPOSED");
            return store.amendment(amendmentId).orElseThrow();
        });
    }

    public Amendment withdraw(UUID amendmentId, Caller caller) {
        return inTransaction.execute(tx -> {
            Amendment before = store.amendment(amendmentId).orElseThrow(() -> LeaseSupport.notFound("Amendment", amendmentId));
            support.require(caller, SflPermission.FACILITIES_LEASE_MANAGE, before.siteCode(), "Amendment", amendmentId.toString());
            if (!before.status().open()) {
                throw LeaseSupport.invalid("This amendment is " + before.status() + ".");
            }
            if (!caller.actor().actorId().equals(before.proposedBy())) {
                throw LeaseSupport.invalid("Only whoever proposed an amendment can withdraw it.");
            }
            Amendment after = new Amendment(before.id(), before.reference(), before.agreementId(), before.siteCode(), before.kind(),
                    before.material(), AmendmentStatus.WITHDRAWN, before.reason(), before.newEndDate(), before.newAnnualRent(),
                    before.newDepositAmount(), before.newNoticeDays(), before.newRentReviewDate(), before.newRenewalTermMonths(),
                    before.effectiveOn(), before.priorVersion(), before.proposedBy(), before.proposedAt(), caller.actor().actorId(),
                    support.now(), "Withdrawn by the proposer", before.legalReviewNote(), before.legalClearedBy(),
                    before.legalClearedAt(), before.version());
            if (!store.update(after, before.version())) {
                throw LeaseSupport.conflict();
            }
            support.history(before.siteCode(), "AMENDMENT", before.id(), before.status().name(), "WITHDRAWN", caller.actor().actorId(), null);
            return store.amendment(amendmentId).orElseThrow();
        });
    }

    // ---- documents and obligations

    public LeaseDocument fileDocument(UUID agreementId, DocumentKind kind, String reference, String fileName, String mediaType,
            long sizeBytes, String contentHash, LocalDate expiresOn, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement agreement = manageable(agreementId, caller);
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException("sizeBytes must be positive");
            }
            if (contentHash == null || !SHA_256.matcher(contentHash.strip()).matches()) {
                throw new IllegalArgumentException("contentHash must be a SHA-256 digest (64 hex characters)");
            }
            LeaseDocument document = new LeaseDocument(UUID.randomUUID(), agreementId, agreement.siteCode(), kind,
                    LeaseSupport.required(reference, "reference"), LeaseSupport.required(fileName, "fileName"),
                    LeaseSupport.required(mediaType, "mediaType"), sizeBytes, contentHash.strip().toLowerCase(),
                    RetentionClass.LEGAL.name(), expiresOn, caller.actor().actorId(), support.now());
            store.insert(document);
            support.history(agreement.siteCode(), "AGREEMENT", agreementId, agreement.status().name(), agreement.status().name(),
                    caller.actor().actorId(), "Document filed: " + kind);
            support.audit(caller, AuditAction.LEASE_DOCUMENT_FILED, "Agreement", agreementId, agreement.siteCode(), null, document);
            return document;
        });
    }

    public Obligation addObligation(UUID agreementId, ObligationKind kind, String title, LocalDate dueOn, String owner, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement agreement = manageable(agreementId, caller);
            if (kind == null || dueOn == null) {
                throw new IllegalArgumentException("kind and dueOn are required");
            }
            if (AgreementStatus.ENDED.contains(agreement.status())) {
                throw LeaseSupport.invalid("An ended agreement takes no new obligations.");
            }
            Instant now = support.now();
            Obligation obligation = new Obligation(UUID.randomUUID(), agreementId, agreement.siteCode(), kind,
                    LeaseSupport.required(title, "title"), dueOn, LeaseSupport.blankToNull(owner) == null ? agreement.ownerReference()
                            : owner.strip(), ObligationStatus.OPEN, null, null, null, false, caller.actor().actorId(), now, now, 0);
            store.insert(obligation);
            support.audit(caller, AuditAction.LEASE_OBLIGATION_UPDATED, "Agreement", agreementId, agreement.siteCode(), null, obligation);
            return obligation;
        });
    }

    public Obligation completeObligation(UUID obligationId, String note, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Obligation before = store.obligation(obligationId).orElseThrow(() -> LeaseSupport.notFound("Obligation", obligationId));
            support.require(caller, SflPermission.FACILITIES_LEASE_MANAGE, before.siteCode(), "Obligation", obligationId.toString());
            LeaseSupport.checkVersion(expectedVersion, before.version());
            if (!before.open()) {
                throw LeaseSupport.invalid("This obligation is already " + before.status() + ".");
            }
            return saveObligation(before, ObligationStatus.DONE, support.today(), caller.actor().actorId(), LeaseSupport.blankToNull(note),
                    caller, "Completed");
        });
    }

    /** Waiving an obligation - deciding it need not be done - takes an approver and a reason: otherwise it is a way to make an alert go away. */
    public Obligation waiveObligation(UUID obligationId, String reason, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Obligation before = store.obligation(obligationId).orElseThrow(() -> LeaseSupport.notFound("Obligation", obligationId));
            support.require(caller, SflPermission.FACILITIES_LEASE_APPROVE, before.siteCode(), "Obligation", obligationId.toString());
            LeaseSupport.checkVersion(expectedVersion, before.version());
            if (!before.open()) {
                throw LeaseSupport.invalid("This obligation is already " + before.status() + ".");
            }
            return saveObligation(before, ObligationStatus.WAIVED, null, caller.actor().actorId(),
                    LeaseSupport.required(reason, "reason"), caller, "Waived");
        });
    }

    // ---- reads

    public LeaseStore.Page<Agreement> list(String siteCode, String status, String owner, Integer endsWithinDays, int page, int size,
            Caller caller) {
        String site = LeaseSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LEASE_READ, site, "Agreement", "list");
        String statusFilter = enumName(AgreementStatus.class, status);
        boolean money = support.has(caller.actor(), SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
        LeaseStore.Page<Agreement> found = inTransaction.execute(tx -> store.agreements(site, statusFilter, LeaseSupport.blankToNull(owner),
                endsWithinDays, support.today(), Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return money ? found : new LeaseStore.Page<>(found.items().stream().map(Agreement::withoutFinancials).toList(), found.total(),
                found.page(), found.size());
    }

    public Detail get(UUID id, Caller caller) {
        return inTransaction.execute(tx -> {
            Agreement agreement = support.agreement(id);
            support.require(caller, SflPermission.FACILITIES_LEASE_READ, agreement.siteCode(), "Agreement", id.toString());
            boolean money = support.has(caller.actor(), SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
            List<LeaseDocument> documents = store.documentsOf(id);
            ActivationPolicy.Readiness readiness = ActivationPolicy.assess(agreement, documents);
            if (money && (agreement.annualRent() != null || agreement.depositAmount() != null)) {
                support.audit(caller, AuditAction.LEASE_FINANCIAL_VIEWED, "Agreement", id, agreement.siteCode(), null, "terms");
            }
            LocalDate today = support.today();
            List<String> warnings = new java.util.ArrayList<>(readiness.warnings());
            boolean ownerVerified = agreement.ownerReference() != null && owners.verifyOwner(agreement.ownerReference()).verified();
            if (agreement.ownerReference() != null && !ownerVerified) {
                warnings.add("The owner is recorded, not verified against HR (S140): no HR system is connected.");
            }
            return new Detail(money ? agreement : agreement.withoutFinancials(), readiness.blockers(), warnings,
                    documents.stream().map(d -> new DocumentView(d, d.expired(today))).toList(), store.obligationsOf(id),
                    store.amendmentsOf(id).stream().map(a -> money ? a : a.withoutFinancials()).toList(),
                    store.versions(id).stream().map(v -> money ? v : v.withoutFinancials()).toList(), store.alertsOf(id),
                    store.history(id), money, agreement.status().live() && agreement.endDate().isBefore(today), store.workOrdersOf(id), ownerVerified);
        });
    }

    public record DocumentView(LeaseDocument document, boolean expired) {
    }

    public record Detail(Agreement agreement, List<String> blockers, List<String> warnings, List<DocumentView> documents,
            List<Obligation> obligations, List<Amendment> amendments, List<AgreementVersion> versions, List<LeaseAlert> alerts,
            List<LeaseHistoryEntry> history, boolean financialView, boolean pastEndDate, List<LeaseWorkOrder> workOrders,
            boolean ownerVerified) {
    }

    public LeaseStore.Page<Amendment> amendments(String siteCode, String status, int page, int size, Caller caller) {
        String site = LeaseSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LEASE_READ, site, "Amendment", "list");
        String statusFilter = enumName(AmendmentStatus.class, status);
        boolean money = support.has(caller.actor(), SflPermission.FACILITIES_LEASE_FINANCIAL_READ);
        LeaseStore.Page<Amendment> found = inTransaction.execute(tx -> store.amendments(site, statusFilter, Math.max(0, page),
                Math.min(Math.max(1, size), 100)));
        return money ? found : new LeaseStore.Page<>(found.items().stream().map(Amendment::withoutFinancials).toList(), found.total(),
                found.page(), found.size());
    }

    public LeaseStore.Page<Obligation> obligations(String siteCode, String status, Integer dueWithinDays, int page, int size, Caller caller) {
        String site = LeaseSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_LEASE_READ, site, "Obligation", "list");
        String statusFilter = enumName(ObligationStatus.class, status);
        return inTransaction.execute(tx -> store.obligations(site, statusFilter, dueWithinDays, support.today(), Math.max(0, page),
                Math.min(Math.max(1, size), 100)));
    }

    // ---- internals

    /** Rent is not shown to a role without the financial grant, so it may not be set by one either: it would be writing blind. */
    private void requireFinancialGrant(Caller caller, String site, Object... money) {
        for (Object value : money) {
            if (value != null) {
                support.require(caller, SflPermission.FACILITIES_LEASE_FINANCIAL_READ, site, "Agreement", "financial terms");
                return;
            }
        }
    }

    private void validateTerms(LocalDate start, LocalDate end, Integer noticeDays, Integer renewalMonths, BigDecimal rent,
            BigDecimal deposit, String currency) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("endDate must be after startDate");
        }
        if (noticeDays != null && (noticeDays < 0 || noticeDays > 1095)) {
            throw new IllegalArgumentException("noticeDays must be between 0 and 1095");
        }
        if (renewalMonths != null && renewalMonths <= 0) {
            throw new IllegalArgumentException("renewalTermMonths must be positive");
        }
        if ((rent != null && rent.signum() < 0) || (deposit != null && deposit.signum() < 0)) {
            throw new IllegalArgumentException("rent and deposit cannot be negative");
        }
        if ((rent != null || deposit != null) && (currency == null || !currency.matches("[A-Za-z]{3}"))) {
            throw new IllegalArgumentException("A three-letter currency code is required with a rent or deposit.");
        }
    }

    private void validateAmendment(AmendmentKind kind, Agreement agreement, LocalDate newEnd, Propose c) {
        switch (kind) {
            case RENT_CHANGE -> {
                if (c.newAnnualRent() == null && c.newDepositAmount() == null) {
                    throw new IllegalArgumentException("A rent change needs a new annual rent or a new deposit.");
                }
                if ((c.newAnnualRent() != null && c.newAnnualRent().signum() < 0) || (c.newDepositAmount() != null
                        && c.newDepositAmount().signum() < 0)) {
                    throw new IllegalArgumentException("rent and deposit cannot be negative");
                }
                if (agreement.currency() == null) {
                    throw new IllegalArgumentException("The agreement has no currency; set one before changing rent.");
                }
            }
            case TERM_CHANGE -> {
                if (newEnd == null && c.newNoticeDays() == null && c.newRentReviewDate() == null && c.newRenewalTermMonths() == null) {
                    throw new IllegalArgumentException("A term change needs at least one new term.");
                }
                if (newEnd != null && !newEnd.isAfter(agreement.startDate())) {
                    throw new IllegalArgumentException("The new end date must be after the start date.");
                }
            }
            case RENEWAL -> {
                if (newEnd == null || !newEnd.isAfter(agreement.endDate())) {
                    throw new IllegalArgumentException("A renewal must end after the current end date.");
                }
            }
            case TERMINATION -> {
                if (c.effectiveOn() == null) {
                    throw new IllegalArgumentException("A termination needs the date it takes effect.");
                }
            }
        }
    }

    private Amendment held(Amendment a) {
        return new Amendment(a.id(), a.reference(), a.agreementId(), a.siteCode(), a.kind(), a.material(), AmendmentStatus.LEGAL_REVIEW,
                a.reason(), a.newEndDate(), a.newAnnualRent(), a.newDepositAmount(), a.newNoticeDays(), a.newRentReviewDate(),
                a.newRenewalTermMonths(), a.effectiveOn(), a.priorVersion(), a.proposedBy(), a.proposedAt(), null, null, null,
                "Held: conflicts with another open amendment", null, null, a.version());
    }

    /** Applies an approved amendment: a new version, the new terms, a recomputed notice date and regenerated obligations. */
    private void apply(Agreement agreement, Amendment amendment, Caller caller) {
        Instant now = support.now();
        if (amendment.kind() == AmendmentKind.TERMINATION) {
            LocalDate effective = amendment.effectiveOn();
            Agreement terminated = new Agreement(agreement.id(), agreement.reference(), agreement.siteCode(), agreement.roomId(),
                    agreement.propertyReference(), agreement.kind(), agreement.direction(), agreement.title(),
                    agreement.counterpartyReference(), agreement.counterpartyState(), agreement.contractReference(),
                    agreement.financeReference(), agreement.ownerReference(), agreement.startDate(), agreement.endDate(),
                    agreement.renewalType(), agreement.renewalTermMonths(), agreement.noticeDays(), agreement.noticeDate(),
                    agreement.rentReviewDate(), agreement.annualRent(), agreement.depositAmount(), agreement.currency(),
                    AgreementStatus.TERMINATED, agreement.versionNumber() + 1, agreement.requestedBy(), agreement.approvedBy(),
                    agreement.approvedAt(), effective, agreement.createdBy(), agreement.createdAt(), now, agreement.version());
            Agreement saved = save(agreement, terminated, caller, "Terminated effective " + effective, AuditAction.LEASE_TERMINATED);
            snapshot(saved, amendment, caller);
            store.cancelGeneratedOpen(saved.id(), now);
            support.publish(LeaseEvents.TERMINATED, "Agreement", saved.id(), saved.siteCode(), caller.actor(), "agreementId",
                    saved.id(), "reference", saved.reference(), "effectiveOn", effective);
            return;
        }
        LocalDate end = amendment.newEndDate() != null ? amendment.newEndDate() : agreement.endDate();
        Integer notice = amendment.newNoticeDays() != null ? amendment.newNoticeDays() : agreement.noticeDays();
        Agreement amended = new Agreement(agreement.id(), agreement.reference(), agreement.siteCode(), agreement.roomId(),
                agreement.propertyReference(), agreement.kind(), agreement.direction(), agreement.title(),
                agreement.counterpartyReference(), agreement.counterpartyState(), agreement.contractReference(),
                agreement.financeReference(), agreement.ownerReference(), agreement.startDate(), end, agreement.renewalType(),
                amendment.newRenewalTermMonths() != null ? amendment.newRenewalTermMonths() : agreement.renewalTermMonths(), notice,
                NoticePolicy.noticeDate(end, notice, support.calendar()),
                amendment.newRentReviewDate() != null ? amendment.newRentReviewDate() : agreement.rentReviewDate(),
                amendment.newAnnualRent() != null ? amendment.newAnnualRent() : agreement.annualRent(),
                amendment.newDepositAmount() != null ? amendment.newDepositAmount() : agreement.depositAmount(), agreement.currency(),
                agreement.status(), agreement.versionNumber() + 1, agreement.requestedBy(), agreement.approvedBy(),
                agreement.approvedAt(), agreement.terminatedOn(), agreement.createdBy(), agreement.createdAt(), now, agreement.version());
        Agreement saved = save(agreement, amended, caller, "Amended (" + amendment.reference() + ")", AuditAction.LEASE_UPDATED);
        snapshot(saved, amendment, caller);
        if (amendment.kind() == AmendmentKind.RENEWAL) {
            store.obligationsOf(saved.id()).stream().filter(o -> o.open() && o.generated() && o.kind() == ObligationKind.RENEWAL)
                    .forEach(o -> saveObligation(o, ObligationStatus.DONE, support.today(), caller.actor().actorId(),
                            "Renewal approved (" + amendment.reference() + ")", caller, "Completed by approved renewal"));
        }
        store.cancelGeneratedOpen(saved.id(), now);
        generateObligations(saved, caller);
        support.publish(LeaseEvents.AMENDED, "Agreement", saved.id(), saved.siteCode(), caller.actor(), "agreementId", saved.id(),
                "reference", saved.reference(), "amendmentId", amendment.id(), "kind", amendment.kind(), "endDate", saved.endDate(),
                "noticeDate", saved.noticeDate(), "version", saved.versionNumber());
    }

    /** Generates the obligations the terms imply: notice, renewal and rent review, each due on a date the calendar allows. */
    void generateObligations(Agreement a, Caller caller) {
        Instant now = support.now();
        List<Obligation> generated = new ArrayList<>();
        if (a.noticeDate() != null) {
            generated.add(obligation(a, ObligationKind.NOTICE, "Serve or receive notice for " + a.reference(), a.noticeDate(), now, caller));
            if (a.renewalType() != RenewalType.NONE) {
                generated.add(obligation(a, ObligationKind.RENEWAL, "Decide on renewal of " + a.reference(), a.noticeDate(), now, caller));
            }
        }
        if (a.rentReviewDate() != null) {
            generated.add(obligation(a, ObligationKind.RENT_REVIEW, "Rent review for " + a.reference(),
                    support.calendar().onOrBefore(a.rentReviewDate()), now, caller));
        }
        generated.forEach(store::insert);
    }

    private Obligation obligation(Agreement a, ObligationKind kind, String title, LocalDate due, Instant now, Caller caller) {
        return new Obligation(UUID.randomUUID(), a.id(), a.siteCode(), kind, title, due, a.ownerReference(), ObligationStatus.OPEN, null,
                null, null, true, caller.actor().actorId(), now, now, 0);
    }

    private Obligation saveObligation(Obligation before, ObligationStatus status, LocalDate completedOn, String by, String note,
            Caller caller, String reason) {
        Obligation after = new Obligation(before.id(), before.agreementId(), before.siteCode(), before.kind(), before.title(),
                before.dueOn(), before.ownerReference(), status, completedOn, by, note, before.generated(), before.createdBy(),
                before.createdAt(), support.now(), before.version());
        if (!store.update(after, before.version())) {
            throw LeaseSupport.conflict();
        }
        support.history(before.siteCode(), "OBLIGATION", before.id(), before.status().name(), status.name(), by, note == null ? reason : note);
        support.audit(caller, AuditAction.LEASE_OBLIGATION_UPDATED, "Agreement", before.agreementId(), before.siteCode(), before.status(), status);
        return store.obligation(before.id()).orElseThrow();
    }

    private void raiseAlert(Agreement a, UUID obligationId, AlertLevel level, AlertReason reason, String detail, Caller caller) {
        LeaseAlert alert = new LeaseAlert(UUID.randomUUID(), a.siteCode(), a.id(), obligationId, level, reason, detail, support.now(), null, null);
        if (store.insert(alert)) {
            support.audit(caller, AuditAction.LEASE_ALERT_RAISED, "Agreement", a.id(), a.siteCode(), null, alert);
            support.publish(LeaseEvents.ALERT_RAISED, "Agreement", a.id(), a.siteCode(), caller.actor(), "agreementId", a.id(),
                    "reference", a.reference(), "level", level, "reason", reason);
        }
    }

    private void snapshot(Agreement a, Amendment amendment, Caller caller) {
        store.insert(new AgreementVersion(UUID.randomUUID(), a.id(), a.siteCode(), a.versionNumber(), a.endDate(), a.annualRent(),
                a.depositAmount(), a.noticeDays(), a.rentReviewDate(), a.renewalTermMonths(), a.ownerReference(),
                a.counterpartyReference(), a.status(), amendment == null ? null : amendment.id(),
                amendment == null ? a.approvedBy() : amendment.decidedBy(), amendment == null ? a.approvedAt() : amendment.decidedAt(),
                caller.actor().actorId(), support.now()));
    }

    private CounterpartyState counterpartyState(String counterparty) {
        if (counterparty == null) {
            return CounterpartyState.UNRESOLVED;
        }
        LeaseReferencePort.Verification check = references.verifyCounterparty(counterparty);
        return check.available() && check.verified() ? CounterpartyState.VERIFIED : CounterpartyState.UNRESOLVED;
    }

    private Agreement manageable(UUID id, Caller caller) {
        Agreement agreement = support.agreement(id);
        support.require(caller, SflPermission.FACILITIES_LEASE_MANAGE, agreement.siteCode(), "Agreement", id.toString());
        return agreement;
    }

    private static void requireMove(Agreement agreement, AgreementStatus next) {
        if (!agreement.status().canMoveTo(next)) {
            throw new FacilitiesException(FacilitiesErrorCode.INVALID_STATE_TRANSITION,
                    "A " + agreement.status() + " agreement cannot become " + next + ".");
        }
    }

    private Agreement with(Agreement b, AgreementStatus status, String requestedBy, String approvedBy, Instant approvedAt, LocalDate terminatedOn) {
        return new Agreement(b.id(), b.reference(), b.siteCode(), b.roomId(), b.propertyReference(), b.kind(), b.direction(), b.title(),
                b.counterpartyReference(), b.counterpartyState(), b.contractReference(), b.financeReference(), b.ownerReference(),
                b.startDate(), b.endDate(), b.renewalType(), b.renewalTermMonths(), b.noticeDays(), b.noticeDate(), b.rentReviewDate(),
                b.annualRent(), b.depositAmount(), b.currency(), status, b.versionNumber(), requestedBy, approvedBy, approvedAt,
                terminatedOn, b.createdBy(), b.createdAt(), support.now(), b.version());
    }

    private Agreement save(Agreement before, Agreement after, Caller caller, String reason, AuditAction action) {
        if (!store.update(after, before.version())) {
            throw LeaseSupport.conflict();
        }
        Agreement saved = support.agreement(after.id());
        support.history(saved.siteCode(), "AGREEMENT", saved.id(), before.status().name(), saved.status().name(), caller.actor().actorId(), reason);
        support.audit(caller, action, "Agreement", saved.id(), saved.siteCode(), before.withoutFinancials(), saved.withoutFinancials());
        return saved;
    }

    static <E extends Enum<E>> String enumName(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase()).name();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }
}

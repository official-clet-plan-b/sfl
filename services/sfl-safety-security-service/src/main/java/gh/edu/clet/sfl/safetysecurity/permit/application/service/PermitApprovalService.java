package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRiskAssessmentPort;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalDecision;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalPurpose;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.CompetencyCheck;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ApprovalPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.CompetencyPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.SubmissionPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S164-02: isolation verification by a named verifier who is not the requester, competence checks, and multi-level
 * approval. Verification, each approval and each rejection are separate acts, each individually audited; a refused approval is
 * recorded with the SRS's name for it. A permit becomes active only when its last required stage approves.
 */
@Service
public class PermitApprovalService {

    private final PermitRepository repository;
    private final PermitRecords records;
    private final PermitRecorder recorder;
    private final PermitAccessPolicy access;
    private final PermitRiskAssessmentPort assessments;
    private final PermitEvents events;

    public PermitApprovalService(PermitRepository repository, PermitRecords records, PermitRecorder recorder, PermitAccessPolicy access,
            PermitRiskAssessmentPort assessments, PermitEvents events) {
        this.repository = repository;
        this.records = records;
        this.recorder = recorder;
        this.access = access;
        this.assessments = assessments;
        this.events = events;
    }

    // ---- verification -----------------------------------------------------------------------------

    @Transactional
    public PermitDetail verifyIsolation(UUID permitId, UUID isolationId, String tagReference, String note, Caller caller) {
        Permit permit = verifierFor(permitId, caller, PermitStatus.SUBMITTED);
        Isolation isolation = repository.findIsolation(isolationId).filter(i -> i.permitId().equals(permitId))
                .orElseThrow(() -> PermitException.notFound("Isolation", isolationId));
        if (isolation.status() != IsolationStatus.REQUIRED) {
            throw PermitException.invalidState(isolation.status().name(), IsolationStatus.VERIFIED.name());
        }
        Instant now = recorder.now();
        Isolation verified = repository.updateIsolation(new Isolation(isolation.id(), permitId, isolation.siteCode(), isolation.kind(), isolation.description(),
                PermitRequestService.blank(tagReference) != null ? tagReference.strip() : isolation.tagReference(), IsolationStatus.VERIFIED, caller.id(), now,
                PermitRequestService.blank(note), null, null, null, isolation.createdAt()));
        recorder.history(permit, permit.status().name(), permit.status().name(), "ISOLATION_VERIFIED", caller.id(), isolation.kind() + ": " + isolation.description());
        recorder.audit(caller, "PERMIT_ISOLATION_VERIFIED", permit, isolation, verified, note);
        return records.detail(permit);
    }

    /** The explicit verification step the SRS requires: recorded by a named verifier, once every required isolation is verified. */
    @Transactional
    public PermitDetail completeVerification(UUID permitId, String note, Long expectedVersion, Caller caller) {
        Permit before = verifierFor(permitId, caller, PermitStatus.SUBMITTED);
        PermitRequestService.checkVersion(before, expectedVersion);
        PermitType type = records.type(before);
        List<Isolation> isolations = repository.isolations(permitId);
        if (type.requiresIsolation() && isolations.isEmpty()) {
            throw new PermitException(PermitErrorCode.PERMIT_ISOLATIONS_REQUIRED, Map.of("permitType", type.code()));
        }
        ApprovalPolicy.requireIsolationsVerified(isolations);
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.ISOLATION_VERIFIED).updatedAt(recorder.now()).build(), before.version());
        recorder.history(after, "SUBMITTED", "ISOLATION_VERIFIED", "ISOLATION_VERIFICATION_COMPLETE", caller.id(),
                PermitRequestService.blank(note) == null ? isolations.size() + " isolation(s) verified" : note.strip());
        recorder.audit(caller, "PERMIT_ISOLATION_VERIFICATION_COMPLETE", after, before, after, note);
        return records.detail(after);
    }

    @Transactional
    public PermitDetail recordCompetency(UUID permitId, UUID workerId, String competencyCode, boolean competent, String evidenceReference, LocalDate validUntil,
            String note, Caller caller) {
        Permit permit = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_VERIFY_ISOLATION, permit.siteCode(), "Permit", permitId.toString());
        requireIndependent(permit, caller, PermitErrorCode.PERMIT_VERIFIER_NOT_INDEPENDENT);
        if (permit.status() != PermitStatus.SUBMITTED && permit.status() != PermitStatus.ISOLATION_VERIFIED && permit.status() != PermitStatus.STAGE1_APPROVED
                && permit.status() != PermitStatus.RESUMPTION_PENDING) {
            throw PermitException.invalidState(permit.status().name(), "COMPETENCY_CHECKED");
        }
        PermitWorker worker = repository.findWorker(workerId).filter(w -> w.permitId().equals(permitId)).orElseThrow(() -> PermitException.notFound("PermitWorker", workerId));
        PermitType type = records.type(permit);
        String code = PermitRequestService.required(competencyCode, "competencyCode").toUpperCase(java.util.Locale.ROOT);
        if (type.requiredCompetencies().stream().noneMatch(c -> c.equalsIgnoreCase(code))) {
            throw PermitException.invalid(type.name() + " does not check the competence " + code);
        }
        CompetencyCheck check = repository.insertCheck(new CompetencyCheck(UUID.randomUUID(), permitId, permit.siteCode(), worker.id(), code, competent,
                PermitRequestService.blank(evidenceReference), validUntil, caller.id(), recorder.now(), PermitRequestService.blank(note)));
        recorder.history(permit, permit.status().name(), permit.status().name(), "COMPETENCY_RECORDED", caller.id(),
                worker.displayName() + " " + code + (competent ? " competent" : " NOT competent"));
        recorder.audit(caller, "PERMIT_COMPETENCY_RECORDED", permit, null, check, note);
        return records.detail(permit);
    }

    // ---- approval ---------------------------------------------------------------------------------

    /**
     * Approves the stage the permit is waiting on: the issuing authority first, then - for a higher-risk type - an independent
     * safety sign-off. Refuses, with the SRS's name for it, an approval by the requester, an approval before isolations are verified,
     * a second stage by the first stage's person, and an approval while the assessment is not current or a worker's competence is not
     * recorded. When the last stage approves the permit becomes active for its validity window and S176 is told.
     */
    @Transactional
    public PermitDetail approve(UUID permitId, Long expectedVersion, String conditions, String comment, Caller caller) {
        Permit before = records.require(permitId);
        PermitType type = records.type(before);
        ApprovalPurpose purpose = before.status() == PermitStatus.RESUMPTION_PENDING ? ApprovalPurpose.RESUME : ApprovalPurpose.ISSUE;
        List<PermitApproval> round = records.thisRound(before, purpose, null);
        ApprovalStage stage = ApprovalPolicy.nextStage(type, round).orElseThrow(() -> PermitException.invalidState(before.status().name(), "APPROVED"));
        access.require(caller.actor(), stage == ApprovalStage.ISSUING_AUTHORITY ? SflPermission.PERMIT_APPROVE : SflPermission.PERMIT_SAFETY_SIGN_OFF,
                before.siteCode(), "Permit", permitId.toString());
        PermitRequestService.checkVersion(before, expectedVersion);
        Instant now = recorder.now();
        RiskAssessmentStanding standing = before.riskAssessmentId() == null ? RiskAssessmentStanding.notLinked()
                : assessments.standing(before.riskAssessmentId(), before.siteCode(), now);
        try {
            ApprovalPolicy.require(before, type, stage, caller.id(), round, repository.isolations(permitId));
            SubmissionPolicy.requireRiskAssessment(type, standing);
            List<CompetencyPolicy.Exception> exceptions = records.competencyExceptions(before, type, before.endsAt());
            if (!exceptions.isEmpty()) {
                throw new PermitException(PermitErrorCode.PERMIT_COMPETENCY_EXCEPTION, Map.of("workers", exceptions.stream()
                        .map(e -> e.worker().displayName() + " - " + e.competency() + " (" + e.reason() + ")").toList()));
            }
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_APPROVAL", refused.errorCode().code(), stage + " " + refused.details());
            throw refused;
        }
        PermitApproval approval = repository.insertApproval(new PermitApproval(UUID.randomUUID(), permitId, before.siteCode(), purpose, null,
                before.approvalRound(), stage, ApprovalDecision.APPROVED, caller.id(), now, PermitRequestService.blank(conditions), PermitRequestService.blank(comment)));
        boolean complete = ApprovalPolicy.nextStage(type, append(round, approval)).isEmpty();
        Permit.Builder next = before.toBuilder().updatedAt(now).riskAssessmentReference(standing.reference()).riskAssessmentVersion(standing.version())
                .riskLevel(standing.riskLevel()).riskReviewDueAt(standing.reviewDueAt());
        Permit after;
        if (complete) {
            after = repository.updatePermit(next.status(PermitStatus.ACTIVE).statusReason(null).issuedAt(before.issuedAt() == null ? now : before.issuedAt()).build(), before.version());
            if (purpose == ApprovalPurpose.RESUME) {
                repository.markResumed(permitId, now);
            }
            events.issued(after, now, caller.actor());
        } else {
            after = repository.updatePermit(next.status(purpose == ApprovalPurpose.ISSUE ? PermitStatus.STAGE1_APPROVED : PermitStatus.RESUMPTION_PENDING).build(), before.version());
        }
        recorder.history(after, before.status().name(), after.status().name(), "PERMIT_" + stage + "_APPROVED", caller.id(),
                PermitRequestService.blank(conditions) == null ? PermitRequestService.blank(comment) : "Conditions: " + conditions.strip());
        recorder.audit(caller, "PERMIT_" + stage + "_APPROVED", after, before, approval, conditions);
        return records.detail(after);
    }

    /** Rejects the permit at the stage it is waiting on, with a reason. A rejected resumption leaves the permit suspended. */
    @Transactional
    public PermitDetail reject(UUID permitId, String reason, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        PermitType type = records.type(before);
        if (!before.status().preIssue() && before.status() != PermitStatus.RESUMPTION_PENDING || before.status() == PermitStatus.DRAFT) {
            throw PermitException.invalidState(before.status().name(), "REJECTED");
        }
        ApprovalPurpose purpose = before.status() == PermitStatus.RESUMPTION_PENDING ? ApprovalPurpose.RESUME : ApprovalPurpose.ISSUE;
        List<PermitApproval> round = records.thisRound(before, purpose, null);
        ApprovalStage stage = ApprovalPolicy.nextStage(type, round).orElse(ApprovalStage.ISSUING_AUTHORITY);
        access.require(caller.actor(), stage == ApprovalStage.ISSUING_AUTHORITY ? SflPermission.PERMIT_APPROVE : SflPermission.PERMIT_SAFETY_SIGN_OFF,
                before.siteCode(), "Permit", permitId.toString());
        PermitRequestService.checkVersion(before, expectedVersion);
        if (before.requestedBy().equals(caller.id())) {
            recorder.refusal(caller, before, "PERMIT_REJECTION", PermitErrorCode.PERMIT_SELF_APPROVAL.code(), null);
            throw new PermitException(PermitErrorCode.PERMIT_SELF_APPROVAL, Map.of("permit", before.reference()));
        }
        String why = PermitRequestService.reason(reason);
        Instant now = recorder.now();
        PermitApproval rejection = repository.insertApproval(new PermitApproval(UUID.randomUUID(), permitId, before.siteCode(), purpose, null,
                before.approvalRound(), stage, ApprovalDecision.REJECTED, caller.id(), now, null, why));
        PermitStatus to = purpose == ApprovalPurpose.RESUME ? PermitStatus.SUSPENDED : PermitStatus.REJECTED;
        Permit after = repository.updatePermit(before.toBuilder().status(to).statusReason(why).updatedAt(now).build(), before.version());
        recorder.history(after, before.status().name(), to.name(), "PERMIT_" + stage + "_REJECTED", caller.id(), why);
        recorder.audit(caller, "PERMIT_" + stage + "_REJECTED", after, before, rejection, why);
        return records.detail(after);
    }

    // ---- helpers

    private Permit verifierFor(UUID permitId, Caller caller, PermitStatus expected) {
        Permit permit = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_VERIFY_ISOLATION, permit.siteCode(), "Permit", permitId.toString());
        requireIndependent(permit, caller, PermitErrorCode.PERMIT_VERIFIER_NOT_INDEPENDENT);
        if (permit.status() != expected) {
            throw PermitException.invalidState(permit.status().name(), expected.name());
        }
        return permit;
    }

    private void requireIndependent(Permit permit, Caller caller, PermitErrorCode code) {
        if (permit.requestedBy().equals(caller.id())) {
            recorder.refusal(caller, permit, "PERMIT_VERIFICATION", code.code(), null);
            throw new PermitException(code, Map.of("permit", permit.reference()));
        }
    }

    private static List<PermitApproval> append(List<PermitApproval> list, PermitApproval one) {
        List<PermitApproval> all = new java.util.ArrayList<>(list);
        all.add(one);
        return all;
    }
}

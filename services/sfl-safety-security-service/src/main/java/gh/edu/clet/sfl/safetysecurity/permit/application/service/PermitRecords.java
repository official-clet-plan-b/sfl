package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRiskAssessmentPort;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads a permit and assembles its detail: the read side every S164 service shares. */
@Component
public class PermitRecords {

    private final PermitRepository repository;
    private final PermitRiskAssessmentPort assessments;
    private final PermitRecorder recorder;

    public PermitRecords(PermitRepository repository, PermitRiskAssessmentPort assessments, PermitRecorder recorder) {
        this.repository = repository;
        this.assessments = assessments;
        this.recorder = recorder;
    }

    public Permit require(UUID id) {
        return repository.findPermit(id).orElseThrow(() -> PermitException.notFound("Permit", id));
    }

    public PermitType type(Permit permit) {
        return repository.findType(permit.permitTypeId()).orElseThrow(() -> PermitException.notFound("PermitType", permit.permitTypeId()));
    }

    /** The approvals recorded for the permit's current round of this purpose. */
    public List<PermitApproval> thisRound(Permit permit, ApprovalPurpose purpose, UUID refId) {
        return repository.approvals(permit.id()).stream().filter(a -> a.purpose() == purpose && a.approvalRound() == roundOf(permit, purpose)
                && java.util.Objects.equals(a.refId(), refId)).toList();
    }

    private static int roundOf(Permit permit, ApprovalPurpose purpose) {
        return purpose == ApprovalPurpose.EXTEND ? 1 : permit.approvalRound();
    }

    public List<CompetencyPolicy.Exception> competencyExceptions(Permit permit, PermitType type, Instant through) {
        return CompetencyPolicy.exceptions(type, repository.workers(permit.id()), repository.checks(permit.id()), through);
    }

    public PermitDetail detail(Permit permit) {
        PermitType type = type(permit);
        Instant now = recorder.now();
        List<PermitWorker> workers = repository.workers(permit.id());
        List<CompetencyCheck> checks = repository.checks(permit.id());
        List<Isolation> isolations = repository.isolations(permit.id());
        List<PermitApproval> approvals = repository.approvals(permit.id());
        List<PermitDetail.CompetencyException> exceptions = CompetencyPolicy.exceptions(type, workers, checks, permit.endsAt()).stream()
                .map(e -> new PermitDetail.CompetencyException(e.worker().id(), e.worker().displayName(), e.competency(), e.reason().name())).toList();
        RiskAssessmentStanding standing = permit.riskAssessmentId() == null ? RiskAssessmentStanding.notLinked()
                : assessments.standing(permit.riskAssessmentId(), permit.siteCode(), now);
        ApprovalStage next = permit.status() == PermitStatus.ISOLATION_VERIFIED || permit.status() == PermitStatus.STAGE1_APPROVED
                || permit.status() == PermitStatus.RESUMPTION_PENDING
                        ? ApprovalPolicy.nextStage(type, thisRound(permit, permit.status() == PermitStatus.RESUMPTION_PENDING
                                ? ApprovalPurpose.RESUME : ApprovalPurpose.ISSUE, null)).orElse(null)
                : null;

        List<String> blockers = new ArrayList<>();
        long unverified = isolations.stream().filter(i -> i.status() == IsolationStatus.REQUIRED).count();
        if (permit.status() == PermitStatus.SUBMITTED) {
            if (unverified > 0) {
                blockers.add(unverified + " isolation(s) still need verification by someone other than the requester.");
            } else {
                blockers.add("Isolation verification has not been completed and recorded.");
            }
        }
        boolean beingApproved = permit.status() == PermitStatus.SUBMITTED || permit.status() == PermitStatus.ISOLATION_VERIFIED
                || permit.status() == PermitStatus.STAGE1_APPROVED || permit.status() == PermitStatus.RESUMPTION_PENDING;
        if (beingApproved) {
            if (!exceptions.isEmpty()) {
                blockers.add(exceptions.size() + " competence check(s) are missing, failed or expired for the workers named.");
            }
            if (type.riskAssessmentRequired() && !standing.current()) {
                blockers.add("The linked risk assessment is not current (" + standing.reason() + ").");
            }
        }
        if (permit.status() == PermitStatus.WORK_COMPLETE) {
            long toRemove = isolations.stream().filter(i -> i.status() != IsolationStatus.REMOVED).count();
            if (toRemove > 0) {
                blockers.add(toRemove + " isolation(s) still need a recorded removal before the permit can be closed.");
            }
        }
        List<String> unverifiedRefs = new ArrayList<>();
        unverifiedRefs.add("Location " + permit.locationCode() + " is an S152 reference recorded here, not checked.");
        if (permit.contractorReference() != null) {
            unverifiedRefs.add("Contractor " + permit.contractorReference() + " is recorded, not verified against the vendor master (S133).");
        }
        if (permit.originReference() != null) {
            unverifiedRefs.add("Originating work " + permit.originReference() + " (" + permit.originSystem() + ") is recorded, not verified.");
        }
        return new PermitDetail(permit, type, workers, checks, exceptions, isolations, approvals, next, repository.extensions(permit.id()),
                repository.suspensions(permit.id()), repository.notifications(permit.id()), repository.evidence(permit.id()),
                repository.flags(permit.id()), repository.escalations(permit.id()), repository.history(permit.id()), blockers, standing,
                permit.overdue(now), unverifiedRefs);
    }
}

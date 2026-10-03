package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRiskAssessmentPort;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalDecision;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalPurpose;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EvidenceKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ExtensionStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.FlagType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEvidence;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitExtension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitFlag;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitNotification;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitSuspension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ApprovalPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ClosurePolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.CompetencyPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.ExtensionPolicy;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.SubmissionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S164-03 and -04: what happens to a permit once it is issued - suspension that tells the people on it as part of the
 * same act, resumption only through a fresh round of approval, extension checked like a new request, and a close-out in which
 * completing the work and removing the isolations are separate acts by different people.
 */
@Service
public class PermitLifecycleService {

    private final PermitRepository repository;
    private final PermitRecords records;
    private final PermitRecorder recorder;
    private final PermitAccessPolicy access;
    private final PermitRiskAssessmentPort assessments;
    private final PermitEvents events;

    public PermitLifecycleService(PermitRepository repository, PermitRecords records, PermitRecorder recorder, PermitAccessPolicy access,
            PermitRiskAssessmentPort assessments, PermitEvents events) {
        this.repository = repository;
        this.records = records;
        this.recorder = recorder;
        this.access = access;
        this.assessments = assessments;
        this.events = events;
    }

    // ---- suspension and resumption (S164-03) ------------------------------------------------------

    /**
     * Suspends an active permit, immediately: the reason is recorded, the supervisor and every worker on the permit are queued for
     * notification in the same transaction, and S176 is told. There is no way to suspend without notifying - it is not a separate step.
     */
    @Transactional
    public PermitDetail suspend(UUID permitId, String reason, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_SUSPEND, before.siteCode(), "Permit", permitId.toString());
        PermitRequestService.checkVersion(before, expectedVersion);
        if (before.status() != PermitStatus.ACTIVE) {
            throw PermitException.invalidState(before.status().name(), PermitStatus.SUSPENDED.name());
        }
        String why = PermitRequestService.reason(reason);
        Instant now = recorder.now();
        PermitSuspension suspension = repository.insertSuspension(new PermitSuspension(UUID.randomUUID(), permitId, before.siteCode(), caller.id(), now, why, null));
        repository.insertNotification(note(before, suspension, before.supervisorReference(), null, "SUPERVISOR", now));
        for (PermitWorker worker : repository.workers(permitId)) {
            repository.insertNotification(note(before, suspension, worker.personReference(), worker.displayName(), "WORKER", now));
        }
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.SUSPENDED).statusReason(why).updatedAt(now).build(), before.version());
        recorder.history(after, "ACTIVE", "SUSPENDED", "PERMIT_SUSPENDED", caller.id(), why);
        recorder.audit(caller, "PERMIT_SUSPENDED", after, before, after, why);
        events.suspended(after, why, now, caller.actor());
        return records.detail(after);
    }

    /**
     * Resumption is a fresh approval, never an un-suspend: this opens the next round, and the permit becomes active again only when
     * every stage approves it afresh. The checks that could have lapsed - the assessment's currency, the isolations - are made now
     * too, so a request that cannot succeed is refused at once.
     */
    @Transactional
    public PermitDetail requestResumption(UUID permitId, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        boolean requester = before.requestedBy().equals(caller.id()) && access.has(caller.actor(), SflPermission.PERMIT_REQUEST);
        if (requester) {
            access.require(caller.actor(), SflPermission.PERMIT_REQUEST, before.siteCode(), "Permit", permitId.toString());
        } else {
            access.require(caller.actor(), SflPermission.PERMIT_SUSPEND, before.siteCode(), "Permit", permitId.toString());
        }
        PermitRequestService.checkVersion(before, expectedVersion);
        if (before.status() != PermitStatus.SUSPENDED) {
            throw PermitException.invalidState(before.status().name(), PermitStatus.RESUMPTION_PENDING.name());
        }
        PermitType type = records.type(before);
        try {
            SubmissionPolicy.requireRiskAssessment(type, standing(before));
            ApprovalPolicy.requireIsolationsVerified(repository.isolations(permitId));
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_RESUMPTION", refused.errorCode().code(), refused.details().toString());
            throw refused;
        }
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.RESUMPTION_PENDING).approvalRound(before.approvalRound() + 1)
                .updatedAt(recorder.now()).build(), before.version());
        recorder.history(after, "SUSPENDED", "RESUMPTION_PENDING", "PERMIT_RESUMPTION_REQUESTED", caller.id(), "Fresh approval round " + after.approvalRound());
        recorder.audit(caller, "PERMIT_RESUMPTION_REQUESTED", after, before, after, null);
        return records.detail(after);
    }

    // ---- extension (S164-03) ----------------------------------------------------------------------

    /** An extension follows the same currency checks as a new request: assessment current, isolations standing, competence valid through the new end. */
    @Transactional
    public PermitDetail requestExtension(UUID permitId, Instant newEndsAt, String reason, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_REQUEST, before.siteCode(), "Permit", permitId.toString());
        if (!before.requestedBy().equals(caller.id())) {
            throw PermitException.unauthorizedScope(before.siteCode(), "Permit", permitId.toString());
        }
        PermitRequestService.checkVersion(before, expectedVersion);
        PermitType type = records.type(before);
        if (repository.extensions(permitId).stream().anyMatch(e -> e.status() == ExtensionStatus.PENDING)) {
            throw new PermitException(PermitErrorCode.PERMIT_EXTENSION_PENDING);
        }
        String why = PermitRequestService.reason(reason);
        try {
            ExtensionPolicy.require(before, type, newEndsAt);
            SubmissionPolicy.requireRiskAssessment(type, standing(before));
            ApprovalPolicy.requireIsolationsVerified(repository.isolations(permitId));
            List<CompetencyPolicy.Exception> exceptions = records.competencyExceptions(before, type, newEndsAt);
            if (!exceptions.isEmpty()) {
                throw new PermitException(PermitErrorCode.PERMIT_COMPETENCY_EXCEPTION, Map.of("workers", exceptions.stream()
                        .map(e -> e.worker().displayName() + " - " + e.competency() + " (" + e.reason() + ")").toList()));
            }
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_EXTENSION", refused.errorCode().code(), refused.details().toString());
            throw refused;
        }
        PermitExtension extension = repository.insertExtension(new PermitExtension(UUID.randomUUID(), permitId, before.siteCode(), caller.id(), recorder.now(),
                before.endsAt(), newEndsAt, why, ExtensionStatus.PENDING, null, null, null));
        recorder.history(before, before.status().name(), before.status().name(), "PERMIT_EXTENSION_REQUESTED", caller.id(), "to " + newEndsAt + ": " + why);
        recorder.audit(caller, "PERMIT_EXTENSION_REQUESTED", before, null, extension, why);
        return records.detail(before);
    }

    /** Decides the pending extension in the same stages as an issue; the permit's validity moves only when the last stage approves. */
    @Transactional
    public PermitDetail decideExtension(UUID permitId, UUID extensionId, boolean approve, String note, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        PermitType type = records.type(before);
        PermitExtension extension = repository.findExtension(extensionId).filter(e -> e.permitId().equals(permitId))
                .orElseThrow(() -> PermitException.notFound("PermitExtension", extensionId));
        if (extension.status() != ExtensionStatus.PENDING) {
            throw PermitException.invalidState(extension.status().name(), approve ? "APPROVED" : "REJECTED");
        }
        List<PermitApproval> round = records.thisRound(before, ApprovalPurpose.EXTEND, extensionId);
        ApprovalStage stage = ApprovalPolicy.nextStage(type, round).orElseThrow(() -> PermitException.invalidState(extension.status().name(), "APPROVED"));
        access.require(caller.actor(), stage == ApprovalStage.ISSUING_AUTHORITY ? SflPermission.PERMIT_APPROVE : SflPermission.PERMIT_SAFETY_SIGN_OFF,
                before.siteCode(), "Permit", permitId.toString());
        PermitRequestService.checkVersion(before, expectedVersion);
        Instant now = recorder.now();
        if (!approve) {
            String why = PermitRequestService.reason(note);
            if (before.requestedBy().equals(caller.id()) || extension.requestedBy().equals(caller.id())) {
                throw new PermitException(PermitErrorCode.PERMIT_SELF_APPROVAL);
            }
            repository.insertApproval(new PermitApproval(UUID.randomUUID(), permitId, before.siteCode(), ApprovalPurpose.EXTEND, extensionId, 1, stage,
                    ApprovalDecision.REJECTED, caller.id(), now, null, why));
            repository.updateExtension(new PermitExtension(extensionId, permitId, extension.siteCode(), extension.requestedBy(), extension.requestedAt(),
                    extension.previousEndsAt(), extension.newEndsAt(), extension.reason(), ExtensionStatus.REJECTED, caller.id(), now, why));
            recorder.history(before, before.status().name(), before.status().name(), "PERMIT_EXTENSION_REJECTED", caller.id(), why);
            recorder.audit(caller, "PERMIT_EXTENSION_REJECTED", before, extension, null, why);
            return records.detail(before);
        }
        try {
            ApprovalPolicy.requireExtensionStep(before, type, stage, caller.id(), extension.requestedBy(), round, repository.isolations(permitId));
            SubmissionPolicy.requireRiskAssessment(type, standing(before));
            List<CompetencyPolicy.Exception> exceptions = records.competencyExceptions(before, type, extension.newEndsAt());
            if (!exceptions.isEmpty()) {
                throw new PermitException(PermitErrorCode.PERMIT_COMPETENCY_EXCEPTION);
            }
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_EXTENSION_APPROVAL", refused.errorCode().code(), stage + " " + refused.details());
            throw refused;
        }
        PermitApproval approval = repository.insertApproval(new PermitApproval(UUID.randomUUID(), permitId, before.siteCode(), ApprovalPurpose.EXTEND, extensionId, 1,
                stage, ApprovalDecision.APPROVED, caller.id(), now, null, PermitRequestService.blank(note)));
        List<PermitApproval> after = new ArrayList<>(round);
        after.add(approval);
        Permit permit = before;
        if (ApprovalPolicy.nextStage(type, after).isEmpty()) {
            repository.updateExtension(new PermitExtension(extensionId, permitId, extension.siteCode(), extension.requestedBy(), extension.requestedAt(),
                    extension.previousEndsAt(), extension.newEndsAt(), extension.reason(), ExtensionStatus.APPROVED, caller.id(), now, PermitRequestService.blank(note)));
            permit = repository.updatePermit(before.toBuilder().endsAt(extension.newEndsAt()).updatedAt(now).build(), before.version());
            events.extended(permit, now, caller.actor());
        }
        recorder.history(permit, before.status().name(), permit.status().name(), "PERMIT_EXTENSION_" + stage + "_APPROVED", caller.id(), PermitRequestService.blank(note));
        recorder.audit(caller, "PERMIT_EXTENSION_" + stage + "_APPROVED", permit, before, approval, note);
        return records.detail(permit);
    }

    // ---- close-out (S164-04) ----------------------------------------------------------------------

    @Transactional
    public PermitDetail addEvidence(UUID permitId, EvidenceKind kind, String reference, String fileName, String mediaType, long sizeBytes, String contentHash,
            Caller caller) {
        Permit permit = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_REQUEST, permit.siteCode(), "Permit", permitId.toString());
        if (permit.status() != PermitStatus.ACTIVE && permit.status() != PermitStatus.SUSPENDED && permit.status() != PermitStatus.WORK_COMPLETE) {
            throw PermitException.invalidState(permit.status().name(), "EVIDENCE_FILED");
        }
        if (sizeBytes <= 0) {
            throw PermitException.invalid("sizeBytes must be positive");
        }
        PermitEvidence evidence = repository.insertEvidence(new PermitEvidence(UUID.randomUUID(), permitId, permit.siteCode(), kind == null ? EvidenceKind.OTHER : kind,
                PermitRequestService.required(reference, "reference"), PermitRequestService.required(fileName, "fileName"), PermitRequestService.required(mediaType, "mediaType"),
                sizeBytes, PermitRequestService.required(contentHash, "contentHash"), "SAFETY_CRITICAL", caller.id(), recorder.now()));
        recorder.history(permit, permit.status().name(), permit.status().name(), "PERMIT_EVIDENCE_FILED", caller.id(), evidence.fileName());
        recorder.audit(caller, "PERMIT_EVIDENCE_FILED", permit, null, evidence, null);
        return records.detail(permit);
    }

    /** Work is complete: a statement and evidence. Isolations are still in place - removing them is the next, separate act. */
    @Transactional
    public PermitDetail completeWork(UUID permitId, String statement, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_REQUEST, before.siteCode(), "Permit", permitId.toString());
        PermitRequestService.checkVersion(before, expectedVersion);
        try {
            ClosurePolicy.requireCanComplete(before, statement, repository.evidence(permitId).size());
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_WORK_COMPLETION", refused.errorCode().code(), null);
            throw refused;
        }
        Instant now = recorder.now();
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.WORK_COMPLETE).completionStatement(statement.strip()).workCompletedAt(now)
                .workCompletedBy(caller.id()).updatedAt(now).build(), before.version());
        recorder.history(after, before.status().name(), "WORK_COMPLETE", "PERMIT_WORK_COMPLETED", caller.id(), statement.strip());
        recorder.audit(caller, "PERMIT_WORK_COMPLETED", after, before, after, statement);
        return records.detail(after);
    }

    /** Records the removal of one isolation, by a competent person who is not the requester - the two-person rule at issuance, mirrored. */
    @Transactional
    public PermitDetail removeIsolation(UUID permitId, UUID isolationId, String note, Caller caller) {
        Permit permit = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_VERIFY_ISOLATION, permit.siteCode(), "Permit", permitId.toString());
        Isolation isolation = repository.findIsolation(isolationId).filter(i -> i.permitId().equals(permitId))
                .orElseThrow(() -> PermitException.notFound("Isolation", isolationId));
        try {
            ClosurePolicy.requireCanRemove(permit, isolation, caller.id());
        } catch (PermitException refused) {
            recorder.refusal(caller, permit, "PERMIT_ISOLATION_REMOVAL", refused.errorCode().code(), null);
            throw refused;
        }
        Isolation removed = repository.updateIsolation(new Isolation(isolation.id(), permitId, isolation.siteCode(), isolation.kind(), isolation.description(),
                isolation.tagReference(), IsolationStatus.REMOVED, isolation.verifiedBy(), isolation.verifiedAt(), isolation.verificationNote(), caller.id(),
                recorder.now(), PermitRequestService.blank(note), isolation.createdAt()));
        recorder.history(permit, permit.status().name(), permit.status().name(), "ISOLATION_REMOVED", caller.id(), isolation.kind() + ": " + isolation.description());
        recorder.audit(caller, "PERMIT_ISOLATION_REMOVED", permit, isolation, removed, note);
        return records.detail(permit);
    }

    /** Closes the permit - only when every isolation has a recorded removal. */
    @Transactional
    public PermitDetail close(UUID permitId, Long expectedVersion, Caller caller) {
        Permit before = records.require(permitId);
        boolean requester = before.requestedBy().equals(caller.id()) && access.has(caller.actor(), SflPermission.PERMIT_REQUEST);
        if (requester) {
            access.require(caller.actor(), SflPermission.PERMIT_REQUEST, before.siteCode(), "Permit", permitId.toString());
        } else {
            access.require(caller.actor(), SflPermission.PERMIT_APPROVE, before.siteCode(), "Permit", permitId.toString());
        }
        PermitRequestService.checkVersion(before, expectedVersion);
        try {
            ClosurePolicy.requireCanClose(before, repository.isolations(permitId));
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_CLOSE", refused.errorCode().code(), refused.details().toString());
            throw refused;
        }
        Instant now = recorder.now();
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.CLOSED).closedAt(now).closedBy(caller.id()).updatedAt(now).build(), before.version());
        recorder.history(after, "WORK_COMPLETE", "CLOSED", "PERMIT_CLOSED", caller.id(), null);
        recorder.audit(caller, "PERMIT_CLOSED", after, before, after, null);
        events.closed(after, now, caller.actor());
        return records.detail(after);
    }

    // ---- flags (S164-03, -05) ---------------------------------------------------------------------

    /** Records that the flag has been looked at - by an authoriser or the SOC. It does not suspend; suspension is its own, deliberate act. */
    @Transactional
    public PermitDetail reviewFlag(UUID permitId, UUID flagId, String note, Caller caller) {
        Permit permit = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_SUSPEND, permit.siteCode(), "Permit", permitId.toString());
        PermitFlag flag = repository.findFlag(flagId).filter(f -> f.permitId().equals(permitId)).orElseThrow(() -> PermitException.notFound("PermitFlag", flagId));
        if (!flag.open()) {
            throw PermitException.invalidState(flag.status(), "REVIEWED");
        }
        String why = PermitRequestService.reason(note);
        PermitFlag reviewed = repository.updateFlag(new PermitFlag(flag.id(), permitId, flag.siteCode(), flag.flagType(), flag.reference(), flag.detail(), "REVIEWED",
                flag.raisedAt(), caller.id(), recorder.now(), why));
        recorder.history(permit, permit.status().name(), permit.status().name(), "PERMIT_FLAG_REVIEWED", caller.id(), flag.flagType() + " " + flag.reference() + ": " + why);
        recorder.audit(caller, "PERMIT_FLAG_REVIEWED", permit, flag, reviewed, why);
        return records.detail(permit);
    }

    /** Links a permit to an S163 incident by hand - for the investigation record where the automatic match could not know. */
    @Transactional
    public PermitDetail linkIncident(UUID permitId, String incidentReference, String detail, Caller caller) {
        Permit permit = records.require(permitId);
        access.require(caller.actor(), SflPermission.PERMIT_SUSPEND, permit.siteCode(), "Permit", permitId.toString());
        String ref = PermitRequestService.required(incidentReference, "incidentReference");
        PermitFlag flag = new PermitFlag(UUID.randomUUID(), permitId, permit.siteCode(), FlagType.INCIDENT, ref, PermitRequestService.blank(detail), "OPEN", recorder.now(),
                null, null, null);
        if (repository.insertFlag(flag)) {
            recorder.history(permit, permit.status().name(), permit.status().name(), "PERMIT_FLAGGED_INCIDENT", caller.id(), ref);
            recorder.audit(caller, "PERMIT_FLAGGED_INCIDENT", permit, null, flag, detail);
            events.flagged(permit, FlagType.INCIDENT.name(), ref, recorder.now(), caller.actor());
        }
        return records.detail(permit);
    }

    // ---- helpers

    private RiskAssessmentStanding standing(Permit permit) {
        return permit.riskAssessmentId() == null ? RiskAssessmentStanding.notLinked()
                : assessments.standing(permit.riskAssessmentId(), permit.siteCode(), recorder.now());
    }

    private static PermitNotification note(Permit permit, PermitSuspension suspension, String recipient, String name, String role, Instant now) {
        return new PermitNotification(UUID.randomUUID(), permit.id(), permit.siteCode(), suspension.id(), recipient, name, role, "QUEUED", now);
    }
}

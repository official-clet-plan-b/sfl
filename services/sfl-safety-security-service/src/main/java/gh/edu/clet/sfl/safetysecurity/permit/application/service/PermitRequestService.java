package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRepository;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitRiskAssessmentPort;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitZonePort;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.OriginSystem;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.WorkRole;
import gh.edu.clet.sfl.safetysecurity.permit.domain.policy.SubmissionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S164-01: request a permit for high-risk work, with the applicable risk assessment attached. The request is
 * assembled as a draft - workers, isolations, the assessment - and submitted only if S165 says the assessment is current;
 * a refusal names which of the two SRS error states it is and is recorded even though the submission is not.
 */
@Service
public class PermitRequestService {

    private final PermitRepository repository;
    private final PermitRecords records;
    private final PermitRecorder recorder;
    private final PermitAccessPolicy access;
    private final PermitRiskAssessmentPort assessments;
    private final PermitZonePort zones;

    public PermitRequestService(PermitRepository repository, PermitRecords records, PermitRecorder recorder, PermitAccessPolicy access,
            PermitRiskAssessmentPort assessments, PermitZonePort zones) {
        this.repository = repository;
        this.records = records;
        this.recorder = recorder;
        this.access = access;
        this.assessments = assessments;
        this.zones = zones;
    }

    public record WorkerInput(String personReference, String displayName, WorkRole workRole) {
    }

    public record IsolationInput(IsolationKind kind, String description, String tagReference) {
    }

    public record PermitInput(UUID permitTypeId, String title, String workDescription, String locationCode, UUID zoneId, Instant startsAt,
            Instant endsAt, UUID riskAssessmentId, String contractorReference, String supervisorReference, String supervisorContact,
            OriginSystem originSystem, String originReference) {
    }

    @Transactional
    public PermitDetail create(String siteCode, PermitInput in, List<WorkerInput> workers, List<IsolationInput> isolations, Caller caller) {
        String site = site(siteCode);
        access.require(caller.actor(), SflPermission.PERMIT_REQUEST, site, "Permit", null);
        PermitType type = repository.findType(in.permitTypeId()).orElseThrow(() -> PermitException.notFound("PermitType", in.permitTypeId()));
        if (!type.active()) {
            throw new PermitException(PermitErrorCode.PERMIT_TYPE_INACTIVE);
        }
        SubmissionPolicy.requireWindow(type, in.startsAt(), in.endsAt());
        PermitZonePort.Zone zone = zone(in.zoneId(), site);
        Instant now = recorder.now();
        OriginSystem origin = origin(in.originSystem(), in.originReference());
        Permit permit = repository.insertPermit(new Permit(UUID.randomUUID(), site, String.format("PTW-%06d", repository.nextReference()), type.id(),
                type.code(), required(in.title(), "title"), required(in.workDescription(), "workDescription"), required(in.locationCode(), "locationCode"),
                zone == null ? null : zone.id(), zone == null ? null : zone.zoneCode(), in.startsAt(), in.endsAt(), PermitStatus.DRAFT, null,
                in.riskAssessmentId(), null, null, null, null, blank(in.contractorReference()), required(in.supervisorReference(), "supervisorReference"),
                blank(in.supervisorContact()), origin, origin == OriginSystem.NONE ? null : blank(in.originReference()), 1, caller.id(), null, null, null,
                null, null, null, null, caller.id(), now, now, 0));
        if (workers != null) {
            workers.forEach(w -> insertWorker(permit, w, now));
        }
        if (isolations != null) {
            isolations.forEach(i -> insertIsolation(permit, i, now));
        }
        recorder.history(permit, null, "DRAFT", "PERMIT_DRAFTED", caller.id(), null);
        recorder.audit(caller, "PERMIT_DRAFTED", permit, null, permit, null);
        return records.detail(permit);
    }

    /** Edits a draft - only its requester, only before submission. */
    @Transactional
    public PermitDetail reviseDraft(UUID id, PermitInput in, Long expectedVersion, Caller caller) {
        Permit before = draftOfRequester(id, caller);
        checkVersion(before, expectedVersion);
        PermitType type = in.permitTypeId() == null ? records.type(before) : repository.findType(in.permitTypeId())
                .orElseThrow(() -> PermitException.notFound("PermitType", in.permitTypeId()));
        SubmissionPolicy.requireWindow(type, in.startsAt(), in.endsAt());
        PermitZonePort.Zone zone = zone(in.zoneId(), before.siteCode());
        OriginSystem origin = origin(in.originSystem(), in.originReference());
        Permit after = repository.updatePermit(before.toBuilder().title(required(in.title(), "title"))
                .workDescription(required(in.workDescription(), "workDescription")).locationCode(required(in.locationCode(), "locationCode"))
                .zoneId(zone == null ? null : zone.id()).zoneCode(zone == null ? null : zone.zoneCode()).startsAt(in.startsAt()).endsAt(in.endsAt())
                .riskAssessmentId(in.riskAssessmentId()).contractorReference(blank(in.contractorReference()))
                .supervisorReference(required(in.supervisorReference(), "supervisorReference")).supervisorContact(blank(in.supervisorContact()))
                .originSystem(origin).originReference(origin == OriginSystem.NONE ? null : blank(in.originReference()))
                .updatedAt(recorder.now()).build(), before.version());
        recorder.history(after, "DRAFT", "DRAFT", "PERMIT_DRAFT_REVISED", caller.id(), null);
        recorder.audit(caller, "PERMIT_DRAFT_REVISED", after, before, after, null);
        return records.detail(after);
    }

    @Transactional
    public PermitDetail addWorker(UUID id, WorkerInput worker, Caller caller) {
        Permit permit = draftOfRequester(id, caller);
        insertWorker(permit, worker, recorder.now());
        recorder.audit(caller, "PERMIT_WORKER_ADDED", permit, null, worker.personReference(), null);
        return records.detail(permit);
    }

    @Transactional
    public PermitDetail removeWorker(UUID id, UUID workerId, Caller caller) {
        Permit permit = draftOfRequester(id, caller);
        PermitWorker worker = repository.findWorker(workerId).filter(w -> w.permitId().equals(id)).orElseThrow(() -> PermitException.notFound("PermitWorker", workerId));
        repository.deleteWorker(workerId);
        recorder.audit(caller, "PERMIT_WORKER_REMOVED", permit, worker.personReference(), null, null);
        return records.detail(permit);
    }

    @Transactional
    public PermitDetail addIsolation(UUID id, IsolationInput isolation, Caller caller) {
        Permit permit = draftOfRequester(id, caller);
        insertIsolation(permit, isolation, recorder.now());
        recorder.audit(caller, "PERMIT_ISOLATION_ADDED", permit, null, isolation.kind(), null);
        return records.detail(permit);
    }

    @Transactional
    public PermitDetail removeIsolation(UUID id, UUID isolationId, Caller caller) {
        Permit permit = draftOfRequester(id, caller);
        Isolation isolation = repository.findIsolation(isolationId).filter(i -> i.permitId().equals(id))
                .orElseThrow(() -> PermitException.notFound("Isolation", isolationId));
        repository.deleteIsolation(isolationId);
        recorder.audit(caller, "PERMIT_ISOLATION_REMOVED_FROM_DRAFT", permit, isolation.kind(), null, null);
        return records.detail(permit);
    }

    /**
     * Routes the request for approval - if S165 says the linked assessment is current. A refusal is recorded (history and audit,
     * in their own transaction) and then thrown with the SRS's name for it.
     */
    @Transactional
    public PermitDetail submit(UUID id, Long expectedVersion, Caller caller) {
        Permit before = draftOfRequester(id, caller);
        checkVersion(before, expectedVersion);
        PermitType type = records.type(before);
        Instant now = recorder.now();
        RiskAssessmentStanding standing = before.riskAssessmentId() == null ? RiskAssessmentStanding.notLinked()
                : assessments.standing(before.riskAssessmentId(), before.siteCode(), now);
        try {
            SubmissionPolicy.require(type, before.startsAt(), before.endsAt(), repository.workers(id).size(), repository.isolations(id).size(), standing);
        } catch (PermitException refused) {
            recorder.refusal(caller, before, "PERMIT_SUBMISSION", refused.errorCode().code(), refused.details().toString());
            throw refused;
        }
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.SUBMITTED).submittedAt(now).updatedAt(now)
                .riskAssessmentReference(standing.reference()).riskAssessmentVersion(standing.version()).riskLevel(standing.riskLevel())
                .riskReviewDueAt(standing.reviewDueAt()).build(), before.version());
        recorder.history(after, "DRAFT", "SUBMITTED", "PERMIT_SUBMITTED", caller.id(), null);
        recorder.audit(caller, "PERMIT_SUBMITTED", after, before, after, null);
        return records.detail(after);
    }

    /** Withdraws a permit that has not been issued - its requester, or an authoriser. */
    @Transactional
    public PermitDetail cancel(UUID id, String reason, Long expectedVersion, Caller caller) {
        Permit before = records.require(id);
        boolean requester = before.requestedBy().equals(caller.id()) && access.has(caller.actor(), SflPermission.PERMIT_REQUEST);
        if (!requester) {
            access.require(caller.actor(), SflPermission.PERMIT_APPROVE, before.siteCode(), "Permit", id.toString());
        } else {
            access.require(caller.actor(), SflPermission.PERMIT_REQUEST, before.siteCode(), "Permit", id.toString());
        }
        checkVersion(before, expectedVersion);
        if (!before.status().preIssue()) {
            throw PermitException.invalidState(before.status().name(), PermitStatus.CANCELLED.name());
        }
        String why = reason(reason);
        Permit after = repository.updatePermit(before.toBuilder().status(PermitStatus.CANCELLED).statusReason(why).updatedAt(recorder.now()).build(), before.version());
        recorder.history(after, before.status().name(), "CANCELLED", "PERMIT_CANCELLED", caller.id(), why);
        recorder.audit(caller, "PERMIT_CANCELLED", after, before, after, why);
        return records.detail(after);
    }

    // ---- helpers

    private Permit draftOfRequester(UUID id, Caller caller) {
        Permit permit = records.require(id);
        access.require(caller.actor(), SflPermission.PERMIT_REQUEST, permit.siteCode(), "Permit", id.toString());
        if (!permit.requestedBy().equals(caller.id())) {
            throw PermitException.unauthorizedScope(permit.siteCode(), "Permit", id.toString());
        }
        if (permit.status() != PermitStatus.DRAFT) {
            throw PermitException.invalidState(permit.status().name(), "DRAFT");
        }
        return permit;
    }

    private void insertWorker(Permit permit, WorkerInput w, Instant now) {
        repository.insertWorker(new PermitWorker(UUID.randomUUID(), permit.id(), permit.siteCode(), required(w.personReference(), "personReference"),
                required(w.displayName(), "displayName"), w.workRole() == null ? WorkRole.OPERATIVE : w.workRole(), now));
    }

    private void insertIsolation(Permit permit, IsolationInput i, Instant now) {
        repository.insertIsolation(new Isolation(UUID.randomUUID(), permit.id(), permit.siteCode(), i.kind() == null ? IsolationKind.OTHER : i.kind(),
                required(i.description(), "description"), blank(i.tagReference()), IsolationStatus.REQUIRED, null, null, null, null, null, null, now));
    }

    private PermitZonePort.Zone zone(UUID zoneId, String site) {
        if (zoneId == null) {
            return null;
        }
        return zones.find(zoneId, site).orElseThrow(() -> new PermitException(PermitErrorCode.PERMIT_ZONE_NOT_FOUND));
    }

    private static OriginSystem origin(OriginSystem system, String reference) {
        if (reference == null || reference.isBlank()) {
            return OriginSystem.NONE;
        }
        if (system == null || system == OriginSystem.NONE) {
            throw PermitException.invalid("originSystem is required when an originating reference is given");
        }
        return system;
    }

    static void checkVersion(Permit permit, Long expected) {
        if (expected != null && expected != permit.version()) {
            throw PermitException.conflict();
        }
    }

    static String reason(String value) {
        if (value == null || value.isBlank()) {
            throw new PermitException(PermitErrorCode.PERMIT_REASON_REQUIRED);
        }
        return value.strip();
    }

    static String site(String value) {
        if (value == null || value.isBlank()) {
            throw PermitException.invalid("siteCode is required");
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw PermitException.invalid(field + " is required");
        }
        return value.strip();
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

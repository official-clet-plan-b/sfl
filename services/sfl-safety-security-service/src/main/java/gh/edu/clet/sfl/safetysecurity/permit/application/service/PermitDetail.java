package gh.edu.clet.sfl.safetysecurity.permit.application.service;

import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.CompetencyCheck;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.HistoryEntry;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEscalation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitEvidence;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitExtension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitFlag;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitNotification;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitSuspension;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import java.util.List;
import java.util.UUID;

/**
 * One permit with everything the screen and the auditor need, and what currently stands between it and its next step.
 *
 * @param blockers plain statements of what stops the permit advancing now - empty when nothing does
 * @param nextStage the approval stage a round is waiting on, or null
 * @param riskAssessment S165's current verdict on the linked assessment, live - not the snapshot taken at submission
 * @param integrations what S164 holds by reference and has not verified
 */
public record PermitDetail(Permit permit, PermitType type, List<PermitWorker> workers, List<CompetencyCheck> competencyChecks,
        List<CompetencyException> competencyExceptions, List<Isolation> isolations, List<PermitApproval> approvals, ApprovalStage nextStage,
        List<PermitExtension> extensions, List<PermitSuspension> suspensions, List<PermitNotification> notifications,
        List<PermitEvidence> evidence, List<PermitFlag> flags, List<PermitEscalation> escalations, List<HistoryEntry> history,
        List<String> blockers, RiskAssessmentStanding riskAssessment, boolean overdue, List<String> unverified) {

    public record CompetencyException(UUID workerId, String workerName, String competency, String reason) {
    }
}

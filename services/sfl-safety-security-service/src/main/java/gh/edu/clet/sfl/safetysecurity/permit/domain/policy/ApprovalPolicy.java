package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalDecision;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.ApprovalStage;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitApproval;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SRS-SFL-S164-02: approval only after isolations are verified, never by the person who asked for the work, and - for a
 * higher-risk type - in two independent stages. Pure: callers pass what they have read.
 */
public final class ApprovalPolicy {

    private ApprovalPolicy() {
    }

    /** The stage a round of approvals is waiting on, or empty once it is complete. */
    public static Optional<ApprovalStage> nextStage(PermitType type, List<PermitApproval> thisRound) {
        boolean first = thisRound.stream().anyMatch(a -> a.stage() == ApprovalStage.ISSUING_AUTHORITY
                && a.decision() == ApprovalDecision.APPROVED);
        boolean second = thisRound.stream().anyMatch(a -> a.stage() == ApprovalStage.SAFETY_SIGN_OFF
                && a.decision() == ApprovalDecision.APPROVED);
        if (!first) {
            return Optional.of(ApprovalStage.ISSUING_AUTHORITY);
        }
        if (type.twoStage() && !second) {
            return Optional.of(ApprovalStage.SAFETY_SIGN_OFF);
        }
        return Optional.empty();
    }

    /**
     * Refuses an approval that is not allowed, with the SRS's name for why.
     *
     * @param thisRound the approvals already recorded for this permit's current round
     */
    public static void require(Permit permit, PermitType type, ApprovalStage stage, String approver, List<PermitApproval> thisRound,
            List<Isolation> isolations) {
        if (permit.status() == PermitStatus.SUBMITTED) {
            throw new PermitException(PermitErrorCode.PERMIT_ISOLATION_NOT_VERIFIED, Map.of("permit", permit.reference()));
        }
        if (permit.status() != PermitStatus.ISOLATION_VERIFIED && permit.status() != PermitStatus.STAGE1_APPROVED
                && permit.status() != PermitStatus.RESUMPTION_PENDING) {
            throw PermitException.invalidState(permit.status().name(), "APPROVED");
        }
        if (permit.requestedBy().equals(approver)) {
            throw new PermitException(PermitErrorCode.PERMIT_SELF_APPROVAL, Map.of("permit", permit.reference()));
        }
        if (nextStage(type, thisRound).orElse(null) != stage) {
            throw new PermitException(PermitErrorCode.PERMIT_STAGE_OUT_OF_ORDER, Map.of("stage", stage.name()));
        }
        if (stage == ApprovalStage.SAFETY_SIGN_OFF && thisRound.stream().anyMatch(a -> a.stage() == ApprovalStage.ISSUING_AUTHORITY
                && a.decidedBy().equals(approver))) {
            throw new PermitException(PermitErrorCode.PERMIT_STAGE_NOT_INDEPENDENT, Map.of("permit", permit.reference()));
        }
        requireIsolationsVerified(isolations);
    }

    /** "A permit is never issued on the assumption that isolation should have happened." */
    public static void requireIsolationsVerified(List<Isolation> isolations) {
        long unverified = isolations.stream().filter(i -> i.status() == IsolationStatus.REQUIRED).count();
        if (unverified > 0) {
            throw new PermitException(PermitErrorCode.PERMIT_ISOLATION_NOT_VERIFIED, Map.of("unverified", unverified));
        }
    }

    /**
     * An extension is approved in the same stages as an issue: the permit's requester cannot, the extension's requester cannot,
     * and the second stage is never the first stage's person. Isolations must still stand.
     */
    public static void requireExtensionStep(Permit permit, PermitType type, ApprovalStage stage, String approver, String extensionRequester,
            List<PermitApproval> thisExtension, List<Isolation> isolations) {
        if (permit.status() != PermitStatus.ACTIVE) {
            throw PermitException.invalidState(permit.status().name(), "EXTENDED");
        }
        if (permit.requestedBy().equals(approver) || extensionRequester.equals(approver)) {
            throw new PermitException(PermitErrorCode.PERMIT_SELF_APPROVAL, Map.of("permit", permit.reference()));
        }
        if (nextStage(type, thisExtension).orElse(null) != stage) {
            throw new PermitException(PermitErrorCode.PERMIT_STAGE_OUT_OF_ORDER, Map.of("stage", stage.name()));
        }
        if (stage == ApprovalStage.SAFETY_SIGN_OFF && thisExtension.stream().anyMatch(a -> a.stage() == ApprovalStage.ISSUING_AUTHORITY
                && a.decidedBy().equals(approver))) {
            throw new PermitException(PermitErrorCode.PERMIT_STAGE_NOT_INDEPENDENT, Map.of("permit", permit.reference()));
        }
        requireIsolationsVerified(isolations);
    }
}

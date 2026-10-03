package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.RiskAssessmentStanding;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * SRS-SFL-S164-01: what a request needs before it is routed for approval. The first thing checked is the risk assessment,
 * and the refusal names which of the two SRS error states it is - "No Risk Assessment Linked" or "Risk Assessment Not
 * Current" - rather than a generic failure. Pure: the caller supplies S165's verdict.
 */
public final class SubmissionPolicy {

    private SubmissionPolicy() {
    }

    public static void require(PermitType type, Instant startsAt, Instant endsAt, int workers, int isolations,
            RiskAssessmentStanding assessment) {
        if (!type.active()) {
            throw new PermitException(PermitErrorCode.PERMIT_TYPE_INACTIVE, Map.of("permitType", type.code()));
        }
        requireRiskAssessment(type, assessment);
        requireWindow(type, startsAt, endsAt);
        if (workers < 1) {
            throw new PermitException(PermitErrorCode.PERMIT_WORKERS_REQUIRED);
        }
        if (type.requiresIsolation() && isolations < 1) {
            throw new PermitException(PermitErrorCode.PERMIT_ISOLATIONS_REQUIRED, Map.of("permitType", type.code()));
        }
    }

    /** The currency and cover checks, which are also made again at approval, extension and resumption. */
    public static void requireRiskAssessment(PermitType type, RiskAssessmentStanding assessment) {
        if (!type.riskAssessmentRequired()) {
            return;
        }
        if (assessment == null || assessment.assessmentId() == null || !assessment.found()) {
            throw new PermitException(PermitErrorCode.PERMIT_NO_RISK_ASSESSMENT, Map.of("permitType", type.code()));
        }
        if (!assessment.current()) {
            throw new PermitException(PermitErrorCode.PERMIT_RISK_ASSESSMENT_NOT_CURRENT, Map.of("assessment",
                    String.valueOf(assessment.reference()), "reason", String.valueOf(assessment.reason()), "reviewDueAt",
                    assessment.reviewDueAt() == null ? "" : assessment.reviewDueAt().toString()));
        }
        if (type.activityType() != null && !type.activityType().isBlank() && assessment.activityType() != null
                && !type.activityType().equalsIgnoreCase(assessment.activityType())) {
            throw new PermitException(PermitErrorCode.PERMIT_RISK_ASSESSMENT_MISMATCH, Map.of("expected", type.activityType(),
                    "assessmentCovers", assessment.activityType()));
        }
    }

    public static void requireWindow(PermitType type, Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new PermitException(PermitErrorCode.PERMIT_WINDOW_INVALID, Map.of("reason", "ends must be after it starts"));
        }
        if (Duration.between(startsAt, endsAt).compareTo(type.maxValidity()) > 0) {
            throw new PermitException(PermitErrorCode.PERMIT_WINDOW_INVALID, Map.of("reason",
                    type.name() + " is valid for at most " + type.maxValidityHours() + " hours"));
        }
    }
}

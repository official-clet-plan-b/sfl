package gh.edu.clet.sfl.safetysecurity.riskassessment.application.service;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskAssessment;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the register: the assessment, its current version's headline, and where it stands right
 * now. {@link #standing} and {@link #currencyReason} are computed at read time by the shared currency
 * rule - the same answer S164, S173 and S176 will get - never stored.
 */
public record AssessmentSummary(
        UUID id,
        String siteCode,
        String reference,
        String title,
        String activityType,
        String locationCode,
        Integer currentVersion,
        Integer draftVersion,
        int latestVersion,
        RiskLevel riskLevel,
        Instant reviewDueAt,
        String authorId,
        String signedOffBy,
        AssessmentStanding standing,
        RiskAssessmentCurrency.Reason currencyReason,
        long recordVersion,
        Instant lastModifiedAt) {

    public static AssessmentSummary of(RiskAssessment assessment, Instant now) {
        RiskAssessmentCurrency.Verdict verdict = assessment.currency(now);
        return new AssessmentSummary(assessment.id(), assessment.siteCode(), assessment.reference(),
                assessment.title(), assessment.activityType(), assessment.locationCode(), assessment.currentVersion(),
                assessment.draftVersion(), assessment.latestVersion(), assessment.currentRiskLevel(),
                assessment.currentReviewDueAt(), assessment.currentAuthorId(), assessment.currentSignedOffBy(),
                AssessmentStanding.of(verdict), verdict.reason(), assessment.metadata().version(),
                assessment.metadata().lastModifiedAt());
    }
}

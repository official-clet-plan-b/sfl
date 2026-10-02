package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An out-of-cycle review raised against an assessment because something went wrong in practice -
 * SRS-SFL-S165-04: "a control measure that failed in practice gets re-examined promptly, not at the next
 * scheduled cycle."
 *
 * <p>Independent of the review date: a flagged assessment is still current (the SRS does not suspend
 * it), but it is on the reviewer's queue until a review with findings clears it.
 *
 * @param versionNumber the version that was current when the incident happened - the one whose controls
 *        are in question, even if a later version has since been published
 * @param sourceId the triggering record's id in its own module (an S163 incident id), held by value
 * @param sourceReference that record's human reference, e.g. {@code INC-1A2B3C4D}
 */
public record ReviewFlag(
        UUID id,
        UUID assessmentId,
        String assessmentReference,
        int versionNumber,
        String siteCode,
        ReviewTrigger trigger,
        String sourceId,
        String sourceReference,
        String reason,
        ReviewFlagStatus status,
        Instant raisedAt,
        Instant deferredUntil,
        String deferralReason,
        String deferredBy,
        int deferralCount,
        Instant clearedAt,
        String clearedBy,
        String findings,
        RecordMetadata metadata) {

    public ReviewFlag {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(assessmentId, "assessmentId is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        Objects.requireNonNull(trigger, "trigger is required");
        Objects.requireNonNull(sourceId, "sourceId is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(raisedAt, "raisedAt is required");
        Objects.requireNonNull(metadata, "metadata is required");
        if (status == ReviewFlagStatus.CLEARED && (findings == null || findings.isBlank())) {
            throw new IllegalArgumentException("A cleared flag always carries its review findings");
        }
    }

    public static ReviewFlag raise(UUID id, RiskAssessment assessment, int versionNumber, ReviewTrigger trigger,
            String sourceId, String sourceReference, String reason, String actorId, Instant now,
            SourceChannel channel, String correlationId) {
        return new ReviewFlag(id, assessment.id(), assessment.reference(), versionNumber, assessment.siteCode(),
                trigger, sourceId, sourceReference, reason, ReviewFlagStatus.OPEN, now, null, null, null, 0, null,
                null, null, RecordMetadata.createdBy(actorId, now, channel, correlationId));
    }

    /** S165-04: "it can be deferred with a named reason and date". The date must be in the future. */
    public ReviewFlag defer(String reasonForDeferral, Instant until, String actorId, Instant now,
            SourceChannel channel, String correlationId) {
        requireNotCleared();
        if (reasonForDeferral == null || reasonForDeferral.isBlank() || until == null || !until.isAfter(now)) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_DEFERRAL_INVALID);
        }
        return new ReviewFlag(id, assessmentId, assessmentReference, versionNumber, siteCode, trigger, sourceId,
                sourceReference, reason, ReviewFlagStatus.DEFERRED, raisedAt, until, reasonForDeferral.strip(),
                actorId, deferralCount + 1, null, null, null,
                metadata.modifiedBy(actorId, now, channel, correlationId));
    }

    /** S165-04: "The flag is cleared only by a completed review with recorded findings." */
    public ReviewFlag complete(String reviewFindings, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        requireNotCleared();
        if (reviewFindings == null || reviewFindings.isBlank()) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_REVIEW_FINDINGS_REQUIRED);
        }
        return new ReviewFlag(id, assessmentId, assessmentReference, versionNumber, siteCode, trigger, sourceId,
                sourceReference, reason, ReviewFlagStatus.CLEARED, raisedAt, deferredUntil, deferralReason,
                deferredBy, deferralCount, now, actorId, reviewFindings.strip(),
                metadata.modifiedBy(actorId, now, channel, correlationId));
    }

    /** A deferral is not a dismissal: when its date passes, the flag is back on the queue. */
    public boolean deferralExpired(Instant now) {
        return status == ReviewFlagStatus.DEFERRED && deferredUntil != null && !deferredUntil.isAfter(now);
    }

    public ReviewFlag reopen(String actorId, Instant now, String correlationId) {
        if (!deferralExpired(now)) {
            throw RiskAssessmentException.invalidState("Only a deferral whose date has passed reopens.");
        }
        return new ReviewFlag(id, assessmentId, assessmentReference, versionNumber, siteCode, trigger, sourceId,
                sourceReference, reason, ReviewFlagStatus.OPEN, raisedAt, deferredUntil, deferralReason, deferredBy,
                deferralCount, null, null, null, metadata.modifiedBy(actorId, now, SourceChannel.SYSTEM, correlationId));
    }

    private void requireNotCleared() {
        if (status == ReviewFlagStatus.CLEARED) {
            throw RiskAssessmentException.invalidState("This review flag has already been cleared.");
        }
    }
}

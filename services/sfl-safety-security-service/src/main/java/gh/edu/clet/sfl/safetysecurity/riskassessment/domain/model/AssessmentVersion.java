package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.PublishPolicy;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.ReviewSchedulePolicy;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy.SignOffPolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One version of a risk assessment - SRS-SFL-S165-01: "Each edit creates a new version. A superseded
 * version remains retrievable for audit but is clearly marked as not current."
 *
 * <h2>What "each edit creates a new version" means here</h2>
 *
 * <p>A {@link Status#DRAFT} is the author's working copy and is edited in place - nobody can rely on a
 * draft, so there is nothing to protect by versioning its keystrokes. Once a version is
 * {@link Status#PUBLISHED} it is never edited again: changing it means opening a new draft at the next
 * version number, and publishing that draft supersedes this one. That is the reading under which
 * "history is never lost to an overwrite" holds - nothing anyone relied on is ever rewritten.
 *
 * <p>The lifecycle is DRAFT -> PUBLISHED -> SUPERSEDED, and nothing else. "Lapsed" is not a status: it is
 * what {@link RiskAssessmentCurrency} concludes from a published version whose review date has passed,
 * and {@link #reviewLapsedAt} only records that the sweep noticed (so it announces it once).
 */
public record AssessmentVersion(
        UUID id,
        UUID assessmentId,
        String siteCode,
        int versionNumber,
        Status status,
        AssessmentContent content,
        String authorId,
        String authorName,
        Integer reviewIntervalDays,
        Instant reviewDueAt,
        Instant reviewReminderSentAt,
        Instant reviewLapsedAt,
        Instant publishedAt,
        String publishedBy,
        String signedOffBy,
        String signedOffByName,
        Instant signedOffAt,
        Instant supersededAt,
        RecordMetadata metadata) {

    public AssessmentVersion {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(assessmentId, "assessmentId is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber starts at 1");
        }
        Objects.requireNonNull(status, "status is required");
        if (status == Status.WITHDRAWN) {
            throw new IllegalArgumentException("S165 has no withdrawal path; a version is drafted, published or superseded");
        }
        Objects.requireNonNull(content, "content is required");
        Objects.requireNonNull(authorId, "authorId is required");
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public static AssessmentVersion draft(UUID id, UUID assessmentId, String siteCode, int versionNumber,
            AssessmentContent content, String authorId, String authorName, Instant now, SourceChannel channel,
            String correlationId) {
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, Status.DRAFT, content, authorId,
                authorName, null, null, null, null, null, null, null, null, null, null,
                RecordMetadata.createdBy(authorId, now, channel, correlationId));
    }

    /** The computed level - the highest residual rating among the hazards. Null only for an empty draft. */
    public RiskLevel riskLevel() {
        return content.riskLevel();
    }

    public boolean isDraft() {
        return status == Status.DRAFT;
    }

    public boolean isPublished() {
        return status == Status.PUBLISHED;
    }

    /** A draft's working copy changes in place - see the class docblock. */
    public AssessmentVersion edit(AssessmentContent revised, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        requireStatus(Status.DRAFT, "Only a draft can be edited. Revise a published assessment to change it.");
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, status,
                Objects.requireNonNull(revised, "content is required"), authorId, authorName, reviewIntervalDays,
                reviewDueAt, reviewReminderSentAt, reviewLapsedAt, publishedAt, publishedBy, signedOffBy,
                signedOffByName, signedOffAt, supersededAt, metadata.modifiedBy(actorId, now, channel, correlationId));
    }

    /**
     * Publishes the draft as the current version, due for review {@code intervalDays} from now - the
     * interval driven by the level this content computes to (S165-02).
     */
    public AssessmentVersion publish(int intervalDays, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        requireStatus(Status.DRAFT, "Only a draft can be published.");
        PublishPolicy.requirePublishable(content);
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, Status.PUBLISHED, content, authorId,
                authorName, intervalDays, ReviewSchedulePolicy.dueAt(now, intervalDays), null, null, now, actorId,
                null, null, null, null, metadata.modifiedBy(actorId, now, channel, correlationId));
    }

    /**
     * Records a review sign-off and renews the review date - S165-02 "reviewed and re-signed". A lapsed
     * version is signed off the same way, which is how it becomes current again.
     */
    public AssessmentVersion signOff(String reviewerId, String reviewerName, int intervalDays, Instant now,
            SourceChannel channel, String correlationId) {
        requireStatus(Status.PUBLISHED, "Only the published version can be signed off.");
        SignOffPolicy.requireAcceptable(riskLevel(), authorId, reviewerId);
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, status, content, authorId,
                authorName, intervalDays, ReviewSchedulePolicy.dueAt(now, intervalDays), null, null, publishedAt,
                publishedBy, reviewerId.strip(), reviewerName, now, supersededAt,
                metadata.modifiedBy(reviewerId, now, channel, correlationId));
    }

    /** Publishing the next version retires this one. It stays readable; it is never deleted. */
    public AssessmentVersion supersede(String actorId, Instant now, SourceChannel channel, String correlationId) {
        requireStatus(Status.PUBLISHED, "Only the published version can be superseded.");
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, Status.SUPERSEDED, content, authorId,
                authorName, reviewIntervalDays, reviewDueAt, reviewReminderSentAt, reviewLapsedAt, publishedAt,
                publishedBy, signedOffBy, signedOffByName, signedOffAt, now,
                metadata.modifiedBy(actorId, now, channel, correlationId));
    }

    public AssessmentVersion reminderSent(String actorId, Instant now, String correlationId) {
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, status, content, authorId,
                authorName, reviewIntervalDays, reviewDueAt, now, reviewLapsedAt, publishedAt, publishedBy,
                signedOffBy, signedOffByName, signedOffAt, supersededAt,
                metadata.modifiedBy(actorId, now, SourceChannel.SYSTEM, correlationId));
    }

    public AssessmentVersion lapseRecorded(String actorId, Instant now, String correlationId) {
        return new AssessmentVersion(id, assessmentId, siteCode, versionNumber, status, content, authorId,
                authorName, reviewIntervalDays, reviewDueAt, reviewReminderSentAt, now, publishedAt, publishedBy,
                signedOffBy, signedOffByName, signedOffAt, supersededAt,
                metadata.modifiedBy(actorId, now, SourceChannel.SYSTEM, correlationId));
    }

    /** What the shared currency rule needs. Only meaningful once published - a draft has no review date. */
    public RiskAssessmentCurrency.Snapshot snapshot() {
        RiskLevel level = riskLevel();
        return new RiskAssessmentCurrency.Snapshot(assessmentId.toString(), versionNumber, status,
                level == null ? RiskLevel.LOW : level, reviewDueAt, authorId, signedOffBy);
    }

    public RiskAssessmentCurrency.Verdict currency(Instant at) {
        return RiskAssessmentCurrency.assess(snapshot(), at);
    }

    private void requireStatus(Status expected, String reason) {
        if (status != expected) {
            throw RiskAssessmentException.invalidState(reason);
        }
    }
}

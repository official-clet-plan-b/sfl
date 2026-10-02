package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One review sign-off, kept as history - SRS-SFL-S165-02. The version carries only its latest sign-off;
 * this is the record of every one, including who renewed a lapsed assessment and from which date.
 *
 * @param independent whether the reviewer was someone other than the version's author - always true for
 *        HIGH and CRITICAL, which {@code SignOffPolicy} refuses otherwise
 */
public record SignOff(UUID id, UUID assessmentId, UUID versionId, int versionNumber, String siteCode,
        String reviewerId, String reviewerName, Instant signedOffAt, String notes, Instant previousReviewDueAt,
        Instant reviewDueAt, boolean independent, RecordMetadata metadata) {

    public SignOff {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(assessmentId, "assessmentId is required");
        Objects.requireNonNull(versionId, "versionId is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("A sign-off needs a named reviewer");
        }
        Objects.requireNonNull(signedOffAt, "signedOffAt is required");
        Objects.requireNonNull(reviewDueAt, "reviewDueAt is required");
        notes = notes == null || notes.isBlank() ? null : notes.strip();
        Objects.requireNonNull(metadata, "metadata is required");
    }
}

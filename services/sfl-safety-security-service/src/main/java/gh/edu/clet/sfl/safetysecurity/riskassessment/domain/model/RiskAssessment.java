package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A risk assessment's identity and scope, and a summary of its current version - SRS-SFL-S165-01.
 *
 * <p>The scope - site, activity type, S152 location - belongs to the assessment, not to a version.
 * Changing what an assessment covers is a different assessment: an HSE officer relying on "the hot-work
 * assessment for Block A" must not find it quietly became one for a different location at version 4.
 * The content (title, hazards, controls) is versioned; see {@link AssessmentVersion}.
 *
 * <p>The summary fields repeat what the current version says, written in the same transaction as the
 * version itself, so the register can be listed, filtered and ordered without loading every version.
 * The versions remain the record; this is an index of them.
 *
 * @param activityType normalised by {@link ActivityTypes}, or null when the scope is a location only
 * @param locationCode an S152 room/space/zone code held by value - never a foreign key across services
 * @param templateId the template the first draft was copied from, if any. Recorded for provenance only;
 *        a later template edit never reaches an assessment already copied from it
 */
public record RiskAssessment(
        UUID id,
        String siteCode,
        String reference,
        String activityType,
        String locationCode,
        UUID templateId,
        String title,
        Integer currentVersion,
        Integer draftVersion,
        int latestVersion,
        RiskLevel currentRiskLevel,
        Instant currentReviewDueAt,
        String currentAuthorId,
        String currentSignedOffBy,
        RecordMetadata metadata) {

    public RiskAssessment {
        Objects.requireNonNull(id, "id is required");
        siteCode = normaliseSite(siteCode);
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference is required");
        }
        activityType = ActivityTypes.normalise(activityType);
        locationCode = locationCode == null || locationCode.isBlank() ? null
                : locationCode.strip().toUpperCase(Locale.ROOT);
        if (activityType == null && locationCode == null) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_SCOPE_REQUIRED);
        }
        Objects.requireNonNull(title, "title is required");
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public static RiskAssessment create(UUID id, String siteCode, String reference, String activityType,
            String locationCode, UUID templateId, String title, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        return new RiskAssessment(id, siteCode, reference, activityType, locationCode, templateId, title, null, 1, 1,
                null, null, null, null, RecordMetadata.createdBy(actorId, now, channel, correlationId));
    }

    public boolean hasOpenDraft() {
        return draftVersion != null;
    }

    /** A new draft at the next version number, opened from the current version. */
    public RiskAssessment draftOpened(int versionNumber, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        if (hasOpenDraft()) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_DRAFT_ALREADY_OPEN,
                    java.util.Map.of("draftVersion", draftVersion));
        }
        return new RiskAssessment(id, siteCode, reference, activityType, locationCode, templateId, title,
                currentVersion, versionNumber, Math.max(latestVersion, versionNumber), currentRiskLevel,
                currentReviewDueAt, currentAuthorId, currentSignedOffBy,
                metadata.modifiedBy(actorId, now, channel, correlationId));
    }

    /** Re-reads the summary from a version that has just changed. */
    public RiskAssessment summarise(AssessmentVersion version, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        if (!version.assessmentId().equals(id)) {
            throw new IllegalArgumentException("That version belongs to a different assessment");
        }
        RecordMetadata touched = metadata.modifiedBy(actorId, now, channel, correlationId);
        if (version.isDraft()) {
            String newTitle = currentVersion == null ? version.content().title() : title;
            return new RiskAssessment(id, siteCode, reference, activityType, locationCode, templateId, newTitle,
                    currentVersion, version.versionNumber(), Math.max(latestVersion, version.versionNumber()),
                    currentRiskLevel, currentReviewDueAt, currentAuthorId, currentSignedOffBy, touched);
        }
        if (version.isPublished()) {
            Integer openDraft = draftVersion != null && draftVersion == version.versionNumber() ? null : draftVersion;
            return new RiskAssessment(id, siteCode, reference, activityType, locationCode, templateId,
                    version.content().title(), version.versionNumber(), openDraft,
                    Math.max(latestVersion, version.versionNumber()), version.riskLevel(), version.reviewDueAt(),
                    version.authorId(), version.signedOffBy(), touched);
        }
        return new RiskAssessment(id, siteCode, reference, activityType, locationCode, templateId, title,
                currentVersion, draftVersion, latestVersion, currentRiskLevel, currentReviewDueAt, currentAuthorId,
                currentSignedOffBy, touched);
    }

    /** What the currency rule says about the current version, from the summary alone. */
    public RiskAssessmentCurrency.Verdict currency(Instant at) {
        if (currentVersion == null || currentRiskLevel == null) {
            return RiskAssessmentCurrency.assess(null, at);
        }
        return RiskAssessmentCurrency.assess(new RiskAssessmentCurrency.Snapshot(id.toString(), currentVersion,
                Status.PUBLISHED, currentRiskLevel, currentReviewDueAt, currentAuthorId, currentSignedOffBy), at);
    }

    public AssessmentStanding standing(Instant at) {
        return AssessmentStanding.of(currency(at));
    }

    private static String normaliseSite(String siteCode) {
        if (siteCode == null || siteCode.isBlank()) {
            throw new RiskAssessmentException(RiskAssessmentErrorCode.RISK_ASSESSMENT_MISSING_SITE_SCOPE);
        }
        return siteCode.strip().toUpperCase(Locale.ROOT);
    }
}

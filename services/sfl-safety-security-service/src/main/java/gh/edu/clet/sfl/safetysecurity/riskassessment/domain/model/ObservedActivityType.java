package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An activity type the platform has actually used at a site - SRS-SFL-S165-03: "Coverage-gap detection
 * is based on actual referenced activity types in the platform, not a static hard-coded list, so a new
 * work type introduced elsewhere is caught."
 *
 * <p>One row per site, activity type and source, so the coverage screen can say where a gap came from
 * ("seen 4 times in S176 construction projects") rather than only that it exists.
 *
 * @param sourceSystem the SRS system that used it, e.g. {@code S176} or {@code S163}
 * @param lastSourceReference the most recent record that used it, in its own module's terms
 */
public record ObservedActivityType(UUID id, String siteCode, String activityType, String sourceSystem,
        Instant firstSeenAt, Instant lastSeenAt, long occurrences, String lastSourceReference,
        RecordMetadata metadata) {

    public ObservedActivityType {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        activityType = Objects.requireNonNull(ActivityTypes.normalise(activityType), "activityType is required");
        Objects.requireNonNull(sourceSystem, "sourceSystem is required");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt is required");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt is required");
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public static ObservedActivityType firstSeen(UUID id, String siteCode, String activityType, String sourceSystem,
            String sourceReference, String actorId, Instant now, SourceChannel channel, String correlationId) {
        return new ObservedActivityType(id, siteCode, activityType, sourceSystem, now, now, 1, sourceReference,
                RecordMetadata.createdBy(actorId, now, channel, correlationId));
    }

    public ObservedActivityType seenAgain(String sourceReference, String actorId, Instant now, SourceChannel channel,
            String correlationId) {
        return new ObservedActivityType(id, siteCode, activityType, sourceSystem, firstSeenAt,
                now.isAfter(lastSeenAt) ? now : lastSeenAt, occurrences + 1, sourceReference,
                metadata.modifiedBy(actorId, now, channel, correlationId));
    }
}

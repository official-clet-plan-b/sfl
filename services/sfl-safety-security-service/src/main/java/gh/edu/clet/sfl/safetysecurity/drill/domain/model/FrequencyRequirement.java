package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * SRS-SFL-S175-04: "Required frequency is configurable per site and drill type". Runtime configuration, not
 * code - the statutory and insurance figures are CLET's to set.
 *
 * @param warningDays how long before the due date the site shows as due soon ("approaching ... its interval")
 * @param effectiveFrom when the obligation started; a site that has never completed this type of drill is
 *        measured from here
 * @param gapFlaggedForDueAt the due date the last "Compliance Gap" event was raised for, so the daily sweep
 *        raises it once per lapse rather than every day
 */
public record FrequencyRequirement(UUID id, String siteCode, DrillType drillType, int intervalDays,
        int warningDays, Instant effectiveFrom, Instant gapFlaggedForDueAt, RecordMetadata metadata) {

    public FrequencyRequirement {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        siteCode = siteCode.strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(drillType, "drillType is required");
        if (intervalDays < 1) {
            throw new IllegalArgumentException("intervalDays must be at least 1");
        }
        if (warningDays < 0 || warningDays >= intervalDays) {
            throw new IllegalArgumentException("warningDays must be at least 0 and less than intervalDays");
        }
        Objects.requireNonNull(effectiveFrom, "effectiveFrom is required");
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public FrequencyRequirement revise(int interval, int warning, RecordMetadata meta) {
        return new FrequencyRequirement(id, siteCode, drillType, interval, warning, effectiveFrom, gapFlaggedForDueAt,
                meta);
    }

    public FrequencyRequirement gapFlagged(Instant dueAt, RecordMetadata meta) {
        return new FrequencyRequirement(id, siteCode, drillType, intervalDays, warningDays, effectiveFrom, dueAt,
                meta);
    }
}

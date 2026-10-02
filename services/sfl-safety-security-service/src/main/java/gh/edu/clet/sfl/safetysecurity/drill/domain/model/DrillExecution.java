package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What happened on the day - the drill execution record. Written when the drill starts and completed when its
 * roll-call closes.
 *
 * @param baselineAccessDataAsOf the newest S160a access event behind the baseline, or null when there was none
 * @param baselineStale SRS-SFL-S175-02 error state "Stale Baseline": the access data behind the snapshot was
 *        older than the configured freshness window, so the drill does not fully validate roll-call accuracy
 * @param notificationToMusterSeconds S175-03's timing: from the S174 notification to the last check-in at the
 *        muster point; null when nobody checked in
 */
public record DrillExecution(UUID drillId, String siteCode, Instant startedAt, String startedBy,
        UUID notificationActivationId, String notificationNumber, Instant notificationSentAt, UUID musterSessionId,
        Instant baselineTakenAt, Instant baselineAccessDataAsOf, boolean baselineStale, int baselineCount,
        Instant rollCallClosedAt, String rollCallClosedBy, Integer checkedInCount, Integer gapCount,
        Long notificationToMusterSeconds) {

    public DrillExecution {
        Objects.requireNonNull(drillId, "drillId is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(musterSessionId, "musterSessionId is required");
        Objects.requireNonNull(baselineTakenAt, "baselineTakenAt is required");
    }

    /** S175-02's staleness rule, in one place: no access data at all is as stale as old access data. */
    public static boolean isStale(Instant takenAt, Instant accessDataAsOf, Duration freshness) {
        return accessDataAsOf == null || Duration.between(accessDataAsOf, takenAt).compareTo(freshness) > 0;
    }

    public DrillExecution rollCallClosed(Instant at, String by, int checkedIn, int gaps, Instant lastCheckInAt) {
        Long seconds = lastCheckInAt == null || notificationSentAt == null ? null
                : Math.max(0L, Duration.between(notificationSentAt, lastCheckInAt).toSeconds());
        return new DrillExecution(drillId, siteCode, startedAt, startedBy, notificationActivationId,
                notificationNumber, notificationSentAt, musterSessionId, baselineTakenAt, baselineAccessDataAsOf,
                baselineStale, baselineCount, at, by, checkedIn, gaps, seconds);
    }
}

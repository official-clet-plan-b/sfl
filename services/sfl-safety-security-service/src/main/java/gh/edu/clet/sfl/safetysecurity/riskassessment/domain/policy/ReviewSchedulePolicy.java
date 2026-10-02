package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.policy;

import java.time.Duration;
import java.time.Instant;

/**
 * SRS-SFL-S165-02: when an assessment is due for review, when its reminder is due, and when it has lapsed.
 *
 * <p>The due date is exclusive, matching {@code RiskAssessmentCurrency}: an assessment due at 00:00 on
 * the 1st has lapsed at 00:00 on the 1st. A sweep that waited until the next tick would report lapsed
 * later than the currency check already treats it.
 */
public final class ReviewSchedulePolicy {

    private ReviewSchedulePolicy() {
    }

    public static Instant dueAt(Instant from, int intervalDays) {
        if (intervalDays <= 0) {
            throw new IllegalArgumentException("A review interval must be at least one day");
        }
        return from.plus(Duration.ofDays(intervalDays));
    }

    public static boolean lapsed(Instant reviewDueAt, Instant now) {
        return reviewDueAt != null && !reviewDueAt.isAfter(now);
    }

    /** Inside the reminder window and not yet lapsed. */
    public static boolean reminderDue(Instant reviewDueAt, int leadDays, Instant now) {
        if (reviewDueAt == null || lapsed(reviewDueAt, now)) {
            return false;
        }
        return !reviewDueAt.minus(Duration.ofDays(Math.max(leadDays, 0))).isAfter(now);
    }
}

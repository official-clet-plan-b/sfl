package gh.edu.clet.sfl.safetysecurity.permit.domain.policy;

import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Permit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * SRS-SFL-S164-04: a permit approaching its validity expiry without close-out escalates to the authoriser, and one past it
 * is overdue. Cumulative, so a sweep that missed the warning still raises both.
 */
public final class ExpiryPolicy {

    private ExpiryPolicy() {
    }

    public static List<EscalationLevel> owed(Permit permit, Instant now, Duration warnBefore) {
        if (!permit.status().open()) {
            return List.of();
        }
        if (permit.endsAt().isBefore(now)) {
            return List.of(EscalationLevel.NEARING_EXPIRY, EscalationLevel.OVERDUE);
        }
        if (!permit.endsAt().isAfter(now.plus(warnBefore))) {
            return List.of(EscalationLevel.NEARING_EXPIRY);
        }
        return List.of();
    }
}

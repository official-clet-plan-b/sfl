package gh.edu.clet.sfl.safetysecurity.drill.domain.policy;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * SRS-SFL-S175-03 validation: "A drill cannot be marked reviewed and closed while it has open, overdue corrective
 * actions without an explicit deferral reason." Open actions that are not yet overdue do not hold the drill
 * open - they stay tracked to closure on the HSE dashboard, as S163's do.
 */
public final class ClosurePolicy {

    private ClosurePolicy() {
    }

    /** @return the deferral reason to record, or null when none was needed */
    public static String requireClosable(List<DrillCorrectiveAction> actions, LocalDate today, String deferralReason) {
        List<String> overdue = actions.stream().filter(a -> a.isOverdue(today)).map(a -> a.id().toString()).toList();
        String reason = deferralReason == null || deferralReason.isBlank() ? null : deferralReason.strip();
        if (!overdue.isEmpty() && reason == null) {
            throw new DrillException(DrillErrorCode.DRILL_OVERDUE_ACTIONS_OPEN, Map.of("correctiveActionIds", overdue));
        }
        return overdue.isEmpty() ? null : reason;
    }
}

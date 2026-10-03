package gh.edu.clet.sfl.facilities.lease.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Who should have been told, by how close a date is - SRS acceptance criterion "when an agreement enters its
 * notice window, the owner and escalation chain are notified".
 *
 * <p>Ninety days out the owner; sixty their manager as well; thirty the director as well; past due the director
 * and legal. The answer is <em>cumulative</em>: a control that first sees an obligation at twenty days owes the
 * owner, the manager and the director all at once, so a late first run cannot skip a link in the chain.
 */
public final class AlertPolicy {

    public static final int OWNER_DAYS = 90;
    public static final int MANAGER_DAYS = 60;
    public static final int DIRECTOR_DAYS = 30;

    private AlertPolicy() {
    }

    public record Due(AlertLevel level, AlertReason reason) {
    }

    public static List<Due> owed(long daysToDue) {
        List<Due> owed = new ArrayList<>();
        if (daysToDue < 0) {
            owed.add(new Due(AlertLevel.OWNER, AlertReason.OVERDUE));
            owed.add(new Due(AlertLevel.MANAGER, AlertReason.OVERDUE));
            owed.add(new Due(AlertLevel.DIRECTOR, AlertReason.OVERDUE));
            owed.add(new Due(AlertLevel.LEGAL, AlertReason.OVERDUE));
            return owed;
        }
        if (daysToDue <= OWNER_DAYS) {
            owed.add(new Due(AlertLevel.OWNER, AlertReason.DUE_SOON));
        }
        if (daysToDue <= MANAGER_DAYS) {
            owed.add(new Due(AlertLevel.MANAGER, AlertReason.DUE_SOON));
        }
        if (daysToDue <= DIRECTOR_DAYS) {
            owed.add(new Due(AlertLevel.DIRECTOR, AlertReason.DUE_SOON));
        }
        return owed;
    }
}

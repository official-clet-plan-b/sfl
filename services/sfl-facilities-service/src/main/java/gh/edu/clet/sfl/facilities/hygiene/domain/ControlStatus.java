package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * Stored lifecycle of a control. DUE and OVERDUE are not stored: they are what a SCHEDULED control looks
 * like on a given day, so {@link #effective} derives them and a stored value can never go stale.
 */
public enum ControlStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    MISSED,
    CANCELLED;

    public boolean open() {
        return this == SCHEDULED || this == IN_PROGRESS;
    }

    public boolean canMoveTo(ControlStatus next) {
        return switch (this) {
            case SCHEDULED -> Set.of(IN_PROGRESS, COMPLETED, MISSED, CANCELLED).contains(next);
            case IN_PROGRESS -> Set.of(COMPLETED, MISSED, CANCELLED).contains(next);
            // A missed control can still be carried out late; that is a completion, not a reopening.
            case MISSED -> Set.of(IN_PROGRESS, COMPLETED, CANCELLED).contains(next);
            case COMPLETED, CANCELLED -> false;
        };
    }

    /** What a reader sees: the stored status, or DUE / OVERDUE for an open control against {@code today}. */
    public static String effective(ControlStatus status, LocalDate dueOn, LocalDate today) {
        if (status.open()) {
            if (dueOn.isBefore(today)) {
                return "OVERDUE";
            }
            if (status == SCHEDULED && !dueOn.isAfter(today)) {
                return "DUE";
            }
        }
        return status.name();
    }
}

package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.util.Set;

public enum ActionStatus {
    OPEN,
    IN_PROGRESS,
    COMPLETED,
    VERIFIED,
    REJECTED;

    public boolean open() {
        return this == OPEN || this == IN_PROGRESS || this == REJECTED;
    }

    public boolean canMoveTo(ActionStatus next) {
        return switch (this) {
            case OPEN -> Set.of(IN_PROGRESS, COMPLETED).contains(next);
            case IN_PROGRESS -> next == COMPLETED;
            case COMPLETED -> next == VERIFIED || next == REJECTED;
            // A rejected action goes back to its owner to be done properly.
            case REJECTED -> Set.of(IN_PROGRESS, COMPLETED).contains(next);
            case VERIFIED -> false;
        };
    }
}

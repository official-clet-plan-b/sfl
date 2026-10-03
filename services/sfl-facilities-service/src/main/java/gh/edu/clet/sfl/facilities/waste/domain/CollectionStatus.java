package gh.edu.clet.sfl.facilities.waste.domain;

import java.util.Set;

public enum CollectionStatus {
    SCHEDULED,
    COLLECTED,
    HANDED_OVER,
    DESTINATION_CONFIRMED,
    CLOSED,
    MISSED,
    CANCELLED;

    public boolean open() {
        return this != CLOSED && this != CANCELLED;
    }

    public boolean canMoveTo(CollectionStatus next) {
        return switch (this) {
            case SCHEDULED -> Set.of(COLLECTED, MISSED, CANCELLED).contains(next);
            // A missed collection can still be carried out late.
            case MISSED -> Set.of(COLLECTED, CANCELLED).contains(next);
            case COLLECTED -> Set.of(HANDED_OVER, CANCELLED).contains(next);
            case HANDED_OVER -> next == DESTINATION_CONFIRMED;
            case DESTINATION_CONFIRMED -> next == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }
}

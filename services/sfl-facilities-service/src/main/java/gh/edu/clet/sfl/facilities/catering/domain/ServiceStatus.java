package gh.edu.clet.sfl.facilities.catering.domain;

import java.util.Set;

public enum ServiceStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    CONFIRMED,
    DELIVERED,
    RECONCILED,
    CLOSED,
    CANCELLED;

    public boolean open() {
        return this != CLOSED && this != CANCELLED;
    }

    /** Before delivery a plan can still change; after it, only the reconciliation moves. */
    public boolean planning() {
        return this == DRAFT || this == PENDING_APPROVAL || this == APPROVED || this == CONFIRMED;
    }

    public boolean canMoveTo(ServiceStatus next) {
        return switch (this) {
            case DRAFT -> Set.of(PENDING_APPROVAL, CANCELLED).contains(next);
            case PENDING_APPROVAL -> Set.of(APPROVED, DRAFT, CANCELLED).contains(next);
            case APPROVED -> Set.of(CONFIRMED, PENDING_APPROVAL, CANCELLED).contains(next);
            case CONFIRMED -> Set.of(DELIVERED, PENDING_APPROVAL, CANCELLED).contains(next);
            case DELIVERED -> next == RECONCILED;
            case RECONCILED -> next == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }
}

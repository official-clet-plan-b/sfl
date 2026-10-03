package gh.edu.clet.sfl.facilities.lease.domain;

import java.util.Set;

public enum AgreementStatus {
    DRAFT, IN_REVIEW, ACTIVE, EXPIRED, TERMINATED, ARCHIVED;

    public boolean live() {
        return this == ACTIVE;
    }

    public boolean editable() {
        return this == DRAFT;
    }

    public boolean canMoveTo(AgreementStatus next) {
        return switch (this) {
            case DRAFT -> next == IN_REVIEW;
            case IN_REVIEW -> next == ACTIVE || next == DRAFT;
            case ACTIVE -> next == EXPIRED || next == TERMINATED;
            case EXPIRED, TERMINATED -> next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public static final Set<AgreementStatus> ENDED = Set.of(EXPIRED, TERMINATED, ARCHIVED);
}

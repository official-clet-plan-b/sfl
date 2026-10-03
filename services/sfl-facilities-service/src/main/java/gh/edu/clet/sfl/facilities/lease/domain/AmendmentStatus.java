package gh.edu.clet.sfl.facilities.lease.domain;

public enum AmendmentStatus {
    PROPOSED, LEGAL_REVIEW, APPROVED, REJECTED, WITHDRAWN;

    public boolean open() {
        return this == PROPOSED || this == LEGAL_REVIEW;
    }
}

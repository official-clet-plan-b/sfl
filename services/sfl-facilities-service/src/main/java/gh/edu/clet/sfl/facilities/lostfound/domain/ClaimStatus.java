package gh.edu.clet.sfl.facilities.lostfound.domain;

public enum ClaimStatus {
    RECEIVED,
    VERIFIED,
    APPROVED,
    REFUSED,
    RELEASED,
    WITHDRAWN;

    /** A claim still in play: it can block another claimant's release. */
    public boolean open() {
        return this == RECEIVED || this == VERIFIED || this == APPROVED;
    }
}

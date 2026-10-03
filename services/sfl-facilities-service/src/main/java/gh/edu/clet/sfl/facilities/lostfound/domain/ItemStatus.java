package gh.edu.clet.sfl.facilities.lostfound.domain;

public enum ItemStatus {
    REGISTERED,
    STORED,
    ISOLATED,
    RELEASED,
    DISPOSED,
    HANDED_TO_AUTHORITIES;

    public boolean open() {
        return this == REGISTERED || this == STORED || this == ISOLATED;
    }
}

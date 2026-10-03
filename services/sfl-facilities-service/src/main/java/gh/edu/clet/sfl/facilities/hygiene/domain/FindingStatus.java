package gh.edu.clet.sfl.facilities.hygiene.domain;

public enum FindingStatus {
    OPEN,
    IN_PROGRESS,
    AWAITING_VERIFICATION,
    CLOSED;

    public boolean open() {
        return this != CLOSED;
    }
}

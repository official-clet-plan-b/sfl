package gh.edu.clet.sfl.facilities.lostfound.domain;

public enum EscalationReason {
    UNSAFE_ITEM,
    COMPETING_CLAIMS,
    RETENTION_EXPIRED;

    /** Who it goes to: an unsafe item goes to security and the emergency procedures, the rest to security. */
    public String escalatedTo() {
        return this == UNSAFE_ITEM ? "SECURITY_EMERGENCY" : "SECURITY";
    }
}

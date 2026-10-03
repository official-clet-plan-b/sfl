package gh.edu.clet.sfl.facilities.hygiene.domain;

/** Who an overdue or critical item is pushed to. NONE is the finding's resting state, never stored as an escalation. */
public enum EscalationLevel {
    NONE,
    OWNER,
    HSE,
    LEADERSHIP;

    public boolean above(EscalationLevel other) {
        return ordinal() > other.ordinal();
    }
}

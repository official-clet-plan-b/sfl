package gh.edu.clet.sfl.facilities.hygiene.domain;

/**
 * Finding severity, and what each level obliges - SRS-SFL-S170-03.
 *
 * <p>How long the corrective action may take is a rule of the severity, kept here rather than as a
 * number a caller supplies, so a critical finding cannot be given a lenient deadline by whoever logs it.
 */
public enum Severity {
    LOW(30),
    MEDIUM(14),
    HIGH(7),
    CRITICAL(2);

    private final int actionSlaDays;

    Severity(int actionSlaDays) {
        this.actionSlaDays = actionSlaDays;
    }

    public int actionSlaDays() {
        return actionSlaDays;
    }

    public boolean critical() {
        return this == CRITICAL;
    }

    public boolean atLeast(Severity other) {
        return ordinal() >= other.ordinal();
    }
}

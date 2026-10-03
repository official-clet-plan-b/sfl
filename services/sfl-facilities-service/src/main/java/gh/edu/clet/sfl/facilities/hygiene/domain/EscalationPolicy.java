package gh.edu.clet.sfl.facilities.hygiene.domain;

/**
 * Who a finding is escalated to on creation - SRS-SFL-S170-03/05.
 *
 * <p>A critical finding goes to HSE at once; if it is the second critical finding of the same category
 * in the same place inside {@link #REPEAT_WINDOW_DAYS} days it goes to leadership as well, because a
 * repeat means the first corrective action did not hold.
 */
public final class EscalationPolicy {

    public static final int REPEAT_WINDOW_DAYS = 90;

    private EscalationPolicy() {
    }

    public static EscalationLevel onCreation(Severity severity, boolean repeatCritical) {
        if (!severity.critical()) {
            return EscalationLevel.NONE;
        }
        return repeatCritical ? EscalationLevel.LEADERSHIP : EscalationLevel.HSE;
    }
}

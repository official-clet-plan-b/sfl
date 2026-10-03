package gh.edu.clet.sfl.facilities.catering.domain;

import java.math.BigDecimal;

/**
 * Safe holding temperatures: hot food at 63 C or above, cold food at 5 C or below. The result of a temperature
 * check is worked out from the reading by these limits, never typed in, so a failing reading cannot be recorded
 * as a pass.
 */
public enum HoldType {
    HOT, COLD;

    private static final BigDecimal HOT_MINIMUM = new BigDecimal("63.0");
    private static final BigDecimal COLD_MAXIMUM = new BigDecimal("5.0");

    public boolean passes(BigDecimal celsius) {
        return this == HOT ? celsius.compareTo(HOT_MINIMUM) >= 0 : celsius.compareTo(COLD_MAXIMUM) <= 0;
    }
}

package gh.edu.clet.sfl.facilities.waste.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The arithmetic of the sustainability figures, kept pure so the rules are testable without a database.
 *
 * <p>Diversion is worked out from <em>measured</em> kilograms only. Estimated kilograms are totalled
 * separately and shown as such; they never inflate or deflate a percentage that is presented as measured.
 */
public final class WasteMetrics {

    private WasteMetrics() {
    }

    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : percent(BigDecimal.valueOf(part), BigDecimal.valueOf(whole));
    }
}

package gh.edu.clet.sfl.facilities.lease.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Financial exposure from the agreement's own terms: the rent still to run, pro rata by day, not a ledger balance. */
public final class ExposurePolicy {

    private ExposurePolicy() {
    }

    public static BigDecimal remainingRent(Agreement agreement, LocalDate today) {
        if (agreement.annualRent() == null || !agreement.endDate().isAfter(today)) {
            return BigDecimal.ZERO;
        }
        long days = ChronoUnit.DAYS.between(today.isBefore(agreement.startDate()) ? agreement.startDate() : today, agreement.endDate());
        return agreement.annualRent().multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP);
    }
}

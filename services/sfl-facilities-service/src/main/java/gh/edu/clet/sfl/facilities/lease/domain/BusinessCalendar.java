package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * The configured business calendar: which weekdays are the weekend and which dates are holidays. Notice and
 * renewal dates are worked out against it - SRS validation "renewal and notice calculations account for
 * configured business calendars and timezone".
 */
public record BusinessCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {

    public boolean isBusinessDay(LocalDate day) {
        return !weekend.contains(day.getDayOfWeek()) && !holidays.contains(day);
    }

    /** The last business day on or before {@code day}. A deadline that falls on a closed day is met the working day before it. */
    public LocalDate onOrBefore(LocalDate day) {
        LocalDate candidate = day;
        for (int guard = 0; guard < 400 && !isBusinessDay(candidate); guard++) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}

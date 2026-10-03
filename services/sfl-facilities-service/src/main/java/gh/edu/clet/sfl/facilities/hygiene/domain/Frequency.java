package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.LocalDate;

/**
 * How often a control recurs, and the one place the next due date is worked out.
 *
 * <p>The next date counts from the day the control was <em>due</em>, not the day it was done: a monthly
 * audit finished a week late is still due on the same day next month, otherwise every late completion
 * would quietly move the whole schedule later. If that date has already passed - the control was missed
 * by more than a period - it rolls forward to the first date after the completion day, so completing a
 * long-overdue control never creates a next one that is already overdue.
 *
 * <p>Month arithmetic clamps to the end of a shorter month (31 January + 1 month is 28 or 29 February).
 */
public enum Frequency {
    ONE_OFF,
    WEEKLY,
    FORTNIGHTLY,
    MONTHLY,
    QUARTERLY,
    BIANNUAL,
    ANNUAL;

    public boolean recurs() {
        return this != ONE_OFF;
    }

    public LocalDate next(LocalDate dueOn, LocalDate completedOn) {
        if (!recurs()) {
            return null;
        }
        LocalDate next = step(dueOn);
        while (!next.isAfter(completedOn)) {
            next = step(next);
        }
        return next;
    }

    private LocalDate step(LocalDate from) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(1);
            case FORTNIGHTLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case BIANNUAL -> from.plusMonths(6);
            case ANNUAL -> from.plusYears(1);
            case ONE_OFF -> throw new IllegalStateException("A one-off control does not recur");
        };
    }
}

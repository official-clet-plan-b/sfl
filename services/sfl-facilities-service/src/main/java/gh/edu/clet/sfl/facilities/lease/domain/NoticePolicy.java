package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.LocalDate;

/**
 * The last day notice can be served. It is the end date less the notice period, moved back to a business day if
 * it lands on the weekend or a holiday - never forward, which would miss the deadline. No notice period means no
 * date: the agreement is flagged incomplete rather than given a guessed one.
 */
public final class NoticePolicy {

    private NoticePolicy() {
    }

    public static LocalDate noticeDate(LocalDate endDate, Integer noticeDays, BusinessCalendar calendar) {
        if (endDate == null || noticeDays == null) {
            return null;
        }
        return calendar.onOrBefore(endDate.minusDays(noticeDays));
    }
}

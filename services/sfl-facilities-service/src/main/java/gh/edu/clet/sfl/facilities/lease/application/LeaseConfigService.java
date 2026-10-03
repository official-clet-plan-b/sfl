package gh.edu.clet.sfl.facilities.lease.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business calendar and timezone that notice and renewal dates are worked out against. Organisation-wide,
 * and changed only by an approver: a holiday added or a weekend moved shifts every notice date in the portfolio,
 * which is a decision with consequences, not a setting.
 */
@Service
public class LeaseConfigService {

    private final LeaseStore store;
    private final LeaseSupport support;

    public LeaseConfigService(LeaseStore store, LeaseSupport support) {
        this.store = store;
        this.support = support;
    }

    @Transactional(readOnly = true)
    public Calendar calendar(Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_LEASE_READ, "LeaseCalendar", "calendar");
        LeaseStore.Settings settings = store.settings();
        return new Calendar(settings.timezone(), settings.weekend(), store.holidayRows());
    }

    public record Calendar(String timezone, Set<DayOfWeek> weekend, List<LeaseStore.Holiday> holidays) {
    }

    @Transactional
    public Calendar saveSettings(String timezone, Set<DayOfWeek> weekend, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_LEASE_APPROVE, "LeaseCalendar", "settings");
        try {
            ZoneId.of(LeaseSupport.required(timezone, "timezone"));
        } catch (DateTimeException unknown) {
            throw new IllegalArgumentException("Unknown timezone: " + timezone);
        }
        if (weekend == null || weekend.size() > 3) {
            throw new IllegalArgumentException("weekend must name between none and three days");
        }
        store.saveSettings(timezone.strip(), weekend);
        support.audit(caller, AuditAction.LEASE_CALENDAR_CHANGED, "LeaseCalendar", UUID.nameUUIDFromBytes("settings".getBytes()),
                "*", null, timezone + " " + weekend);
        return calendar(caller);
    }

    @Transactional
    public Calendar addHoliday(LocalDate date, String name, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_LEASE_APPROVE, "LeaseCalendar", "holiday");
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        store.addHoliday(date, LeaseSupport.required(name, "name"));
        support.audit(caller, AuditAction.LEASE_CALENDAR_CHANGED, "LeaseCalendar", UUID.nameUUIDFromBytes(date.toString().getBytes()),
                "*", null, "holiday " + date);
        return calendar(caller);
    }

    @Transactional
    public Calendar removeHoliday(LocalDate date, Caller caller) {
        support.requireOrganisation(caller, SflPermission.FACILITIES_LEASE_APPROVE, "LeaseCalendar", "holiday");
        store.removeHoliday(date);
        support.audit(caller, AuditAction.LEASE_CALENDAR_CHANGED, "LeaseCalendar", UUID.nameUUIDFromBytes(date.toString().getBytes()),
                "*", "holiday " + date, null);
        return calendar(caller);
    }
}

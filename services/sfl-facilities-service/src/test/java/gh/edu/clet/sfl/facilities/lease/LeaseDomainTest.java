package gh.edu.clet.sfl.facilities.lease;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.facilities.lease.domain.ActivationPolicy;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementKind;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementStatus;
import gh.edu.clet.sfl.facilities.lease.domain.AlertLevel;
import gh.edu.clet.sfl.facilities.lease.domain.AlertPolicy;
import gh.edu.clet.sfl.facilities.lease.domain.AlertReason;
import gh.edu.clet.sfl.facilities.lease.domain.Amendment;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentKind;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentPolicy;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentStatus;
import gh.edu.clet.sfl.facilities.lease.domain.BusinessCalendar;
import gh.edu.clet.sfl.facilities.lease.domain.CounterpartyState;
import gh.edu.clet.sfl.facilities.lease.domain.Direction;
import gh.edu.clet.sfl.facilities.lease.domain.DocumentKind;
import gh.edu.clet.sfl.facilities.lease.domain.ExposurePolicy;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseDocument;
import gh.edu.clet.sfl.facilities.lease.domain.NoticePolicy;
import gh.edu.clet.sfl.facilities.lease.domain.RenewalType;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeaseDomainTest {

    private static final BusinessCalendar CALENDAR = new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            Set.of(LocalDate.of(2026, 12, 25)));

    @Test
    @DisplayName("a deadline on a weekend or holiday moves back to the last business day, never forward")
    void business_day() {
        assertThat(CALENDAR.isBusinessDay(LocalDate.of(2026, 6, 10))).isTrue();
        assertThat(CALENDAR.onOrBefore(LocalDate.of(2026, 6, 13))).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(CALENDAR.onOrBefore(LocalDate.of(2026, 6, 14))).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(CALENDAR.onOrBefore(LocalDate.of(2026, 12, 25))).isEqualTo(LocalDate.of(2026, 12, 24));
    }

    @Test
    @DisplayName("a holiday on a Monday carries the deadline back over the weekend to Friday")
    void holiday_after_weekend() {
        BusinessCalendar withMonday = new BusinessCalendar(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(LocalDate.of(2026, 6, 15)));
        assertThat(withMonday.onOrBefore(LocalDate.of(2026, 6, 15))).isEqualTo(LocalDate.of(2026, 6, 12));
    }

    @Test
    @DisplayName("the notice date is the end date less the notice period, adjusted to a business day; no notice period, no date")
    void notice_date() {
        assertThat(NoticePolicy.noticeDate(LocalDate.of(2027, 1, 31), 90, CALENDAR)).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(NoticePolicy.noticeDate(LocalDate.of(2027, 1, 31), 98, CALENDAR)).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(NoticePolicy.noticeDate(LocalDate.of(2027, 1, 31), null, CALENDAR)).isNull();
    }

    @Test
    @DisplayName("the chain is cumulative: ninety days the owner, sixty the manager too, thirty the director too, overdue everyone including legal")
    void alert_chain() {
        assertThat(AlertPolicy.owed(120)).isEmpty();
        assertThat(AlertPolicy.owed(90)).extracting(AlertPolicy.Due::level).containsExactly(AlertLevel.OWNER);
        assertThat(AlertPolicy.owed(45)).extracting(AlertPolicy.Due::level).containsExactly(AlertLevel.OWNER, AlertLevel.MANAGER);
        assertThat(AlertPolicy.owed(20)).extracting(AlertPolicy.Due::level).containsExactly(AlertLevel.OWNER, AlertLevel.MANAGER, AlertLevel.DIRECTOR);
        assertThat(AlertPolicy.owed(-1)).extracting(AlertPolicy.Due::level).containsExactly(AlertLevel.OWNER, AlertLevel.MANAGER,
                AlertLevel.DIRECTOR, AlertLevel.LEGAL);
        assertThat(AlertPolicy.owed(-1)).extracting(AlertPolicy.Due::reason).containsOnly(AlertReason.OVERDUE);
    }

    @Test
    @DisplayName("an agreement with everything in place and approval evidence on file is ready")
    void ready() {
        ActivationPolicy.Readiness readiness = ActivationPolicy.assess(agreement("CP-1", "owner", 90), List.of(document(DocumentKind.APPROVAL_EVIDENCE)));
        assertThat(readiness.ready()).isTrue();
        assertThat(readiness.warnings()).anyMatch(w -> w.contains("unresolved"));
    }

    @Test
    @DisplayName("every blocker is named: counterparty, owner, notice period, approval evidence")
    void blockers() {
        ActivationPolicy.Readiness readiness = ActivationPolicy.assess(agreement(null, null, null), List.of());
        assertThat(readiness.ready()).isFalse();
        assertThat(String.join(" ", readiness.blockers())).contains("counterparty", "owner", "notice period is missing", "Approval evidence");
    }

    @Test
    @DisplayName("a verified counterparty clears the warning; an unresolved one stays flagged rather than blocking")
    void counterparty_warning() {
        assertThat(ActivationPolicy.assess(agreement("CP-1", "owner", 90), List.of(document(DocumentKind.APPROVAL_EVIDENCE))).warnings())
                .anyMatch(w -> w.contains("not verified"));
    }

    @Test
    @DisplayName("amendments conflict when they touch the same terms, or either is a termination; unrelated ones do not")
    void conflicts() {
        Amendment rent = amendment(AmendmentKind.RENT_CHANGE, null, new BigDecimal("100"), AmendmentStatus.PROPOSED);
        Amendment otherRent = amendment(AmendmentKind.RENT_CHANGE, null, new BigDecimal("120"), AmendmentStatus.PROPOSED);
        Amendment term = amendment(AmendmentKind.TERM_CHANGE, LocalDate.of(2030, 1, 1), null, AmendmentStatus.PROPOSED);
        Amendment end = amendment(AmendmentKind.TERMINATION, null, null, AmendmentStatus.PROPOSED);
        assertThat(AmendmentPolicy.conflicts(otherRent, List.of(rent))).isTrue();
        assertThat(AmendmentPolicy.conflicts(term, List.of(rent))).isFalse();
        assertThat(AmendmentPolicy.conflicts(end, List.of(rent))).isTrue();
        assertThat(AmendmentPolicy.conflicts(rent, List.of(end))).isTrue();
        assertThat(AmendmentPolicy.conflicts(otherRent, List.of(amendment(AmendmentKind.RENT_CHANGE, null, new BigDecimal("90"), AmendmentStatus.REJECTED)))).isFalse();
    }

    @Test
    @DisplayName("remaining rent runs pro rata by day to the end date, and is nothing once the agreement has ended")
    void exposure() {
        Agreement a = new Agreement(UUID.randomUUID(), "LS-A-1", "S", null, "Block A", AgreementKind.LEASE, Direction.INBOUND, "t", "CP", CounterpartyState.UNRESOLVED,
                null, null, "o", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), RenewalType.NONE, null, 90, null, null, new BigDecimal("36500.00"),
                null, "GHS", AgreementStatus.ACTIVE, 1, null, null, null, null, "u", Instant.now(), Instant.now(), 0);
        assertThat(ExposurePolicy.remainingRent(a, LocalDate.of(2026, 7, 3))).isEqualByComparingTo("18200.00");
        assertThat(ExposurePolicy.remainingRent(a, LocalDate.of(2027, 2, 1))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the view without financials carries no rent or deposit")
    void masking() {
        Agreement a = agreement("CP", "o", 90);
        Agreement masked = new Agreement(a.id(), a.reference(), a.siteCode(), a.roomId(), a.propertyReference(), a.kind(), a.direction(), a.title(),
                a.counterpartyReference(), a.counterpartyState(), a.contractReference(), a.financeReference(), a.ownerReference(), a.startDate(),
                a.endDate(), a.renewalType(), a.renewalTermMonths(), a.noticeDays(), a.noticeDate(), a.rentReviewDate(), new BigDecimal("5"),
                new BigDecimal("1"), "GHS", a.status(), 1, null, null, null, null, "u", Instant.now(), Instant.now(), 0).withoutFinancials();
        assertThat(masked.annualRent()).isNull();
        assertThat(masked.depositAmount()).isNull();
        assertThat(masked.currency()).isNull();
    }

    @Test
    @DisplayName("an agreement moves forward through review to active; ended ones are archived; archived is final")
    void transitions() {
        assertThat(AgreementStatus.DRAFT.canMoveTo(AgreementStatus.ACTIVE)).isFalse();
        assertThat(AgreementStatus.IN_REVIEW.canMoveTo(AgreementStatus.ACTIVE)).isTrue();
        assertThat(AgreementStatus.ACTIVE.canMoveTo(AgreementStatus.TERMINATED)).isTrue();
        assertThat(AgreementStatus.TERMINATED.canMoveTo(AgreementStatus.ARCHIVED)).isTrue();
        assertThat(AgreementStatus.ARCHIVED.canMoveTo(AgreementStatus.ACTIVE)).isFalse();
    }

    private static Agreement agreement(String counterparty, String owner, Integer noticeDays) {
        return new Agreement(UUID.randomUUID(), "LS-A-1", "S", null, "Block A", AgreementKind.LEASE, Direction.INBOUND, "Office lease", counterparty,
                CounterpartyState.UNRESOLVED, null, null, owner, LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1), RenewalType.OPTION, 12, noticeDays,
                null, null, null, null, null, AgreementStatus.IN_REVIEW, 1, "u", null, null, null, "u", Instant.now(), Instant.now(), 0);
    }

    private static LeaseDocument document(DocumentKind kind) {
        return new LeaseDocument(UUID.randomUUID(), UUID.randomUUID(), "S", kind, "R", "f.pdf", "application/pdf", 1, "0".repeat(64), "LEGAL", null,
                "u", Instant.now());
    }

    private static Amendment amendment(AmendmentKind kind, LocalDate newEnd, BigDecimal newRent, AmendmentStatus status) {
        return new Amendment(UUID.randomUUID(), "LS-M-1", UUID.randomUUID(), "S", kind, true, status, "r", newEnd, newRent, null, null, null, null,
                null, 1, "u", Instant.now(), null, null, null, null, null, null, 0);
    }
}

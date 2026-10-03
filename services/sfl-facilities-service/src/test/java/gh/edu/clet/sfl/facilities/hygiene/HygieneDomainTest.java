package gh.edu.clet.sfl.facilities.hygiene;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.facilities.hygiene.domain.ActionStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosurePolicy;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationLevel;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationPolicy;
import gh.edu.clet.sfl.facilities.hygiene.domain.EvidenceStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.Frequency;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEvidence;
import gh.edu.clet.sfl.facilities.hygiene.domain.Severity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HygieneDomainTest {

    @Test
    @DisplayName("the next due date counts from the due date, so a late completion does not drift the schedule")
    void next_due_counts_from_the_due_date() {
        assertThat(Frequency.MONTHLY.next(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 17)))
                .isEqualTo(LocalDate.of(2026, 4, 10));
    }

    @Test
    @DisplayName("a long-overdue completion never produces a successor that is already overdue")
    void next_due_is_after_the_completion_day() {
        assertThat(Frequency.WEEKLY.next(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 20)))
                .isEqualTo(LocalDate.of(2026, 1, 22));
    }

    @Test
    @DisplayName("month arithmetic clamps to the end of a shorter month")
    void month_end_is_clamped() {
        assertThat(Frequency.MONTHLY.next(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31)))
                .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Frequency.ANNUAL.next(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 1)))
                .isEqualTo(LocalDate.of(2029, 2, 28));
    }

    @Test
    @DisplayName("quarterly, biannual and fortnightly steps")
    void other_steps() {
        LocalDate due = LocalDate.of(2026, 5, 15);
        assertThat(Frequency.QUARTERLY.next(due, due)).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(Frequency.BIANNUAL.next(due, due)).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(Frequency.FORTNIGHTLY.next(due, due)).isEqualTo(LocalDate.of(2026, 5, 29));
    }

    @Test
    @DisplayName("a one-off control has no successor")
    void one_off_does_not_recur() {
        assertThat(Frequency.ONE_OFF.recurs()).isFalse();
        assertThat(Frequency.ONE_OFF.next(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1))).isNull();
    }

    @Test
    @DisplayName("DUE and OVERDUE are derived from the date, and only for open controls")
    void effective_status() {
        LocalDate today = LocalDate.of(2026, 6, 10);
        assertThat(ControlStatus.effective(ControlStatus.SCHEDULED, today.plusDays(1), today)).isEqualTo("SCHEDULED");
        assertThat(ControlStatus.effective(ControlStatus.SCHEDULED, today, today)).isEqualTo("DUE");
        assertThat(ControlStatus.effective(ControlStatus.SCHEDULED, today.minusDays(1), today)).isEqualTo("OVERDUE");
        assertThat(ControlStatus.effective(ControlStatus.IN_PROGRESS, today.minusDays(1), today)).isEqualTo("OVERDUE");
        assertThat(ControlStatus.effective(ControlStatus.COMPLETED, today.minusDays(9), today)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a completed or cancelled control is final; a missed one can still be carried out late")
    void control_transitions() {
        assertThat(ControlStatus.COMPLETED.canMoveTo(ControlStatus.SCHEDULED)).isFalse();
        assertThat(ControlStatus.CANCELLED.canMoveTo(ControlStatus.IN_PROGRESS)).isFalse();
        assertThat(ControlStatus.MISSED.canMoveTo(ControlStatus.COMPLETED)).isTrue();
        assertThat(ControlStatus.SCHEDULED.canMoveTo(ControlStatus.IN_PROGRESS)).isTrue();
    }

    @Test
    @DisplayName("an action is verified or rejected only after it is completed")
    void action_transitions() {
        assertThat(ActionStatus.OPEN.canMoveTo(ActionStatus.VERIFIED)).isFalse();
        assertThat(ActionStatus.COMPLETED.canMoveTo(ActionStatus.VERIFIED)).isTrue();
        assertThat(ActionStatus.COMPLETED.canMoveTo(ActionStatus.REJECTED)).isTrue();
        assertThat(ActionStatus.REJECTED.canMoveTo(ActionStatus.IN_PROGRESS)).isTrue();
        assertThat(ActionStatus.VERIFIED.canMoveTo(ActionStatus.OPEN)).isFalse();
    }

    @Test
    @DisplayName("the corrective-action SLA tightens with severity and only critical is critical")
    void severity_sla() {
        assertThat(Severity.CRITICAL.actionSlaDays()).isLessThan(Severity.HIGH.actionSlaDays());
        assertThat(Severity.HIGH.actionSlaDays()).isLessThan(Severity.MEDIUM.actionSlaDays());
        assertThat(Severity.MEDIUM.actionSlaDays()).isLessThan(Severity.LOW.actionSlaDays());
        assertThat(Severity.CRITICAL.critical()).isTrue();
        assertThat(Severity.HIGH.critical()).isFalse();
    }

    @Test
    @DisplayName("critical goes to HSE, a repeat critical to leadership, anything else to nobody")
    void escalation_on_creation() {
        assertThat(EscalationPolicy.onCreation(Severity.CRITICAL, false)).isEqualTo(EscalationLevel.HSE);
        assertThat(EscalationPolicy.onCreation(Severity.CRITICAL, true)).isEqualTo(EscalationLevel.LEADERSHIP);
        assertThat(EscalationPolicy.onCreation(Severity.HIGH, true)).isEqualTo(EscalationLevel.NONE);
    }

    @Test
    @DisplayName("submitted evidence alone closes nothing; accepted evidence with every action verified does")
    void closure_by_evidence() {
        assertThat(ClosurePolicy.closableByEvidence(List.of(evidence(EvidenceStatus.SUBMITTED)), List.of())).isFalse();
        assertThat(ClosurePolicy.closableByEvidence(List.of(evidence(EvidenceStatus.REJECTED)), List.of())).isFalse();
        assertThat(ClosurePolicy.closableByEvidence(List.of(evidence(EvidenceStatus.ACCEPTED)),
                List.of(action(ActionStatus.COMPLETED)))).isFalse();
        assertThat(ClosurePolicy.closableByEvidence(List.of(evidence(EvidenceStatus.ACCEPTED)),
                List.of(action(ActionStatus.VERIFIED)))).isTrue();
    }

    @Test
    @DisplayName("an exception needs a real reason and an approver who is not the requester")
    void closure_by_exception() {
        assertThat(ClosurePolicy.validException("Accepted: tenant refuses access", "a", "b")).isTrue();
        assertThat(ClosurePolicy.validException("short", "a", "b")).isFalse();
        assertThat(ClosurePolicy.validException(null, "a", "b")).isFalse();
        assertThat(ClosurePolicy.validException("Accepted: tenant refuses access", "a", "a")).isFalse();
    }

    private static HygieneEvidence evidence(EvidenceStatus status) {
        return new HygieneEvidence(UUID.randomUUID(), UUID.randomUUID(), null, "S", "ref", "f.pdf", "application/pdf",
                1, "0".repeat(64), "COMPLIANCE", null, status, "u", Instant.now(), null, null, null);
    }

    private static HygieneAction action(ActionStatus status) {
        return new HygieneAction(UUID.randomUUID(), UUID.randomUUID(), "S", "d", "o", LocalDate.now(), status, null,
                null, null, null, null, "u", Instant.now(), Instant.now(), 0);
    }
}

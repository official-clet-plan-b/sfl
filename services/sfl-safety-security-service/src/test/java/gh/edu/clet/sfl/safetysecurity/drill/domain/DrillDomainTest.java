package gh.edu.clet.sfl.safetysecurity.drill.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ComplianceStanding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.Drill;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillExecution;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillModule;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillReview;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.FrequencyRequirement;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.RecordMetadata;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ClosurePolicy;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ExecutionReadinessPolicy;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.FrequencyCompliancePolicy;
import gh.edu.clet.sfl.safetysecurity.drill.domain.policy.ReviewCompletenessPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DrillDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-02");

    private static DrillPlan plan(DrillType type, List<ModuleExpectation> expectations) {
        return new DrillPlan(type, "Block A evacuation", null, null, "BLOCK-A", NOW, UUID.randomUUID(),
                List.of(UUID.randomUUID()), List.of(), expectations);
    }

    private static void refused(DrillErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DrillException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("S175-05: a combined drill names two modules, and every expectation says how success is measured")
    void readiness() {
        refused(DrillErrorCode.DRILL_EXPECTATIONS_REQUIRED, () -> ExecutionReadinessPolicy.requireReady(plan(
                DrillType.COMBINED,
                List.of(ModuleExpectation.planned(DrillModule.S166_FLEET, "Ready", "On standby")))));
        refused(DrillErrorCode.DRILL_EXPECTATIONS_REQUIRED, () -> ExecutionReadinessPolicy.requireReady(plan(
                DrillType.FIRE, List.of(ModuleExpectation.planned(DrillModule.S166_FLEET, "Ready", null)))));
        refused(DrillErrorCode.DRILL_NOT_READY, () -> ExecutionReadinessPolicy.requireReady(new DrillPlan(
                DrillType.FIRE, "t", null, null, null, null, null, null, null, null)));
        ExecutionReadinessPolicy.requireReady(plan(DrillType.COMBINED, List.of(
                ModuleExpectation.planned(DrillModule.S160A_ACCESS_CONTROL, "Lockdown", "Doors locked in 60s"),
                ModuleExpectation.planned(DrillModule.S166_FLEET, "Vehicle ready", "No real dispatch"))));
    }

    @Test
    @DisplayName("lifecycle: a postponed or cancelled drill keeps a reason; nothing skips the review")
    void lifecycle() {
        RecordMetadata meta = RecordMetadata.createdBy("hse", NOW, SourceChannel.WEB, null);
        Drill drill = Drill.plan(UUID.randomUUID(), "main", "DRL-1", plan(DrillType.FIRE, List.of()), meta);
        assertThat(drill.siteCode()).isEqualTo("MAIN");
        refused(DrillErrorCode.DRILL_REASON_REQUIRED, () -> drill.cancel(" ", meta));
        refused(DrillErrorCode.DRILL_INVALID_STATE_TRANSITION, () -> drill.start(meta));
        Drill completed = drill.schedule(NOW, meta).start(meta).completeRollCall(meta);
        refused(DrillErrorCode.DRILL_INVALID_STATE_TRANSITION, () -> completed.close(null, meta));
        assertThat(completed.markReviewed(meta).status().countsTowardCompliance()).isTrue();
        assertThat(DrillStatus.CANCELLED.countsTowardCompliance()).isFalse();
    }

    @Test
    @DisplayName("S175-02 Stale Baseline: old or missing access data marks the baseline stale")
    void stale_baseline() {
        Duration window = Duration.ofMinutes(15);
        assertThat(DrillExecution.isStale(NOW, NOW.minusSeconds(60), window)).isFalse();
        assertThat(DrillExecution.isStale(NOW, NOW.minus(Duration.ofMinutes(16)), window)).isTrue();
        assertThat(DrillExecution.isStale(NOW, null, window)).isTrue();
    }

    @Test
    @DisplayName("S175-03 Unactioned Finding: each finding needs an action or a justification")
    void unactioned_findings() {
        UUID drillId = UUID.randomUUID();
        DrillFinding actioned = new DrillFinding(UUID.randomUUID(), drillId, "MAIN", 1, "a", null, "hse", NOW);
        DrillFinding justified = new DrillFinding(UUID.randomUUID(), drillId, "MAIN", 2, "b", "Known", "hse", NOW);
        DrillFinding bare = new DrillFinding(UUID.randomUUID(), drillId, "MAIN", 3, "c", null, "hse", NOW);
        DrillReview review = new DrillReview(drillId, "MAIN", "Summary", null, "hse", NOW, null, null);

        assertThat(ReviewCompletenessPolicy.unactioned(List.of(actioned, justified, bare), Set.of(actioned.id())))
                .containsExactly(bare);
        refused(DrillErrorCode.DRILL_UNACTIONED_FINDING, () -> ReviewCompletenessPolicy.requireSubmittable(review,
                plan(DrillType.FIRE, List.of()), List.of(), List.of(actioned, bare), Set.of(actioned.id())));
        refused(DrillErrorCode.DRILL_REVIEW_SUMMARY_REQUIRED, () -> ReviewCompletenessPolicy.requireSubmittable(
                new DrillReview(drillId, "MAIN", " ", null, "hse", NOW, null, null), plan(DrillType.FIRE, List.of()),
                List.of(), List.of(), Set.of()));
    }

    @Test
    @DisplayName("S175-03 closure: overdue open actions need a deferral reason; actions not yet due do not")
    void closure() {
        DrillFinding finding = new DrillFinding(UUID.randomUUID(), UUID.randomUUID(), "MAIN", 1, "a", null, "hse",
                NOW);
        DrillCorrectiveAction overdue = DrillCorrectiveAction.open(UUID.randomUUID(), finding, "Fix", "fac",
                TODAY.minusDays(1), "hse", NOW);
        DrillCorrectiveAction notDue = DrillCorrectiveAction.open(UUID.randomUUID(), finding, "Fix", "fac",
                TODAY.plusDays(10), "hse", NOW);

        assertThat(ClosurePolicy.requireClosable(List.of(notDue), TODAY, null)).isNull();
        refused(DrillErrorCode.DRILL_OVERDUE_ACTIONS_OPEN,
                () -> ClosurePolicy.requireClosable(List.of(overdue), TODAY, " "));
        assertThat(ClosurePolicy.requireClosable(List.of(overdue), TODAY, "Parts on order"))
                .isEqualTo("Parts on order");
        assertThat(ClosurePolicy.requireClosable(List.of(overdue.verify("Done", "hse", NOW)), TODAY, null)).isNull();
    }

    @Test
    @DisplayName("S175-04: due one interval after the last reviewed drill, or after the requirement took effect")
    void frequency() {
        FrequencyRequirement quarterly = new FrequencyRequirement(UUID.randomUUID(), "MAIN", DrillType.FIRE, 90, 14,
                NOW.minus(Duration.ofDays(200)), null, RecordMetadata.createdBy("dir", NOW, SourceChannel.WEB, null));

        Instant neverDrilled = FrequencyCompliancePolicy.dueAt(quarterly, null);
        assertThat(FrequencyCompliancePolicy.standing(quarterly, neverDrilled, NOW))
                .isEqualTo(ComplianceStanding.COMPLIANCE_GAP);
        Instant afterRecent = FrequencyCompliancePolicy.dueAt(quarterly, NOW.minus(Duration.ofDays(80)));
        assertThat(FrequencyCompliancePolicy.standing(quarterly, afterRecent, NOW))
                .isEqualTo(ComplianceStanding.DUE_SOON);
        Instant afterFresh = FrequencyCompliancePolicy.dueAt(quarterly, NOW.minus(Duration.ofDays(10)));
        assertThat(FrequencyCompliancePolicy.standing(quarterly, afterFresh, NOW))
                .isEqualTo(ComplianceStanding.COMPLIANT);
    }
}

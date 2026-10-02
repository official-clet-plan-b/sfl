package gh.edu.clet.sfl.safetysecurity.drill.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.port.AccessControlRepository;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessDirection;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessEvent;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessEventKind;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessZone;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillComplianceService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillCorrectiveActionService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillDetail;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillExecutionService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillPlanningService;
import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillReviewService;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ComplianceStanding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillCorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillFinding;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillModule;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillStatus;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ExpectationOutcome;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.GapFollowUp;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository;
import gh.edu.clet.sfl.safetysecurity.emergency.application.service.EmergencyRecordsService;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.AudienceGroup;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.ChannelType;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationActivation;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.model.NotificationTemplate;
import gh.edu.clet.sfl.safetysecurity.emergency.domain.policy.DrillSeparationPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S175's acceptance criteria against a real PostgreSQL, through the real S174 drill mode and the real S162a muster:
 * plan, schedule, run, review and close a drill, and the frequency-compliance sweep.
 */
@SpringBootTest(properties = {"sfl.security.enabled=false", "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.emergency.messaging.drainer-enabled=false", "sfl.life-safety.scheduling.enabled=false",
        "sfl.emergency.scheduling.enabled=false", "sfl.drill.scheduling.enabled=false"})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class DrillMandatoryScenariosEndToEndTest extends SafetySecurityPostgresSupport {

    private static final String MARKER = DrillSeparationPolicy.MARKER;

    @Autowired DrillPlanningService planning;
    @Autowired DrillExecutionService execution;
    @Autowired DrillReviewService reviews;
    @Autowired DrillCorrectiveActionService actions;
    @Autowired DrillComplianceService compliance;
    @Autowired EmergencyRecordsService emergency;
    @Autowired EmergencyRepository emergencyRepository;
    @Autowired AccessControlRepository access;
    @Autowired JdbcTemplate jdbc;

    private String site;
    private ActorContext hse;
    private ActorContext soc;
    private AudienceGroup audience;
    private NotificationTemplate drillTemplate;
    private NotificationTemplate realTemplate;

    @BeforeEach
    void fixture() {
        site = "DRL" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        hse = actor("hse", SflRole.HSE_MANAGER);
        soc = actor("soc", SflRole.SOC_OPERATOR);
        ActorContext director = actor("director", SflRole.SECURITY_DIRECTOR);
        audience = emergency.createAudienceGroup(new EmergencyRecordsService.CreateAudienceGroup(site, null,
                "All staff", "dir://all", 10, director, emergencySource()));
        drillTemplate = emergency.createTemplate(new EmergencyRecordsService.CreateTemplate(site, null,
                MARKER + ": fire drill", MARKER + ". Assemble at point B.", List.of(ChannelType.SMS), false, director,
                emergencySource(), true));
        realTemplate = emergency.createTemplate(new EmergencyRecordsService.CreateTemplate(site, null, "Evacuate now",
                "Leave the building immediately.", List.of(ChannelType.SMS), false, director, emergencySource(),
                false));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        access.saveZone(new AccessZone(UUID.randomUUID(), site, "BLOCK-A", "Block A", null, "24x7", Map.of(), false,
                List.of(), null, accessMeta(now)));
        accessEvent("STAFF-1", AccessDirection.ENTRY, now.minusSeconds(600));
        accessEvent("STAFF-3", AccessDirection.ENTRY, now.minusSeconds(500));
        accessEvent("STAFF-2", AccessDirection.ENTRY, now.minusSeconds(450));
        accessEvent("STAFF-2", AccessDirection.EXIT, now.minusSeconds(400));
    }

    @Test
    @DisplayName("S175-01: a plan naming a real S174 template is refused at scheduling with Test/Real Ambiguity")
    void real_template_is_refused_at_setup() {
        DrillDetail planned = plan(DrillType.FIRE, realTemplate.id(), List.of());

        assertThatThrownBy(() -> planning.schedule(planned.drill().id(), null, null, hse, SourceChannel.WEB))
                .isInstanceOfSatisfying(DrillException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DrillErrorCode.DRILL_TEST_REAL_AMBIGUITY));
        assertThat(planning.get(planned.drill().id(), hse).drill().status()).isEqualTo(DrillStatus.PLANNED);
        assertThat(planning.drillTemplates(site, hse)).extracting(t -> t.templateId())
                .containsExactly(drillTemplate.id());
    }

    @Test
    @DisplayName("S175-05: a combined drill without explicit per-module expectations cannot be scheduled")
    void combined_drill_needs_explicit_expectations() {
        DrillDetail vague = plan(DrillType.COMBINED, drillTemplate.id(),
                List.of(ModuleExpectation.planned(DrillModule.S160A_ACCESS_CONTROL, "Simulate lockdown", null)));

        assertThatThrownBy(() -> planning.schedule(vague.drill().id(), null, null, hse, SourceChannel.WEB))
                .isInstanceOfSatisfying(DrillException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DrillErrorCode.DRILL_EXPECTATIONS_REQUIRED));
    }

    @Test
    @DisplayName("S175-01/-02/-03/-05: plan, run on the real path, gap list, review, CAPA and closure")
    void a_drill_end_to_end() {
        DrillDetail planned = plan(DrillType.COMBINED, drillTemplate.id(), List.of(
                ModuleExpectation.planned(DrillModule.S160A_ACCESS_CONTROL, "Simulate lockdown of Block A",
                        "All Block A doors report locked within 60s"),
                ModuleExpectation.planned(DrillModule.S174_MASS_NOTIFICATION, "Drill notification reaches staff",
                        "95% delivered")));
        UUID id = planned.drill().id();
        DrillDetail scheduled = planning.schedule(id, null, 0L, hse, SourceChannel.WEB);
        assertThat(scheduled.drill().status()).isEqualTo(DrillStatus.SCHEDULED);

        // S175-01/-02: start - baseline from live occupancy, S162a muster, S174 drill-mode notification.
        DrillDetail started = execution.start(id, scheduled.drill().metadata().version(), soc, SourceChannel.WEB);
        assertThat(started.drill().status()).isEqualTo(DrillStatus.IN_PROGRESS);
        assertThat(started.execution().baselineCount()).isEqualTo(2);
        assertThat(started.execution().baselineStale()).isFalse();
        NotificationActivation activation = emergencyRepository
                .findActivation(started.execution().notificationActivationId()).orElseThrow();
        assertThat(activation.mode()).isEqualTo(NotificationActivation.Mode.DRILL);
        assertThat(activation.incidentReference()).isEqualTo(started.drill().reference());

        execution.checkIn(id, "STAFF-1", soc);
        execution.checkIn(id, "VISITOR-X", soc);
        var rollCall = execution.rollCall(id, soc);
        assertThat(rollCall.outstanding()).extracting(p -> p.personRef()).containsExactly("STAFF-3");
        assertThat(rollCall.unexpected()).containsExactly("VISITOR-X");

        // S175-02 AC: everyone on site per the baseline who did not check in is on the gap list.
        DrillDetail completed = execution.closeRollCall(id, started.drill().metadata().version(), soc,
                SourceChannel.WEB);
        assertThat(completed.drill().status()).isEqualTo(DrillStatus.COMPLETED);
        assertThat(completed.gaps()).extracting(g -> g.personRef()).containsExactly("STAFF-3");
        assertThat(completed.execution().checkedInCount()).isEqualTo(1);
        assertThat(completed.execution().notificationToMusterSeconds()).isNotNull();

        reviews.recordReview(id, "Block A cleared in 4 minutes; one person unaccounted.", null, hse,
                SourceChannel.WEB);
        refused(DrillErrorCode.DRILL_GAPS_UNRESOLVED, () -> reviews.submit(id, null, hse, SourceChannel.WEB));
        execution.followUpGap(id, completed.gaps().get(0).id(), GapFollowUp.NOT_ON_SITE,
                "Left by the side gate without badging out", soc, SourceChannel.WEB);

        DrillFinding badging = reviews.addFinding(id, "Side gate allows exit without badging out", hse,
                SourceChannel.WEB);
        DrillFinding signage = reviews.addFinding(id, "Assembly signage faded", hse, SourceChannel.WEB);
        refused(DrillErrorCode.DRILL_EXPECTATION_OUTCOME_REQUIRED,
                () -> reviews.submit(id, null, hse, SourceChannel.WEB));
        DrillDetail judged = reviews.judgeExpectations(id, List.of(
                new DrillReviewService.ExpectationJudgement(0, ExpectationOutcome.MET, null),
                new DrillReviewService.ExpectationJudgement(1, ExpectationOutcome.PARTIALLY_MET, "88% delivered")),
                completed.drill().metadata().version(), hse, SourceChannel.WEB);

        // S175-03 AC: every finding needs a corrective action or a justification - "Unactioned Finding".
        assertThat(judged.unactionedFindingIds()).containsExactlyInAnyOrder(badging.id(), signage.id());
        refused(DrillErrorCode.DRILL_UNACTIONED_FINDING, () -> reviews.submit(id, null, hse, SourceChannel.WEB));
        DrillCorrectiveAction overdue = actions.open(id, badging.id(), "Fit a reader on the side gate", "facilities",
                LocalDate.now().minusDays(1), hse, SourceChannel.WEB);
        reviews.justifyNoAction(id, signage.id(), "Already scheduled for replacement under work order WO-77", hse,
                SourceChannel.WEB);
        refused(DrillErrorCode.DRILL_FINDING_ACTIONED,
                () -> reviews.justifyNoAction(id, badging.id(), "Not needed", hse, SourceChannel.WEB));

        DrillDetail reviewed = reviews.submit(id, judged.drill().metadata().version(), hse, SourceChannel.WEB);
        assertThat(reviewed.drill().status()).isEqualTo(DrillStatus.REVIEWED);

        // S175-03 validation: overdue open actions hold the drill open unless deferred with a reason.
        assertThat(reviewed.overdueActionIds()).containsExactly(overdue.id());
        refused(DrillErrorCode.DRILL_OVERDUE_ACTIONS_OPEN, () -> reviews.close(id, null, null, hse, SourceChannel.WEB));
        DrillDetail closed = reviews.close(id, "Reader on order; due next month", null, hse, SourceChannel.WEB);
        assertThat(closed.drill().status()).isEqualTo(DrillStatus.CLOSED);
        assertThat(closed.drill().statusReason()).isEqualTo("Reader on order; due next month");

        // The action is still tracked after closure, on the shared HSE panel.
        assertThat(actions.counts(site, hse).overdue()).isEqualTo(1);
        actions.verify(id, overdue.id(), "Reader fitted and tested", hse, SourceChannel.WEB);
        assertThat(actions.counts(site, hse).verified()).isEqualTo(1);

        assertThat(outboxEvents(id)).contains("sfl.ssemp.drill-scheduled.v1", "sfl.ssemp.drill-started.v1",
                "sfl.ssemp.drill-completed.v1", "sfl.ssemp.drill-closed.v1");
    }

    @Test
    @DisplayName("S175-04: a lapsed site raises Compliance Gap once per due date; a reviewed drill restores it")
    void compliance_gap_is_flagged_once() {
        ActorContext director = actor("director", SflRole.SECURITY_DIRECTOR);
        var row = compliance.setRequirement(new DrillComplianceService.SetRequirement(site, DrillType.FIRE, 90, 14,
                Instant.now().minus(Duration.ofDays(120)), director, SourceChannel.WEB));
        assertThat(row.standing()).isEqualTo(ComplianceStanding.COMPLIANCE_GAP);

        ActorContext system = new ActorContext(new SiteScopedPrincipal("sweep", "Sweep", Set.of(SflRole.SFL_ADMIN),
                Set.of("*"), true), "sweep");
        assertThat(compliance.sweep(row.requirement().id(), system)).isTrue();
        assertThat(compliance.sweep(row.requirement().id(), system)).as("not re-flagged for the same lapse").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_security.outbox_messages WHERE aggregate_id = ? "
                + "AND event_type = 'sfl.ssemp.drill-compliance-gap.v1'", Long.class,
                row.requirement().id().toString())).isEqualTo(1L);

        DrillDetail cancelled = planning.cancel(plan(DrillType.FIRE, drillTemplate.id(), List.of()).drill().id(),
                "Clashes with exams", null, hse, SourceChannel.WEB);
        assertThat(cancelled.drill().status()).isEqualTo(DrillStatus.CANCELLED);
        assertThat(compliance.compliance(site, director)).singleElement()
                .satisfies(r -> assertThat(r.standing()).as("a cancelled drill never counts")
                        .isEqualTo(ComplianceStanding.COMPLIANCE_GAP));
    }

    @Test
    @DisplayName("an Idempotency-Key retry returns the first drill; a different body under the same key is refused")
    void idempotent_planning() {
        DrillPlan plan = plan(DrillType.FIRE, drillTemplate.id(), List.of()).drill().plan();
        String key = "key-" + site; // the e2e database outlives a run; keys are per caller, so make it per run
        DrillDetail first = planning.create(new DrillPlanningService.CreateDrill(site, plan, key, hse,
                SourceChannel.WEB));
        DrillDetail retry = planning.create(new DrillPlanningService.CreateDrill(site, plan, key, hse,
                SourceChannel.WEB));
        assertThat(retry.drill().id()).isEqualTo(first.drill().id());
        refused(DrillErrorCode.DRILL_IDEMPOTENCY_KEY_CONFLICT, () -> planning.create(new DrillPlanningService
                .CreateDrill(site, plan.rescheduledFor(Instant.now().plusSeconds(99)), key, hse,
                SourceChannel.WEB)));
    }

    @Test
    @DisplayName("an actor scoped to another site can neither read nor run this site's drills")
    void site_scope_is_enforced() {
        DrillDetail planned = plan(DrillType.FIRE, drillTemplate.id(), List.of());
        ActorContext elsewhere = new ActorContext(new SiteScopedPrincipal("other", "Other",
                Set.of(SflRole.HSE_MANAGER), Set.of("ELSEWHERE"), false), "drill-e2e");

        refused(DrillErrorCode.DRILL_UNAUTHORIZED_SCOPE, () -> planning.get(planned.drill().id(), elsewhere));
        assertThat(planning.search(new DrillPlanningService.SearchDrills(null, null, null, null, null, null,
                new gh.edu.clet.sfl.safetysecurity.drill.application.port.Paging(0, 50, null), elsewhere)).content())
                .extracting(d -> d.siteCode()).doesNotContain(site);
    }

    private DrillDetail plan(DrillType type, UUID templateId, List<ModuleExpectation> expectations) {
        return planning.create(new DrillPlanningService.CreateDrill(site, new DrillPlan(type, "Block A evacuation",
                "Smoke reported on the second floor", "All Block A staff and visitors", "BLOCK-A",
                Instant.now().plus(Duration.ofDays(2)), templateId, List.of(audience.id()), List.of(), expectations),
                null, hse, SourceChannel.WEB));
    }

    private List<String> outboxEvents(UUID drillId) {
        return jdbc.queryForList("SELECT event_type FROM safety_security.outbox_messages WHERE aggregate_id = ?",
                String.class, drillId.toString());
    }

    private static void refused(DrillErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DrillException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private ActorContext actor(String id, SflRole role) {
        return new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(site), false), "drill-e2e");
    }

    private static gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel emergencySource() {
        return gh.edu.clet.sfl.safetysecurity.emergency.domain.model.SourceChannel.WEB;
    }

    private void accessEvent(String person, AccessDirection direction, Instant at) {
        access.saveEvent(new AccessEvent(UUID.randomUUID(), site, "VENDOR-SIM", "ext-" + UUID.randomUUID(), "RDR-1",
                "DOOR-1", "BLOCK-A", person, AccessEventKind.GRANTED, direction, at, accessMeta(at)));
    }

    private static gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.RecordMetadata accessMeta(Instant at) {
        return gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.RecordMetadata.createdBy("vendor", at,
                gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.SourceChannel.INTEGRATION, "drill-e2e");
    }
}

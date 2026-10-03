package gh.edu.clet.sfl.safetysecurity.permit.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentReportingService;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentSource;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.Paging;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.Caller;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitApprovalService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitDetail;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitFlagService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitLifecycleService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitMonitoringService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitQueryService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitRequestService;
import gh.edu.clet.sfl.safetysecurity.permit.application.service.PermitTypeService;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitErrorCode;
import gh.edu.clet.sfl.safetysecurity.permit.domain.exception.PermitException;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EscalationLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EvidenceKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.Isolation;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.OriginSystem;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitStatus;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitType;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitWorker;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.WorkRole;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentAuthoringService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlMeasure;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskScore;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Severity;
import gh.edu.clet.sfl.safetysecurity.riskassessment.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Database-backed proof of S164's acceptance criteria and error states, driven through the real application services and the real S165
 * against a real PostgreSQL - the shape the S165 and S175 scenario tests use. Each test works at its own site.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.risk-assessment.scheduling.enabled=false",
        "sfl.permit.scheduling.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable", disabledReason = "No PostgreSQL available")
class PermitMandatoryScenariosEndToEndTest extends SafetySecurityPostgresSupport {

    private static final Instant START = Instant.parse("2026-10-01T09:00:00Z");

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock clock() {
            return new MutableClock(START);
        }
    }

    @Autowired private MutableClock clock;
    @Autowired private PermitRequestService requests;
    @Autowired private PermitApprovalService approvals;
    @Autowired private PermitLifecycleService lifecycle;
    @Autowired private PermitQueryService queries;
    @Autowired private PermitFlagService flags;
    @Autowired private PermitMonitoringService monitoring;
    @Autowired private PermitTypeService types;
    @Autowired private RiskAssessmentAuthoringService authoring;
    @Autowired private IncidentReportingService incidents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private String site;

    @BeforeEach
    void freshSite() {
        clock.set(START);
        site = "PT" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
    }

    // ---- S164-01 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S164-01 AC: a hot-work request with no linked risk assessment is refused with a named reason, and the refusal is recorded")
    void no_risk_assessment_is_refused_and_recorded() {
        PermitDetail draft = draft(type("HOT_WORK"), null);

        assertThatThrownBy(() -> requests.submit(draft.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_NO_RISK_ASSESSMENT));

        PermitDetail after = queries.get(draft.permit().id(), requester());
        assertThat(after.permit().status()).isEqualTo(PermitStatus.DRAFT);
        assertThat(after.history()).extracting(h -> h.action()).contains("PERMIT_SUBMISSION_REFUSED");
        assertThat(after.history()).filteredOn(h -> h.action().equals("PERMIT_SUBMISSION_REFUSED")).singleElement()
                .satisfies(h -> assertThat(h.reason()).contains("PERMIT_NO_RISK_ASSESSMENT"));
    }

    @Test
    @DisplayName("S164-01: a linked assessment past its review date is not current - the request is refused until it is renewed")
    void a_lapsed_assessment_is_refused() {
        PermitDetail draft = draft(type("HOT_WORK"), assessment("HOT_WORK"));
        clock.advance(Duration.ofDays(400));

        assertThatThrownBy(() -> requests.submit(draft.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_NOT_CURRENT);
            assertThat(e.getMessage()).startsWith("Risk Assessment Not Current");
        });
    }

    @Test
    @DisplayName("S164-01: an assessment that covers a different kind of work is refused; so is a window longer than the type allows")
    void mismatched_assessment_and_long_window() {
        PermitDetail draft = draft(type("HOT_WORK"), assessment("WORKING_AT_HEIGHT"));
        assertThatThrownBy(() -> requests.submit(draft.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_MISMATCH));

        assertThatThrownBy(() -> requests.create(site, input(type("HOT_WORK"), null, START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(20))),
                workers(), isolations(), requester())).isInstanceOfSatisfying(PermitException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_WINDOW_INVALID));
    }

    @Test
    @DisplayName("S164-01: a permit type that requires isolations cannot be submitted without them; one that names no workers cannot either")
    void workers_and_isolations_are_required() {
        PermitType hot = type("HOT_WORK");
        PermitDetail noIsolation = requests.create(site, input(hot, assessment("HOT_WORK"), START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5))), workers(),
                List.of(), requester());
        assertThatThrownBy(() -> requests.submit(noIsolation.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_ISOLATIONS_REQUIRED));

        PermitDetail noWorkers = requests.create(site, input(hot, assessment("HOT_WORK"), START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5))), List.of(),
                isolations(), requester());
        assertThatThrownBy(() -> requests.submit(noWorkers.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_WORKERS_REQUIRED));
    }

    @Test
    @DisplayName("S164-01: a zone that does not exist at the site is refused")
    void unknown_zone() {
        assertThatThrownBy(() -> requests.create(site, new PermitRequestService.PermitInput(type("HOT_WORK").id(), "Weld", "Weld the rail", "Block A", UUID.randomUUID(),
                START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5)), null, "ACME", "sup-1", "0200000000", OriginSystem.NONE, null), workers(), isolations(),
                requester())).isInstanceOfSatisfying(PermitException.class, e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_ZONE_NOT_FOUND));
    }

    @Test
    @DisplayName("S164-01: a permit type of high risk cannot be configured without two-stage approval")
    void high_risk_types_need_two_stages() {
        assertThatThrownBy(() -> types.create(new PermitTypeService.TypeDefinition("LIFTING", "Lifting", null, PermitRiskLevel.HIGH, null, true, false, false, 8, List.of(),
                true), configurer())).isInstanceOfSatisfying(PermitException.class, e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_TYPE_INVALID));
        PermitType ok = types.create(new PermitTypeService.TypeDefinition("COLD_WORK_" + site, "Cold work", null, PermitRiskLevel.LOW, null, false, false, false, 24, List.of(),
                true), configurer());
        assertThat(ok.twoStage()).isFalse();
    }

    // ---- S164-02 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S164-02 AC: approval before isolations are verified is refused until a verification record exists")
    void isolation_not_verified() {
        PermitDetail submitted = submitted();

        assertThatThrownBy(() -> approvals.approve(submitted.permit().id(), null, null, null, authoriser())).isInstanceOfSatisfying(PermitException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_ISOLATION_NOT_VERIFIED);
            assertThat(e.getMessage()).startsWith("Isolation Not Verified");
        });
        assertThat(queries.get(submitted.permit().id(), requester()).history()).extracting(h -> h.action()).contains("PERMIT_APPROVAL_REFUSED");

        verifyAll(submitted);
        assertThat(queries.get(submitted.permit().id(), requester()).permit().status()).isEqualTo(PermitStatus.ISOLATION_VERIFIED);
    }

    @Test
    @DisplayName("S164-02: the requester cannot be the verifier, and cannot approve their own permit")
    void independence_of_the_requester() {
        ActorContext director = actor("fd-self", SflRole.FACILITIES_DIRECTOR);
        PermitDetail own = requests.create(site, input(type("HOT_WORK"), assessment("HOT_WORK"), START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5))), workers(), isolations(),
                caller(director));
        requests.submit(own.permit().id(), null, caller(director));

        ActorContext engineer = actor("eng-self", SflRole.FACILITIES_ENGINEER);
        PermitDetail engineers = requests.create(site, input(type("HOT_WORK"), assessment("HOT_WORK"), START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5))),
                workers(), isolations(), caller(engineer));
        requests.submit(engineers.permit().id(), null, caller(engineer));
        UUID isolation = engineers.isolations().get(0).id();

        assertThatThrownBy(() -> approvals.verifyIsolation(engineers.permit().id(), isolation, null, null, caller(engineer))).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_VERIFIER_NOT_INDEPENDENT));
        verifyAll(own);
        assertThatThrownBy(() -> approvals.approve(own.permit().id(), null, null, null, caller(director))).isInstanceOfSatisfying(PermitException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_SELF_APPROVAL);
            assertThat(e.getMessage()).startsWith("Self-Approval Attempted");
        });
    }

    @Test
    @DisplayName("S164-02: a higher-risk permit needs two independent stages; it is active only after the second, and S176 is told with the payload it reads")
    void two_stage_approval_issues_the_permit() throws Exception {
        PermitDetail submitted = submitted();
        verifyAll(submitted);
        recordCompetence(submitted, true);

        PermitDetail first = approvals.approve(submitted.permit().id(), null, "Fire watch for 60 minutes after", null, authoriser());
        assertThat(first.permit().status()).isEqualTo(PermitStatus.STAGE1_APPROVED);
        assertThat(first.nextStage()).isNotNull();
        assertThatThrownBy(() -> approvals.approve(submitted.permit().id(), null, null, null, authoriser())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isIn(PermitErrorCode.PERMIT_STAGE_OUT_OF_ORDER, PermitErrorCode.PERMIT_UNAUTHORIZED_SCOPE));
        PermitDetail second = approvals.approve(submitted.permit().id(), null, null, null, caller(actor("sd-1", SflRole.SECURITY_DIRECTOR)));

        assertThat(second.permit().status()).isEqualTo(PermitStatus.ACTIVE);
        assertThat(second.permit().issuedAt()).isEqualTo(clock.instant());
        assertThat(second.approvals()).extracting(a -> a.stage().name()).containsExactly("ISSUING_AUTHORITY", "SAFETY_SIGN_OFF");
        assertThat(second.approvals().get(0).conditions()).isEqualTo("Fire watch for 60 minutes after");

        Map<String, Object> issued = payload(second.permit().id(), "sfl.ssemp.permit-issued.v1");
        // docs/facilities/S176_Event_Contracts.md: what S176's PermitEventHandler reads.
        assertThat(issued).containsKeys("permitId", "permitReference", "siteCode", "workType", "validFrom", "validTo", "contractorReference", "originReference", "occurredAt");
        assertThat(issued.get("permitId")).isEqualTo(second.permit().id().toString());
        assertThat(issued.get("workType")).isEqualTo("HOT_WORK");
        assertThat(Instant.parse((String) issued.get("validTo"))).isEqualTo(second.permit().endsAt());
        assertThat(issued).doesNotContainKeys("workers", "supervisorContact");
    }

    @Test
    @DisplayName("S164-02: the safety sign-off cannot be the person who was the issuing authority")
    void stage_two_is_independent_of_stage_one() {
        PermitDetail submitted = submitted();
        verifyAll(submitted);
        recordCompetence(submitted, true);
        ActorContext hse = actor("hse-one", SflRole.HSE_MANAGER);
        approvals.approve(submitted.permit().id(), null, null, null, caller(hse));

        assertThatThrownBy(() -> approvals.approve(submitted.permit().id(), null, null, null, caller(hse))).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_STAGE_NOT_INDEPENDENT));
    }

    @Test
    @DisplayName("S164-02: a worker without a competence check blocks approval; recording the check lifts it")
    void competence_exception_blocks_approval() {
        PermitDetail submitted = submitted();
        verifyAll(submitted);

        assertThatThrownBy(() -> approvals.approve(submitted.permit().id(), null, null, null, authoriser())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_COMPETENCY_EXCEPTION));
        assertThat(queries.get(submitted.permit().id(), requester()).competencyExceptions()).isNotEmpty();
        assertThat(queries.dashboard(site, authoriser()).competencyExceptions()).isNotEmpty();

        recordCompetence(submitted, true);
        assertThat(approvals.approve(submitted.permit().id(), null, null, null, authoriser()).permit().status()).isEqualTo(PermitStatus.STAGE1_APPROVED);
    }

    @Test
    @DisplayName("S164-02: an assessment that lapses before approval stops the approval - the check is made again, not trusted from submission")
    void approval_rechecks_the_assessment() {
        PermitDetail submitted = submitted();
        verifyAll(submitted);
        recordCompetence(submitted, true);
        clock.advance(Duration.ofDays(400));

        assertThatThrownBy(() -> approvals.approve(submitted.permit().id(), null, null, null, authoriser())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_NOT_CURRENT));
    }

    @Test
    @DisplayName("S164-02: a rejection ends the permit with its reason; nobody can approve it afterwards")
    void rejection() {
        PermitDetail submitted = submitted();

        PermitDetail rejected = approvals.reject(submitted.permit().id(), "Not enough fire watch cover", null, authoriser());

        assertThat(rejected.permit().status()).isEqualTo(PermitStatus.REJECTED);
        assertThat(rejected.permit().statusReason()).isEqualTo("Not enough fire watch cover");
        assertThatThrownBy(() -> approvals.approve(submitted.permit().id(), null, null, null, authoriser())).isInstanceOf(PermitException.class);
    }

    // ---- S164-03 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S164-03 AC: suspension queues a notification for the supervisor and every worker in the same act, and S176 is told")
    void suspension_notifies_as_part_of_the_act() {
        PermitDetail active = active();

        PermitDetail suspended = lifecycle.suspend(active.permit().id(), "Gas smell reported in the plant room", null, soc());

        assertThat(suspended.permit().status()).isEqualTo(PermitStatus.SUSPENDED);
        assertThat(suspended.suspensions()).singleElement().satisfies(s -> assertThat(s.reason()).isEqualTo("Gas smell reported in the plant room"));
        assertThat(suspended.notifications()).extracting(n -> n.recipientRole()).containsExactlyInAnyOrder("SUPERVISOR", "WORKER", "WORKER");
        assertThat(suspended.notifications()).allSatisfy(n -> assertThat(n.state()).isEqualTo("QUEUED"));
        assertThat(payload(active.permit().id(), "sfl.ssemp.permit-suspended.v1")).containsEntry("reason", "Gas smell reported in the plant room");
        assertThatThrownBy(() -> lifecycle.suspend(active.permit().id(), "again", null, soc())).isInstanceOf(PermitException.class);
        assertThatThrownBy(() -> lifecycle.suspend(active.permit().id(), " ", null, soc())).isInstanceOf(PermitException.class);
    }

    @Test
    @DisplayName("S164-03: only an authoriser or the SOC can suspend; a requester cannot")
    void who_can_suspend() {
        PermitDetail active = active();

        assertThatThrownBy(() -> lifecycle.suspend(active.permit().id(), "No authority", null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_UNAUTHORIZED_SCOPE));
    }

    @Test
    @DisplayName("S164-03: a suspended permit is not resumed by an un-suspend - it needs a fresh round of approvals, and is issued again to S176")
    void resumption_needs_fresh_approval() {
        PermitDetail active = active();
        lifecycle.suspend(active.permit().id(), "Wind speed over limit", null, soc());

        PermitDetail pending = lifecycle.requestResumption(active.permit().id(), null, requester());
        assertThat(pending.permit().status()).isEqualTo(PermitStatus.RESUMPTION_PENDING);
        assertThat(pending.permit().approvalRound()).isEqualTo(2);
        assertThat(pending.approvals().stream().filter(a -> a.approvalRound() == 2)).isEmpty();

        approvals.approve(active.permit().id(), null, null, null, authoriser());
        PermitDetail resumed = approvals.approve(active.permit().id(), null, null, null, caller(actor("sd-1", SflRole.SECURITY_DIRECTOR)));

        assertThat(resumed.permit().status()).isEqualTo(PermitStatus.ACTIVE);
        assertThat(resumed.approvals()).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_security.outbox_messages WHERE aggregate_id = ? AND event_type = 'sfl.ssemp.permit-issued.v1'", Long.class,
                active.permit().id().toString())).isEqualTo(2);
        assertThat(resumed.suspensions().get(0).resumedAt()).isNotNull();
    }

    @Test
    @DisplayName("S164-03: a resumption request is refused while the assessment is not current")
    void resumption_is_refused_if_the_assessment_lapsed() {
        PermitDetail active = active();
        lifecycle.suspend(active.permit().id(), "Stop", null, soc());
        clock.advance(Duration.ofDays(400));

        assertThatThrownBy(() -> lifecycle.requestResumption(active.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_NOT_CURRENT));
    }

    @Test
    @DisplayName("S164-03: an extension is checked like a new request and approved in the same stages; validity moves only on the last, and S176 is told")
    void extension() {
        PermitDetail active = active();
        Instant newEnd = active.permit().endsAt().plus(Duration.ofHours(2));

        assertThatThrownBy(() -> lifecycle.requestExtension(active.permit().id(), active.permit().endsAt().minusSeconds(60), "Earlier", null, requester()))
                .isInstanceOfSatisfying(PermitException.class, e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_EXTENSION_INVALID));
        PermitDetail asked = lifecycle.requestExtension(active.permit().id(), newEnd, "Weld needs another pass", null, requester());
        UUID extension = asked.extensions().get(0).id();
        assertThatThrownBy(() -> lifecycle.requestExtension(active.permit().id(), newEnd.plusSeconds(60), "Twice", null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_EXTENSION_PENDING));
        assertThatThrownBy(() -> lifecycle.decideExtension(active.permit().id(), extension, true, null, null, requester())).isInstanceOf(PermitException.class);

        PermitDetail stage1 = lifecycle.decideExtension(active.permit().id(), extension, true, "First look", null, authoriser());
        assertThat(stage1.permit().endsAt()).isEqualTo(active.permit().endsAt());
        PermitDetail done = lifecycle.decideExtension(active.permit().id(), extension, true, "Independent sign-off", null, caller(actor("sd-1", SflRole.SECURITY_DIRECTOR)));

        assertThat(done.permit().endsAt()).isEqualTo(newEnd);
        assertThat(done.extensions().get(0).status().name()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_security.outbox_messages WHERE aggregate_id = ? AND event_type = 'sfl.ssemp.permit-extended.v1'", Long.class,
                active.permit().id().toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("S164-03: an extension is refused when the assessment has lapsed - it is not a rubber stamp")
    void extension_rechecks_currency() {
        PermitDetail active = active();
        clock.advance(Duration.ofDays(400));

        assertThatThrownBy(() -> lifecycle.requestExtension(active.permit().id(), active.permit().endsAt().plus(Duration.ofHours(1)), "More time", null, requester()))
                .isInstanceOfSatisfying(PermitException.class, e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_RISK_ASSESSMENT_NOT_CURRENT));
    }

    @Test
    @DisplayName("S164-03 AC: an emergency over a zone flags the live permits there for suspension review, once, and does not suspend them")
    void emergency_flags_live_permits() {
        PermitDetail active = active();

        int first = flags.flagZoneEmergency(site, List.of(), "BG-000123", actor("coordinator", SflRole.SOC_OPERATOR));
        int again = flags.flagZoneEmergency(site, List.of(), "BG-000123", actor("coordinator", SflRole.SOC_OPERATOR));

        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        PermitDetail flagged = queries.get(active.permit().id(), soc());
        assertThat(flagged.permit().status()).isEqualTo(PermitStatus.ACTIVE);
        assertThat(flagged.flags()).singleElement().satisfies(f -> {
            assertThat(f.flagType().name()).isEqualTo("EMERGENCY_ZONE");
            assertThat(f.status()).isEqualTo("OPEN");
        });
        assertThat(queries.dashboard(site, soc()).openFlags()).hasSize(1);

        PermitDetail reviewed = lifecycle.reviewFlag(active.permit().id(), flagged.flags().get(0).id(), "Work is outside the affected wing", soc());
        assertThat(reviewed.flags().get(0).status()).isEqualTo("REVIEWED");
    }

    // ---- S164-04 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S164-04: close-out needs evidence and a statement; closing needs every isolation's removal recorded by someone other than the requester")
    void close_out() {
        PermitDetail active = active();

        assertThatThrownBy(() -> lifecycle.completeWork(active.permit().id(), "Weld complete", null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_EVIDENCE_REQUIRED));
        lifecycle.addEvidence(active.permit().id(), EvidenceKind.PHOTO, "REC-1", "weld.jpg", "image/jpeg", 2048, "a".repeat(64), requester());
        assertThatThrownBy(() -> lifecycle.completeWork(active.permit().id(), " ", null, requester())).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_COMPLETION_STATEMENT_REQUIRED));
        PermitDetail complete = lifecycle.completeWork(active.permit().id(), "Weld complete; area cleaned and inspected", null, requester());
        assertThat(complete.permit().status()).isEqualTo(PermitStatus.WORK_COMPLETE);
        assertThat(complete.blockers()).anyMatch(b -> b.contains("isolation"));

        assertThatThrownBy(() -> lifecycle.close(active.permit().id(), null, requester())).isInstanceOfSatisfying(PermitException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_ISOLATION_REMOVAL_NOT_RECORDED);
            assertThat(e.getMessage()).startsWith("Isolation Removal Not Recorded");
        });
        UUID isolation = complete.isolations().get(0).id();
        assertThatThrownBy(() -> lifecycle.removeIsolation(active.permit().id(), isolation, null, requester())).isInstanceOf(PermitException.class);
        ActorContext requesterAsVerifier = actor("req-1", SflRole.FACILITIES_ENGINEER);
        assertThatThrownBy(() -> lifecycle.removeIsolation(active.permit().id(), isolation, null, caller(requesterAsVerifier))).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_REMOVER_NOT_INDEPENDENT));

        PermitDetail removed = lifecycle.removeIsolation(active.permit().id(), isolation, "Lock and tag removed", verifier());
        assertThat(removed.isolations().get(0).removedBy()).isEqualTo("eng-1");
        PermitDetail closed = lifecycle.close(active.permit().id(), null, requester());

        assertThat(closed.permit().status()).isEqualTo(PermitStatus.CLOSED);
        assertThat(closed.permit().closedBy()).isEqualTo("req-1");
        assertThat(payload(active.permit().id(), "sfl.ssemp.permit-closed.v1")).containsKey("validTo");
        assertThat(closed.history()).extracting(h -> h.action()).containsSubsequence("PERMIT_DRAFTED", "PERMIT_SUBMITTED", "ISOLATION_VERIFICATION_COMPLETE", "PERMIT_WORK_COMPLETED",
                "ISOLATION_REMOVED", "PERMIT_CLOSED");
    }

    @Test
    @DisplayName("S164-04 AC: a permit past its validity without close-out escalates to the authoriser once per level, and is on the overdue dashboard")
    void expiry_escalates_and_is_overdue() {
        PermitDetail active = active();
        UUID id = active.permit().id();
        ActorContext system = actor("scheduler", SflRole.SFL_ADMIN);

        clock.set(active.permit().endsAt().minus(Duration.ofMinutes(30)));
        PermitMonitoringService.SweepResult near = monitoring.sweep(id, system);
        assertThat(near.nearingExpiry()).isEqualTo(1);
        assertThat(near.overdue()).isZero();
        assertThat(queries.dashboard(site, authoriser()).nearingExpiry()).extracting(p -> p.id()).contains(id);

        clock.set(active.permit().endsAt().plus(Duration.ofMinutes(10)));
        PermitMonitoringService.SweepResult late = monitoring.sweep(id, system);
        assertThat(late.overdue()).isEqualTo(1);
        assertThat(late.nearingExpiry()).isZero();
        assertThat(monitoring.sweep(id, system)).isEqualTo(new PermitMonitoringService.SweepResult(0, 0));

        assertThat(queries.get(id, authoriser()).escalations()).extracting(e -> e.level()).containsExactlyInAnyOrder(EscalationLevel.NEARING_EXPIRY, EscalationLevel.OVERDUE);
        assertThat(queries.dashboard(site, authoriser()).overdueCloseOuts()).extracting(p -> p.id()).contains(id);
        assertThat(queries.get(id, authoriser()).overdue()).isTrue();
        assertThat(payload(id, "sfl.ssemp.permit-overdue.v1")).containsKey("permitReference");
    }

    // ---- S164-05 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S164-05 AC: an incident logged during permitted work, naming the permit's assessment, flags the permit - in the incident's own transaction")
    void incident_flags_the_permit() {
        PermitDetail active = active();
        UUID assessment = active.permit().riskAssessmentId();
        clock.advance(Duration.ofHours(2));

        incidents.report(new IncidentReportingService.ReportIncident(site, IncidentSource.HSE, false, "reporter", null, "Spark ignited packaging near the weld", false,
                actor("hse-1", SflRole.HSE_MANAGER), gh.edu.clet.sfl.safetysecurity.incident.domain.model.SourceChannel.WEB, assessment, "HOT_WORK"));

        PermitDetail flagged = queries.get(active.permit().id(), authoriser());
        assertThat(flagged.flags()).singleElement().satisfies(f -> {
            assertThat(f.flagType().name()).isEqualTo("INCIDENT");
            assertThat(f.reference()).startsWith("INC-");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_security.outbox_messages WHERE aggregate_id = ? AND event_type = 'sfl.ssemp.permit-flagged.v1'", Long.class,
                active.permit().id().toString())).isEqualTo(1);
        assertThat(queries.analytics(site, null, null, authoriser()).flaggedForIncident()).isEqualTo(1);

        PermitDetail linked = lifecycle.linkIncident(active.permit().id(), "INC-MANUAL-1", "Found later in the investigation", authoriser());
        assertThat(linked.flags()).hasSize(2);
    }

    @Test
    @DisplayName("S164-05: the statutory export carries each permit's complete lifecycle, says which are incomplete, needs its own grant and a reason, and is audited")
    void statutory_export() {
        PermitDetail active = active();
        PermitDetail open = submitted();
        lifecycle.addEvidence(active.permit().id(), EvidenceKind.CHECKLIST, "REC-9", "close.pdf", "application/pdf", 100, "b".repeat(64), requester());
        lifecycle.completeWork(active.permit().id(), "=cmd|' /C calc'!A0", null, requester());
        lifecycle.removeIsolation(active.permit().id(), active.isolations().get(0).id(), null, verifier());
        lifecycle.close(active.permit().id(), null, requester());

        PermitQueryService.ExportFile file = queries.export(new PermitQueryService.Search(site, null, null, null, null, null, false, false, null, new Paging(0, 100, null)),
                "Statutory audit of hot work 2026", compliance());

        assertThat(file.permits()).isEqualTo(2);
        assertThat(file.incomplete()).isEqualTo(1);
        assertThat(file.csv()).contains("# CLET S164 permit register - STATUTORY EVIDENCE").contains("# Reason: Statutory audit of hot work 2026")
                .contains("1 with an incomplete lifecycle").contains("COMPLETE").contains("INCOMPLETE - permit is still SUBMITTED");
        assertThat(file.csv()).contains("'=cmd|' /C calc'!A0").doesNotContain(",=cmd");
        assertThat(file.csv()).contains(open.permit().reference()).contains(active.permit().reference());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM safety_security.audit_log WHERE action = 'PERMIT_REGISTER_EXPORTED' AND site_scope = ?", Long.class, site)).isEqualTo(1);

        assertThatThrownBy(() -> queries.export(new PermitQueryService.Search(site, null, null, null, null, null, false, false, null, new Paging(0, 100, null)), "audit",
                compliance())).isInstanceOfSatisfying(PermitException.class, e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_EXPORT_REASON_REQUIRED));
        assertThatThrownBy(() -> queries.export(new PermitQueryService.Search(site, null, null, null, null, null, false, false, null, new Paging(0, 100, null)),
                "Statutory audit of hot work 2026", requester())).isInstanceOfSatisfying(PermitException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_UNAUTHORIZED_SCOPE));
    }

    @Test
    @DisplayName("S164-05: analytics count volume by type, contractor and outcome, and mean time open")
    void analytics() {
        PermitDetail active = active();
        lifecycle.addEvidence(active.permit().id(), EvidenceKind.PHOTO, "REC-1", "p.jpg", "image/jpeg", 10, "c".repeat(64), requester());
        lifecycle.completeWork(active.permit().id(), "Done", null, requester());
        lifecycle.removeIsolation(active.permit().id(), active.isolations().get(0).id(), null, verifier());
        clock.advance(Duration.ofHours(3));
        lifecycle.close(active.permit().id(), null, requester());
        submitted();

        PermitQueryService.Analytics a = queries.analytics(site, null, null, compliance());

        assertThat(a.permits()).isEqualTo(2);
        assertThat(a.byType()).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("HOT_WORK");
            assertThat(r.count()).isEqualTo(2);
        });
        assertThat(a.byContractor()).singleElement().satisfies(r -> assertThat(r.key()).isEqualTo("ACME Contracting"));
        assertThat(a.byOutcome()).extracting(r -> r.key()).containsExactlyInAnyOrder("CLOSED", "SUBMITTED");
        assertThat(a.meanOpenHours()).isBetween(2.9, 3.1);
    }

    // ---- scope ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an actor scoped to another site can neither read nor change a permit here")
    void site_scope() {
        PermitDetail active = active();
        ActorContext elsewhere = new ActorContext(new SiteScopedPrincipal("fd-elsewhere", "fd", Set.of(SflRole.FACILITIES_DIRECTOR), Set.of("OTHER-SITE"), false), "x");

        assertThatThrownBy(() -> queries.get(active.permit().id(), caller(elsewhere))).isInstanceOfSatisfying(PermitException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_UNAUTHORIZED_SCOPE));
        assertThatThrownBy(() -> lifecycle.suspend(active.permit().id(), "Nope", null, caller(elsewhere))).isInstanceOf(PermitException.class);
        assertThat(queries.search(new PermitQueryService.Search(null, null, null, null, null, null, false, false, null, new Paging(0, 50, null)), caller(elsewhere)).content())
                .extracting(p -> p.id()).doesNotContain(active.permit().id());
    }

    @Test
    @DisplayName("a draft can be revised, and its workers and isolations changed, only by its requester and only before submission")
    void drafts_are_the_requesters() {
        PermitDetail draft = draft(type("HOT_WORK"), assessment("HOT_WORK"));
        PermitDetail withWorker = requests.addWorker(draft.permit().id(), new PermitRequestService.WorkerInput("W-9", "Kofi Mensah", WorkRole.FIRE_WATCH), requester());
        assertThat(withWorker.workers()).hasSize(3);
        PermitWorker added = withWorker.workers().get(2);
        assertThat(requests.removeWorker(draft.permit().id(), added.id(), requester()).workers()).hasSize(2);
        assertThatThrownBy(() -> requests.addWorker(draft.permit().id(), new PermitRequestService.WorkerInput("W-10", "Ama", WorkRole.OPERATIVE), caller(actor("other", SflRole.FACILITIES_MANAGER))))
                .isInstanceOf(PermitException.class);
        requests.submit(draft.permit().id(), null, requester());
        assertThatThrownBy(() -> requests.addWorker(draft.permit().id(), new PermitRequestService.WorkerInput("W-11", "Yaw", WorkRole.OPERATIVE), requester()))
                .isInstanceOfSatisfying(PermitException.class, e -> assertThat(e.errorCode()).isEqualTo(PermitErrorCode.PERMIT_INVALID_STATE_TRANSITION));
        PermitDetail cancelled = requests.cancel(draft.permit().id(), "Job postponed", null, requester());
        assertThat(cancelled.permit().status()).isEqualTo(PermitStatus.CANCELLED);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private PermitType type(String code) {
        return types.list(false, configurer()).stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }

    private UUID assessment(String activityType) {
        UUID id = authoring.create(new RiskAssessmentAuthoringService.CreateAssessment(site, activityType, null, "Assessment for " + activityType, null,
                List.of(new Hazard(HazardType.HOT_WORK_FIRE, "Sparks igniting stored materials", "Contractors", new RiskScore(Likelihood.LIKELY, Severity.MAJOR),
                        new RiskScore(Likelihood.RARE, Severity.MINOR), List.of(new ControlMeasure(ControlType.ADMINISTRATIVE, "Hot-work permit and fire watch")))),
                null, null, actor("ra-author", SflRole.HSE_MANAGER), gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel.WEB)).assessment().id();
        authoring.publish(id, null, actor("ra-author", SflRole.HSE_MANAGER), gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel.WEB);
        return id;
    }

    private PermitRequestService.PermitInput input(PermitType type, UUID assessmentId, Instant starts, Instant ends) {
        return new PermitRequestService.PermitInput(type.id(), "Weld the pipe rack", "Welding the supports on the pipe rack, plant room B", "Plant room B", null, starts, ends,
                assessmentId, "ACME Contracting", "sup-1", "0200000000", OriginSystem.S153, "WO-1001");
    }

    private static List<PermitRequestService.WorkerInput> workers() {
        return List.of(new PermitRequestService.WorkerInput("W-1", "Esi Boateng", WorkRole.SUPERVISOR), new PermitRequestService.WorkerInput("W-2", "Kwame Asare", WorkRole.OPERATIVE));
    }

    private static List<PermitRequestService.IsolationInput> isolations() {
        return List.of(new PermitRequestService.IsolationInput(IsolationKind.ELECTRICAL, "Isolate the supply to the rack lighting", "LOTO-12"));
    }

    private PermitDetail draft(PermitType type, UUID assessmentId) {
        return requests.create(site, input(type, assessmentId, START.plus(Duration.ofHours(1)), START.plus(Duration.ofHours(5))), workers(), isolations(), requester());
    }

    private PermitDetail submitted() {
        PermitDetail draft = draft(type("HOT_WORK"), assessment("HOT_WORK"));
        return requests.submit(draft.permit().id(), null, requester());
    }

    private void verifyAll(PermitDetail permit) {
        for (Isolation isolation : queries.get(permit.permit().id(), requester()).isolations()) {
            approvals.verifyIsolation(permit.permit().id(), isolation.id(), null, "Lock applied and tested", verifier());
        }
        approvals.completeVerification(permit.permit().id(), "Tested dead", null, verifier());
    }

    private void recordCompetence(PermitDetail permit, boolean competent) {
        PermitDetail current = queries.get(permit.permit().id(), requester());
        for (PermitWorker worker : current.workers()) {
            for (String competency : current.type().requiredCompetencies()) {
                approvals.recordCompetency(permit.permit().id(), worker.id(), competency, competent, "CERT-" + competency, LocalDate.of(2030, 1, 1), null, verifier());
            }
        }
    }

    private PermitDetail active() {
        PermitDetail submitted = submitted();
        verifyAll(submitted);
        recordCompetence(submitted, true);
        approvals.approve(submitted.permit().id(), null, null, null, authoriser());
        return approvals.approve(submitted.permit().id(), null, null, null, caller(actor("sd-1", SflRole.SECURITY_DIRECTOR)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(UUID permitId, String eventType) {
        try {
            String payload = jdbc.queryForObject("SELECT payload::text FROM safety_security.outbox_messages WHERE aggregate_id = ? AND event_type = ? ORDER BY created_at DESC LIMIT 1",
                    String.class, permitId.toString(), eventType);
            return json.readValue(payload, Map.class);
        } catch (Exception e) {
            throw new AssertionError("no " + eventType + " event for " + permitId, e);
        }
    }

    private Caller requester() {
        return caller(actor("req-1", SflRole.FACILITIES_MANAGER));
    }

    private Caller verifier() {
        return caller(actor("eng-1", SflRole.FACILITIES_ENGINEER));
    }

    private Caller authoriser() {
        return caller(actor("fd-1", SflRole.FACILITIES_DIRECTOR));
    }

    private Caller soc() {
        return caller(actor("soc-1", SflRole.SOC_OPERATOR));
    }

    private Caller compliance() {
        return caller(actor("co-1", SflRole.COMPLIANCE_OFFICER));
    }

    private Caller configurer() {
        return caller(actor("hse-config", SflRole.HSE_MANAGER));
    }

    private static Caller caller(ActorContext actor) {
        return new Caller(actor, SourceChannel.WEB);
    }

    private ActorContext actor(String id, SflRole role) {
        return new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(site), false), "e2e-" + UUID.randomUUID());
    }
}

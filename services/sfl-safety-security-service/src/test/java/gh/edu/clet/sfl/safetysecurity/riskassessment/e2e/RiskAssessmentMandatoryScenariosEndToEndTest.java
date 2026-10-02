package gh.edu.clet.sfl.safetysecurity.riskassessment.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.Status;
import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentReportingService;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentSource;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.SecurityIncident;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.InboundIntegrationEvent;
import gh.edu.clet.sfl.safetysecurity.platform.application.integration.IntegrationEventHandler;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.contract.RiskAssessmentDirectory.LinkCheck;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.AssessmentDetail;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.CoverageService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.ReviewFlagService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentAuthoringService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentReviewService;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.RiskAssessmentReviewService.SweepOutcome;
import gh.edu.clet.sfl.safetysecurity.riskassessment.application.service.VersionView;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentErrorCode;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.exception.RiskAssessmentException;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentStanding;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentVersion;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlMeasure;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlag;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ReviewFlagStatus;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskScore;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Severity;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.SourceChannel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Database-backed proof of S165's four acceptance criteria and its error states, driven through the real
 * application services against a real PostgreSQL - the shape {@code SecurityIncidentMandatoryScenariosEndToEndTest}
 * uses. Each test works at its own site, so it neither sees nor disturbs another's rows.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "sfl.safety-security.messaging.drainer-enabled=false",
        "sfl.risk-assessment.scheduling.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class RiskAssessmentMandatoryScenariosEndToEndTest extends SafetySecurityPostgresSupport {

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
    @Autowired private RiskAssessmentAuthoringService authoring;
    @Autowired private RiskAssessmentReviewService reviews;
    @Autowired private ReviewFlagService flags;
    @Autowired private CoverageService coverage;
    @Autowired private IncidentReportingService incidents;
    @Autowired private List<IntegrationEventHandler> handlers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private String site;

    @BeforeEach
    void freshSite() {
        clock.set(START);
        site = "RA" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
    }

    // ---- S165-01 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S165-01 AC: republished with changes, the prior version stays accessible and is marked superseded")
    void republishing_supersedes_without_deleting() {
        AssessmentDetail created = create("HOT_WORK", hazard(Likelihood.UNLIKELY, Severity.MINOR));
        UUID id = created.assessment().id();
        AssessmentDetail v1 = publish(id, officer());
        assertThat(v1.assessment().currentVersion()).isEqualTo(1);

        clock.advance(Duration.ofDays(1));
        AssessmentDetail revising = authoring.openRevision(id, officer(), SourceChannel.WEB);
        AssessmentVersion draft2 = revising.versions().get(0).version();
        assertThat(draft2.versionNumber()).isEqualTo(2);
        authoring.editDraft(id, new AssessmentContent("Hot work - revised", "Adds a second fire watch", List.of(
                hazard(Likelihood.UNLIKELY, Severity.MINOR), hazard(Likelihood.RARE, Severity.MODERATE))),
                draft2.metadata().version(), officer(), SourceChannel.WEB);
        AssessmentDetail v2 = publish(id, officer());

        assertThat(v2.assessment().currentVersion()).isEqualTo(2);
        VersionView prior = authoring.version(id, 1, officer());
        assertThat(prior.version().status()).isEqualTo(Status.SUPERSEDED);
        assertThat(prior.version().supersededAt()).isNotNull();
        assertThat(prior.hazards()).hasSize(1);
        assertThat(prior.current()).isFalse();
        assertThat(prior.currencyReason()).isEqualTo(RiskAssessmentCurrency.Reason.SUPERSEDED);
        VersionView current = authoring.version(id, 2, officer());
        assertThat(current.hazards()).hasSize(2);
        assertThat(current.current()).isTrue();
        // The service scores and bands each hazard, so no client re-derives the matrix.
        assertThat(current.hazards().get(1).residualScore()).isEqualTo(3); // RARE x MODERATE
        assertThat(current.hazards().get(1).residualLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(v2.versions()).extracting(view -> view.version().status())
                .containsExactly(Status.PUBLISHED, Status.SUPERSEDED);

        assertThat(eventTypes(id)).containsSubsequence("sfl.ssemp.risk-assessment-published.v1",
                "sfl.ssemp.risk-assessment-superseded.v1", "sfl.ssemp.risk-assessment-published.v1");
    }

    @Test
    @DisplayName("S165-01 error state: Hazard Without Control is refused at publish")
    void hazard_without_control_is_refused() {
        UUID id = authoring.create(new RiskAssessmentAuthoringService.CreateAssessment(site, "WORKING_AT_HEIGHT", null,
                "Ladder access", null, List.of(new Hazard(HazardType.WORKING_AT_HEIGHT, "Fall from ladder", null,
                        new RiskScore(Likelihood.POSSIBLE, Severity.MAJOR),
                        new RiskScore(Likelihood.UNLIKELY, Severity.MAJOR), List.of())),
                null, null, officer(), SourceChannel.WEB)).assessment().id();

        assertThatThrownBy(() -> publish(id, officer())).isInstanceOfSatisfying(RiskAssessmentException.class,
                e -> assertThat(e.errorCode()).isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_HAZARD_WITHOUT_CONTROL));
        assertThat(authoring.get(id, officer()).assessment().currentVersion()).isNull();
    }

    @Test
    @DisplayName("S165-01: the published payload matches the S173 contract field for field")
    void published_payload_matches_the_reserved_contract() throws Exception {
        UUID id = create("HOT_WORK", hazard(Likelihood.UNLIKELY, Severity.MINOR)).assessment().id();
        publish(id, officer());

        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM safety_security.outbox_messages
                 WHERE aggregate_id = ? AND event_type = 'sfl.ssemp.risk-assessment-published.v1'
                """, String.class, id.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = json.readValue(payload, Map.class);
        // docs/facilities/S173_Event_Contracts.md: assessmentId, version (int), siteCode, riskLevel,
        // reviewDueAt (ISO instant), authorId, signedOffBy (nullable).
        assertThat(fields).containsKeys("assessmentId", "version", "siteCode", "riskLevel", "reviewDueAt", "authorId",
                "signedOffBy");
        assertThat(fields.get("assessmentId")).isEqualTo(id.toString());
        assertThat(fields.get("version")).isEqualTo(1);
        assertThat(fields.get("siteCode")).isEqualTo(site);
        assertThat(fields.get("riskLevel")).isEqualTo("LOW");
        assertThat(Instant.parse((String) fields.get("reviewDueAt"))).isEqualTo(START.plus(Duration.ofDays(365)));
        assertThat(fields.get("signedOffBy")).isNull();
        assertThat(jdbc.queryForObject("SELECT site_scope FROM safety_security.outbox_messages WHERE aggregate_id = ? "
                + "AND event_type = 'sfl.ssemp.risk-assessment-published.v1'", String.class, id.toString()))
                .as("the siteCode header facilities reads").isEqualTo(site);
    }

    @Test
    @DisplayName("Idempotency-Key: a retried create returns the original; a different body under the key is refused")
    void idempotency_key_on_create() {
        RiskAssessmentAuthoringService.CreateAssessment command = new RiskAssessmentAuthoringService.CreateAssessment(
                site, "HOT_WORK", null, "Hot work", null, List.of(hazard(Likelihood.RARE, Severity.MINOR)), null,
                "create-" + site, officer(), SourceChannel.WEB);
        UUID first = authoring.create(command).assessment().id();
        UUID retried = authoring.create(command).assessment().id();
        assertThat(retried).isEqualTo(first);

        assertThatThrownBy(() -> authoring.create(new RiskAssessmentAuthoringService.CreateAssessment(site, "HOT_WORK",
                null, "A different title", null, List.of(hazard(Likelihood.RARE, Severity.MINOR)), null,
                "create-" + site, officer(), SourceChannel.WEB)))
                .isInstanceOfSatisfying(RiskAssessmentException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_IDEMPOTENCY_KEY_CONFLICT));
    }

    // ---- S165-02 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S165-02 AC: a high-risk assessment whose review date passes without sign-off is refused a new link")
    void a_lapsed_high_risk_assessment_cannot_be_linked() {
        UUID id = create("CONFINED_SPACE", hazard(Likelihood.POSSIBLE, Severity.MAJOR)).assessment().id(); // 12: HIGH
        AssessmentDetail published = publish(id, officer());
        assertThat(published.assessment().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(published.assessment().standing()).isEqualTo(AssessmentStanding.AWAITING_INDEPENDENT_SIGN_OFF);

        // The author cannot be the reviewer at HIGH.
        assertThatThrownBy(() -> reviews.signOff(id, null, null, officer(), SourceChannel.WEB))
                .isInstanceOfSatisfying(RiskAssessmentException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_INDEPENDENT_REVIEW_REQUIRED));
        reviews.signOff(id, "Isolation plan checked", null, reviewer(), SourceChannel.WEB);
        assertThat(reviews.checkLink(id, site, clock.instant()).linkable()).isTrue();

        clock.advance(Duration.ofDays(91));
        LinkCheck lapsed = reviews.checkLink(id, site, clock.instant());
        assertThat(lapsed.linkable()).isFalse();
        assertThat(lapsed.verdict().reason()).isEqualTo(RiskAssessmentCurrency.Reason.REVIEW_LAPSED);
        assertThatThrownBy(() -> RiskAssessmentReviewService.requireLinkable(lapsed))
                .isInstanceOfSatisfying(RiskAssessmentException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_LAPSED);
                    assertThat(e.getMessage()).startsWith("Lapsed Assessment");
                });

        // Signing it off again is how it becomes current.
        reviews.signOff(id, "Re-reviewed after lapse", null, reviewer(), SourceChannel.WEB);
        assertThat(reviews.checkLink(id, site, clock.instant()).linkable()).isTrue();
    }

    @Test
    @DisplayName("S165-02: the sweep reminds once ahead of the date and records the lapse once after it")
    void the_sweep_reminds_and_lapses_exactly_once() {
        UUID id = create("HOT_WORK", hazard(Likelihood.POSSIBLE, Severity.MAJOR)).assessment().id(); // 12: HIGH
        publish(id, officer());
        reviews.signOff(id, null, null, reviewer(), SourceChannel.WEB);
        ActorContext system = new ActorContext(new SiteScopedPrincipal("sweep", "sweep", Set.of(SflRole.SFL_ADMIN),
                Set.of("*"), true), "sweep");
        RiskAssessmentReviewService.SweepCandidate candidate = new RiskAssessmentReviewService.SweepCandidate(id, 1);

        assertThat(reviews.sweep(candidate, system)).isEqualTo(SweepOutcome.NOTHING);
        clock.advance(Duration.ofDays(90 - 14)); // HIGH: 90 days, 14-day lead (V19 defaults)
        assertThat(reviews.sweepCandidates()).contains(candidate);
        assertThat(reviews.sweep(candidate, system)).isEqualTo(SweepOutcome.REMINDED);
        assertThat(reviews.sweep(candidate, system)).isEqualTo(SweepOutcome.NOTHING);

        clock.advance(Duration.ofDays(14));
        assertThat(reviews.sweep(candidate, system)).isEqualTo(SweepOutcome.LAPSED);
        assertThat(reviews.sweep(candidate, system)).isEqualTo(SweepOutcome.NOTHING);
        assertThat(reviews.sweepCandidates()).doesNotContain(candidate);

        assertThat(eventTypes(id)).filteredOn(t -> t.endsWith("review-due.v1")).hasSize(1);
        assertThat(eventTypes(id)).filteredOn(t -> t.endsWith("review-lapsed.v1")).hasSize(1);
    }

    // ---- S165-03 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S165-03 AC: a new S176 work type with no matching assessment appears as a gap")
    void an_s176_work_type_with_no_assessment_is_a_gap() {
        receiveFromFacilities("sfl.ifimp.project-registered.v1", Map.of("projectReference", "CP-" + site + "-1",
                "workTypes", List.of("DEMOLITION", "HOT_WORK")));
        receiveFromFacilities("sfl.ifimp.project-started.v1", Map.of("projectReference", "CP-" + site + "-1",
                "workTypes", List.of("HOT_WORK")));

        CoverageService.CoverageReport before = coverage.coverage(site, officer());
        assertThat(before.gaps()).extracting(CoverageService.CoverageLine::activityType)
                .containsExactly("HOT_WORK", "DEMOLITION");
        assertThat(before.gaps().get(0).occurrences()).isEqualTo(2);
        assertThat(before.gaps().get(0).sources()).singleElement()
                .satisfies(s -> assertThat(s.sourceSystem()).isEqualTo("S176"));

        UUID id = create("HOT_WORK", hazard(Likelihood.RARE, Severity.MINOR)).assessment().id();
        assertThat(coverage.coverage(site, officer()).gaps()).extracting(CoverageService.CoverageLine::activityType)
                .as("a draft covers nothing").contains("HOT_WORK");
        publish(id, officer());
        CoverageService.CoverageReport after = coverage.coverage(site, officer());
        assertThat(after.gaps()).extracting(CoverageService.CoverageLine::activityType).containsExactly("DEMOLITION");
        assertThat(after.coveredLines()).extracting(CoverageService.CoverageLine::activityType)
                .containsExactly("HOT_WORK");

        clock.advance(Duration.ofDays(366));
        assertThat(coverage.coverage(site, officer()).gaps()).extracting(CoverageService.CoverageLine::activityType)
                .as("a lapsed assessment covers nothing either").contains("HOT_WORK");
    }

    @Test
    @DisplayName("S165-03: hazard frequency rolls up across current assessments only")
    void hazard_frequency_counts_current_assessments() {
        UUID a = create("HOT_WORK", hazard(Likelihood.RARE, Severity.MINOR), hazard(Likelihood.RARE, Severity.MINOR))
                .assessment().id();
        publish(a, officer());
        create("ROOFING", hazard(Likelihood.RARE, Severity.MINOR)); // draft only - not counted

        List<CoverageService.HazardFrequency> hazards = coverage.hazardFrequency(site, officer());
        assertThat(hazards).singleElement().satisfies(h -> {
            assertThat(h.hazardType()).isEqualTo(HazardType.HOT_WORK_FIRE);
            assertThat(h.occurrences()).isEqualTo(2);
            assertThat(h.assessments()).isEqualTo(1);
        });
    }

    // ---- S165-04 ----------------------------------------------------------------------------------

    @Test
    @DisplayName("S165-04 AC: an incident against assessed work flags the assessment when the incident is saved")
    void an_incident_flags_its_assessment_in_the_same_transaction() {
        UUID id = create("HOT_WORK", hazard(Likelihood.RARE, Severity.MINOR)).assessment().id();
        publish(id, officer());

        SecurityIncident incident = incidents.report(new IncidentReportingService.ReportIncident(site,
                IncidentSource.HSE, false, "reporter", null, "Smouldering insulation found after hot work", false,
                hseActor(), gh.edu.clet.sfl.safetysecurity.incident.domain.model.SourceChannel.WEB, null, "hot work"));

        List<ReviewFlag> raised = flags.search(site, ReviewFlagStatus.OPEN,
                new gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging(0, 25, null), officer())
                .content();
        assertThat(raised).singleElement().satisfies(flag -> {
            assertThat(flag.assessmentId()).isEqualTo(id);
            assertThat(flag.sourceId()).isEqualTo(incident.id().toString());
            assertThat(flag.sourceReference()).isEqualTo(incident.reference());
            assertThat(flag.versionNumber()).isEqualTo(1);
        });
        assertThat(coverage.coverage(site, officer()).coveredLines()).singleElement()
                .satisfies(line -> assertThat(line.sources()).extracting(CoverageService.ObservationSource::sourceSystem)
                        .contains("S163"));

        // The same incident's context saved again raises nothing new.
        incidents.recordRiskContext(incident.id(), id, "HOT_WORK", null, hseActor(),
                gh.edu.clet.sfl.safetysecurity.incident.domain.model.SourceChannel.WEB);
        assertThat(flags.search(site, null, new gh.edu.clet.sfl.safetysecurity.riskassessment.application.port.Paging(
                0, 25, null), officer()).totalElements()).isEqualTo(1);

        // Cleared only by a completed review with findings; deferral is a postponement.
        ReviewFlag flag = raised.get(0);
        assertThatThrownBy(() -> flags.complete(flag.id(), " ", null, officer(), SourceChannel.WEB))
                .isInstanceOfSatisfying(RiskAssessmentException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_REVIEW_FINDINGS_REQUIRED));
        ReviewFlag deferred = flags.defer(flag.id(), "Awaiting the contractor's fire-watch log",
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(7), null, officer(), SourceChannel.WEB);
        assertThat(deferred.status()).isEqualTo(ReviewFlagStatus.DEFERRED);
        ReviewFlag cleared = flags.complete(flag.id(), "Fire watch ended early; control strengthened in v2.",
                deferred.metadata().version(), officer(), SourceChannel.WEB);
        assertThat(cleared.status()).isEqualTo(ReviewFlagStatus.CLEARED);
        assertThat(cleared.findings()).contains("Fire watch");
    }

    @Test
    @DisplayName("S165-04: a link naming no assessment at the incident's site refuses the incident save")
    void a_bad_link_refuses_the_incident() {
        UUID elsewhere = UUID.randomUUID();
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM safety_security.security_incidents WHERE site_code = ?",
                Long.class, site);

        assertThatThrownBy(() -> incidents.report(new IncidentReportingService.ReportIncident(site, IncidentSource.HSE,
                false, "reporter", null, "Trip on cable", false, hseActor(),
                gh.edu.clet.sfl.safetysecurity.incident.domain.model.SourceChannel.WEB, elsewhere, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found at this site");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM safety_security.security_incidents WHERE site_code = ?",
                Long.class, site)).isEqualTo(before);
    }

    @Test
    @DisplayName("A consumer role reads the library and may not author it")
    void consumer_roles_read_only() {
        UUID id = create("HOT_WORK", hazard(Likelihood.RARE, Severity.MINOR)).assessment().id();
        ActorContext projectManager = actor("pm-1", SflRole.CONSTRUCTION_PROJECT_MANAGER);
        assertThat(authoring.get(id, projectManager).assessment().id()).isEqualTo(id);
        assertThatThrownBy(() -> publish(id, projectManager)).isInstanceOfSatisfying(RiskAssessmentException.class,
                e -> assertThat(e.errorCode()).isEqualTo(RiskAssessmentErrorCode.RISK_ASSESSMENT_UNAUTHORIZED_SCOPE));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private AssessmentDetail create(String activityType, Hazard... hazards) {
        return authoring.create(new RiskAssessmentAuthoringService.CreateAssessment(site, activityType, null,
                "Assessment for " + activityType, null, List.of(hazards), null, null, officer(), SourceChannel.WEB));
    }

    private AssessmentDetail publish(UUID id, ActorContext actor) {
        return authoring.publish(id, null, actor, SourceChannel.WEB);
    }

    private static Hazard hazard(Likelihood residualLikelihood, Severity residualSeverity) {
        return new Hazard(HazardType.HOT_WORK_FIRE, "Sparks igniting stored materials", "Contractors",
                new RiskScore(Likelihood.LIKELY, Severity.MAJOR), new RiskScore(residualLikelihood, residualSeverity),
                List.of(new ControlMeasure(ControlType.ADMINISTRATIVE, "Hot-work permit and 60-minute fire watch")));
    }

    private void receiveFromFacilities(String eventType, Map<String, Object> payload) {
        InboundIntegrationEvent event = new InboundIntegrationEvent(UUID.randomUUID(), eventType, "ConstructionProject",
                UUID.randomUUID().toString(), site, "corr-" + site, null, payload);
        handlers.stream().filter(h -> h.handles(eventType)).forEach(h -> h.handle(event));
    }

    private List<String> eventTypes(UUID assessmentId) {
        return jdbc.queryForList("SELECT event_type FROM safety_security.outbox_messages WHERE aggregate_id = ? "
                + "ORDER BY created_at, event_type DESC", String.class, assessmentId.toString());
    }

    private ActorContext officer() {
        return actor("hse-officer-1", SflRole.HSE_MANAGER);
    }

    private ActorContext reviewer() {
        return actor("hse-director-2", SflRole.HSE_MANAGER);
    }

    private ActorContext hseActor() {
        return actor("hse-officer-1", SflRole.HSE_MANAGER);
    }

    private ActorContext actor(String id, SflRole role) {
        return new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(site), false),
                "e2e-" + UUID.randomUUID());
    }
}

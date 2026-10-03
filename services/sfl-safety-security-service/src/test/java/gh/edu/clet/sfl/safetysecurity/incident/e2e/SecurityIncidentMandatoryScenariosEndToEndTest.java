package gh.edu.clet.sfl.safetysecurity.incident.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport;
import gh.edu.clet.sfl.safetysecurity.emergency.application.port.EmergencyRepository.Paging;
import gh.edu.clet.sfl.safetysecurity.incident.application.port.SecurityIncidentRepository;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.CorrectiveActionService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentClosureService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentInvestigationService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentReportingService;
import gh.edu.clet.sfl.safetysecurity.incident.application.service.IncidentTriageService;
import gh.edu.clet.sfl.safetysecurity.incident.domain.exception.IncidentErrorCode;
import gh.edu.clet.sfl.safetysecurity.incident.domain.exception.IncidentException;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.CorrectiveAction;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Impact;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentSource;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.IncidentStatus;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.RiskRating;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.SecurityIncident;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.Severity;
import gh.edu.clet.sfl.safetysecurity.incident.domain.model.SourceChannel;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database-backed proof of the S163 mandatory scenarios: report through triage, investigation, CAPA,
 * the mandatory-CAPA closure gate (hard rule 1), and emergency escalation (hard rule 2). Each test
 * drives the real application services directly against a real Postgres, the same shape as {@code
 * EmergencyMandatoryScenariosEndToEndTest} and {@code VisitorMandatoryScenariosEndToEndTest}.
 */
@SpringBootTest(properties = {"sfl.security.enabled=false"})
@EnabledIf(value = "gh.edu.clet.sfl.safetysecurity.e2e.SafetySecurityPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available")
class SecurityIncidentMandatoryScenariosEndToEndTest extends SafetySecurityPostgresSupport {

    @Autowired
    private IncidentReportingService reporting;
    @Autowired
    private IncidentTriageService triage;
    @Autowired
    private IncidentInvestigationService investigation;
    @Autowired
    private CorrectiveActionService correctiveActions;
    @Autowired
    private IncidentClosureService closure;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String SITE = "E2E-HQ";

    private ActorContext actor(String id, SflRole role) {
        return new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(SITE), false),
                "e2e-" + UUID.randomUUID());
    }

    private SecurityIncident report(boolean nearMiss) {
        return reporting.report(new IncidentReportingService.ReportIncident(SITE, IncidentSource.HSE, false,
                "reporter-e2e", "reporter@example.com", "A near-miss on the loading dock during a delivery.",
                nearMiss, actor("hse-e2e", SflRole.HSE_MANAGER), SourceChannel.WEB));
    }

    @Test
    void the_full_lifecycle_runs_report_to_close_once_capa_is_verified() {
        SecurityIncident reported = report(true);
        assertThat(reported.status()).isEqualTo(IncidentStatus.TRIAGE);

        SecurityIncident triaged = triage.triage(new IncidentTriageService.Triage(reported.id(), Severity.HIGH,
                new RiskRating(Likelihood.LIKELY, Impact.MAJOR), false, null, reported.metadata().version(),
                actor("hse-e2e", SflRole.HSE_MANAGER), SourceChannel.WEB));
        assertThat(triaged.severity()).isEqualTo(Severity.HIGH);

        SecurityIncident investigating = investigation.openOrUpdateInvestigation(
                new IncidentInvestigationService.OpenOrUpdateInvestigation(triaged.id(), "investigator-e2e",
                        "Root cause: loose pallet strap.", triaged.metadata().version(),
                        actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR), SourceChannel.WEB));
        assertThat(investigating.status()).isEqualTo(IncidentStatus.INVESTIGATING);

        CorrectiveAction capa = correctiveActions.open(new CorrectiveActionService.OpenCorrectiveAction(
                investigating.id(), "Retrain loading-dock staff on strap inspection.", "supervisor-e2e",
                LocalDate.now().plusDays(14), true, actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR),
                SourceChannel.WEB));
        assertThat(capa.blocksClosure()).isTrue();

        assertThatThrownBy(() -> closure.close(new IncidentClosureService.Close(investigating.id(),
                "Attempting early closure.", investigating.metadata().version(),
                actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR), SourceChannel.WEB)))
                .isInstanceOf(IncidentException.class)
                .satisfies(e -> assertThat(((IncidentException) e).errorCode())
                        .isEqualTo(IncidentErrorCode.INCIDENT_MANDATORY_CAPA_OPEN));

        correctiveActions.verify(new CorrectiveActionService.TransitionCorrectiveAction(capa.id(),
                "Retraining completed and confirmed effective.", actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR),
                SourceChannel.WEB));

        SecurityIncident closed = closure.close(new IncidentClosureService.Close(investigating.id(),
                "All corrective actions verified.", investigating.metadata().version(),
                actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR), SourceChannel.WEB));
        assertThat(closed.status()).isEqualTo(IncidentStatus.CLOSED);
    }

    @Test
    void an_emergency_rating_escalates_and_is_recorded_in_the_outbox() {
        SecurityIncident reported = report(false);
        SecurityIncident escalated = triage.triage(new IncidentTriageService.Triage(reported.id(),
                Severity.EMERGENCY, new RiskRating(Likelihood.ALMOST_CERTAIN, Impact.CATASTROPHIC), false, null,
                reported.metadata().version(), actor("hse-e2e", SflRole.HSE_MANAGER), SourceChannel.WEB));
        assertThat(escalated.emergencyEscalated()).isTrue();

        Long escalationEvents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM safety_security.outbox_messages
                WHERE event_type = 'sfl.ssemp.security-incident-emergency-escalated.v1' AND aggregate_id = ?
                """, Long.class, escalated.id().toString());
        assertThat(escalationEvents).isEqualTo(1);
    }

    @Test
    void a_non_mandatory_corrective_action_does_not_block_closure() {
        SecurityIncident reported = report(false);
        SecurityIncident triaged = triage.triage(new IncidentTriageService.Triage(reported.id(), Severity.LOW,
                new RiskRating(Likelihood.RARE, Impact.MINOR), false, null, reported.metadata().version(),
                actor("hse-e2e", SflRole.HSE_MANAGER), SourceChannel.WEB));
        SecurityIncident investigating = investigation.openOrUpdateInvestigation(
                new IncidentInvestigationService.OpenOrUpdateInvestigation(triaged.id(), "investigator-e2e", null,
                        triaged.metadata().version(), actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR),
                        SourceChannel.WEB));

        correctiveActions.open(new CorrectiveActionService.OpenCorrectiveAction(investigating.id(),
                "Optional housekeeping improvement.", "supervisor-e2e", LocalDate.now().plusDays(30), false,
                actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR), SourceChannel.WEB));

        SecurityIncident closed = closure.close(new IncidentClosureService.Close(investigating.id(),
                "No mandatory actions outstanding.", investigating.metadata().version(),
                actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR), SourceChannel.WEB));
        assertThat(closed.status()).isEqualTo(IncidentStatus.CLOSED);
    }

    /** The incident half of the HSE dashboard's shared incidents-and-drills corrective-action panel (S175-03). */
    @Test
    void corrective_action_counts_include_overdue_actions() {
        ActorContext hse = actor("hse-e2e", SflRole.HSE_MANAGER);
        var before = reporting.correctiveActionCounts(SITE, hse);
        SecurityIncident reported = report(false);
        SecurityIncident triaged = triage.triage(new IncidentTriageService.Triage(reported.id(), Severity.LOW,
                new RiskRating(Likelihood.RARE, Impact.MINOR), false, null, reported.metadata().version(), hse,
                SourceChannel.WEB));
        SecurityIncident investigating = investigation.openOrUpdateInvestigation(
                new IncidentInvestigationService.OpenOrUpdateInvestigation(triaged.id(), "investigator-e2e", null,
                        triaged.metadata().version(), actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR),
                        SourceChannel.WEB));
        correctiveActions.open(new CorrectiveActionService.OpenCorrectiveAction(investigating.id(),
                "Already late.", "supervisor-e2e", LocalDate.now().minusDays(1), false,
                actor("investigator-e2e", SflRole.INCIDENT_INVESTIGATOR), SourceChannel.WEB));

        var after = reporting.correctiveActionCounts(SITE, hse);
        assertThat(after.open()).isEqualTo(before.open() + 1);
        assertThat(after.overdue()).isEqualTo(before.overdue() + 1);
        assertThat(after.ageing().get(0)).isEqualTo(before.ageing().get(0) + 1);
    }

    @Test
    void search_finds_a_reported_incident_by_site_and_status() {
        SecurityIncident reported = report(true);

        var found = reporting.search(
                new SecurityIncidentRepository.IncidentQuery(SITE, IncidentStatus.TRIAGE, null, 50),
                actor("hse-e2e", SflRole.HSE_MANAGER));
        assertThat(found).extracting(SecurityIncident::id).contains(reported.id());
    }

    @Test
    void searchPage_reports_a_total_count_and_page_size_a_bare_list_never_could() {
        SecurityIncident reported = report(true);

        var firstPage = reporting.searchPage(SITE, IncidentStatus.TRIAGE, null, new Paging(0, 1, null),
                actor("hse-e2e", SflRole.HSE_MANAGER));
        assertThat(firstPage.content()).hasSize(1);
        assertThat(firstPage.size()).isEqualTo(1);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.totalElements()).isGreaterThanOrEqualTo(1L);
        assertThat(firstPage.totalPages()).isGreaterThanOrEqualTo(firstPage.content().size());

        var allOfThem = reporting.searchPage(SITE, IncidentStatus.TRIAGE, null, new Paging(0, 50, null),
                actor("hse-e2e", SflRole.HSE_MANAGER));
        assertThat(allOfThem.content()).extracting(SecurityIncident::id).contains(reported.id());
    }

    @Test
    void the_dashboard_counts_by_status_and_severity() {
        SecurityIncident reported = report(true);
        triage.triage(new IncidentTriageService.Triage(reported.id(), Severity.MEDIUM,
                new RiskRating(Likelihood.POSSIBLE, Impact.MODERATE), false, null, reported.metadata().version(),
                actor("hse-e2e", SflRole.HSE_MANAGER), SourceChannel.WEB));

        var dashboard = reporting.dashboard(SITE, actor("compliance-e2e", SflRole.COMPLIANCE_OFFICER));
        assertThat(dashboard.byStatus().getOrDefault(IncidentStatus.TRIAGE, 0L)).isGreaterThanOrEqualTo(1L);
        assertThat(dashboard.bySeverity().getOrDefault(Severity.MEDIUM, 0L)).isGreaterThanOrEqualTo(1L);
    }
}

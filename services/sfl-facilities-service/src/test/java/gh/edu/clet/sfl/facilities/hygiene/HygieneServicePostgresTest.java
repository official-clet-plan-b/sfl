package gh.edu.clet.sfl.facilities.hygiene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.AddAction;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.Caller;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CloseFinding;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CompleteControl;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CreateControl;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.CreateFinding;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneCommands.SubmitEvidence;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneControlService;
import gh.edu.clet.sfl.facilities.hygiene.application.HygieneFindingService;
import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneEstatePort;
import gh.edu.clet.sfl.facilities.hygiene.application.ports.HygieneWorkOrderPort;
import gh.edu.clet.sfl.facilities.hygiene.domain.ActionStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosureMode;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlType;
import gh.edu.clet.sfl.facilities.hygiene.domain.EscalationLevel;
import gh.edu.clet.sfl.facilities.hygiene.domain.FindingStatus;
import gh.edu.clet.sfl.facilities.hygiene.domain.Frequency;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneAction;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneControl;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneEvidence;
import gh.edu.clet.sfl.facilities.hygiene.domain.HygieneFinding;
import gh.edu.clet.sfl.facilities.hygiene.domain.LinkState;
import gh.edu.clet.sfl.facilities.hygiene.domain.RiskCategory;
import gh.edu.clet.sfl.facilities.hygiene.domain.Severity;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * S170 against a real PostgreSQL: the rules a reviewer would try to break, run as the roles that hold them.
 * S152 and S153 are doubles - the point here is S170's own behaviour, and that a refusal from S153 leaves the
 * finding standing and honestly pending.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
        "sfl.hygiene.scheduling.enabled=false",
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class HygieneServicePostgresTest {

    private static final String HASH = "a".repeat(64);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired
    private HygieneControlService controls;
    @Autowired
    private HygieneFindingService findings;
    @MockitoBean
    private HygieneEstatePort estate;
    @MockitoBean
    private HygieneWorkOrderPort workOrders;

    private String site;
    private Caller manager;
    private Caller hse;
    private Caller director;

    @BeforeEach
    void setUp() {
        site = "HYG" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        when(estate.siteExists(anyString())).thenReturn(true);
        when(estate.siteOfRoom(any())).thenReturn(Optional.empty());
        when(workOrders.raise(any(), any(), anyString(), anyString()))
                .thenReturn(new HygieneWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-1"));
        manager = caller("fm-user", SflRole.FACILITIES_MANAGER, site);
        hse = caller("hse-user", SflRole.HSE_MANAGER, site);
        director = caller("director-user", SflRole.FACILITIES_DIRECTOR, site);
    }

    private static Caller caller(String id, SflRole role, String... sites) {
        return new Caller(new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(sites), false),
                "corr-" + id), SourceChannel.WEB);
    }

    private HygieneControl control(Caller by, Frequency frequency, LocalDate due, String provider) {
        return controls.create(new CreateControl(site, null, "Main kitchen", provider == null ? ControlType.AUDIT
                : ControlType.PEST_VISIT, RiskCategory.FOOD_SAFETY, "Kitchen hygiene audit", "J. Mensah", frequency,
                due, provider, null, by));
    }

    private HygieneFinding finding(HygieneControl control, Severity severity, String owner) {
        return findings.create(new CreateFinding(control.id(), null, null, "Droppings near store", "Seen by auditor",
                severity, owner, severity.critical() ? LocalDate.now().plusDays(2) : null, false, manager));
    }

    @Test
    @DisplayName("completing a recurring control schedules the next one from the due date")
    void completion_schedules_the_successor() {
        LocalDate due = LocalDate.now().minusDays(3);
        HygieneControl created = control(manager, Frequency.MONTHLY, due, null);

        HygieneControlService.Completion done = controls.complete(
                new CompleteControl(created.id(), LocalDate.now(), "All clear", null, manager));

        assertThat(done.control().status()).isEqualTo(ControlStatus.COMPLETED);
        assertThat(done.next()).isNotNull();
        assertThat(done.next().dueOn()).isEqualTo(due.plusMonths(1));
        assertThat(done.next().previousControlId()).isEqualTo(created.id());
        assertThat(done.next().status()).isEqualTo(ControlStatus.SCHEDULED);
    }

    @Test
    @DisplayName("a one-off control completes with no successor")
    void one_off_has_no_successor() {
        HygieneControl created = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        assertThat(controls.complete(new CompleteControl(created.id(), null, null, null, manager)).next()).isNull();
    }

    @Test
    @DisplayName("a pest visit with a named provider cannot be completed until the provider confirms it")
    void provider_confirmation_gates_completion() {
        HygieneControl visit = control(manager, Frequency.QUARTERLY, LocalDate.now(), "PestAway Ltd");

        assertThatThrownBy(() -> controls.complete(new CompleteControl(visit.id(), null, null, null, manager)))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.HYGIENE_PROVIDER_UNCONFIRMED));

        controls.confirmProvider(visit.id(), null, manager);
        assertThat(controls.complete(new CompleteControl(visit.id(), null, null, null, manager)).control().status())
                .isEqualTo(ControlStatus.COMPLETED);
    }

    @Test
    @DisplayName("a completed control cannot be completed again")
    void completed_is_final() {
        HygieneControl created = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        controls.complete(new CompleteControl(created.id(), null, null, null, manager));
        assertThatThrownBy(() -> controls.complete(new CompleteControl(created.id(), null, null, null, manager)))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("a stale version is refused instead of overwriting")
    void stale_version_conflicts() {
        HygieneControl created = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        controls.start(created.id(), created.version(), manager);
        assertThatThrownBy(() -> controls.start(created.id(), created.version(), manager))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("a room from another site is refused")
    void room_must_belong_to_the_site() {
        UUID room = UUID.randomUUID();
        when(estate.siteOfRoom(room)).thenReturn(Optional.of("ELSEWHERE"));
        assertThatThrownBy(() -> controls.create(new CreateControl(site, room, null, ControlType.AUDIT,
                RiskCategory.GENERAL, "Audit", "owner", Frequency.ONE_OFF, LocalDate.now(), null, null, manager)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in site");
    }

    @Test
    @DisplayName("a critical finding gets an owner, a corrective action with an SLA, HSE escalation, a work order and a pending incident")
    void critical_finding_obligations() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);

        HygieneFinding critical = finding(audit, Severity.CRITICAL, "K. Owusu");

        assertThat(critical.escalationLevel()).isEqualTo(EscalationLevel.HSE);
        assertThat(critical.workOrderState()).isEqualTo(LinkState.RAISED);
        assertThat(critical.workOrderNumber()).isEqualTo("WO-1");
        assertThat(critical.incidentState()).isEqualTo(LinkState.PENDING_MANUAL);
        HygieneFindingService.FindingDetail detail = findings.get(critical.id(), manager);
        assertThat(detail.actions()).hasSize(1);
        assertThat(detail.actions().get(0).dueOn()).isEqualTo(LocalDate.now().plusDays(Severity.CRITICAL.actionSlaDays()));
        assertThat(controls.escalations(site, true, 0, 10, manager).items()).extracting(e -> e.level())
                .contains(EscalationLevel.HSE);
    }

    @Test
    @DisplayName("a critical finding without an owner is refused; a low one needs none and gets a default target date")
    void owner_and_target_rules() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        assertThatThrownBy(() -> finding(audit, Severity.CRITICAL, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
        HygieneFinding low = finding(audit, Severity.LOW, null);
        assertThat(low.targetDate()).isEqualTo(LocalDate.now().plusDays(Severity.LOW.actionSlaDays()));
        assertThat(low.workOrderState()).isEqualTo(LinkState.NOT_REQUIRED);
        assertThat(low.escalationLevel()).isEqualTo(EscalationLevel.NONE);
    }

    @Test
    @DisplayName("a second critical finding of the same category and place goes to leadership")
    void repeat_critical_goes_to_leadership() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        HygieneFinding first = finding(audit, Severity.CRITICAL, "K. Owusu");
        HygieneFinding second = finding(audit, Severity.CRITICAL, "K. Owusu");

        assertThat(second.repeatOfId()).isEqualTo(first.id());
        assertThat(second.escalationLevel()).isEqualTo(EscalationLevel.LEADERSHIP);
    }

    @Test
    @DisplayName("when S153 refuses, the finding still exists, says PENDING_MANUAL, and a retry raises the order once")
    void work_order_failure_is_honest_and_retryable() {
        when(workOrders.raise(any(), any(), anyString(), anyString())).thenThrow(new IllegalStateException("S153 down"));
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);

        HygieneFinding high = finding(audit, Severity.HIGH, "K. Owusu");

        assertThat(high.workOrderState()).isEqualTo(LinkState.PENDING_MANUAL);
        assertThat(high.workOrderId()).isNull();

        doReturn(new HygieneWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-9")).when(workOrders)
                .raise(any(), any(), anyString(), anyString());
        HygieneFinding retried = findings.retryWorkOrder(high.id(), manager);
        assertThat(retried.workOrderState()).isEqualTo(LinkState.RAISED);
        assertThat(retried.workOrderNumber()).isEqualTo("WO-9");
    }

    @Test
    @DisplayName("a finding does not close on submitted evidence; it closes on accepted evidence with verified actions, by someone else")
    void closure_needs_accepted_evidence() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        HygieneFinding f = finding(audit, Severity.MEDIUM, "K. Owusu");
        HygieneAction action = findings.addAction(new AddAction(f.id(), "Deep clean", "K. Owusu",
                LocalDate.now().plusDays(3), manager));
        HygieneEvidence evidence = findings.submitEvidence(new SubmitEvidence(f.id(), action.id(), "REC-1",
                "photo.jpg", "image/jpeg", 2048, HASH, null, null, manager));

        assertThat(evidence.retentionClass()).isEqualTo("COMPLIANCE");
        assertThat(findings.get(f.id(), manager).finding().status()).isEqualTo(FindingStatus.AWAITING_VERIFICATION);
        assertThatThrownBy(() -> findings.close(new CloseFinding(f.id(), ClosureMode.EVIDENCE, null, null, director)))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.HYGIENE_CLOSURE_BLOCKED));

        assertThatThrownBy(() -> findings.reviewEvidence(evidence.id(), true, null, caller("fm-user",
                SflRole.HSE_MANAGER, site))).isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.HYGIENE_SELF_VERIFICATION));
        findings.reviewEvidence(evidence.id(), true, null, director);
        findings.moveAction(action.id(), ActionStatus.COMPLETED, null, null, manager);
        findings.moveAction(action.id(), ActionStatus.VERIFIED, null, null, director);

        HygieneFinding closed = findings.close(new CloseFinding(f.id(), ClosureMode.EVIDENCE, null, null, director));
        assertThat(closed.status()).isEqualTo(FindingStatus.CLOSED);
        assertThat(closed.closureMode()).isEqualTo(ClosureMode.EVIDENCE);
        assertThat(findings.get(f.id(), manager).history()).extracting(h -> h.toStatus()).contains("CLOSED");
    }

    @Test
    @DisplayName("the person who completed an action cannot verify it")
    void no_self_verification_of_actions() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        HygieneFinding f = finding(audit, Severity.MEDIUM, null);
        HygieneAction action = findings.addAction(new AddAction(f.id(), "Fix", "o", LocalDate.now().plusDays(1), hse));
        findings.moveAction(action.id(), ActionStatus.COMPLETED, null, null, hse);

        assertThatThrownBy(() -> findings.moveAction(action.id(), ActionStatus.VERIFIED, null, null, hse))
                .isInstanceOfSatisfying(FacilitiesException.class,
                        e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.HYGIENE_SELF_VERIFICATION));
    }

    @Test
    @DisplayName("an exception closes a finding only with a reason and an approver who did not raise it")
    void exception_closure() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        HygieneFinding f = finding(audit, Severity.LOW, null);

        assertThatThrownBy(() -> findings.close(new CloseFinding(f.id(), ClosureMode.EXCEPTION, "short", null,
                director))).isInstanceOf(FacilitiesException.class);
        HygieneFinding closed = findings.close(new CloseFinding(f.id(), ClosureMode.EXCEPTION,
                "Landlord's responsibility, accepted in writing", null, director));
        assertThat(closed.closureMode()).isEqualTo(ClosureMode.EXCEPTION);
        assertThat(closed.closureApprovedBy()).isEqualTo("director-user");

        HygieneFinding reopened = findings.reopen(f.id(), "Landlord withdrew", director);
        assertThat(reopened.status()).isEqualTo(FindingStatus.IN_PROGRESS);
        assertThat(reopened.closureMode()).isNull();
    }

    @Test
    @DisplayName("the facilities manager manages but cannot verify or close; the requester can do neither")
    void permissions() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        HygieneFinding f = finding(audit, Severity.LOW, null);

        assertThatThrownBy(() -> findings.close(new CloseFinding(f.id(), ClosureMode.EXCEPTION,
                "A perfectly good reason", null, manager))).isInstanceOf(RuntimeException.class);
        Caller requester = caller("req", SflRole.IFIMP_REQUESTER, site);
        assertThatThrownBy(() -> controls.list(site, null, null, false, 0, 10, requester))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> findings.evidence(f.id(), requester)).isInstanceOf(RuntimeException.class);
        Caller compliance = caller("aud", SflRole.COMPLIANCE_OFFICER, site);
        assertThat(findings.evidence(f.id(), compliance)).isEmpty();
        assertThatThrownBy(() -> findings.start(f.id(), null, compliance)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("another site's role cannot see this site's controls")
    void site_scope() {
        control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        Caller elsewhere = caller("fm-else", SflRole.FACILITIES_MANAGER, "OTHER-SITE");
        assertThatThrownBy(() -> controls.list(site, null, null, false, 0, 10, elsewhere))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an unknown control is not found")
    void not_found() {
        assertThatThrownBy(() -> controls.get(UUID.randomUUID(), manager)).isInstanceOfSatisfying(
                FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.RECORD_NOT_FOUND));
    }

    @Test
    @DisplayName("the sweep tells the owner once, marks a long-overdue control missed, and keeps the schedule going")
    void sweep() {
        HygieneControl late = control(manager, Frequency.MONTHLY, LocalDate.now().minusDays(20), null);
        HygieneControl recent = control(manager, Frequency.MONTHLY, LocalDate.now().minusDays(2), null);
        ActorContext system = new ActorContext(new SiteScopedPrincipal("system.hygiene", "Hygiene scheduler",
                Set.of(SflRole.SFL_ADMIN), Set.of("*"), true), "sweep");

        HygieneControlService.SweepResult first = controls.sweep(7, system);
        HygieneControlService.SweepResult second = controls.sweep(7, system);

        assertThat(first.controlsMissed()).isGreaterThanOrEqualTo(1);
        assertThat(second.ownersNotified()).isZero();
        assertThat(controls.get(late.id(), manager).control().status()).isEqualTo(ControlStatus.MISSED);
        assertThat(controls.get(recent.id(), manager).control().status()).isEqualTo(ControlStatus.SCHEDULED);
        assertThat(controls.get(recent.id(), manager).effectiveStatus()).isEqualTo("OVERDUE");
        assertThat(controls.list(site, "SCHEDULED", null, false, 0, 50, manager).items())
                .anyMatch(c -> late.id().equals(c.previousControlId()));
    }

    @Test
    @DisplayName("the dashboard counts overdue work and open critical findings for the site")
    void dashboard() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now().minusDays(1), null);
        finding(audit, Severity.CRITICAL, "K. Owusu");

        var kpis = controls.dashboard(site, 90, manager).kpis();

        assertThat(kpis.overdueControls()).isEqualTo(1);
        assertThat(kpis.openCriticalFindings()).isEqualTo(1);
        assertThat(kpis.openEscalations()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("filters apply on the server: severity, status and overdue")
    void filters() {
        HygieneControl audit = control(manager, Frequency.ONE_OFF, LocalDate.now(), null);
        finding(audit, Severity.LOW, null);
        finding(audit, Severity.HIGH, "K. Owusu");

        assertThat(findings.list(site, null, "HIGH", null, false, 0, 10, manager).items()).hasSize(1);
        assertThat(findings.list(site, "OPEN", null, null, false, 0, 10, manager).total()).isEqualTo(2);
        assertThat(findings.list(site, null, null, null, true, 0, 10, manager).items()).isEmpty();
        assertThatThrownBy(() -> findings.list(site, null, "WIZARD", null, false, 0, 10, manager))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

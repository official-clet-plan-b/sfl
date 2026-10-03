package gh.edu.clet.sfl.facilities.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.lease.application.LeaseAgreementService;
import gh.edu.clet.sfl.facilities.lease.application.LeaseAgreementService.Propose;
import gh.edu.clet.sfl.facilities.lease.application.LeaseAgreementService.Register;
import gh.edu.clet.sfl.facilities.lease.application.LeaseConfigService;
import gh.edu.clet.sfl.facilities.lease.application.LeaseWorkOrderService;
import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseWorkOrderPort;
import gh.edu.clet.sfl.facilities.lease.domain.LeaseWorkOrder;
import gh.edu.clet.sfl.facilities.lease.application.LeaseOpsService;
import gh.edu.clet.sfl.facilities.lease.application.ports.LeaseEstatePort;
import gh.edu.clet.sfl.facilities.lease.domain.Agreement;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementKind;
import gh.edu.clet.sfl.facilities.lease.domain.AgreementStatus;
import gh.edu.clet.sfl.facilities.lease.domain.AlertLevel;
import gh.edu.clet.sfl.facilities.lease.domain.AlertReason;
import gh.edu.clet.sfl.facilities.lease.domain.Amendment;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentKind;
import gh.edu.clet.sfl.facilities.lease.domain.AmendmentStatus;
import gh.edu.clet.sfl.facilities.lease.domain.CounterpartyState;
import gh.edu.clet.sfl.facilities.lease.domain.Direction;
import gh.edu.clet.sfl.facilities.lease.domain.DocumentKind;
import gh.edu.clet.sfl.facilities.lease.domain.Obligation;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationKind;
import gh.edu.clet.sfl.facilities.lease.domain.ObligationStatus;
import gh.edu.clet.sfl.facilities.lease.domain.RenewalType;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * S177 against a real PostgreSQL, run as the roles that hold each grant. S152 is a double; the reference port is
 * the real adapter, so "unresolved, never verified" is tested as shipped. Under test: activation needs a complete,
 * approved agreement; nothing material changes without a third-party approval; every approved change leaves the
 * prior version; conflicting amendments are held; the daily control tells the chain exactly once; and money is
 * visible only to the financial grant.
 */
@SpringBootTest(properties = {
        "sfl.security.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sfl.facilities.messaging.drainer-enabled=false",
        "sfl.maintenance.scheduling.enabled=false",
        "sfl.booking.scheduling.enabled=false",
        "sfl.hygiene.scheduling.enabled=false",
        "sfl.waste.scheduling.enabled=false",
        "sfl.lostfound.scheduling.enabled=false",
        "sfl.lease.scheduling.enabled=false",
        "sfl.catering.scheduling.enabled=false",
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class LeaseServicePostgresTest {

    private static final String HASH = "b".repeat(64);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Accra"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired private LeaseAgreementService agreements;
    @Autowired private LeaseConfigService config;
    @Autowired private LeaseOpsService ops;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeaseWorkOrderService leaseWorkOrders;
    @MockitoBean private LeaseEstatePort estate;
    @MockitoBean private LeaseWorkOrderPort s153;

    private String site;
    private Caller manager;
    private Caller planner;
    private Caller director;
    private Caller compliance;

    @BeforeEach
    void setUp() {
        site = "LSE" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        when(estate.siteExists(anyString())).thenReturn(true);
        when(estate.siteOfRoom(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        manager = caller("manager-user", SflRole.FACILITIES_MANAGER);
        planner = caller("planner-user", SflRole.SPACE_PLANNING_OFFICER);
        director = caller("director-user", SflRole.FACILITIES_DIRECTOR);
        compliance = caller("compliance-user", SflRole.COMPLIANCE_OFFICER);
    }

    private static Caller caller(String id, SflRole role, String... sites) {
        return new Caller(new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(sites.length == 0 ? new String[] {"*"} : sites), false),
                "corr-" + id), SourceChannel.WEB);
    }

    private Register terms(LocalDate end, Integer noticeDays, String owner, String counterparty) {
        return new Register(site, null, "Block A, 3rd floor", AgreementKind.LEASE, Direction.INBOUND, "Office lease", counterparty, "CON-1", "FIN-1",
                owner, TODAY.minusDays(30), end, RenewalType.OPTION, 12, noticeDays, end.minusMonths(6), new BigDecimal("120000.00"),
                new BigDecimal("20000.00"), "ghs", manager);
    }

    private Agreement draft() {
        return agreements.register(terms(TODAY.plusDays(400), 90, "K. Owusu", "CP-77"));
    }

    private void file(UUID id, DocumentKind kind, LocalDate expires) {
        agreements.fileDocument(id, kind, "REF-" + kind, "doc.pdf", "application/pdf", 10, HASH, expires, manager);
    }

    private Agreement active(Agreement draft) {
        file(draft.id(), DocumentKind.APPROVAL_EVIDENCE, null);
        agreements.submit(draft.id(), null, manager);
        return agreements.approve(draft.id(), null, director);
    }

    private Agreement active() {
        return active(draft());
    }

    @Test
    @DisplayName("registering calculates the notice date, leaves the counterparty unresolved, and refuses impossible terms")
    void registering() {
        Agreement a = draft();

        assertThat(a.status()).isEqualTo(AgreementStatus.DRAFT);
        assertThat(a.noticeDate()).isEqualTo(TODAY.plusDays(400 - 90));
        assertThat(a.counterpartyState()).isEqualTo(CounterpartyState.UNRESOLVED);
        assertThat(a.currency()).isEqualTo("GHS");
        assertThatThrownBy(() -> agreements.register(terms(TODAY.minusDays(40), 90, "o", "cp"))).hasMessageContaining("after");
        Register noCurrency = new Register(site, null, "x", AgreementKind.TENANCY, Direction.OUTBOUND, "t", "cp", null, null, "o", TODAY, TODAY.plusDays(100),
                RenewalType.NONE, null, 30, null, new BigDecimal("10"), null, null, manager);
        assertThatThrownBy(() -> agreements.register(noCurrency)).hasMessageContaining("currency");
    }

    @Test
    @DisplayName("an agreement with no notice period is incomplete and cannot be approved; the refusal lists every gap")
    void activation_blockers() {
        Agreement a = agreements.register(terms(TODAY.plusDays(400), null, null, null));
        agreements.submit(a.id(), null, manager);

        assertThat(agreements.get(a.id(), manager).blockers()).hasSizeGreaterThanOrEqualTo(4);
        assertThatThrownBy(() -> agreements.approve(a.id(), null, director)).isInstanceOfSatisfying(FacilitiesException.class, e -> {
            assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LEASE_NOT_READY);
            assertThat(e.getMessage()).contains("counterparty", "owner", "notice period is missing", "Approval evidence");
        });
    }

    @Test
    @DisplayName("whoever submitted an agreement cannot approve it; approval creates version 1 and the obligations its terms imply")
    void approval() {
        Agreement a = draft();
        file(a.id(), DocumentKind.APPROVAL_EVIDENCE, null);
        agreements.submit(a.id(), null, director);
        assertThatThrownBy(() -> agreements.approve(a.id(), null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LEASE_SELF_APPROVAL));
        assertThatThrownBy(() -> agreements.approve(a.id(), null, manager)).isInstanceOf(RuntimeException.class);

        Agreement approved = agreements.approve(a.id(), null, caller("other-director", SflRole.FACILITIES_DIRECTOR));

        assertThat(approved.status()).isEqualTo(AgreementStatus.ACTIVE);
        LeaseAgreementService.Detail detail = agreements.get(a.id(), manager);
        assertThat(detail.versions()).hasSize(1);
        assertThat(detail.versions().get(0).approvedBy()).isEqualTo("other-director");
        assertThat(detail.obligations()).extracting(Obligation::kind).containsExactlyInAnyOrder(ObligationKind.NOTICE, ObligationKind.RENEWAL,
                ObligationKind.RENT_REVIEW);
        assertThat(detail.obligations()).filteredOn(o -> o.kind() == ObligationKind.NOTICE).extracting(Obligation::dueOn)
                .containsExactly(approved.noticeDate());
    }

    @Test
    @DisplayName("a draft is edited in place; once submitted, only an amendment changes it")
    void draft_edits() {
        Agreement a = draft();
        Agreement edited = agreements.update(new LeaseAgreementService.Update(a.id(), null, "Renamed lease", null, null, null, null, null, null, null,
                null, 60, null, null, null, null, null, manager));
        assertThat(edited.title()).isEqualTo("Renamed lease");
        assertThat(edited.noticeDate()).isEqualTo(a.endDate().minusDays(60));

        agreements.submit(a.id(), null, manager);
        assertThatThrownBy(() -> agreements.update(new LeaseAgreementService.Update(a.id(), null, "Again", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, manager))).hasMessageContaining("amendment");
        assertThat(agreements.returnToDraft(a.id(), "Evidence missing", null, director).status()).isEqualTo(AgreementStatus.DRAFT);
    }

    @Test
    @DisplayName("a material amendment enters the approval queue and changes nothing; the proposer cannot approve; approval writes version 2 and keeps version 1")
    void amendment_and_versions() {
        Agreement a = active();
        Amendment m = agreements.propose(new Propose(a.id(), AmendmentKind.RENT_CHANGE, null, new BigDecimal("150000.00"), null, null, null, null, null,
                "Rent review outcome", manager));

        assertThat(m.status()).isEqualTo(AmendmentStatus.PROPOSED);
        assertThat(agreements.get(a.id(), manager).agreement().annualRent()).isEqualByComparingTo("120000.00");
        assertThat(agreements.get(a.id(), manager).agreement().versionNumber()).isEqualTo(1);
        assertThatThrownBy(() -> agreements.decide(m.id(), true, null, null, planner)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> agreements.decide(m.id(), true, null, null, caller("manager-user", SflRole.FACILITIES_DIRECTOR)))
                .isInstanceOfSatisfying(FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LEASE_SELF_APPROVAL));

        agreements.decide(m.id(), true, "Matches the valuer's report", null, director);

        LeaseAgreementService.Detail detail = agreements.get(a.id(), manager);
        assertThat(detail.agreement().annualRent()).isEqualByComparingTo("150000.00");
        assertThat(detail.agreement().versionNumber()).isEqualTo(2);
        assertThat(detail.versions()).hasSize(2);
        assertThat(detail.versions().get(0).annualRent()).isEqualByComparingTo("120000.00");
        assertThat(detail.versions().get(1).annualRent()).isEqualByComparingTo("150000.00");
        assertThat(detail.versions().get(1).amendmentId()).isEqualTo(m.id());
        assertThat(detail.versions().get(1).approvedBy()).isEqualTo("director-user");
    }

    @Test
    @DisplayName("a rejected amendment changes nothing and records why")
    void rejection() {
        Agreement a = active();
        Amendment m = agreements.propose(new Propose(a.id(), AmendmentKind.TERM_CHANGE, a.endDate().plusYears(2), null, null, null, null, null, null,
                "Longer term", manager));
        assertThatThrownBy(() -> agreements.decide(m.id(), false, "", null, director)).isInstanceOf(IllegalArgumentException.class);
        Amendment rejected = agreements.decide(m.id(), false, "Not in the budget", null, director);
        assertThat(rejected.status()).isEqualTo(AmendmentStatus.REJECTED);
        assertThat(agreements.get(a.id(), manager).agreement().endDate()).isEqualTo(a.endDate());
    }

    @Test
    @DisplayName("a conflicting amendment is held for legal review with an alert, cannot be approved, and returns to the queue when legal clears it")
    void conflicting_amendment() {
        Agreement a = active();
        Amendment first = agreements.propose(new Propose(a.id(), AmendmentKind.RENT_CHANGE, null, new BigDecimal("130000"), null, null, null, null, null,
                "Option A", manager));
        Amendment second = agreements.propose(new Propose(a.id(), AmendmentKind.RENT_CHANGE, null, new BigDecimal("140000"), null, null, null, null, null,
                "Option B", manager));

        assertThat(first.status()).isEqualTo(AmendmentStatus.PROPOSED);
        assertThat(second.status()).isEqualTo(AmendmentStatus.LEGAL_REVIEW);
        assertThatThrownBy(() -> agreements.decide(second.id(), true, null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LEASE_AMENDMENT_BLOCKED));
        assertThat(ops.alerts(site, true, 0, 10, manager).items()).anyMatch(x -> x.level() == AlertLevel.LEGAL
                && x.reason() == AlertReason.CONFLICTING_AMENDMENT);

        assertThatThrownBy(() -> agreements.clearLegalReview(second.id(), "ok", manager)).isInstanceOf(RuntimeException.class);
        assertThat(agreements.clearLegalReview(second.id(), "Option A withdrawn by agreement with the counterparty", compliance).status())
                .isEqualTo(AmendmentStatus.PROPOSED);

        agreements.decide(first.id(), true, "Approved", null, director);
        assertThatThrownBy(() -> agreements.decide(second.id(), true, null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(FacilitiesErrorCode.LEASE_AMENDMENT_BLOCKED);
                    assertThat(e.getMessage()).contains("changed since");
                });
    }

    @Test
    @DisplayName("an approved renewal extends the term, recomputes the notice date, completes the renewal obligation and generates the next ones")
    void renewal() {
        Agreement a = active();
        Amendment m = agreements.propose(new Propose(a.id(), AmendmentKind.RENEWAL, null, null, null, null, null, null, null, "Exercise the option", manager));
        assertThat(m.newEndDate()).isEqualTo(a.endDate().plusMonths(12));

        agreements.decide(m.id(), true, null, null, director);

        LeaseAgreementService.Detail detail = agreements.get(a.id(), manager);
        assertThat(detail.agreement().endDate()).isEqualTo(a.endDate().plusMonths(12));
        assertThat(detail.agreement().noticeDate()).isEqualTo(a.endDate().plusMonths(12).minusDays(90));
        assertThat(detail.obligations()).filteredOn(o -> o.kind() == ObligationKind.RENEWAL && o.status() == ObligationStatus.DONE).hasSize(1);
        assertThat(detail.obligations()).filteredOn(o -> o.kind() == ObligationKind.RENEWAL && o.status() == ObligationStatus.OPEN).hasSize(1);
    }

    @Test
    @DisplayName("an agreement with no renewal right cannot be renewed; a termination needs the notice filed, ends the agreement and waives its generated obligations")
    void termination() {
        Agreement fixed = agreements.register(new Register(site, null, "Annex", AgreementKind.TENANCY, Direction.OUTBOUND, "Annex tenancy", "CP-9", null, null,
                "owner", TODAY.minusDays(10), TODAY.plusDays(300), RenewalType.NONE, null, 60, null, null, null, null, manager));
        agreements.fileDocument(fixed.id(), DocumentKind.APPROVAL_EVIDENCE, "REF", "d.pdf", "application/pdf", 1, HASH, null, manager);
        agreements.submit(fixed.id(), null, manager);
        agreements.approve(fixed.id(), null, director);
        assertThatThrownBy(() -> agreements.propose(new Propose(fixed.id(), AmendmentKind.RENEWAL, null, null, null, null, null, null, null, "r", manager)))
                .hasMessageContaining("no renewal right");

        Amendment end = agreements.propose(new Propose(fixed.id(), AmendmentKind.TERMINATION, null, null, null, null, null, null, TODAY.plusDays(30),
                "Tenant leaving", manager));
        assertThatThrownBy(() -> agreements.decide(end.id(), true, null, null, director)).hasMessageContaining("termination notice");
        file(fixed.id(), DocumentKind.TERMINATION_NOTICE, null);
        agreements.decide(end.id(), true, null, null, director);

        LeaseAgreementService.Detail detail = agreements.get(fixed.id(), manager);
        assertThat(detail.agreement().status()).isEqualTo(AgreementStatus.TERMINATED);
        assertThat(detail.agreement().terminatedOn()).isEqualTo(TODAY.plusDays(30));
        assertThat(detail.obligations()).allMatch(o -> o.status() != ObligationStatus.OPEN);
        assertThat(detail.versions()).hasSize(2);
    }

    @Test
    @DisplayName("the daily control tells the chain by how close a date is, exactly once however often it runs")
    void daily_control_alerts() {
        Agreement a = agreements.register(terms(TODAY.plusDays(100), 90, "K. Owusu", "CP-1"));
        active(a);
        ActorContext system = system();

        LeaseOpsService.ControlResult first = ops.dailyControl(system);
        LeaseOpsService.ControlResult second = ops.dailyControl(system);

        assertThat(first.alertsRaised()).isGreaterThanOrEqualTo(3);
        assertThat(second.alertsRaised()).isZero();
        assertThat(ops.alerts(site, true, 0, 50, manager).items()).extracting(x -> x.level())
                .contains(AlertLevel.OWNER, AlertLevel.MANAGER, AlertLevel.DIRECTOR);
    }

    @Test
    @DisplayName("an overdue obligation goes to everyone in the chain including legal; waiving one takes an approver and a reason")
    void overdue_and_waiver() {
        Agreement a = active();
        Obligation overdue = agreements.addObligation(a.id(), ObligationKind.INSURANCE, "Renew insurance cover", TODAY.minusDays(3), null, manager);

        ops.dailyControl(system());

        assertThat(ops.alerts(site, true, 0, 50, manager).items()).filteredOn(x -> overdue.id().equals(x.obligationId())).extracting(x -> x.level())
                .containsExactlyInAnyOrder(AlertLevel.OWNER, AlertLevel.MANAGER, AlertLevel.DIRECTOR, AlertLevel.LEGAL);
        assertThatThrownBy(() -> agreements.waiveObligation(overdue.id(), "Not applicable", null, manager)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> agreements.waiveObligation(overdue.id(), "", null, director)).isInstanceOf(IllegalArgumentException.class);
        assertThat(agreements.waiveObligation(overdue.id(), "Cover now provided by the landlord", null, director).status()).isEqualTo(ObligationStatus.WAIVED);
        assertThat(agreements.completeObligation(agreements.addObligation(a.id(), ObligationKind.PAYMENT, "Pay deposit", TODAY.plusDays(5), null, manager).id(),
                "Paid", null, manager).status()).isEqualTo(ObligationStatus.DONE);
    }

    @Test
    @DisplayName("an agreement past its end date is marked expired and the director and legal are told")
    void expiry() {
        Agreement a = agreements.register(new Register(site, null, "Old premises", AgreementKind.LEASE, Direction.INBOUND, "Old lease", "CP-3", null, null,
                "owner", TODAY.minusDays(400), TODAY.minusDays(2), RenewalType.NONE, null, 30, null, null, null, null, manager));
        active(a);

        LeaseOpsService.ControlResult result = ops.dailyControl(system());

        assertThat(result.expired()).isGreaterThanOrEqualTo(1);
        assertThat(agreements.get(a.id(), manager).agreement().status()).isEqualTo(AgreementStatus.EXPIRED);
        assertThat(ops.alerts(site, true, 0, 50, manager).items()).filteredOn(x -> x.reason() == AlertReason.EXPIRED).extracting(x -> x.level())
                .contains(AlertLevel.DIRECTOR, AlertLevel.LEGAL);
    }

    private Agreement expiredAgreement() {
        Agreement a = agreements.register(new Register(site, null, "Old premises", AgreementKind.LEASE, Direction.INBOUND, "Old lease", "CP-3", null, null,
                "owner", TODAY.minusDays(400), TODAY.minusDays(2), RenewalType.NONE, null, 30, null, null, null, null, manager));
        active(a);
        return a;
    }

    @Test
    @DisplayName("a lapsed agreement gets one S153 review order for whoever relied on it, however often the control runs")
    void expiry_raises_one_review_order() {
        when(s153.raise(any(), any(), anyString(), any())).thenReturn(new LeaseWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-77"));
        Agreement a = expiredAgreement();

        ops.dailyControl(system());
        ops.dailyControl(system());

        List<LeaseWorkOrder> orders = agreements.get(a.id(), manager).workOrders();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).state()).isEqualTo("RAISED");
        assertThat(orders.get(0).workOrderNumber()).isEqualTo("WO-77");
        assertThat(orders.get(0).description()).contains("without renewal");
    }

    @Test
    @DisplayName("when S153 refuses, the request stays PENDING_MANUAL, never shown as raised, and a retry links it")
    void s153_outage_is_pending_then_retried() {
        when(s153.raise(any(), any(), anyString(), any())).thenThrow(new IllegalStateException("S153 down"));
        Agreement a = expiredAgreement();

        ops.dailyControl(system());

        LeaseWorkOrder pending = agreements.get(a.id(), manager).workOrders().get(0);
        assertThat(pending.state()).isEqualTo("PENDING_MANUAL");
        assertThat(pending.workOrderNumber()).isNull();

        org.mockito.Mockito.doReturn(new LeaseWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-78")).when(s153).raise(any(), any(), anyString(), any());
        LeaseWorkOrder retried = leaseWorkOrders.retry(pending.id(), manager);

        assertThat(retried.state()).isEqualTo("RAISED");
        assertThat(retried.workOrderNumber()).isEqualTo("WO-78");
    }

    @Test
    @DisplayName("corrective work can be raised by hand by a manager, and not by a role that can only read")
    void manual_work_order() {
        when(s153.raise(any(), any(), anyString(), any())).thenReturn(new LeaseWorkOrderPort.RaisedWorkOrder(UUID.randomUUID(), "WO-79"));
        Agreement a = active();

        LeaseWorkOrder order = leaseWorkOrders.raise(a.id(), null, "Roof leak reported by the landlord", manager);

        assertThat(order.state()).isEqualTo("RAISED");
        assertThatThrownBy(() -> leaseWorkOrders.raise(a.id(), null, "x", compliance)).isInstanceOf(FacilitiesException.class);
        assertThatThrownBy(() -> leaseWorkOrders.raise(a.id(), null, " ", manager)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an owner is shown as not verified against HR while no HR system is connected")
    void owner_is_not_verified() {
        Agreement a = draft();

        LeaseAgreementService.Detail detail = agreements.get(a.id(), manager);

        assertThat(detail.ownerVerified()).isFalse();
        assertThat(detail.warnings()).anyMatch(w -> w.contains("S140"));
    }

    @Test
    @DisplayName("an expired document on a live agreement raises an alert")
    void document_expiry() {
        Agreement a = active();
        file(a.id(), DocumentKind.INSURANCE_CERTIFICATE, TODAY.minusDays(1));

        ops.dailyControl(system());

        assertThat(agreements.get(a.id(), manager).documents()).anyMatch(d -> d.expired());
        assertThat(ops.alerts(site, true, 0, 50, manager).items()).anyMatch(x -> x.reason() == AlertReason.DOCUMENT_EXPIRED);
    }

    @Test
    @DisplayName("adding a holiday on a notice date moves it back to the previous business day, and the obligation with it")
    void calendar_moves_notice_dates() {
        Agreement a = active();
        LocalDate notice = a.noticeDate();
        try {
            config.addHoliday(notice, "Test holiday", director);
            ops.dailyControl(system());

            Agreement moved = agreements.get(a.id(), manager).agreement();
            assertThat(moved.noticeDate()).isBefore(notice);
            assertThat(config.calendar(manager).holidays()).anyMatch(h -> h.date().equals(notice));
            assertThat(agreements.get(a.id(), manager).obligations()).filteredOn(o -> o.kind() == ObligationKind.NOTICE).extracting(Obligation::dueOn)
                    .containsExactly(moved.noticeDate());
            assertThatThrownBy(() -> config.addHoliday(notice, "x", manager)).isInstanceOf(RuntimeException.class);
        } finally {
            config.removeHoliday(notice, director);
        }
    }

    @Test
    @DisplayName("the timezone is configuration: the portfolio reports its date in the configured zone, and an unknown zone is refused")
    void timezone() {
        try {
            config.saveSettings("Pacific/Kiritimati", Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), director);
            assertThat(ops.portfolio(site, manager).asOf()).isEqualTo(LocalDate.now(ZoneId.of("Pacific/Kiritimati")).toString());
            assertThatThrownBy(() -> config.saveSettings("Mars/Olympus", Set.of(), director)).hasMessageContaining("Unknown timezone");
        } finally {
            config.saveSettings("Africa/Accra", Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), director);
        }
    }

    @Test
    @DisplayName("a role that is not shown rent cannot set it either: not on registration, not on a draft, not in an amendment")
    void money_needs_the_financial_grant() {
        Register withRent = new Register(site, null, "Block B", AgreementKind.LEASE, Direction.INBOUND, "Lease", "cp", null, null, "o", TODAY, TODAY.plusDays(400),
                RenewalType.NONE, null, 30, null, new BigDecimal("1000"), null, "GHS", planner);
        assertThatThrownBy(() -> agreements.register(withRent)).isInstanceOf(RuntimeException.class);
        Register withoutRent = new Register(site, null, "Block B", AgreementKind.LEASE, Direction.INBOUND, "Lease", "cp", null, null, "o", TODAY, TODAY.plusDays(400),
                RenewalType.NONE, null, 30, null, null, null, null, planner);
        assertThat(agreements.register(withoutRent).annualRent()).isNull();
    }

    @Test
    @DisplayName("without the financial grant rent, deposit and exposure are absent, in the list, the detail, the versions and the portfolio")
    void financial_masking() {
        Agreement a = active();
        agreements.propose(new Propose(a.id(), AmendmentKind.RENT_CHANGE, null, new BigDecimal("150000"), null, null, null, null, null, "Review", manager));

        assertThat(agreements.list(site, null, null, null, 0, 10, planner).items()).allSatisfy(x -> {
            assertThat(x.annualRent()).isNull();
            assertThat(x.depositAmount()).isNull();
        });
        LeaseAgreementService.Detail masked = agreements.get(a.id(), planner);
        assertThat(masked.financialView()).isFalse();
        assertThat(masked.agreement().annualRent()).isNull();
        assertThat(masked.versions()).allSatisfy(v -> assertThat(v.annualRent()).isNull());
        assertThat(masked.amendments()).allSatisfy(m -> assertThat(m.newAnnualRent()).isNull());
        assertThat(ops.portfolio(site, planner).financialExposure()).isNull();

        assertThat(agreements.get(a.id(), manager).agreement().annualRent()).isEqualByComparingTo("120000.00");
        assertThat(ops.portfolio(site, manager).financialExposure()).containsKey("GHS");
        assertThat(ops.portfolio(site, manager).financialExposure().get("GHS").payableRemaining()).isPositive();
        assertThat(agreements.get(a.id(), compliance).agreement().annualRent()).isNotNull();
    }

    @Test
    @DisplayName("the portfolio counts by status, expiry horizon, owner and unresolved obligations")
    void portfolio() {
        active();
        Agreement soon = agreements.register(terms(TODAY.plusDays(50), 20, "A. Mensah", "CP-5"));
        active(soon);
        agreements.addObligation(soon.id(), ObligationKind.COMPLIANCE, "Fire certificate", TODAY.minusDays(1), null, manager);

        LeaseOpsService.Portfolio p = ops.portfolio(site, manager);

        assertThat(p.agreementsByStatus().get("ACTIVE")).isEqualTo(2);
        assertThat(p.expiringWithin().get("within60")).isEqualTo(1);
        assertThat(p.expiringWithin().get("within365")).isEqualTo(1);
        // The compliance obligation, and the second agreement's rent review, whose date six months before its end has passed.
        assertThat(p.overdueObligations()).isEqualTo(2);
        assertThat(p.unresolvedCounterparties()).isEqualTo(2);
        assertThat(p.byOwner()).extracting(o -> o.owner()).contains("K. Owusu", "A. Mensah");
    }

    @Test
    @DisplayName("changing an owner is administrative: it writes a version without an amendment")
    void reassign() {
        Agreement a = active();
        Agreement changed = agreements.reassign(a.id(), "New Owner", null, manager);
        assertThat(changed.ownerReference()).isEqualTo("New Owner");
        assertThat(changed.versionNumber()).isEqualTo(2);
        assertThat(agreements.get(a.id(), manager).versions()).hasSize(2);
        assertThat(agreements.get(a.id(), manager).amendments()).isEmpty();
    }

    @Test
    @DisplayName("roles: the requester reads nothing, another site is refused, construction reads but cannot manage")
    void permissions() {
        Agreement a = draft();
        assertThatThrownBy(() -> agreements.list(site, null, null, null, 0, 10, caller("req", SflRole.IFIMP_REQUESTER))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> agreements.list(site, null, null, null, 0, 10, caller("fm", SflRole.FACILITIES_MANAGER, "OTHER"))).isInstanceOf(RuntimeException.class);
        Caller construction = caller("pm", SflRole.CONSTRUCTION_PROJECT_MANAGER);
        assertThat(agreements.list(site, null, null, null, 0, 10, construction).items()).hasSize(1);
        assertThatThrownBy(() -> agreements.submit(a.id(), null, construction)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> agreements.approve(a.id(), null, manager)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("unknown ids are not found, a stale version conflicts, and filters apply on the server")
    void lookup_and_versioning() {
        assertThatThrownBy(() -> agreements.get(UUID.randomUUID(), manager)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.RECORD_NOT_FOUND));
        Agreement a = draft();
        agreements.submit(a.id(), a.version(), manager);
        assertThatThrownBy(() -> agreements.submit(a.id(), a.version(), manager)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.VERSION_CONFLICT));
        assertThat(agreements.list(site, "IN_REVIEW", null, null, 0, 10, manager).total()).isEqualTo(1);
        assertThat(agreements.list(site, "ACTIVE", null, null, 0, 10, manager).items()).isEmpty();
        assertThat(agreements.list(site, null, "Nobody", null, 0, 10, manager).items()).isEmpty();
        assertThatThrownBy(() -> agreements.list(site, "WIZARD", null, null, 0, 10, manager)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM facilities.lease_history WHERE subject_id = ?", Long.class, a.id())).isGreaterThanOrEqualTo(2);
    }

    private static ActorContext system() {
        return new ActorContext(new SiteScopedPrincipal("system.lease", "Lease scheduler", Set.of(SflRole.SFL_ADMIN), Set.of("*"), true), "control");
    }
}

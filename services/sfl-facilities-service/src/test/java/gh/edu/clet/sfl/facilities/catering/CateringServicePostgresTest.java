package gh.edu.clet.sfl.facilities.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport;
import gh.edu.clet.sfl.facilities.catering.application.CateringConfigService;
import gh.edu.clet.sfl.facilities.catering.application.CateringOpsService;
import gh.edu.clet.sfl.facilities.catering.application.CateringServiceService;
import gh.edu.clet.sfl.facilities.catering.application.CateringServiceService.Change;
import gh.edu.clet.sfl.facilities.catering.application.CateringServiceService.Create;
import gh.edu.clet.sfl.facilities.catering.application.ports.CateringEstatePort;
import gh.edu.clet.sfl.facilities.catering.domain.Allergen;
import gh.edu.clet.sfl.facilities.catering.domain.CateringException;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.CheckType;
import gh.edu.clet.sfl.facilities.catering.domain.ContextType;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryRequest;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryTag;
import gh.edu.clet.sfl.facilities.catering.domain.EvidenceKind;
import gh.edu.clet.sfl.facilities.catering.domain.ExceptionType;
import gh.edu.clet.sfl.facilities.catering.domain.FinanceState;
import gh.edu.clet.sfl.facilities.catering.domain.HoldType;
import gh.edu.clet.sfl.facilities.catering.domain.Menu;
import gh.edu.clet.sfl.facilities.catering.domain.MenuItem;
import gh.edu.clet.sfl.facilities.catering.domain.MenuStatus;
import gh.edu.clet.sfl.facilities.catering.domain.NeedType;
import gh.edu.clet.sfl.facilities.catering.domain.ServiceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Supplier;
import gh.edu.clet.sfl.facilities.catering.domain.SupplierStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Variance;
import gh.edu.clet.sfl.facilities.catering.domain.VarianceKind;
import gh.edu.clet.sfl.facilities.catering.domain.Venue;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.SourceChannel;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesErrorCode;
import gh.edu.clet.sfl.facilities.shared.domain.error.FacilitiesException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * S172 against a real PostgreSQL, run as the roles that hold each grant. S152 is a double; the S163 incident port
 * and the finance port are the real adapters, so "recorded, not matched" is tested as shipped. What is under test
 * is the module's own rules: allergens flagged before approval, controls recomputed at confirmation, controlled
 * changes, separation of duties, temperature-derived results, and honest reconciliation.
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
})
@EnabledIf(value = "gh.edu.clet.sfl.facilities.FacilitiesPostgresSupport#databaseAvailable",
        disabledReason = "No PostgreSQL available; see FacilitiesPostgresSupport.unavailableReason()")
class CateringServicePostgresTest {

    private static final String HASH = "e".repeat(64);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        FacilitiesPostgresSupport.datasource(registry);
    }

    @Autowired private CateringConfigService config;
    @Autowired private CateringServiceService services;
    @Autowired private CateringOpsService ops;
    @MockitoBean private CateringEstatePort estate;

    private String site;
    private String tag;
    private Caller coordinator;
    private Caller director;
    private Caller hse;
    private Venue hall;
    private Supplier supplier;
    private Menu menu;
    private MenuItem satay;
    private MenuItem rice;

    @BeforeEach
    void setUp() {
        site = "CAT" + Math.abs(UUID.randomUUID().hashCode() % 1_000_000);
        tag = site.substring(3);
        when(estate.siteExists(anyString())).thenReturn(true);
        when(estate.siteOfRoom(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        coordinator = caller("coordinator-user", SflRole.FACILITIES_MANAGER, "*");
        director = caller("director-user", SflRole.FACILITIES_DIRECTOR, "*");
        hse = caller("hse-user", SflRole.HSE_MANAGER, "*");
        hall = config.createVenue(site, "HALL", "Main hall", null, 100, coordinator);
        supplier = config.createSupplier("SUP" + tag, "Accra Kitchens", "CERT-" + tag, LocalDate.now().plusYears(1), "FIN-1", coordinator);
        ops.recordCheck(new CateringOpsService.Check(site, null, supplier.id(), CheckType.SUPPLIER, null, null, true, "Premises ok", coordinator));
        menu = config.createMenu(site, "LUNCH", "Lunch menu", null, coordinator);
        satay = config.addItem(menu.id(), "Chicken satay", Set.of(Allergen.PEANUTS), true, Set.of(), coordinator);
        rice = config.addItem(menu.id(), "Jollof rice", Set.of(), true, Set.of(DietaryTag.VEGAN), coordinator);
        menu = config.approveMenu(menu.id(), null, director);
    }

    private static Caller caller(String id, SflRole role, String... sites) {
        return new Caller(new ActorContext(new SiteScopedPrincipal(id, id, Set.of(role), Set.of(sites), false), "corr-" + id),
                SourceChannel.WEB);
    }

    private CateringService draft(int guests, Instant startsAt, Integer cutoffHours) {
        return services.create(new Create(site, hall.id(), menu.id(), supplier.id(), ContextType.ROUTINE, null, "Staff lunch",
                startsAt, guests, 60, cutoffHours, coordinator));
    }

    private CateringService draft() {
        return draft(50, Instant.now().plus(3, ChronoUnit.DAYS), null);
    }

    private CateringService approved() {
        CateringService s = draft();
        services.submit(s.id(), null, coordinator);
        return services.approve(s.id(), null, null, null, director);
    }

    private CateringService confirmed() {
        CateringService s = approved();
        return services.confirm(s.id(), null, coordinator);
    }

    private CateringService delivered(int portions) {
        CateringService s = confirmed();
        ops.recordCheck(new CateringOpsService.Check(site, s.id(), null, CheckType.TEMPERATURE, HoldType.HOT, new BigDecimal("70.0"), null, null, coordinator));
        return services.deliver(s.id(), portions, null, coordinator);
    }

    private void file(UUID serviceId, EvidenceKind kind) {
        ops.submitEvidence(serviceId, kind, "REF-" + kind, "doc.pdf", "application/pdf", 10, HASH, coordinator);
    }

    @Test
    @DisplayName("a menu cannot be approved with undeclared allergens, and changing an approved menu sends planned services back for approval")
    void controlled_menu_changes() {
        Menu fresh = config.createMenu(site, "DRAFT1", "Draft", null, coordinator);
        MenuItem undeclared = config.addItem(fresh.id(), "Stew", Set.of(), false, Set.of(), coordinator);
        assertThatThrownBy(() -> config.approveMenu(fresh.id(), null, director)).hasMessageContaining("Stew");
        config.updateItem(undeclared.id(), Set.of(Allergen.FISH), true, Set.of(), coordinator);
        assertThat(config.approveMenu(fresh.id(), null, director).status()).isEqualTo(MenuStatus.APPROVED);

        CateringService planned = confirmed();
        config.updateItem(rice.id(), Set.of(Allergen.SESAME), true, Set.of(DietaryTag.VEGAN), coordinator);

        assertThat(services.get(planned.id(), coordinator).menu().status()).isEqualTo(MenuStatus.DRAFT);
        CateringService sentBack = services.get(planned.id(), coordinator).service();
        assertThat(sentBack.status()).isEqualTo(ServiceStatus.PENDING_APPROVAL);
        assertThat(sentBack.approvedBy()).isNull();
    }

    @Test
    @DisplayName("an event or booking service must name its context; a service must start in the future; the venue must be at the site")
    void creation_validation() {
        assertThatThrownBy(() -> services.create(new Create(site, hall.id(), menu.id(), supplier.id(), ContextType.EVENT, null, "Gala",
                Instant.now().plus(2, ChronoUnit.DAYS), 10, 10, null, coordinator))).hasMessageContaining("contextReference");
        assertThatThrownBy(() -> draft(10, Instant.now().minus(1, ChronoUnit.HOURS), null)).hasMessageContaining("future");
        Venue elsewhere = config.createVenue("OTHER" + tag, "HALL", "Other", null, 10, coordinator);
        assertThatThrownBy(() -> services.create(new Create(site, elsewhere.id(), menu.id(), supplier.id(), ContextType.ROUTINE, null,
                "x", Instant.now().plus(2, ChronoUnit.DAYS), 10, 10, null, coordinator))).hasMessageContaining("belong to");
    }

    @Test
    @DisplayName("a dietary need takes an opaque person reference, not a name, and a known allergen or diet")
    void dietary_data_is_minimal() {
        CateringService s = draft();
        assertThatThrownBy(() -> services.addDietary(s.id(), "Ama Mensah", NeedType.ALLERGY, "PEANUTS", "Event lead", coordinator))
                .hasMessageContaining("opaque");
        assertThatThrownBy(() -> services.addDietary(s.id(), "P-17", NeedType.ALLERGY, "GLITTER", "Event lead", coordinator))
                .hasMessageContaining("Unknown");
        assertThat(services.addDietary(s.id(), "P-17", NeedType.ALLERGY, "PEANUTS", "Event lead", coordinator).personReference()).isEqualTo("P-17");
    }

    @Test
    @DisplayName("an allergy is flagged on the dishes that contain it, and a menu with nothing safe blocks approval until a safe substitution is approved by someone else")
    void allergen_conflict_and_substitution() {
        Menu risky = config.createMenu(site, "RISKY", "Peanut menu", null, coordinator);
        MenuItem onlySatay = config.addItem(risky.id(), "Satay", Set.of(Allergen.PEANUTS), true, Set.of(), coordinator);
        config.approveMenu(risky.id(), null, director);
        CateringService s = services.create(new Create(site, hall.id(), risky.id(), supplier.id(), ContextType.ROUTINE, null, "Lunch",
                Instant.now().plus(3, ChronoUnit.DAYS), 20, 20, null, coordinator));
        DietaryRequest need = services.addDietary(s.id(), "P-1", NeedType.ALLERGY, "PEANUTS", "Event lead", coordinator);
        services.submit(s.id(), null, coordinator);

        CateringServiceService.ServiceDetail detail = services.get(s.id(), coordinator);
        assertThat(detail.needs().get(0).flaggedItems()).containsExactly("Satay");
        assertThat(detail.needs().get(0).blocked()).isTrue();
        assertThatThrownBy(() -> services.approve(s.id(), null, null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_ALLERGEN_CONFLICT));

        assertThatThrownBy(() -> services.substitute(need.id(), onlySatay.id(), null, director)).hasMessageContaining("not safe");
        assertThatThrownBy(() -> services.substitute(need.id(), rice.id(), null, director)).hasMessageContaining("not on this service");
        MenuItem safe = config.addItem(risky.id(), "Plain rice", Set.of(), true, Set.of(), coordinator);
        config.approveMenu(risky.id(), null, director);
        // The menu change sent the service back for approval; it is already pending.
        assertThat(services.get(s.id(), coordinator).service().status()).isEqualTo(ServiceStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> services.substitute(need.id(), safe.id(), null, coordinator)).isInstanceOf(RuntimeException.class);
        assertThat(services.substitute(need.id(), safe.id(), null, director).status().name()).isEqualTo("SUBSTITUTED");
        assertThat(services.approve(s.id(), null, null, null, director).status()).isEqualTo(ServiceStatus.APPROVED);
    }

    @Test
    @DisplayName("whoever requested a service cannot approve it, and whoever recorded a need cannot approve its substitution")
    void separation_of_duties() {
        CateringService s = draft();
        services.submit(s.id(), null, director);
        assertThatThrownBy(() -> services.approve(s.id(), null, null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_SELF_APPROVAL));
        DietaryRequest need = services.addDietary(s.id(), "P-2", NeedType.DIETARY, "HALAL", "Lead", director);
        assertThatThrownBy(() -> services.waive(need.id(), "Guest confirmed they will bring their own", null, director))
                .isInstanceOfSatisfying(FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_SELF_APPROVAL));
        assertThat(services.waive(need.id(), "Guest confirmed they will bring their own", null, hse).status().name()).isEqualTo("WAIVED");
    }

    @Test
    @DisplayName("an overdue supplier check blocks approval; an approver can accept it with a reason, and a passing check clears it")
    void supplier_check_overdue() {
        Supplier lapsed = config.createSupplier("LAP" + tag, "Lapsed Cooks", "CERT-L", LocalDate.now().plusYears(1), null, coordinator);
        CateringService s = services.create(new Create(site, hall.id(), menu.id(), lapsed.id(), ContextType.ROUTINE, null, "Lunch",
                Instant.now().plus(3, ChronoUnit.DAYS), 20, 20, null, coordinator));
        services.submit(s.id(), null, coordinator);

        assertThat(services.get(s.id(), coordinator).readiness()).extracting(b -> b.code()).contains("SUPPLIER_CHECK_OVERDUE");
        assertThatThrownBy(() -> services.approve(s.id(), null, null, null, director)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID);
                    assertThat(e.getMessage()).contains("no passing check");
                });

        ops.recordCheck(new CateringOpsService.Check(site, null, lapsed.id(), CheckType.SUPPLIER, null, null, true, null, coordinator));
        assertThat(services.get(s.id(), coordinator).readiness()).isEmpty();
        assertThat(services.approve(s.id(), null, null, null, director).supplierApprovedBy()).isNull();
    }

    @Test
    @DisplayName("an approver can accept an overdue supplier check with a written reason, and it is recorded on the service")
    void supplier_exception() {
        Supplier lapsed = config.createSupplier("EXC" + tag, "Exception Cooks", "CERT-E", LocalDate.now().plusYears(1), null, coordinator);
        CateringService s = services.create(new Create(site, hall.id(), menu.id(), lapsed.id(), ContextType.ROUTINE, null, "Lunch",
                Instant.now().plus(3, ChronoUnit.DAYS), 20, 20, null, coordinator));
        services.submit(s.id(), null, coordinator);

        CateringService approved = services.approve(s.id(), null, "Inspection booked for Monday; used last week", null, director);

        assertThat(approved.supplierExceptionReason()).contains("Inspection");
        assertThat(approved.supplierApprovedBy()).isEqualTo("director-user");
    }

    @Test
    @DisplayName("confirmation recomputes the controls: a supplier suspended after approval cannot be confirmed")
    void confirmation_recomputes() {
        CateringService s = approved();
        config.updateSupplier(supplier.id(), null, null, SupplierStatus.SUSPENDED, null, coordinator);

        assertThatThrownBy(() -> services.confirm(s.id(), null, coordinator)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID);
                    assertThat(e.getMessage()).contains("suspended");
                });
        assertThat(services.get(s.id(), coordinator).service().status()).isEqualTo(ServiceStatus.APPROVED);
    }

    @Test
    @DisplayName("more guests than the venue holds needs an approver to accept it, with a reason")
    void capacity_exceeded() {
        CateringService s = draft(150, Instant.now().plus(3, ChronoUnit.DAYS), null);
        services.submit(s.id(), null, coordinator);
        assertThatThrownBy(() -> services.approve(s.id(), null, null, null, director)).hasMessageContaining("exceed the capacity");
        CateringService approved = services.approve(s.id(), "Overflow marquee booked", null, null, director);
        assertThat(approved.capacityExceptionReason()).isEqualTo("Overflow marquee booked");
        assertThat(approved.status()).isEqualTo(ServiceStatus.APPROVED);
    }

    @Test
    @DisplayName("a temperature result comes from the reading; a failing one raises a food-safety incident, pending S163, and blocks delivery until an approver resolves it")
    void temperature_and_incident() {
        CateringService s = confirmed();

        var failed = ops.recordCheck(new CateringOpsService.Check(site, s.id(), null, CheckType.TEMPERATURE, HoldType.HOT, new BigDecimal("50.0"), true, null, coordinator));
        assertThat(failed.result().name()).isEqualTo("FAIL");
        CateringException incident = services.get(s.id(), coordinator).exceptions().get(0);
        assertThat(incident.exceptionType()).isEqualTo(ExceptionType.FOOD_SAFETY_INCIDENT);
        assertThat(incident.incidentState()).isEqualTo("PENDING_MANUAL");

        ops.recordCheck(new CateringOpsService.Check(site, s.id(), null, CheckType.TEMPERATURE, HoldType.HOT, new BigDecimal("68.0"), null, null, coordinator));
        assertThatThrownBy(() -> services.deliver(s.id(), 60, null, coordinator)).hasMessageContaining("food-safety incident");
        assertThatThrownBy(() -> ops.resolveException(incident.id(), "Reheated", null, coordinator)).isInstanceOf(RuntimeException.class);
        ops.linkIncident(incident.id(), "INC-77", coordinator);
        ops.resolveException(incident.id(), "Food discarded and reheated; hot-hold unit serviced", null, hse);
        assertThat(services.deliver(s.id(), 60, null, coordinator).status()).isEqualTo(ServiceStatus.DELIVERED);
    }

    @Test
    @DisplayName("a service cannot be delivered with no passing temperature check on record")
    void delivery_needs_a_check() {
        CateringService s = confirmed();
        assertThatThrownBy(() -> services.deliver(s.id(), 60, null, coordinator)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_SERVICE_NOT_VALID));
    }

    @Test
    @DisplayName("a quantity difference needs a variance with an owner and a reason before reconciling, approved by someone else; with no invoice it stays pending finance")
    void reconciliation() {
        CateringService s = delivered(54);

        assertThatThrownBy(() -> ops.reconcile(s.id(), "PO-1", "INV-1", null, coordinator)).hasMessageContaining("record the variance");
        assertThatThrownBy(() -> ops.recordVariance(s.id(), VarianceKind.QUANTITY, new BigDecimal("60"), new BigDecimal("54"), "K. Owusu", "", coordinator))
                .isInstanceOf(RuntimeException.class);
        Variance variance = ops.recordVariance(s.id(), VarianceKind.QUANTITY, new BigDecimal("60"), new BigDecimal("54"), "K. Owusu",
                "Six guests did not attend", coordinator);
        assertThat(variance.difference()).isEqualByComparingTo("-6");

        CateringOpsService.Reconciliation pending = ops.reconcile(s.id(), "PO-1", null, null, coordinator);
        assertThat(pending.service().status()).isEqualTo(ServiceStatus.DELIVERED);
        assertThat(pending.service().financeState()).isEqualTo(FinanceState.PENDING_FINANCE);

        CateringOpsService.Reconciliation done = ops.reconcile(s.id(), "PO-1", "INV-1", null, coordinator);
        assertThat(done.service().status()).isEqualTo(ServiceStatus.RECONCILED);
        assertThat(done.service().financeState()).isEqualTo(FinanceState.RECORDED);
        assertThat(done.finance().matched()).isFalse();
        assertThat(done.note()).contains("not verified");

        assertThatThrownBy(() -> ops.approveVariance(variance.id(), caller("coordinator-user", SflRole.HSE_MANAGER, "*")))
                .isInstanceOfSatisfying(FacilitiesException.class, e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.CAT_SELF_APPROVAL));
        assertThatThrownBy(() -> ops.close(s.id(), null, coordinator)).hasMessageContaining("variance").hasMessageContaining("delivery note");
        ops.approveVariance(variance.id(), director);
        file(s.id(), EvidenceKind.DELIVERY_NOTE);
        file(s.id(), EvidenceKind.INVOICE);

        CateringService closed = ops.close(s.id(), null, coordinator);
        assertThat(closed.status()).isEqualTo(ServiceStatus.CLOSED);
        CateringOpsService.Pack pack = ops.pack(s.id(), coordinator);
        assertThat(pack.portionDifference()).isEqualTo(-6);
        assertThat(pack.evidence()).hasSize(2);
        assertThat(pack.finance().available()).isFalse();
    }

    @Test
    @DisplayName("a change after approval needs a reason and sends the service back; after the cut-off it needs an approver, as does cancelling")
    void controlled_changes_and_cutoff() {
        CateringService s = approved();
        assertThatThrownBy(() -> services.change(new Change(s.id(), 55, null, null, null, null, "", null, coordinator)))
                .isInstanceOf(IllegalArgumentException.class);
        CateringService changed = services.change(new Change(s.id(), 55, 65, null, null, null, "Two more tables", null, coordinator));
        assertThat(changed.status()).isEqualTo(ServiceStatus.PENDING_APPROVAL);
        assertThat(changed.approvedBy()).isNull();
        assertThat(changed.plannedPortions()).isEqualTo(65);

        CateringService late = draft(30, Instant.now().plus(2, ChronoUnit.HOURS), 24);
        services.submit(late.id(), null, coordinator);
        assertThatThrownBy(() -> services.change(new Change(late.id(), 31, null, null, null, null, "One more", null, coordinator)))
                .isInstanceOf(RuntimeException.class);
        assertThat(services.change(new Change(late.id(), 31, null, null, null, null, "One more", null, director)).expectedGuests()).isEqualTo(31);
        assertThatThrownBy(() -> services.cancel(late.id(), "No longer needed", null, coordinator)).isInstanceOf(RuntimeException.class);
        assertThat(services.cancel(late.id(), "No longer needed", null, director).status()).isEqualTo(ServiceStatus.CANCELLED);

        CateringService free = draft();
        assertThat(services.cancel(free.id(), "Event moved", null, coordinator).status()).isEqualTo(ServiceStatus.CANCELLED);
    }

    @Test
    @DisplayName("a reader without the dietary grant sees how many needs there are but not who needs what")
    void dietary_masking() {
        CateringService s = draft();
        services.addDietary(s.id(), "P-9", NeedType.ALLERGY, "MILK", "Lead", coordinator);
        Caller auditor = caller("aud", SflRole.COMPLIANCE_OFFICER, "*");

        CateringServiceService.ServiceDetail masked = services.get(s.id(), auditor);
        assertThat(masked.dietaryView()).isFalse();
        assertThat(masked.needs()).isEmpty();
        assertThat(masked.dietaryCount()).isEqualTo(1);
        assertThat(services.get(s.id(), coordinator).needs()).hasSize(1);
    }

    @Test
    @DisplayName("the dashboard counts delivered against confirmed, checked services, the dietary exception rate and variance ageing")
    void dashboard() {
        delivered(60);
        CateringService notYet = confirmed();
        DietaryRequest need = services.addDietary(notYet.id(), "P-3", NeedType.DIETARY, "HALAL", "Lead", coordinator);
        services.waive(need.id(), "Guest will bring their own meal", null, hse);

        CateringOpsService.Dashboard d = ops.dashboard(site, 90, coordinator);

        // A confirmed service dated in the future is not yet due, so only the delivered one counts.
        assertThat(d.confirmedServices()).isEqualTo(1);
        assertThat(d.deliveredPercent()).isEqualByComparingTo("100.0");
        assertThat(d.servicesDelivered()).isEqualTo(1);
        assertThat(d.servicesWithPassingCheck()).isEqualTo(1);
        assertThat(d.checkedPercent()).isEqualByComparingTo("100.0");
        assertThat(d.dietaryExceptions()).isEqualTo(1);
        assertThat(d.awaitingFinance()).isEqualTo(1);
    }

    @Test
    @DisplayName("roles: the requester reads nothing, another site is refused, the compliance officer cannot manage or approve")
    void permissions() {
        CateringService s = draft();
        assertThatThrownBy(() -> services.list(site, null, 0, 10, caller("req", SflRole.IFIMP_REQUESTER, "*"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> services.list(site, null, 0, 10, caller("fm", SflRole.FACILITIES_MANAGER, "OTHER"))).isInstanceOf(RuntimeException.class);
        Caller auditor = caller("aud", SflRole.COMPLIANCE_OFFICER, "*");
        assertThat(services.list(site, null, 0, 10, auditor).items()).hasSize(1);
        assertThatThrownBy(() -> services.submit(s.id(), null, auditor)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> services.approve(s.id(), null, null, null, coordinator)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("unknown ids are not found, a stale version conflicts, and filters apply on the server")
    void lookup_and_versioning() {
        assertThatThrownBy(() -> services.get(UUID.randomUUID(), coordinator)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.RECORD_NOT_FOUND));
        CateringService s = draft();
        services.submit(s.id(), s.version(), coordinator);
        assertThatThrownBy(() -> services.submit(s.id(), s.version(), coordinator)).isInstanceOfSatisfying(FacilitiesException.class,
                e -> assertThat(e.code()).isEqualTo(FacilitiesErrorCode.VERSION_CONFLICT));
        assertThat(services.list(site, "PENDING_APPROVAL", 0, 10, coordinator).total()).isEqualTo(1);
        assertThat(services.list(site, "CLOSED", 0, 10, coordinator).items()).isEmpty();
        assertThatThrownBy(() -> services.list(site, "WIZARD", 0, 10, coordinator)).isInstanceOf(IllegalArgumentException.class);
    }
}

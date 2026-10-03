package gh.edu.clet.sfl.facilities.catering;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.facilities.catering.domain.Allergen;
import gh.edu.clet.sfl.facilities.catering.domain.AllergenPolicy;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.ContextType;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryRequest;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryTag;
import gh.edu.clet.sfl.facilities.catering.domain.FinanceState;
import gh.edu.clet.sfl.facilities.catering.domain.HoldType;
import gh.edu.clet.sfl.facilities.catering.domain.Menu;
import gh.edu.clet.sfl.facilities.catering.domain.MenuItem;
import gh.edu.clet.sfl.facilities.catering.domain.MenuStatus;
import gh.edu.clet.sfl.facilities.catering.domain.NeedStatus;
import gh.edu.clet.sfl.facilities.catering.domain.NeedType;
import gh.edu.clet.sfl.facilities.catering.domain.ReadinessPolicy;
import gh.edu.clet.sfl.facilities.catering.domain.ServiceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Supplier;
import gh.edu.clet.sfl.facilities.catering.domain.SupplierStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CateringDomainTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 1);

    @Test
    @DisplayName("hot food passes at 63 C and above, cold food at 5 C and below")
    void temperature_limits() {
        assertThat(HoldType.HOT.passes(new BigDecimal("63.0"))).isTrue();
        assertThat(HoldType.HOT.passes(new BigDecimal("62.9"))).isFalse();
        assertThat(HoldType.COLD.passes(new BigDecimal("5.0"))).isTrue();
        assertThat(HoldType.COLD.passes(new BigDecimal("5.1"))).isFalse();
    }

    @Test
    @DisplayName("a service moves forward one step at a time; delivery cannot be skipped; closed and cancelled are final")
    void status_transitions() {
        assertThat(ServiceStatus.DRAFT.canMoveTo(ServiceStatus.CONFIRMED)).isFalse();
        assertThat(ServiceStatus.APPROVED.canMoveTo(ServiceStatus.PENDING_APPROVAL)).isTrue();
        assertThat(ServiceStatus.CONFIRMED.canMoveTo(ServiceStatus.DELIVERED)).isTrue();
        assertThat(ServiceStatus.DELIVERED.canMoveTo(ServiceStatus.CLOSED)).isFalse();
        assertThat(ServiceStatus.CLOSED.canMoveTo(ServiceStatus.CANCELLED)).isFalse();
        assertThat(ServiceStatus.CONFIRMED.planning()).isTrue();
        assertThat(ServiceStatus.DELIVERED.planning()).isFalse();
    }

    @Test
    @DisplayName("an allergy is flagged on the dishes containing the allergen, and blocked when nothing declared-safe is on the menu")
    void allergen_matching() {
        MenuItem nuts = item("Satay", true, Set.of(Allergen.PEANUTS), Set.of());
        MenuItem rice = item("Jollof rice", true, Set.of(), Set.of(DietaryTag.VEGAN));
        DietaryRequest peanut = need(NeedType.ALLERGY, "PEANUTS", NeedStatus.OPEN);

        AllergenPolicy.Finding withSafe = AllergenPolicy.assess(List.of(peanut), List.of(nuts, rice)).get(0);
        assertThat(withSafe.flagged()).extracting(MenuItem::name).containsExactly("Satay");
        assertThat(withSafe.blocked()).isFalse();

        AllergenPolicy.Finding noneSafe = AllergenPolicy.assess(List.of(peanut), List.of(nuts)).get(0);
        assertThat(noneSafe.blocked()).isTrue();
        assertThat(noneSafe.message()).contains("PEANUTS");
    }

    @Test
    @DisplayName("a dish whose allergens nobody has declared is never counted as safe")
    void undeclared_is_not_safe() {
        MenuItem undeclared = item("Stew", false, Set.of(), Set.of());
        assertThat(AllergenPolicy.assess(List.of(need(NeedType.ALLERGY, "MILK", NeedStatus.OPEN)), List.of(undeclared)).get(0).blocked()).isTrue();
    }

    @Test
    @DisplayName("a substituted or waived need is no longer blocking")
    void resolved_needs() {
        MenuItem nuts = item("Satay", true, Set.of(Allergen.PEANUTS), Set.of());
        assertThat(AllergenPolicy.assess(List.of(need(NeedType.ALLERGY, "PEANUTS", NeedStatus.SUBSTITUTED)), List.of(nuts)).get(0).blocked()).isFalse();
        assertThat(AllergenPolicy.assess(List.of(need(NeedType.ALLERGY, "PEANUTS", NeedStatus.WAIVED)), List.of(nuts)).get(0).blocked()).isFalse();
    }

    @Test
    @DisplayName("a dietary need is met by a dish carrying the tag, and blocked without one")
    void dietary_tag() {
        MenuItem vegan = item("Salad", true, Set.of(), Set.of(DietaryTag.VEGAN));
        MenuItem meat = item("Grill", true, Set.of(), Set.of());
        DietaryRequest need = need(NeedType.DIETARY, "VEGAN", NeedStatus.OPEN);
        assertThat(AllergenPolicy.assess(List.of(need), List.of(vegan, meat)).get(0).blocked()).isFalse();
        assertThat(AllergenPolicy.assess(List.of(need), List.of(meat)).get(0).blocked()).isTrue();
    }

    @Test
    @DisplayName("a substitute must really be safe: declared and free of the allergen, or carrying the tag")
    void substitute_suitability() {
        DietaryRequest peanut = need(NeedType.ALLERGY, "PEANUTS", NeedStatus.OPEN);
        assertThat(AllergenPolicy.suitable(peanut, item("Rice", true, Set.of(), Set.of()))).isTrue();
        assertThat(AllergenPolicy.suitable(peanut, item("Satay", true, Set.of(Allergen.PEANUTS), Set.of()))).isFalse();
        assertThat(AllergenPolicy.suitable(peanut, item("Mystery", false, Set.of(), Set.of()))).isFalse();
    }

    @Test
    @DisplayName("a clean service has no blockers")
    void ready() {
        assertThat(blockers(service(40, null, null), venue(true, 100), supplier(SupplierStatus.APPROVED, DAY.plusDays(30)),
                menu(MenuStatus.APPROVED), true)).isEmpty();
    }

    @Test
    @DisplayName("every blocker is named: venue, capacity, menu, supplier status, certificate and overdue check")
    void blockers_listed() {
        List<ReadinessPolicy.Blocker> blockers = blockers(service(200, null, null), venue(false, 100),
                supplier(SupplierStatus.SUSPENDED, DAY.minusDays(1)), menu(MenuStatus.DRAFT), false);
        assertThat(blockers).extracting(ReadinessPolicy.Blocker::code).containsExactlyInAnyOrder("VENUE_INACTIVE",
                "CAPACITY_EXCEEDED", "MENU_NOT_APPROVED", "SUPPLIER_SUSPENDED", "SUPPLIER_CERTIFICATE_EXPIRED", "SUPPLIER_CHECK_OVERDUE");
    }

    @Test
    @DisplayName("capacity and supplier problems can be accepted by an approver; a suspended supplier, an inactive venue and an allergen block cannot")
    void exceptions_allowed_only_where_sensible() {
        List<ReadinessPolicy.Blocker> blockers = blockers(service(200, null, null), venue(false, 100),
                supplier(SupplierStatus.SUSPENDED, DAY.minusDays(1)), menu(MenuStatus.DRAFT), false);
        assertThat(blockers).filteredOn(ReadinessPolicy.Blocker::exceptionable).extracting(ReadinessPolicy.Blocker::code)
                .containsExactlyInAnyOrder("CAPACITY_EXCEEDED", "SUPPLIER_CERTIFICATE_EXPIRED", "SUPPLIER_CHECK_OVERDUE");
    }

    @Test
    @DisplayName("an approved capacity exception removes the capacity blocker and nothing else")
    void accepted_exceptions() {
        assertThat(blockers(service(200, "Overflow tent", "approver"), venue(true, 100),
                supplier(SupplierStatus.APPROVED, DAY.plusDays(5)), menu(MenuStatus.APPROVED), true)).isEmpty();
        assertThat(blockers(service(200, "Overflow tent", "approver"), venue(true, 100),
                supplier(SupplierStatus.APPROVED, DAY.minusDays(1)), menu(MenuStatus.APPROVED), true))
                .extracting(ReadinessPolicy.Blocker::code).containsExactly("SUPPLIER_CERTIFICATE_EXPIRED");
    }

    private static List<ReadinessPolicy.Blocker> blockers(CateringService service, Venue venue, Supplier supplier, Menu menu,
            boolean checkCurrent) {
        List<MenuItem> items = List.of(item("Rice", true, Set.of(), Set.of()));
        return ReadinessPolicy.blockers(service, venue, supplier, menu, items, AllergenPolicy.assess(List.of(), items), checkCurrent);
    }

    private static MenuItem item(String name, boolean declared, Set<Allergen> allergens, Set<DietaryTag> tags) {
        return new MenuItem(UUID.randomUUID(), UUID.randomUUID(), "S", name,
                allergens.isEmpty() ? EnumSet.noneOf(Allergen.class) : EnumSet.copyOf(allergens), declared,
                tags.isEmpty() ? EnumSet.noneOf(DietaryTag.class) : EnumSet.copyOf(tags), "u", Instant.now());
    }

    private static DietaryRequest need(NeedType type, String code, NeedStatus status) {
        return new DietaryRequest(UUID.randomUUID(), UUID.randomUUID(), "S", "P-1", type, code, "coordinator", status, null, null,
                null, "u", Instant.now(), Instant.now(), 0);
    }

    private static Venue venue(boolean active, int capacity) {
        return new Venue(UUID.randomUUID(), "S", "HALL", "Hall", null, capacity, active, "u", Instant.now(), Instant.now(), 0);
    }

    private static Supplier supplier(SupplierStatus status, LocalDate expires) {
        return new Supplier(UUID.randomUUID(), "SUP", "Supplier", "CERT", expires, null, status, "u", Instant.now(), Instant.now(), 0);
    }

    private static Menu menu(MenuStatus status) {
        return new Menu(UUID.randomUUID(), "S", "M1", "Menu", null, status, null, null, "u", Instant.now(), Instant.now(), 0);
    }

    private static CateringService service(int guests, String capacityReason, String capacityBy) {
        return new CateringService(UUID.randomUUID(), "CAT-S-1", "S", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ContextType.ROUTINE, null, "Lunch", DAY, Instant.now(), Instant.now(), guests, 100, null, ServiceStatus.PENDING_APPROVAL,
                "u", null, null, capacityReason, capacityBy, null, null, null, null, FinanceState.NOT_STARTED, null, null, null, "u",
                Instant.now(), Instant.now(), 0);
    }
}

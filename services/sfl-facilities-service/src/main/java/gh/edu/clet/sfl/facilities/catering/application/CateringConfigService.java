package gh.edu.clet.sfl.facilities.catering.application;

import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.facilities.catering.domain.Allergen;
import gh.edu.clet.sfl.facilities.catering.domain.CateringService;
import gh.edu.clet.sfl.facilities.catering.domain.DietaryTag;
import gh.edu.clet.sfl.facilities.catering.domain.Menu;
import gh.edu.clet.sfl.facilities.catering.domain.MenuItem;
import gh.edu.clet.sfl.facilities.catering.domain.MenuStatus;
import gh.edu.clet.sfl.facilities.catering.domain.ServiceStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Supplier;
import gh.edu.clet.sfl.facilities.catering.domain.SupplierStatus;
import gh.edu.clet.sfl.facilities.catering.domain.Venue;
import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.shared.domain.audit.AuditAction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suppliers, venues and menus - SRS-SFL-S172-01 and the menu half of -04.
 *
 * <h2>Menus are controlled</h2>
 *
 * A menu is approved by someone with the approve grant, and only once every dish has its allergens declared. An
 * approved menu is not edited in place: adding or changing a dish returns it to DRAFT, and every service still
 * being planned on it goes back for approval with the reason recorded. That is the "controlled change to menus"
 * rule - the thing people were told they could eat cannot change underneath them.
 */
@Service
public class CateringConfigService {

    private final CateringStore store;
    private final CateringSupport support;
    private final TransactionTemplate inTransaction;

    public CateringConfigService(CateringStore store, CateringSupport support, PlatformTransactionManager transactions) {
        this.store = store;
        this.support = support;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    public Configuration configuration(String siteCode, Caller caller) {
        String site = CateringSupport.required(siteCode, "siteCode").toUpperCase();
        support.require(caller, SflPermission.FACILITIES_CATERING_READ, site, "CateringConfiguration", site);
        return inTransaction.execute(tx -> new Configuration(store.suppliers(), store.venues(site),
                store.menus(site).stream().map(m -> new MenuView(m, store.itemsOf(m.id()))).toList()));
    }

    public record MenuView(Menu menu, List<MenuItem> items) {
    }

    public record Configuration(List<Supplier> suppliers, List<Venue> venues, List<MenuView> menus) {
    }

    // ---- suppliers

    public Supplier createSupplier(String code, String name, String certificateReference, LocalDate certificateExpiresOn,
            String financeReference, Caller caller) {
        return inTransaction.execute(tx -> {
            support.requireOrganisation(caller, SflPermission.FACILITIES_CATERING_MANAGE, "Supplier", "new");
            if (certificateExpiresOn == null) {
                throw new IllegalArgumentException("certificateExpiresOn is required");
            }
            Instant now = support.now();
            Supplier supplier = new Supplier(UUID.randomUUID(), CateringSupport.required(code, "code").toUpperCase(),
                    CateringSupport.required(name, "name"), CateringSupport.required(certificateReference, "certificateReference"),
                    certificateExpiresOn, CateringSupport.blankToNull(financeReference), SupplierStatus.APPROVED,
                    caller.actor().actorId(), now, now, 0);
            store.insert(supplier);
            support.audit(caller, AuditAction.CAT_CONFIG_CHANGED, "Supplier", supplier.id(), "*", null, supplier);
            return supplier;
        });
    }

    public Supplier updateSupplier(UUID id, String certificateReference, LocalDate certificateExpiresOn,
            SupplierStatus status, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            support.requireOrganisation(caller, SflPermission.FACILITIES_CATERING_MANAGE, "Supplier", id.toString());
            Supplier before = store.supplier(id).orElseThrow(() -> CateringSupport.notFound("Supplier", id));
            CateringSupport.checkVersion(expectedVersion, before.version());
            Supplier after = new Supplier(id, before.code(), before.name(),
                    certificateReference == null ? before.certificateReference() : certificateReference.strip(),
                    certificateExpiresOn == null ? before.certificateExpiresOn() : certificateExpiresOn,
                    before.financeReference(), status == null ? before.status() : status, before.createdBy(),
                    before.createdAt(), support.now(), before.version());
            if (!store.update(after, before.version())) {
                throw CateringSupport.conflict();
            }
            Supplier saved = store.supplier(id).orElseThrow();
            support.audit(caller, AuditAction.CAT_CONFIG_CHANGED, "Supplier", id, "*", before, saved);
            return saved;
        });
    }

    // ---- venues

    public Venue createVenue(String siteCode, String code, String name, UUID roomId, int capacity, Caller caller) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(siteCode);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, site, "Venue", "new");
            support.validateRoom(site, roomId);
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            Instant now = support.now();
            Venue venue = new Venue(UUID.randomUUID(), site, CateringSupport.required(code, "code").toUpperCase(),
                    CateringSupport.required(name, "name"), roomId, capacity, true, caller.actor().actorId(), now, now, 0);
            store.insert(venue);
            support.audit(caller, AuditAction.CAT_CONFIG_CHANGED, "Venue", venue.id(), site, null, venue);
            return venue;
        });
    }

    public Venue updateVenue(UUID id, String name, Integer capacity, Boolean active, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Venue before = store.venue(id).orElseThrow(() -> CateringSupport.notFound("Venue", id));
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, before.siteCode(), "Venue", id.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (capacity != null && capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            Venue after = new Venue(id, before.siteCode(), before.code(), name == null ? before.name() : name.strip(),
                    before.roomId(), capacity == null ? before.capacity() : capacity, active == null ? before.active() : active,
                    before.createdBy(), before.createdAt(), support.now(), before.version());
            if (!store.update(after, before.version())) {
                throw CateringSupport.conflict();
            }
            Venue saved = store.venue(id).orElseThrow();
            support.audit(caller, AuditAction.CAT_CONFIG_CHANGED, "Venue", id, saved.siteCode(), before, saved);
            return saved;
        });
    }

    // ---- menus

    public Menu createMenu(String siteCode, String code, String name, String description, Caller caller) {
        return inTransaction.execute(tx -> {
            String site = support.validateSite(siteCode);
            support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, site, "Menu", "new");
            Instant now = support.now();
            Menu menu = new Menu(UUID.randomUUID(), site, CateringSupport.required(code, "code").toUpperCase(),
                    CateringSupport.required(name, "name"), CateringSupport.blankToNull(description), MenuStatus.DRAFT,
                    null, null, caller.actor().actorId(), now, now, 0);
            store.insert(menu);
            support.history(site, "MENU", menu.id(), null, "DRAFT", caller.actor().actorId(), null);
            support.audit(caller, AuditAction.CAT_MENU_UPDATED, "Menu", menu.id(), site, null, menu);
            return menu;
        });
    }

    public MenuItem addItem(UUID menuId, String name, Set<Allergen> allergens, boolean allergensDeclared,
            Set<DietaryTag> tags, Caller caller) {
        return inTransaction.execute(tx -> {
            Menu menu = manageable(menuId, caller);
            if (menu.status() == MenuStatus.RETIRED) {
                throw CateringSupport.invalid("A retired menu cannot be changed.");
            }
            MenuItem item = new MenuItem(UUID.randomUUID(), menuId, menu.siteCode(), CateringSupport.required(name, "name"),
                    enumSet(Allergen.class, allergens),
                    allergensDeclared, enumSet(DietaryTag.class, tags),
                    caller.actor().actorId(), support.now());
            store.insert(item);
            support.audit(caller, AuditAction.CAT_MENU_UPDATED, "Menu", menuId, menu.siteCode(), null, item);
            changed(menu, "Dish added: " + item.name(), caller);
            return item;
        });
    }

    /** Declares or corrects a dish's allergens and diets. On an approved menu this is a controlled change. */
    public MenuItem updateItem(UUID itemId, Set<Allergen> allergens, boolean allergensDeclared, Set<DietaryTag> tags,
            Caller caller) {
        return inTransaction.execute(tx -> {
            MenuItem before = store.item(itemId).orElseThrow(() -> CateringSupport.notFound("Menu item", itemId));
            Menu menu = manageable(before.menuId(), caller);
            if (menu.status() == MenuStatus.RETIRED) {
                throw CateringSupport.invalid("A retired menu cannot be changed.");
            }
            MenuItem after = new MenuItem(itemId, before.menuId(), before.siteCode(), before.name(),
                    enumSet(Allergen.class, allergens),
                    allergensDeclared, enumSet(DietaryTag.class, tags),
                    before.createdBy(), before.createdAt());
            store.updateItem(after);
            support.audit(caller, AuditAction.CAT_MENU_UPDATED, "Menu", menu.id(), menu.siteCode(), before, after);
            changed(menu, "Dish changed: " + before.name(), caller);
            return after;
        });
    }

    public Menu approveMenu(UUID menuId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Menu before = store.menu(menuId).orElseThrow(() -> CateringSupport.notFound("Menu", menuId));
            support.require(caller, SflPermission.FACILITIES_CATERING_APPROVE, before.siteCode(), "Menu", menuId.toString());
            CateringSupport.checkVersion(expectedVersion, before.version());
            if (before.status() != MenuStatus.DRAFT) {
                throw CateringSupport.invalid("Only a draft menu can be approved.");
            }
            List<MenuItem> items = store.itemsOf(menuId);
            if (items.isEmpty()) {
                throw CateringSupport.invalid("A menu with no dishes cannot be approved.");
            }
            List<String> undeclared = items.stream().filter(i -> !i.allergensDeclared()).map(MenuItem::name).toList();
            if (!undeclared.isEmpty()) {
                throw CateringSupport.invalid("Declare the allergens of every dish before approval. Missing: "
                        + String.join(", ", undeclared) + ".");
            }
            Instant now = support.now();
            Menu after = new Menu(menuId, before.siteCode(), before.code(), before.name(), before.description(),
                    MenuStatus.APPROVED, caller.actor().actorId(), now, before.createdBy(), before.createdAt(), now,
                    before.version());
            if (!store.update(after, before.version())) {
                throw CateringSupport.conflict();
            }
            Menu saved = store.menu(menuId).orElseThrow();
            support.history(saved.siteCode(), "MENU", menuId, "DRAFT", "APPROVED", caller.actor().actorId(), null);
            support.audit(caller, AuditAction.CAT_MENU_APPROVED, "Menu", menuId, saved.siteCode(), before, saved);
            return saved;
        });
    }

    public Menu retireMenu(UUID menuId, Long expectedVersion, Caller caller) {
        return inTransaction.execute(tx -> {
            Menu before = manageable(menuId, caller);
            CateringSupport.checkVersion(expectedVersion, before.version());
            Menu after = new Menu(menuId, before.siteCode(), before.code(), before.name(), before.description(),
                    MenuStatus.RETIRED, before.approvedBy(), before.approvedAt(), before.createdBy(), before.createdAt(),
                    support.now(), before.version());
            if (!store.update(after, before.version())) {
                throw CateringSupport.conflict();
            }
            support.history(before.siteCode(), "MENU", menuId, before.status().name(), "RETIRED",
                    caller.actor().actorId(), null);
            return store.menu(menuId).orElseThrow();
        });
    }

    // ---- internals

    private Menu manageable(UUID id, Caller caller) {
        Menu menu = store.menu(id).orElseThrow(() -> CateringSupport.notFound("Menu", id));
        support.require(caller, SflPermission.FACILITIES_CATERING_MANAGE, menu.siteCode(), "Menu", id.toString());
        return menu;
    }

    /**
     * A menu changed. If it was approved it returns to DRAFT, and each service still being planned on it goes back
     * to PENDING_APPROVAL - its approval, and any confirmation, no longer describes what will be served.
     */
    private void changed(Menu menu, String reason, Caller caller) {
        if (menu.status() != MenuStatus.APPROVED) {
            return;
        }
        Menu reopened = new Menu(menu.id(), menu.siteCode(), menu.code(), menu.name(), menu.description(),
                MenuStatus.DRAFT, null, null, menu.createdBy(), menu.createdAt(), support.now(), menu.version());
        if (!store.update(reopened, menu.version())) {
            throw CateringSupport.conflict();
        }
        support.history(menu.siteCode(), "MENU", menu.id(), "APPROVED", "DRAFT", caller.actor().actorId(), reason);
        for (CateringService service : store.planningServicesOfMenu(menu.id())) {
            CateringService back = CateringServiceService.reopened(service, support.now());
            if (store.update(back, service.version())) {
                support.history(service.siteCode(), "SERVICE", service.id(), service.status().name(),
                        ServiceStatus.PENDING_APPROVAL.name(), caller.actor().actorId(), "The menu changed: " + reason);
            }
        }
    }

    private static <E extends Enum<E>> Set<E> enumSet(Class<E> type, Set<E> values) {
        Set<E> out = EnumSet.noneOf(type);
        if (values != null) {
            out.addAll(values);
        }
        return out;
    }
}

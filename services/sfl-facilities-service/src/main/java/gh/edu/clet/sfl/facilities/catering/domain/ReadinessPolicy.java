package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a service may be approved or confirmed - the validation rule "a service cannot be confirmed where the
 * venue, supplier or food-safety control is not valid", and the acceptance criterion that an overdue supplier
 * check means the service "cannot silently appear compliant".
 *
 * <p>Every blocker is returned, not the first, each saying whether an approver may accept it as an exception.
 * Capacity, an expired certificate and an overdue supplier check can be accepted with a reason; an allergen need
 * with nothing safe to serve, an unapproved menu, a suspended supplier and an inactive venue cannot.
 */
public final class ReadinessPolicy {

    /** A supplier check older than this is overdue. */
    public static final int SUPPLIER_CHECK_VALID_DAYS = 30;

    private ReadinessPolicy() {
    }

    public record Blocker(String code, String message, boolean exceptionable) {
    }

    public static List<Blocker> blockers(CateringService service, Venue venue, Supplier supplier, Menu menu,
            List<MenuItem> items, List<AllergenPolicy.Finding> findings, boolean supplierCheckCurrent) {
        List<Blocker> blockers = new ArrayList<>();
        if (!venue.active()) {
            blockers.add(new Blocker("VENUE_INACTIVE", "Venue " + venue.code() + " is not in use.", false));
        }
        if (service.expectedGuests() > venue.capacity() && service.capacityApprovedBy() == null) {
            blockers.add(new Blocker("CAPACITY_EXCEEDED", service.expectedGuests() + " guests exceed the capacity of "
                    + venue.capacity() + " at " + venue.code() + "; an approver must accept it.", true));
        }
        if (menu.status() != MenuStatus.APPROVED) {
            blockers.add(new Blocker("MENU_NOT_APPROVED", "Menu " + menu.code() + " has not been approved.", false));
        }
        if (items.isEmpty() || items.stream().anyMatch(i -> !i.allergensDeclared())) {
            blockers.add(new Blocker("MENU_ALLERGENS_UNDECLARED",
                    "Every dish needs its allergens declared before the menu can be served.", false));
        }
        findings.stream().filter(AllergenPolicy.Finding::blocked).forEach(f -> blockers.add(new Blocker(
                "ALLERGEN_UNRESOLVED", f.message(), false)));
        if (supplier.status() != SupplierStatus.APPROVED) {
            blockers.add(new Blocker("SUPPLIER_SUSPENDED", "Supplier " + supplier.code() + " is suspended.", false));
        }
        boolean supplierException = service.supplierApprovedBy() != null;
        LocalDate day = service.serviceDate();
        if (supplier.certificateExpiresOn().isBefore(day) && !supplierException) {
            blockers.add(new Blocker("SUPPLIER_CERTIFICATE_EXPIRED", "Supplier " + supplier.code()
                    + "'s certificate expires on " + supplier.certificateExpiresOn() + ", before the service.", true));
        }
        if (!supplierCheckCurrent && !supplierException) {
            blockers.add(new Blocker("SUPPLIER_CHECK_OVERDUE", "Supplier " + supplier.code()
                    + " has no passing check in the last " + SUPPLIER_CHECK_VALID_DAYS + " days.", true));
        }
        return blockers;
    }
}

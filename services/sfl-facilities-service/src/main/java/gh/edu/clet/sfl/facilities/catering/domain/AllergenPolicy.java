package gh.edu.clet.sfl.facilities.catering.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches recorded dietary needs against a menu - the acceptance criterion "when a menu is approved, incompatible
 * items are flagged before confirmation".
 *
 * <p>Two things come out per need. The <strong>flagged</strong> items are the dishes containing the allergen, shown
 * so the coordinator sees exactly what to avoid. The need is <strong>blocked</strong> when nothing on the menu is
 * safe for that person and no substitution or waiver has been approved - the "request blocked or requires approved
 * substitution" error state. An item whose allergens nobody has declared is never counted as safe: not knowing is
 * not the same as being free of it.
 */
public final class AllergenPolicy {

    private AllergenPolicy() {
    }

    public record Finding(DietaryRequest request, List<MenuItem> flagged, List<MenuItem> safe, boolean blocked,
            String message) {
    }

    public static List<Finding> assess(List<DietaryRequest> requests, List<MenuItem> items) {
        List<Finding> findings = new ArrayList<>();
        for (DietaryRequest request : requests) {
            boolean open = request.status() == NeedStatus.OPEN;
            if (request.needType() == NeedType.ALLERGY) {
                Allergen allergen = Allergen.valueOf(request.needCode());
                List<MenuItem> flagged = items.stream().filter(i -> i.contains(allergen)).toList();
                List<MenuItem> safe = items.stream().filter(i -> i.allergensDeclared() && !i.contains(allergen)).toList();
                boolean blocked = open && safe.isEmpty();
                findings.add(new Finding(request, flagged, safe, blocked, blocked
                        ? "Nothing on the menu is safe for a " + allergen + " allergy; approve a substitution first." : null));
            } else {
                DietaryTag tag = DietaryTag.valueOf(request.needCode());
                List<MenuItem> safe = items.stream().filter(i -> i.dietaryTags().contains(tag)).toList();
                boolean blocked = open && safe.isEmpty();
                findings.add(new Finding(request, List.of(), safe, blocked, blocked
                        ? "Nothing on the menu is marked " + tag + "; approve a substitution first." : null));
            }
        }
        return findings;
    }

    /** Whether a proposed substitute really is safe for the need: declared, and free of the allergen or carrying the tag. */
    public static boolean suitable(DietaryRequest request, MenuItem item) {
        if (request.needType() == NeedType.ALLERGY) {
            return item.allergensDeclared() && !item.contains(Allergen.valueOf(request.needCode()));
        }
        return item.dietaryTags().contains(DietaryTag.valueOf(request.needCode()));
    }
}

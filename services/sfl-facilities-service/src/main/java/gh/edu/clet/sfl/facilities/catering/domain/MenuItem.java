package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A dish, with the allergens it contains and the diets it suits. {@code allergensDeclared} is false until someone has said so, even if the set is empty. */
public record MenuItem(UUID id, UUID menuId, String siteCode, String name, Set<Allergen> allergens,
        boolean allergensDeclared, Set<DietaryTag> dietaryTags, String createdBy, Instant createdAt) {

    public boolean contains(Allergen allergen) {
        return allergens.contains(allergen);
    }
}

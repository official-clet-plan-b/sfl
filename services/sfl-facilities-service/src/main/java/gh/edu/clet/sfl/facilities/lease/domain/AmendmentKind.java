package gh.edu.clet.sfl.facilities.lease.domain;

import java.util.Set;

/**
 * What an amendment changes. Every one moves money, time or the agreement's life, so every one is material and
 * goes to an approver; changes of owner or of linked references are not amendments and have their own path.
 */
public enum AmendmentKind {
    RENT_CHANGE(true, Set.of("annualRent", "depositAmount")),
    TERM_CHANGE(true, Set.of("endDate", "noticeDays", "rentReviewDate", "renewalTermMonths")),
    RENEWAL(true, Set.of("endDate")),
    TERMINATION(true, Set.of("*"));

    private final boolean material;
    private final Set<String> fields;

    AmendmentKind(boolean material, Set<String> fields) {
        this.material = material;
        this.fields = fields;
    }

    public boolean material() {
        return material;
    }

    public Set<String> fields() {
        return fields;
    }
}

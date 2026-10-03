package gh.edu.clet.sfl.facilities.lostfound.domain;

/**
 * The controlled category of a found item. Category decides two things the caller does not get to choose:
 * whether the item's detail is private (it can identify its owner - a passport, a phone, a prescription) and
 * whether it must be kept in secure storage.
 */
public enum ItemCategory {
    DOCUMENT(true, true),
    ELECTRONICS(true, true),
    JEWELLERY(false, true),
    CASH_VALUABLES(false, true),
    CLOTHING(false, false),
    BAG(true, false),
    KEYS(false, false),
    MEDICAL(true, true),
    OTHER(false, false);

    private final boolean identifying;
    private final boolean secureStorage;

    ItemCategory(boolean identifying, boolean secureStorage) {
        this.identifying = identifying;
        this.secureStorage = secureStorage;
    }

    /** Its description can identify the owner, so a bare category and a controlled summary are all the public sees. */
    public boolean identifying() {
        return identifying;
    }

    public boolean requiresSecureStorage() {
        return secureStorage;
    }
}

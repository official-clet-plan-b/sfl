package gh.edu.clet.sfl.facilities.waste.domain;

public enum ExceptionType {
    MISSED_COLLECTION,
    CONTAMINATION,
    MISSING_CERTIFICATE,
    MISSING_RECEIVING_EVIDENCE,
    SPILL,
    UNAPPROVED_CARRIER,
    UNAPPROVED_DESTINATION;

    /** Days the owner has to correct it. A spill is the most urgent; a missing certificate can wait for the paperwork. */
    public int dueInDays() {
        return switch (this) {
            case SPILL -> 1;
            case UNAPPROVED_CARRIER, UNAPPROVED_DESTINATION, MISSING_RECEIVING_EVIDENCE -> 2;
            case MISSED_COLLECTION, CONTAMINATION -> 3;
            case MISSING_CERTIFICATE -> 7;
        };
    }

    /** A spill or injury is also a safety incident for S163. */
    public boolean needsIncident() {
        return this == SPILL;
    }
}

package gh.edu.clet.sfl.facilities.catering.domain;

/** What the service is for. Anything but ROUTINE must name the event, booking or examination it serves. */
public enum ContextType {
    EVENT, BOOKING, EXAMINATION, ROUTINE;

    public boolean needsReference() {
        return this != ROUTINE;
    }
}

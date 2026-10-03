package gh.edu.clet.sfl.facilities.catering.domain;

public enum ExceptionType {
    SHORTAGE, SUBSTITUTION, FOOD_SAFETY_INCIDENT, SERVICE_EXCEPTION;

    /** A food-safety event is also an incident for S163. */
    public boolean needsIncident() {
        return this == FOOD_SAFETY_INCIDENT;
    }
}

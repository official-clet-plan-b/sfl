package gh.edu.clet.sfl.facilities.hygiene.domain;

/**
 * Whether a cross-system link is made. {@code PENDING_MANUAL} is the honest state for a link the platform
 * could not make itself - an incident in a different deployable, or a work order the intake refused - so
 * a screen never shows "linked" for something that is not.
 */
public enum LinkState {
    NOT_REQUIRED,
    PENDING_MANUAL,
    LINKED,
    RAISED
}

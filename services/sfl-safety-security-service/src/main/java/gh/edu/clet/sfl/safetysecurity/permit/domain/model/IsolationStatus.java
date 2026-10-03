package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

/** Required, then verified before approval, then removed at close-out - three acts, not one flag. */
public enum IsolationStatus {
    REQUIRED, VERIFIED, REMOVED
}

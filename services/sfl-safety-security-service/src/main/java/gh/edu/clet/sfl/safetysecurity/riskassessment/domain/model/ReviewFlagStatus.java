package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

/**
 * SRS-SFL-S165-04. There is no DISMISSED: "the system does not offer a plain dismiss". A flag leaves
 * {@link #OPEN} only by being {@link #DEFERRED} (named reason and date, and it reopens when the date
 * passes) or {@link #CLEARED} by a completed review with recorded findings.
 */
public enum ReviewFlagStatus {
    OPEN,
    DEFERRED,
    CLEARED
}

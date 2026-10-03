package gh.edu.clet.sfl.facilities.lease.domain;

/** Why corrective work was raised against an agreement. */
public enum WorkOrderTrigger {
    /** The agreement lapsed: whatever service or access relied on it needs reviewing. */
    EXPIRED_REVIEW,
    /** Someone raised it by hand from an alert or an overdue obligation. */
    MANUAL
}

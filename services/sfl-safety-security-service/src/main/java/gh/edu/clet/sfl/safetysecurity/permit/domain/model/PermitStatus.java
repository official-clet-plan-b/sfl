package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

/**
 * Where a permit stands. Work may proceed only while ACTIVE; a suspended permit does not become ACTIVE again by an
 * un-suspend but through a fresh round of approval (RESUMPTION_PENDING), per SRS-SFL-S164-03.
 */
public enum PermitStatus {
    DRAFT, SUBMITTED, ISOLATION_VERIFIED, STAGE1_APPROVED, ACTIVE, SUSPENDED, RESUMPTION_PENDING, WORK_COMPLETE, CLOSED,
    REJECTED, CANCELLED;

    /** Issued and not yet closed: counts as an open permit on the dashboard. */
    public boolean open() {
        return this == ACTIVE || this == SUSPENDED || this == RESUMPTION_PENDING || this == WORK_COMPLETE;
    }

    /** Before the permit is issued: it is still being put together and approved. */
    public boolean preIssue() {
        return this == DRAFT || this == SUBMITTED || this == ISOLATION_VERIFIED || this == STAGE1_APPROVED;
    }

    public boolean terminal() {
        return this == CLOSED || this == REJECTED || this == CANCELLED;
    }
}

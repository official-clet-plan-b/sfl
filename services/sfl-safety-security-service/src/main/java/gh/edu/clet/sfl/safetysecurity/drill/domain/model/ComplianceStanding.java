package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

/** Where a site stands against one frequency requirement - computed, never stored. */
public enum ComplianceStanding {
    COMPLIANT,
    /** Within the warning window of the due date - S175-04's "approaching ... its required interval". */
    DUE_SOON,
    /** SRS-SFL-S175-04 error state "Compliance Gap": past the interval with no completed, reviewed drill. */
    COMPLIANCE_GAP
}

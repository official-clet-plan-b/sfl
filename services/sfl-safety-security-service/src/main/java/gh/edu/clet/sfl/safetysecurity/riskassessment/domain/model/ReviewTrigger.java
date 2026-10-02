package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

/**
 * What raised an out-of-cycle review - SRS-SFL-S165-04. Only S163 incidents exist today; an incident
 * against an S164 permit will arrive as the same trigger once S164 is built, carrying the permit as the
 * route to the assessment.
 */
public enum ReviewTrigger {
    INCIDENT
}

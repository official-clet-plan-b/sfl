package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

/**
 * The likelihood axis of a {@link RiskScore} - the same five steps S163's incident rating uses, so an HSE
 * officer reads one scale across incidents and assessments. Own copy, per the module convention.
 */
public enum Likelihood {
    RARE,
    UNLIKELY,
    POSSIBLE,
    LIKELY,
    ALMOST_CERTAIN
}

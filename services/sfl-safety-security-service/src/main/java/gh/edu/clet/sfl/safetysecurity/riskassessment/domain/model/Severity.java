package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

/**
 * The severity axis of a {@link RiskScore}. SRS-SFL-S165-01 says "likelihood/severity rating"; the steps
 * match S163's {@code Impact} axis so the two modules band a hazard identically.
 */
public enum Severity {
    NEGLIGIBLE,
    MINOR,
    MODERATE,
    MAJOR,
    CATASTROPHIC
}

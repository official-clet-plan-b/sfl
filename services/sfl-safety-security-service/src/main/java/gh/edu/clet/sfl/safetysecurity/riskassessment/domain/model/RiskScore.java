package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import java.util.Objects;

/**
 * A likelihood x severity rating, banded into the shared {@link RiskLevel} - SRS-SFL-S165-01.
 *
 * <h2>The bands are S163's provisional matrix, deliberately</h2>
 *
 * <p>Score 1-25 as the product of each axis's 1-based step, banded at 4 / 9 / 15 - the same placeholder
 * {@code RiskRating} in the incident module uses pending S163's open question Q-163-1 (CLET has not yet
 * supplied its own matrix). Using the same thresholds means an incident and the assessment it is linked
 * to cannot disagree about what "high" means. When CLET's matrix arrives, both change together.
 *
 * <p>The banded output is the shared {@link RiskLevel} rather than a module enum because that is what
 * the currency rule, the review interval and every consumer (S164, S173, S176) key on.
 */
public record RiskScore(Likelihood likelihood, Severity severity) {

    public RiskScore {
        Objects.requireNonNull(likelihood, "likelihood is required");
        Objects.requireNonNull(severity, "severity is required");
    }

    /** 1-25, the product of each axis's 1-based ordinal. */
    public int score() {
        return (likelihood.ordinal() + 1) * (severity.ordinal() + 1);
    }

    public RiskLevel level() {
        int score = score();
        if (score <= 4) {
            return RiskLevel.LOW;
        }
        if (score <= 9) {
            return RiskLevel.MEDIUM;
        }
        if (score <= 15) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.CRITICAL;
    }
}

package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import java.util.Objects;

/**
 * How often an assessment at one risk level is reviewed, and how far ahead the reminder goes -
 * SRS-SFL-S165-02: "a review interval driven by its risk level (shorter for higher risk)".
 *
 * <p>Runtime configuration rather than constants (PLAT-05, config without code): HSE sets the cycle,
 * and changing it is audited. A change applies to the next publish or sign-off; an assessment already
 * due on a date keeps that date, because the date it was signed off against is the one that was agreed.
 */
public record ReviewInterval(RiskLevel riskLevel, int intervalDays, int reminderLeadDays, RecordMetadata metadata) {

    public ReviewInterval {
        Objects.requireNonNull(riskLevel, "riskLevel is required");
        if (intervalDays < 1) {
            throw new IllegalArgumentException("A review interval must be at least one day");
        }
        if (reminderLeadDays < 0 || reminderLeadDays >= intervalDays) {
            throw new IllegalArgumentException("The reminder must fall inside the review interval");
        }
        Objects.requireNonNull(metadata, "metadata is required");
    }
}

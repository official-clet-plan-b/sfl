package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A kind of high-risk work and what authorising it takes (S164-01: "permit types are configurable, each with its own
 * required approvals and validity rules").
 *
 * @param activityType the S165 activity type an assessment must cover, or null for any
 * @param requiredCompetencies the competences every worker named on the permit must be checked for
 */
public record PermitType(UUID id, String code, String name, String description, PermitRiskLevel riskLevel,
        String activityType, boolean riskAssessmentRequired, boolean twoStage, boolean requiresIsolation,
        int maxValidityHours, List<String> requiredCompetencies, boolean active, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {

    public PermitType {
        requiredCompetencies = requiredCompetencies == null ? List.of() : List.copyOf(requiredCompetencies);
    }

    public Duration maxValidity() {
        return Duration.ofHours(maxValidityHours);
    }
}

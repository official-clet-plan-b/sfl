package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * One identified hazard, its rating before and after controls, and the controls themselves -
 * SRS-SFL-S165-01: "hazards, likelihood/severity rating, control measures and residual risk".
 *
 * <p>The residual rating is what the hazard contributes to the assessment's risk level, because that is
 * the risk anyone relying on the assessment is actually accepting.
 *
 * @param whoAtRisk optional - who could be harmed (workers, students, visitors, the public)
 */
public record Hazard(HazardType hazardType, String description, String whoAtRisk, RiskScore inherentRisk,
        RiskScore residualRisk, List<ControlMeasure> controls) {

    public Hazard {
        Objects.requireNonNull(hazardType, "hazardType is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A hazard needs a description");
        }
        description = description.strip();
        whoAtRisk = whoAtRisk == null || whoAtRisk.isBlank() ? null : whoAtRisk.strip();
        Objects.requireNonNull(inherentRisk, "inherentRisk is required");
        Objects.requireNonNull(residualRisk, "residualRisk is required");
        controls = controls == null ? List.of() : List.copyOf(controls);
    }

    public boolean hasControl() {
        return !controls.isEmpty();
    }
}

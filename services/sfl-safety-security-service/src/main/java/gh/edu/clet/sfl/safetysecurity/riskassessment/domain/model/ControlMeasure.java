package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import java.util.Objects;

/** One control measure against one hazard - SRS-SFL-S165-01. */
public record ControlMeasure(ControlType controlType, String description) {

    public ControlMeasure {
        Objects.requireNonNull(controlType, "controlType is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A control measure needs a description");
        }
        description = description.strip();
    }
}

package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A generic hazard/control template an assessment is authored against - SRS-SFL-S165-01's user story
 * ("author a risk assessment against a hazard/control-measure template") and the mapping's "template
 * library".
 *
 * <p>Not site-scoped: a generic assessment of hot work is the same starting point at every centre, and the
 * site-specific part is what the author changes on the copy. A template is a starting point only - an
 * assessment copies its hazards once, at creation, so editing or retiring a template never reaches an
 * assessment that already exists.
 */
public record AssessmentTemplate(UUID id, String name, String activityType, String description, boolean active,
        List<Hazard> hazards, RecordMetadata metadata) {

    public AssessmentTemplate {
        Objects.requireNonNull(id, "id is required");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A template needs a name");
        }
        name = name.strip();
        activityType = ActivityTypes.normalise(activityType);
        description = description == null || description.isBlank() ? null : description.strip();
        hazards = hazards == null ? List.of() : List.copyOf(hazards);
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public AssessmentTemplate revise(String newName, String newActivityType, String newDescription, boolean newActive,
            List<Hazard> newHazards, RecordMetadata touched) {
        return new AssessmentTemplate(id, newName, newActivityType, newDescription, newActive, newHazards, touched);
    }
}

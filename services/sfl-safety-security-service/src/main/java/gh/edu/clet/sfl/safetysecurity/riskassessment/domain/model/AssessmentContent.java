package gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import java.util.Comparator;
import java.util.List;

/**
 * The editable body of one assessment version: what is drafted, revised and published.
 *
 * <p>The risk level is computed, never supplied: the highest residual rating among the hazards. An
 * assessment with no hazards yet has no level - and cannot be published (see {@code PublishPolicy}).
 */
public record AssessmentContent(String title, String summary, List<Hazard> hazards) {

    public AssessmentContent {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("An assessment needs a title");
        }
        title = title.strip();
        summary = summary == null || summary.isBlank() ? null : summary.strip();
        hazards = hazards == null ? List.of() : List.copyOf(hazards);
    }

    /** The highest residual level among the hazards, or null while there are none. */
    public RiskLevel riskLevel() {
        return hazards.stream().map(h -> h.residualRisk().level()).max(Comparator.naturalOrder()).orElse(null);
    }

    /** The highest residual score, for ordering within a band. Zero while there are no hazards. */
    public int residualScore() {
        return hazards.stream().mapToInt(h -> h.residualRisk().score()).max().orElse(0);
    }
}

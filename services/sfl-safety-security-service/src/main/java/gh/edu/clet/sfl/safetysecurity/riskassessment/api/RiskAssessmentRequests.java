package gh.edu.clet.sfl.safetysecurity.riskassessment.api;

import gh.edu.clet.sfl.common.hse.RiskAssessmentCurrency.RiskLevel;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.AssessmentContent;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlMeasure;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.ControlType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Hazard;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.HazardType;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Likelihood;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.RiskScore;
import gh.edu.clet.sfl.safetysecurity.riskassessment.domain.model.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** S165 request bodies. Bean Validation catches shape; the domain enforces the SRS's rules. */
public final class RiskAssessmentRequests {

    private RiskAssessmentRequests() {
    }

    public record ControlRequest(@NotNull ControlType controlType, @NotBlank @Size(max = 1000) String description) {

        ControlMeasure toDomain() {
            return new ControlMeasure(controlType, description);
        }
    }

    /**
     * A hazard as the author rates it. Controls may be empty in a draft - S165-01 refuses that only at
     * publish, naming every uncontrolled hazard.
     */
    public record HazardRequest(@NotNull HazardType hazardType, @NotBlank @Size(max = 1000) String description,
            @Size(max = 300) String whoAtRisk, @NotNull Likelihood inherentLikelihood,
            @NotNull Severity inherentSeverity, @NotNull Likelihood residualLikelihood,
            @NotNull Severity residualSeverity, List<@Valid ControlRequest> controls) {

        Hazard toDomain() {
            return new Hazard(hazardType, description, whoAtRisk, new RiskScore(inherentLikelihood, inherentSeverity),
                    new RiskScore(residualLikelihood, residualSeverity),
                    controls == null ? List.of() : controls.stream().map(ControlRequest::toDomain).toList());
        }
    }

    public static List<Hazard> hazards(List<HazardRequest> hazards) {
        return hazards == null ? List.of() : hazards.stream().map(HazardRequest::toDomain).toList();
    }

    /**
     * @param templateId optional; its hazards are copied when {@code hazards} is empty, and its name and
     *        activity type fill a blank title or activity type
     */
    public record CreateRequest(@NotBlank String siteCode, @Size(max = 80) String activityType,
            @Size(max = 80) String locationCode, @Size(max = 200) String title, @Size(max = 4000) String summary,
            List<@Valid HazardRequest> hazards, UUID templateId) {
    }

    /** The whole draft body - a draft edit replaces it. */
    public record DraftRequest(@NotBlank @Size(max = 200) String title, @Size(max = 4000) String summary,
            List<@Valid HazardRequest> hazards, Long expectedVersion) {

        AssessmentContent toContent() {
            return new AssessmentContent(title, summary, RiskAssessmentRequests.hazards(hazards));
        }
    }

    public record VersionedRequest(Long expectedVersion) {
    }

    public record SignOffRequest(@Size(max = 2000) String notes, Long expectedVersion) {
    }

    /**
     * S165-04: a deferral names its reason and the date the flag comes back. Deliberately not
     * {@code @NotBlank}/{@code @NotNull}: a missing reason or date is the domain's refusal, in the SRS's own
     * words, not Bean Validation's generic one.
     */
    public record DeferRequest(@Size(max = 1000) String reason, LocalDate until, Long expectedVersion) {
    }

    /**
     * S165-04: a flag clears only with recorded findings. Not {@code @NotBlank}, for the same reason - blank
     * findings must come back as "Flag Dismissed Without Review", which is what the reviewer needs to read.
     */
    public record CompleteReviewRequest(@Size(max = 4000) String findings, Long expectedVersion) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 200) String name, @Size(max = 80) String activityType,
            @Size(max = 2000) String description, Boolean active, List<@Valid HazardRequest> hazards,
            Long expectedVersion) {
    }

    public record IntervalRequest(@NotNull RiskLevel riskLevel, @Positive int intervalDays,
            @PositiveOrZero int reminderLeadDays) {
    }

    public record IntervalsRequest(@NotEmpty List<@Valid IntervalRequest> intervals) {
    }
}

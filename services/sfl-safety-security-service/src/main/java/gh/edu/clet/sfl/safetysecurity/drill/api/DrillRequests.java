package gh.edu.clet.sfl.safetysecurity.drill.api;

import gh.edu.clet.sfl.safetysecurity.drill.application.service.DrillReviewService.ExpectationJudgement;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillModule;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillPlan;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.DrillType;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ExpectationOutcome;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.GapFollowUp;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.ModuleExpectation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * S175 request bodies. Bean Validation catches shape; the domain enforces the SRS's rules - a missing reason or
 * deferral comes back in the SRS's own words, not Bean Validation's.
 */
public final class DrillRequests {

    private DrillRequests() {
    }

    public record ExpectationRequest(@NotNull DrillModule module, @NotBlank @Size(max = 1000) String expectation,
            @Size(max = 1000) String successCriterion) {
    }

    /** The plan as written; a draft may leave the scheduling fields empty until it is scheduled. */
    public record PlanRequest(@NotNull DrillType drillType, @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String scenario, @Size(max = 1000) String expectedParticipants,
            @Size(max = 80) String assemblyZone, Instant scheduledFor, UUID notificationTemplateId,
            List<UUID> audienceGroupIds, List<UUID> recipientZoneIds, List<@Valid ExpectationRequest> expectations) {

        DrillPlan toPlan() {
            return new DrillPlan(drillType, title, scenario, expectedParticipants, assemblyZone, scheduledFor,
                    notificationTemplateId, audienceGroupIds, recipientZoneIds,
                    expectations == null ? List.of() : expectations.stream()
                            .map(e -> ModuleExpectation.planned(e.module(), e.expectation(), e.successCriterion()))
                            .toList());
        }
    }

    public record CreateRequest(@NotBlank String siteCode, @NotNull @Valid PlanRequest plan) {
    }

    public record ReviseRequest(@NotNull @Valid PlanRequest plan, Long expectedVersion) {
    }

    public record VersionedRequest(Long expectedVersion) {
    }

    /** @param scheduledFor null keeps the plan's date */
    public record ScheduleRequest(Instant scheduledFor, Long expectedVersion) {
    }

    public record ReasonRequest(@Size(max = 1000) String reason, Long expectedVersion) {
    }

    public record CheckInRequest(@NotBlank @Size(max = 160) String personRef) {
    }

    public record GapFollowUpRequest(@NotNull GapFollowUp followUp, @Size(max = 2000) String notes) {
    }

    public record ReviewRequest(@Size(max = 4000) String summary, @Size(max = 2000) String timingNotes) {
    }

    public record FindingRequest(@NotBlank @Size(max = 2000) String description) {
    }

    public record NoActionRequest(@Size(max = 2000) String justification) {
    }

    public record JudgementRequest(@PositiveOrZero int index, @NotNull ExpectationOutcome outcome,
            @Size(max = 2000) String notes) {

        ExpectationJudgement toJudgement() {
            return new ExpectationJudgement(index, outcome, notes);
        }
    }

    public record JudgementsRequest(List<@Valid JudgementRequest> judgements, Long expectedVersion) {
    }

    /** @param deferralReason needed only when overdue corrective actions remain open */
    public record CloseRequest(@Size(max = 1000) String deferralReason, Long expectedVersion) {
    }

    public record CorrectiveActionRequest(@NotNull UUID findingId, @NotBlank @Size(max = 2000) String description,
            @NotBlank @Size(max = 160) String ownerId, LocalDate dueDate) {
    }

    public record NotesRequest(@Size(max = 2000) String notes) {
    }

    public record RequirementRequest(@NotBlank String siteCode, @NotNull DrillType drillType,
            @Positive int intervalDays, @PositiveOrZero int warningDays, Instant effectiveFrom) {
    }
}

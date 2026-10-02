package gh.edu.clet.sfl.facilities.eventlogistics.api;

import gh.edu.clet.sfl.facilities.eventlogistics.domain.DeliveryOutcome;
import gh.edu.clet.sfl.facilities.eventlogistics.domain.EventResourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The S173 request bodies, with Bean Validation on every field the SRS constrains.
 *
 * <p>{@link Handoff} is not a request body: the hand-off endpoint reads the raw signed envelope, not a
 * validated DTO - see {@link EventLogisticsController}.
 */
public final class EventLogisticsRequests {

    private EventLogisticsRequests() {
    }

    public record Decompose(@NotNull List<@Valid NewRequest> requests, boolean applyTemplate) {
    }

    public record NewRequest(
            @NotNull EventResourceType resourceType,
            @NotBlank @Size(max = 1000) String description,
            @Min(1) Integer quantity,
            UUID bookableResourceId,
            Instant neededFrom,
            Instant neededTo) {
    }

    public record AcceptManualCoordination(@NotBlank @Size(max = 300) String arrangedWith) {
    }

    public record CancelResourceRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record LinkRiskAssessment(@NotBlank @Size(max = 120) String assessmentId, Integer version) {
    }

    public record CompleteSetupTask(@Size(max = 2000) String notes) {
    }

    public record Reconcile(@NotEmpty List<@Valid ReconciliationLine> lines) {
    }

    public record ReconciliationLine(@NotNull UUID resourceRequestId, @NotNull DeliveryOutcome outcome,
            @Min(0) Integer deliveredQuantity, @Size(max = 2000) String notes) {
    }

    public record ConfigureRiskCriteria(@Min(0) Integer attendanceThreshold, Boolean externalContractorsAreHigherRisk,
            Boolean temporaryStructuresAreHigherRisk, Set<String> higherRiskCategories) {
    }
}

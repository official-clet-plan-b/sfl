package gh.edu.clet.sfl.safetysecurity.permit.api;

import gh.edu.clet.sfl.safetysecurity.permit.domain.model.EvidenceKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.IsolationKind;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.OriginSystem;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.PermitRiskLevel;
import gh.edu.clet.sfl.safetysecurity.permit.domain.model.WorkRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies for the S164 API. Free text is bounded so a request cannot carry an unbounded document into the record. */
public final class PermitRequests {

    private PermitRequests() {
    }

    public record WorkerBody(@NotBlank @Size(max = 160) String personReference, @NotBlank @Size(max = 200) String displayName, WorkRole workRole) {
    }

    public record IsolationBody(IsolationKind kind, @NotBlank @Size(max = 1000) String description, @Size(max = 160) String tagReference) {
    }

    public record PermitBody(@NotNull UUID permitTypeId, @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 4000) String workDescription,
            @NotBlank @Size(max = 120) String locationCode, UUID zoneId, @NotNull Instant startsAt, @NotNull Instant endsAt, UUID riskAssessmentId,
            @Size(max = 160) String contractorReference, @NotBlank @Size(max = 160) String supervisorReference, @Size(max = 200) String supervisorContact,
            OriginSystem originSystem, @Size(max = 160) String originReference) {
    }

    public record CreateBody(@NotBlank @Size(max = 80) String siteCode, @Valid @NotNull PermitBody permit, @Valid List<WorkerBody> workers,
            @Valid List<IsolationBody> isolations) {
    }

    public record ReviseBody(@Valid @NotNull PermitBody permit, Long version) {
    }

    public record VersionBody(Long version) {
    }

    public record ReasonBody(@NotBlank @Size(max = 2000) String reason, Long version) {
    }

    public record NoteBody(@Size(max = 2000) String note, Long version) {
    }

    public record VerifyIsolationBody(@Size(max = 160) String tagReference, @Size(max = 1000) String note) {
    }

    public record CompetencyBody(@NotBlank @Size(max = 80) String competencyCode, boolean competent, @Size(max = 240) String evidenceReference,
            LocalDate validUntil, @Size(max = 1000) String note) {
    }

    public record ApproveBody(@Size(max = 2000) String conditions, @Size(max = 2000) String comment, Long version) {
    }

    public record ExtensionBody(@NotNull Instant newEndsAt, @NotBlank @Size(max = 2000) String reason, Long version) {
    }

    public record DecisionBody(boolean approve, @Size(max = 2000) String note, Long version) {
    }

    public record EvidenceBody(EvidenceKind kind, @NotBlank @Size(max = 240) String reference, @NotBlank @Size(max = 240) String fileName,
            @NotBlank @Size(max = 120) String mediaType, @Positive long sizeBytes, @NotBlank @Size(max = 128) String contentHash) {
    }

    public record CompletionBody(@NotBlank @Size(max = 4000) String statement, Long version) {
    }

    public record IncidentLinkBody(@NotBlank @Size(max = 80) String incidentReference, @Size(max = 1000) String detail) {
    }

    public record TypeBody(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
            @NotNull PermitRiskLevel riskLevel, @Size(max = 80) String activityType, boolean riskAssessmentRequired, boolean twoStage, boolean requiresIsolation,
            int maxValidityHours, List<String> requiredCompetencies, boolean active, Long version) {
    }
}

package gh.edu.clet.sfl.facilities.hygiene.application;

import gh.edu.clet.sfl.facilities.shared.application.Caller;
import gh.edu.clet.sfl.facilities.hygiene.domain.ClosureMode;
import gh.edu.clet.sfl.facilities.hygiene.domain.ControlType;
import gh.edu.clet.sfl.facilities.hygiene.domain.Frequency;
import gh.edu.clet.sfl.facilities.hygiene.domain.RiskCategory;
import gh.edu.clet.sfl.facilities.hygiene.domain.Severity;
import java.time.LocalDate;
import java.util.UUID;

/** What each S170 operation is asked to do. The actor and channel travel with every command. */
public final class HygieneCommands {

    private HygieneCommands() {
    }

    public record CreateControl(String siteCode, UUID roomId, String locationLabel, ControlType controlType,
            RiskCategory riskCategory, String title, String ownerReference, Frequency frequency, LocalDate dueOn,
            String providerReference, String notes, Caller caller) {
    }

    public record CompleteControl(UUID controlId, LocalDate completedOn, String notes, Long expectedVersion,
            Caller caller) {
    }

    public record CreateFinding(UUID controlId, UUID roomId, RiskCategory category, String title, String description,
            Severity severity, String ownerReference, LocalDate targetDate, boolean requiresIncident,
            Caller caller) {
    }

    public record UpdateFinding(UUID findingId, String title, String description, String ownerReference,
            LocalDate targetDate, Long expectedVersion, Caller caller) {
    }

    public record AddAction(UUID findingId, String description, String ownerReference, LocalDate dueOn,
            Caller caller) {
    }

    public record SubmitEvidence(UUID findingId, UUID actionId, String reference, String fileName, String mediaType,
            long sizeBytes, String contentHash, String retentionClass, String notes, Caller caller) {
    }

    public record CloseFinding(UUID findingId, ClosureMode mode, String reason, Long expectedVersion, Caller caller) {
    }
}

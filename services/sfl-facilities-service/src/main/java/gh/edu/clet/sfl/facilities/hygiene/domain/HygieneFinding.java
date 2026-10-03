package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record HygieneFinding(UUID id, String reference, UUID controlId, String siteCode, UUID roomId,
        RiskCategory category, String title, String description, Severity severity, FindingStatus status,
        String ownerReference, LocalDate targetDate, boolean requiresIncident, LinkState incidentState,
        String incidentReference, LinkState workOrderState, UUID workOrderId, String workOrderNumber,
        UUID repeatOfId, EscalationLevel escalationLevel, ClosureMode closureMode, String closureReason,
        String closureApprovedBy, Instant closedAt, String closedBy, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {

    public boolean overdue(LocalDate today) {
        return status.open() && targetDate != null && targetDate.isBefore(today);
    }
}

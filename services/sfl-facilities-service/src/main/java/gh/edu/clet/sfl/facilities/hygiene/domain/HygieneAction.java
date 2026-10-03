package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record HygieneAction(UUID id, UUID findingId, String siteCode, String description, String ownerReference,
        LocalDate dueOn, ActionStatus status, Instant completedAt, String completedBy, Instant verifiedAt,
        String verifiedBy, String rejectionReason, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {

    public boolean overdue(LocalDate today) {
        return status.open() && dueOn.isBefore(today);
    }
}

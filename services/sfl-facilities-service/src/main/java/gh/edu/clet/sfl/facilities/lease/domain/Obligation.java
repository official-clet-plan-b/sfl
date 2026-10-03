package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Obligation(UUID id, UUID agreementId, String siteCode, ObligationKind kind, String title, LocalDate dueOn,
        String ownerReference, ObligationStatus status, LocalDate completedOn, String completedBy,
        String completionNote, boolean generated, String createdBy, Instant createdAt, Instant updatedAt, long version) {

    public boolean open() {
        return status == ObligationStatus.OPEN;
    }

    public long daysTo(LocalDate today) {
        return java.time.temporal.ChronoUnit.DAYS.between(today, dueOn);
    }
}

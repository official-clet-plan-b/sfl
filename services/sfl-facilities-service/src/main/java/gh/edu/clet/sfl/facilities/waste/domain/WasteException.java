package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WasteException(UUID id, String reference, String siteCode, UUID collectionId,
        ExceptionType exceptionType, String description, String ownerReference, LocalDate dueOn,
        ExceptionStatus status, String escalatedTo, String incidentState, String incidentReference,
        String workOrderState, UUID workOrderId, String workOrderNumber, String resolution, String resolvedBy, Instant resolvedAt, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {

    public boolean overdue(LocalDate today) {
        return status != ExceptionStatus.RESOLVED && dueOn.isBefore(today);
    }
}

package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.util.UUID;

public record CateringException(UUID id, String reference, String siteCode, UUID serviceId,
        ExceptionType exceptionType, String description, String ownerReference, ExceptionStatus status,
        String incidentState, String incidentReference, String resolution, String resolvedBy, Instant resolvedAt,
        String createdBy, Instant createdAt, Instant updatedAt, long version) {
}

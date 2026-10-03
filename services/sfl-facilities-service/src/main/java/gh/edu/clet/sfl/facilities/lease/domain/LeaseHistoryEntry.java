package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.Instant;
import java.util.UUID;

public record LeaseHistoryEntry(UUID id, String siteCode, String subjectType, UUID subjectId, String fromStatus,
        String toStatus, String actor, String reason, Instant occurredAt) {
}

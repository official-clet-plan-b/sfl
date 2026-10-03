package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.Instant;
import java.util.UUID;

public record HygieneHistoryEntry(UUID id, String siteCode, String subjectType, UUID subjectId, String fromStatus,
        String toStatus, String actor, String reason, Instant occurredAt) {
}

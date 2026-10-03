package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.util.UUID;

public record LfHistoryEntry(UUID id, String siteCode, String subjectType, UUID subjectId, String fromStatus,
        String toStatus, String actor, String reason, Instant occurredAt) {
}

package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

public record HistoryEntry(UUID id, UUID permitId, String siteCode, String fromStatus, String toStatus, String action, String actor,
        String reason, Instant occurredAt) {
}

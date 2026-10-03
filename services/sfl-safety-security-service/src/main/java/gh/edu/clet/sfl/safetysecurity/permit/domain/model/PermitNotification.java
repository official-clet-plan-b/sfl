package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Who is to be told a permit is suspended. QUEUED is queued, not delivered. */
public record PermitNotification(UUID id, UUID permitId, String siteCode, UUID suspensionId, String recipientReference,
        String recipientName, String recipientRole, String state, Instant createdAt) {
}

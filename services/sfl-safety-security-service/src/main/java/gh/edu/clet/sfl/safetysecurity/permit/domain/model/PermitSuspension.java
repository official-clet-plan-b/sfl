package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PermitSuspension(UUID id, UUID permitId, String siteCode, String suspendedBy, Instant suspendedAt, String reason,
        Instant resumedAt) {
}

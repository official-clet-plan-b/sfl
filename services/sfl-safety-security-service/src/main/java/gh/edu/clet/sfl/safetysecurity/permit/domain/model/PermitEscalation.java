package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PermitEscalation(UUID id, UUID permitId, String siteCode, EscalationLevel level, Instant raisedAt) {
}

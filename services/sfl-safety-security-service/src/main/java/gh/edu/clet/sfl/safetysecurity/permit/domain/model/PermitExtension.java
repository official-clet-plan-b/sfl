package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PermitExtension(UUID id, UUID permitId, String siteCode, String requestedBy, Instant requestedAt, Instant previousEndsAt,
        Instant newEndsAt, String reason, ExtensionStatus status, String decidedBy, Instant decidedAt, String decisionNote) {
}

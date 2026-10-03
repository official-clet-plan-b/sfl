package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Close-out evidence by reference: what was filed, its hash, who filed it and how long it must be kept. */
public record PermitEvidence(UUID id, UUID permitId, String siteCode, EvidenceKind kind, String reference, String fileName,
        String mediaType, long sizeBytes, String contentHash, String retentionClass, String submittedBy, Instant submittedAt) {
}

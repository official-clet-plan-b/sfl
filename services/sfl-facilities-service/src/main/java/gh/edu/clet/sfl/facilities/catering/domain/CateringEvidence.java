package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.util.UUID;

public record CateringEvidence(UUID id, UUID serviceId, String siteCode, EvidenceKind kind, String reference,
        String fileName, String mediaType, long sizeBytes, String contentHash, String retentionClass,
        String submittedBy, Instant submittedAt) {
}

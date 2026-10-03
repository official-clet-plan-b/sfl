package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.util.UUID;

public record WasteEvidence(UUID id, UUID collectionId, String siteCode, EvidenceKind kind, String reference,
        String fileName, String mediaType, long sizeBytes, String contentHash, String retentionClass,
        EvidenceStatus status, String submittedBy, Instant submittedAt, String reviewedBy, Instant reviewedAt,
        String reviewReason) {
}

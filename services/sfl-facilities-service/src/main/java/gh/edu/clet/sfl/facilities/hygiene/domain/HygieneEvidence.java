package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.Instant;
import java.util.UUID;

/** Evidence by reference: what was filed, its hash and retention - the bytes live in the records store. */
public record HygieneEvidence(UUID id, UUID findingId, UUID actionId, String siteCode, String reference,
        String fileName, String mediaType, long sizeBytes, String contentHash, String retentionClass, String notes,
        EvidenceStatus status, String submittedBy, Instant submittedAt, String reviewedBy, Instant reviewedAt,
        String reviewReason) {
}

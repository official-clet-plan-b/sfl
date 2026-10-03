package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.util.UUID;

/** Evidence by reference - a photograph, a verification, a receipt. The file is in the records store; this proves what was filed. */
public record LfEvidence(UUID id, UUID itemId, UUID claimId, String siteCode, EvidenceKind kind, String reference,
        String fileName, String mediaType, long sizeBytes, String contentHash, String retentionClass,
        String submittedBy, Instant submittedAt) {
}

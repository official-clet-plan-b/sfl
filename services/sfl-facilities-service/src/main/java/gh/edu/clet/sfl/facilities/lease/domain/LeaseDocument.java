package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaseDocument(UUID id, UUID agreementId, String siteCode, DocumentKind kind, String reference,
        String fileName, String mediaType, long sizeBytes, String contentHash, String retentionClass,
        LocalDate expiresOn, String submittedBy, Instant submittedAt) {

    public boolean expired(LocalDate today) {
        return expiresOn != null && expiresOn.isBefore(today);
    }
}

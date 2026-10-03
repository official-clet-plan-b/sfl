package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.util.UUID;

public record Claim(UUID id, String reference, UUID itemId, String siteCode, String claimantName,
        String claimantContact, String claimantDescription, ClaimStatus status, boolean identityVerified,
        VerificationMethod verificationMethod, String verificationReference, String verifiedBy, Instant verifiedAt,
        String decisionReason, String decidedBy, Instant decidedAt, String releasedBy, Instant releasedAt,
        Instant closedAt, Instant personalDataPurgedAt, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {

    /** A claim as an unauthorised reader sees it: what is claimed and where it stands, never who is claiming. */
    public Claim masked() {
        return new Claim(id, reference, itemId, siteCode, null, null, null, status, identityVerified, null, null,
                null, verifiedAt, null, null, decidedAt, null, releasedAt, closedAt, personalDataPurgedAt, null,
                createdAt, updatedAt, version);
    }
}

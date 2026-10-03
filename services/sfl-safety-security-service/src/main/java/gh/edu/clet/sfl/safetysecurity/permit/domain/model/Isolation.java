package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

/** One isolation or lock-out: required at request, verified by a named person, removed by a named person. */
public record Isolation(UUID id, UUID permitId, String siteCode, IsolationKind kind, String description, String tagReference,
        IsolationStatus status, String verifiedBy, Instant verifiedAt, String verificationNote, String removedBy,
        Instant removedAt, String removalNote, Instant createdAt) {
}

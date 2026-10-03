package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Someone named on a permit: a reference and a name, nothing else about them. */
public record PermitWorker(UUID id, UUID permitId, String siteCode, String personReference, String displayName, WorkRole workRole,
        Instant createdAt) {
}

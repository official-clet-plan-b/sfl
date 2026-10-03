package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PermitFlag(UUID id, UUID permitId, String siteCode, FlagType flagType, String reference, String detail, String status,
        Instant raisedAt, String reviewedBy, Instant reviewedAt, String reviewNote) {

    public boolean open() {
        return "OPEN".equals(status);
    }
}

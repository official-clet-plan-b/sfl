package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.util.UUID;

public record WastePoint(UUID id, String siteCode, String code, String name, UUID roomId,
        String containerDescription, boolean active, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {
}

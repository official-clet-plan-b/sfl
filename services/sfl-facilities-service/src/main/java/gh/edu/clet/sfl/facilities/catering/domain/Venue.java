package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.util.UUID;

public record Venue(UUID id, String siteCode, String code, String name, UUID roomId, int capacity, boolean active,
        String createdBy, Instant createdAt, Instant updatedAt, long version) {
}

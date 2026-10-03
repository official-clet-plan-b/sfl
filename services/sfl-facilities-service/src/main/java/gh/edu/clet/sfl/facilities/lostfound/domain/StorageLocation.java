package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.util.UUID;

public record StorageLocation(UUID id, String siteCode, String code, String name, boolean secure, boolean active,
        String createdBy, Instant createdAt, Instant updatedAt, long version) {
}

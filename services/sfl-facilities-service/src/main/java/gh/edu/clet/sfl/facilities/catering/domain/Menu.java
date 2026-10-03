package gh.edu.clet.sfl.facilities.catering.domain;

import java.time.Instant;
import java.util.UUID;

public record Menu(UUID id, String siteCode, String code, String name, String description, MenuStatus status,
        String approvedBy, Instant approvedAt, String createdBy, Instant createdAt, Instant updatedAt, long version) {
}

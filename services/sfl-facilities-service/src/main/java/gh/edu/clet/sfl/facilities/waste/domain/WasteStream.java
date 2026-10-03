package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.util.UUID;

public record WasteStream(UUID id, String code, String name, WasteCategory category, boolean hazardous,
        boolean diverted, String handlingRules, boolean active, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {
}

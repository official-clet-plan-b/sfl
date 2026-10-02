package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something the drill revealed - SRS-SFL-S175-03. Each finding ends with a corrective action or an explicit
 * "no action required" justification; one with neither is an "Unactioned Finding" and holds the review open.
 */
public record DrillFinding(UUID id, UUID drillId, String siteCode, int sequenceNo, String description,
        String noActionJustification, String recordedBy, Instant recordedAt) {

    public DrillFinding {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(drillId, "drillId is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        description = description.strip();
        noActionJustification = noActionJustification == null || noActionJustification.isBlank() ? null
                : noActionJustification.strip();
    }

    public DrillFinding justified(String justification) {
        return new DrillFinding(id, drillId, siteCode, sequenceNo, description, justification, recordedBy,
                recordedAt);
    }
}

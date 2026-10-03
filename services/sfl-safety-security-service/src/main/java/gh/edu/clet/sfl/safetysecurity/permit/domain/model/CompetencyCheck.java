package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A verifier's recorded check that a worker holds a competence. {@code validUntil} null means it does not lapse. */
public record CompetencyCheck(UUID id, UUID permitId, String siteCode, UUID workerId, String competencyCode, boolean competent,
        String evidenceReference, LocalDate validUntil, String checkedBy, Instant checkedAt, String note) {

    /** Competent, and still valid through {@code through}. */
    public boolean coversThrough(Instant through) {
        return competent && (validUntil == null
                || !validUntil.atStartOfDay(java.time.ZoneOffset.UTC).plusDays(1).toInstant().isBefore(through));
    }
}

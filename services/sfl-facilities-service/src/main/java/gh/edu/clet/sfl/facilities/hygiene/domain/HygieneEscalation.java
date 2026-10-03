package gh.edu.clet.sfl.facilities.hygiene.domain;

import java.time.Instant;
import java.util.UUID;

public record HygieneEscalation(UUID id, String siteCode, String subjectType, UUID subjectId, String subjectReference,
        EscalationLevel level, EscalationReason reason, String detail, Instant raisedAt, String acknowledgedBy,
        Instant acknowledgedAt) {
}

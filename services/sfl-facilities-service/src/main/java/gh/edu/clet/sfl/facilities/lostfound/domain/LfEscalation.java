package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.util.UUID;

public record LfEscalation(UUID id, String siteCode, UUID itemId, UUID claimId, EscalationReason reason,
        String escalatedTo, String detail, String incidentState, String incidentReference, Instant raisedAt,
        String acknowledgedBy, Instant acknowledgedAt) {
}

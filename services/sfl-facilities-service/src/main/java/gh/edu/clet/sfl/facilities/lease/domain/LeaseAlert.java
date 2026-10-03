package gh.edu.clet.sfl.facilities.lease.domain;

import java.time.Instant;
import java.util.UUID;

public record LeaseAlert(UUID id, String siteCode, UUID agreementId, UUID obligationId, AlertLevel level,
        AlertReason reason, String detail, Instant raisedAt, String acknowledgedBy, Instant acknowledgedAt) {
}

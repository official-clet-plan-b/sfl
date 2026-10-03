package gh.edu.clet.sfl.facilities.catering.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Variance(UUID id, UUID serviceId, String siteCode, VarianceKind kind, BigDecimal planned,
        BigDecimal actual, BigDecimal difference, String ownerReference, String reason, VarianceStatus status,
        String approvedBy, Instant approvedAt, String createdBy, Instant createdAt) {
}

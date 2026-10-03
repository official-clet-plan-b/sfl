package gh.edu.clet.sfl.safetysecurity.permit.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PermitApproval(UUID id, UUID permitId, String siteCode, ApprovalPurpose purpose, UUID refId, int approvalRound,
        ApprovalStage stage, ApprovalDecision decision, String decidedBy, Instant decidedAt, String conditions, String comment) {
}

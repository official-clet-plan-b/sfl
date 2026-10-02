package gh.edu.clet.sfl.safetysecurity.emergency.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The S174 workflow aggregate. Routine activations are approval-gated before send; break-glass activations
 * (declared emergency, authorised role, break-glass-eligible template) send immediately and record
 * after-the-fact approval, which gates closure. SFL governs - it never actuates certified life-safety
 * hardware (Arch §0E). Transitions are explicit and return copies; the domain enforces source-state and
 * closure-evidence rules, the application layer enforces authorization and cross-record gating.
 */
public record NotificationActivation(UUID id, String activationNumber, SiteCode siteCode, UUID scenarioId,
        UUID templateId, List<UUID> audienceGroupIds, List<UUID> recipientZoneIds, List<ChannelType> channels,
        Mode mode, Status status, Priority priority, String incidentReference, String approvedBy, Instant approvedAt,
        String rejectionReason, String afterActionApprovedBy, Instant afterActionApprovedAt,
        String afterActionJustification, Instant allClearAt, String closureReason, String deliverySummary,
        String acknowledgementSummary, UUID closureEvidenceId, int escalationLevel, boolean degradedMode,
        String fallbackPath, Long fastLaneMillis, RecordMetadata metadata) {

    /**
     * {@code DRILL} (Phase 2 S175-01): an exercise sent through the real path with a drill template. No approval,
     * never break-glass, never counted as a live emergency, never escalated for missing acknowledgements.
     */
    public enum Mode { ROUTINE, BREAK_GLASS, DEGRADED, DRILL }

    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, ACTIVATING, ACTIVE, BREAK_GLASS_ACTIVE, PARTIALLY_DELIVERED,
        ESCALATED, ALL_CLEAR_PENDING, CLOSED, CANCELLED, FAILED, REOPENED
    }

    public NotificationActivation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(siteCode);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(status);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(metadata);
        activationNumber = require(activationNumber, "activationNumber");
        audienceGroupIds = audienceGroupIds == null ? List.of() : List.copyOf(audienceGroupIds);
        recipientZoneIds = recipientZoneIds == null ? List.of() : List.copyOf(recipientZoneIds);
        channels = channels == null ? List.of() : List.copyOf(channels);
        if (escalationLevel < 0) {
            throw new IllegalArgumentException("escalationLevel cannot be negative");
        }
    }

    public boolean open() {
        return status != Status.CLOSED && status != Status.CANCELLED && status != Status.REJECTED;
    }

    public boolean active() {
        return status == Status.ACTIVE || status == Status.BREAK_GLASS_ACTIVE
                || status == Status.PARTIALLY_DELIVERED || status == Status.ESCALATED;
    }

    public NotificationActivation submit(RecordMetadata changed) {
        requireState(Status.DRAFT);
        if (channels.isEmpty()) {
            throw new IllegalStateException("An activation must select at least one channel before submission");
        }
        return copy(b -> b.status = Status.PENDING_APPROVAL, changed);
    }

    public NotificationActivation approve(String approver, RecordMetadata changed) {
        requireState(Status.PENDING_APPROVAL);
        return copy(b -> { b.status = Status.APPROVED; b.approvedBy = require(approver, "approver");
            b.approvedAt = changed.lastModifiedAt(); }, changed);
    }

    public NotificationActivation reject(String reason, RecordMetadata changed) {
        requireState(Status.PENDING_APPROVAL);
        return copy(b -> { b.status = Status.REJECTED; b.rejectionReason = require(reason, "rejection reason"); },
                changed);
    }

    public NotificationActivation cancel(String reason, RecordMetadata changed) {
        requireState(Status.DRAFT, Status.PENDING_APPROVAL, Status.APPROVED);
        return copy(b -> { b.status = Status.CANCELLED; b.closureReason = require(reason, "cancellation reason"); },
                changed);
    }

    /** Routine send after approval. */
    public NotificationActivation activate(RecordMetadata changed) {
        requireState(Status.APPROVED);
        if (channels.isEmpty()) {
            throw new IllegalStateException("An activation must select at least one channel before send");
        }
        return copy(b -> b.status = Status.ACTIVE, changed);
    }

    /**
     * Drill send (S175-01): straight from draft to active, because an exercise needs no approval to rehearse -
     * the drill template's marker, checked by the caller, is what makes it safe to send.
     */
    public NotificationActivation drillActivate(RecordMetadata changed) {
        requireState(Status.DRAFT);
        if (mode != Mode.DRILL) {
            throw new IllegalStateException("Only a drill activation can be sent without approval as a drill");
        }
        if (channels.isEmpty()) {
            throw new IllegalStateException("A drill activation must select at least one channel");
        }
        return copy(b -> b.status = Status.ACTIVE, changed);
    }

    /**
     * Closes a drill when the drill that sent it is over. Unlike a real closure it needs no evidence file or
     * after-action approval: the drill's own after-action review (S175-03) is where the record lives.
     */
    public NotificationActivation closeDrill(String deliverySummary, String ackSummary, RecordMetadata changed) {
        if (mode != Mode.DRILL) {
            throw new IllegalStateException("Only a drill activation closes as a drill");
        }
        requireState(Status.ACTIVE, Status.PARTIALLY_DELIVERED, Status.ESCALATED, Status.ALL_CLEAR_PENDING);
        return copy(b -> { b.status = Status.CLOSED; b.closureReason = "Drill completed";
            b.deliverySummary = deliverySummary; b.acknowledgementSummary = ackSummary; }, changed);
    }

    /** Break-glass send: no pre-approval. The caller has already checked break-glass eligibility + role. */
    public NotificationActivation breakGlassActivate(RecordMetadata changed) {
        requireState(Status.DRAFT);
        if (channels.isEmpty()) {
            throw new IllegalStateException("A break-glass activation must select at least one channel");
        }
        return copy(b -> { b.status = Status.BREAK_GLASS_ACTIVE; b.mode = Mode.BREAK_GLASS; }, changed);
    }

    public NotificationActivation markPartiallyDelivered(RecordMetadata changed) {
        requireState(Status.ACTIVE, Status.BREAK_GLASS_ACTIVE, Status.PARTIALLY_DELIVERED);
        return copy(b -> b.status = Status.PARTIALLY_DELIVERED, changed);
    }

    public NotificationActivation escalate(String reason, RecordMetadata changed) {
        if (!active()) {
            throw new IllegalStateException("Only an active activation can escalate");
        }
        return copy(b -> { b.status = Status.ESCALATED; b.escalationLevel = escalationLevel + 1; }, changed);
    }

    public NotificationActivation afterActionApprove(String approver, String justification, RecordMetadata changed) {
        if (status == Status.CLOSED || status == Status.CANCELLED) {
            throw new IllegalStateException("After-action approval cannot be recorded on a terminal activation");
        }
        return copy(b -> { b.afterActionApprovedBy = require(approver, "after-action approver");
            b.afterActionJustification = require(justification, "after-action justification");
            b.afterActionApprovedAt = changed.lastModifiedAt(); }, changed);
    }

    public NotificationActivation allClear(RecordMetadata changed) {
        requireState(Status.ACTIVE, Status.BREAK_GLASS_ACTIVE, Status.PARTIALLY_DELIVERED, Status.ESCALATED);
        return copy(b -> { b.status = Status.ALL_CLEAR_PENDING; b.allClearAt = changed.lastModifiedAt(); }, changed);
    }

    /**
     * Closure gate (SRS-SFL-S174-02/03): closure reason, delivery/acknowledgement summary and an evidence
     * reference are all required; a break-glass activation additionally requires after-the-fact approval.
     */
    public NotificationActivation close(String reason, String deliverySummary, String ackSummary, UUID evidenceId,
            RecordMetadata changed) {
        requireState(Status.ALL_CLEAR_PENDING, Status.ACTIVE, Status.PARTIALLY_DELIVERED, Status.ESCALATED,
                Status.BREAK_GLASS_ACTIVE, Status.REOPENED);
        if (reason == null || reason.isBlank() || deliverySummary == null || deliverySummary.isBlank()
                || ackSummary == null || ackSummary.isBlank() || evidenceId == null) {
            throw new IllegalStateException("closure reason, delivery/acknowledgement summary and evidence are required");
        }
        if (mode == Mode.BREAK_GLASS && (afterActionApprovedBy == null || afterActionApprovedBy.isBlank())) {
            throw new IllegalStateException("Break-glass closure requires after-the-fact approval");
        }
        return copy(b -> { b.status = Status.CLOSED; b.closureReason = reason.strip();
            b.deliverySummary = deliverySummary.strip(); b.acknowledgementSummary = ackSummary.strip();
            b.closureEvidenceId = evidenceId; }, changed);
    }

    public NotificationActivation reopen(String reason, RecordMetadata changed) {
        requireState(Status.CLOSED);
        return copy(b -> { b.status = Status.REOPENED; b.closureReason = require(reason, "reopen reason"); }, changed);
    }

    public NotificationActivation withDegradedFallback(String fallbackPath, RecordMetadata changed) {
        if (!active()) {
            throw new IllegalStateException("Only an active activation can enter degraded fallback");
        }
        return copy(b -> { b.degradedMode = true; b.mode = Mode.DEGRADED;
            b.fallbackPath = require(fallbackPath, "fallbackPath"); }, changed);
    }

    public NotificationActivation withFastLaneMillis(long millis, RecordMetadata changed) {
        return copy(b -> b.fastLaneMillis = millis, changed);
    }

    // ---- copy helper -----------------------------------------------------------------------------

    private NotificationActivation copy(java.util.function.Consumer<Builder> mutator, RecordMetadata changed) {
        Builder b = new Builder(this);
        mutator.accept(b);
        return new NotificationActivation(id, activationNumber, siteCode, scenarioId, templateId, audienceGroupIds,
                recipientZoneIds, channels, b.mode, b.status, priority, incidentReference, b.approvedBy, b.approvedAt,
                b.rejectionReason, b.afterActionApprovedBy, b.afterActionApprovedAt, b.afterActionJustification,
                b.allClearAt, b.closureReason, b.deliverySummary, b.acknowledgementSummary, b.closureEvidenceId,
                b.escalationLevel, b.degradedMode, b.fallbackPath, b.fastLaneMillis, changed);
    }

    private static final class Builder {
        Mode mode; Status status; String approvedBy; Instant approvedAt; String rejectionReason;
        String afterActionApprovedBy; Instant afterActionApprovedAt; String afterActionJustification;
        Instant allClearAt; String closureReason; String deliverySummary; String acknowledgementSummary;
        UUID closureEvidenceId; int escalationLevel; boolean degradedMode; String fallbackPath; Long fastLaneMillis;

        Builder(NotificationActivation a) {
            this.mode = a.mode; this.status = a.status; this.approvedBy = a.approvedBy; this.approvedAt = a.approvedAt;
            this.rejectionReason = a.rejectionReason; this.afterActionApprovedBy = a.afterActionApprovedBy;
            this.afterActionApprovedAt = a.afterActionApprovedAt; this.afterActionJustification = a.afterActionJustification;
            this.allClearAt = a.allClearAt; this.closureReason = a.closureReason; this.deliverySummary = a.deliverySummary;
            this.acknowledgementSummary = a.acknowledgementSummary; this.closureEvidenceId = a.closureEvidenceId;
            this.escalationLevel = a.escalationLevel; this.degradedMode = a.degradedMode; this.fallbackPath = a.fallbackPath;
            this.fastLaneMillis = a.fastLaneMillis;
        }
    }

    private void requireState(Status... allowed) {
        for (Status s : allowed) {
            if (s == status) {
                return;
            }
        }
        throw new IllegalStateException("transition not allowed from " + status);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}

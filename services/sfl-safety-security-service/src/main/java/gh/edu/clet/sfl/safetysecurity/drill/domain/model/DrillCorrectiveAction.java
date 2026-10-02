package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A corrective action raised from a drill finding - SRS-SFL-S175-03: "the same CAPA tracking pattern as S163
 * incidents, including an owner and due date". The fields, lifecycle and computed overdue flag are S163's
 * {@code CorrectiveAction}'s; the parent is a finding on a drill rather than an incident.
 *
 * <p>Overdue is computed against today, never stored, exactly as in S163.
 */
public record DrillCorrectiveAction(UUID id, UUID drillId, UUID findingId, String siteCode, String description,
        String ownerId, LocalDate dueDate, CapaStatus status, String verificationNotes, String createdBy,
        Instant createdAt, String resolvedBy, Instant resolvedAt) {

    public DrillCorrectiveAction {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(drillId, "drillId is required");
        Objects.requireNonNull(findingId, "findingId is required");
        Objects.requireNonNull(siteCode, "siteCode is required");
        description = require(description, "description");
        ownerId = require(ownerId, "ownerId");
        Objects.requireNonNull(dueDate, "dueDate is required");
        Objects.requireNonNull(status, "status is required");
        verificationNotes = verificationNotes == null || verificationNotes.isBlank() ? null : verificationNotes.strip();
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (status.isTerminal() != (resolvedAt != null)) {
            throw new IllegalArgumentException("resolvedAt must be set exactly when the status is terminal");
        }
    }

    public static DrillCorrectiveAction open(UUID id, DrillFinding finding, String description, String ownerId,
            LocalDate dueDate, String actorId, Instant at) {
        return new DrillCorrectiveAction(id, finding.drillId(), finding.id(), finding.siteCode(), description,
                ownerId, dueDate, CapaStatus.OPEN, null, actorId, at, null, null);
    }

    public DrillCorrectiveAction startProgress() {
        return new DrillCorrectiveAction(id, drillId, findingId, siteCode, description, ownerId, dueDate,
                status.transitionTo(CapaStatus.IN_PROGRESS), verificationNotes, createdBy, createdAt, null, null);
    }

    /** Effectiveness verified - S163's "completion requires effectiveness verification". */
    public DrillCorrectiveAction verify(String notes, String actorId, Instant at) {
        return new DrillCorrectiveAction(id, drillId, findingId, siteCode, description, ownerId, dueDate,
                status.transitionTo(CapaStatus.VERIFIED), require(notes, "notes"), createdBy, createdAt, actorId, at);
    }

    public DrillCorrectiveAction cancel(String reason, String actorId, Instant at) {
        return new DrillCorrectiveAction(id, drillId, findingId, siteCode, description, ownerId, dueDate,
                status.transitionTo(CapaStatus.CANCELLED), require(reason, "reason"), createdBy, createdAt, actorId,
                at);
    }

    public boolean isOpen() {
        return !status.isTerminal();
    }

    public boolean isOverdue(LocalDate today) {
        return isOpen() && today.isAfter(dueDate);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}

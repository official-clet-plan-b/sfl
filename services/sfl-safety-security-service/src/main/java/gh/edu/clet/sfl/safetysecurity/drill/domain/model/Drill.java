package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A crisis or evacuation drill - the S175 aggregate. Its plan, where it stands, and the reason for the last
 * postponement, cancellation or closure-with-deferral. The execution record, gap list, review and corrective
 * actions are separate records keyed by {@link #id}: each is written at a different moment by a different
 * person, and none of them changes the plan.
 *
 * @param statusReason why it was postponed or cancelled, or the deferral reason it was closed with
 */
public record Drill(UUID id, String siteCode, String reference, DrillPlan plan, DrillStatus status,
        String statusReason, RecordMetadata metadata) {

    public Drill {
        Objects.requireNonNull(id, "id is required");
        if (siteCode == null || siteCode.isBlank()) {
            throw new DrillException(DrillErrorCode.DRILL_MISSING_SITE_SCOPE);
        }
        siteCode = siteCode.strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(reference, "reference is required");
        Objects.requireNonNull(plan, "plan is required");
        Objects.requireNonNull(status, "status is required");
        statusReason = statusReason == null || statusReason.isBlank() ? null : statusReason.strip();
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public static Drill plan(UUID id, String siteCode, String reference, DrillPlan plan, RecordMetadata metadata) {
        return new Drill(id, siteCode, reference, plan, DrillStatus.PLANNED, null, metadata);
    }

    public Drill revise(DrillPlan next, RecordMetadata meta) {
        if (!status.planEditable()) {
            throw DrillException.invalidState(status.name(), "REVISE_PLAN");
        }
        return new Drill(id, siteCode, reference, next, status, statusReason, meta);
    }

    /** Readiness is checked by {@code ExecutionReadinessPolicy} before this is called. */
    public Drill schedule(Instant when, RecordMetadata meta) {
        return new Drill(id, siteCode, reference, plan.rescheduledFor(when), status.transitionTo(DrillStatus.SCHEDULED),
                null, meta);
    }

    public Drill postpone(String reason, RecordMetadata meta) {
        return new Drill(id, siteCode, reference, plan, status.transitionTo(DrillStatus.POSTPONED),
                requireReason(reason), meta);
    }

    public Drill cancel(String reason, RecordMetadata meta) {
        return new Drill(id, siteCode, reference, plan, status.transitionTo(DrillStatus.CANCELLED),
                requireReason(reason), meta);
    }

    public Drill start(RecordMetadata meta) {
        return moveTo(DrillStatus.IN_PROGRESS, meta);
    }

    public Drill completeRollCall(RecordMetadata meta) {
        return moveTo(DrillStatus.COMPLETED, meta);
    }

    public Drill judge(List<ModuleExpectation> judged, RecordMetadata meta) {
        if (status != DrillStatus.COMPLETED) {
            throw DrillException.invalidState(status.name(), "RECORD_REVIEW");
        }
        return new Drill(id, siteCode, reference, plan.withExpectations(judged), status, statusReason, meta);
    }

    public Drill markReviewed(RecordMetadata meta) {
        return moveTo(DrillStatus.REVIEWED, meta);
    }

    /** @param deferralReason required by {@code ClosurePolicy} only when overdue actions remain open */
    public Drill close(String deferralReason, RecordMetadata meta) {
        return new Drill(id, siteCode, reference, plan, status.transitionTo(DrillStatus.CLOSED), deferralReason, meta);
    }

    private Drill moveTo(DrillStatus target, RecordMetadata meta) {
        return new Drill(id, siteCode, reference, plan, status.transitionTo(target), null, meta);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DrillException(DrillErrorCode.DRILL_REASON_REQUIRED);
        }
        return reason.strip();
    }
}

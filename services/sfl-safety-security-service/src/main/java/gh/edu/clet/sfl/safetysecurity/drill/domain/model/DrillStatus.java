package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life of a drill - SRS-SFL-S175-01's "planned -> scheduled -> executed via S174 test mode -> participation
 * tracked -> after-action review opened", then S175-03's "drill closed once actions are closed or deferred".
 * Same {@code ALLOWED}-map shape as S163's {@code IncidentStatus}.
 */
public enum DrillStatus {

    /** Being written. Not on anyone's calendar yet. */
    PLANNED,
    /** Ready and dated: the template is a drill template, and a combined drill's expectations are explicit. */
    SCHEDULED,
    /** Put off, with a reason. The plan is kept for rescheduling (S175-01). */
    POSTPONED,
    /** Under way: the baseline is taken, the muster is open and the S174 drill notification has gone out. */
    IN_PROGRESS,
    /** Roll-call closed and the gap list recorded - the drill is executed, awaiting its after-action review. */
    COMPLETED,
    /** After-action review submitted. Counts toward frequency compliance from here (S175-04). */
    REVIEWED,
    /** Closed once its overdue corrective actions are closed or deferred with a reason (S175-03). Terminal. */
    CLOSED,
    /** Called off, with a reason. Never counts toward compliance. Terminal. */
    CANCELLED;

    private static final Map<DrillStatus, Set<DrillStatus>> ALLOWED = Map.of(
            PLANNED, EnumSet.of(SCHEDULED, CANCELLED),
            SCHEDULED, EnumSet.of(IN_PROGRESS, POSTPONED, CANCELLED),
            POSTPONED, EnumSet.of(SCHEDULED, CANCELLED),
            IN_PROGRESS, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.of(REVIEWED),
            REVIEWED, EnumSet.of(CLOSED),
            CLOSED, EnumSet.noneOf(DrillStatus.class),
            CANCELLED, EnumSet.noneOf(DrillStatus.class));

    public boolean canTransitionTo(DrillStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public DrillStatus transitionTo(DrillStatus target) {
        if (!canTransitionTo(target)) {
            throw DrillException.invalidState(name(), target == null ? "" : target.name());
        }
        return target;
    }

    /** The plan may still change - nothing has been executed against it. */
    public boolean planEditable() {
        return this == PLANNED || this == SCHEDULED || this == POSTPONED;
    }

    /** S175-04: "only a completed, reviewed drill" counts toward frequency compliance. */
    public boolean countsTowardCompliance() {
        return this == REVIEWED || this == CLOSED;
    }
}

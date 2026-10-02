package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life of a {@link DrillCorrectiveAction} - S163's CAPA lifecycle, identical by design (SRS-SFL-S175-03:
 * "following the same CAPA tracking pattern as S163 incidents"). Own copy, as every module keeps its own.
 */
public enum CapaStatus {

    /** Created, not yet started. */
    OPEN,
    /** Being worked. */
    IN_PROGRESS,
    /** Effectiveness verified - SRS §D.9 step 7's "completion requires effectiveness verification". Terminal. */
    VERIFIED,
    /** Withdrawn - a mandatory action that turned out not to be needed still needs a reason on record. Terminal. */
    CANCELLED;

    private static final Map<CapaStatus, Set<CapaStatus>> ALLOWED = Map.of(
            OPEN, EnumSet.of(IN_PROGRESS, VERIFIED, CANCELLED),
            IN_PROGRESS, EnumSet.of(VERIFIED, CANCELLED),
            VERIFIED, EnumSet.noneOf(CapaStatus.class),
            CANCELLED, EnumSet.noneOf(CapaStatus.class));

    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }

    public boolean canTransitionTo(CapaStatus target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public CapaStatus transitionTo(CapaStatus target) {
        if (!canTransitionTo(target)) {
            throw DrillException.invalidState(name(), target == null ? "" : target.name());
        }
        return target;
    }
}

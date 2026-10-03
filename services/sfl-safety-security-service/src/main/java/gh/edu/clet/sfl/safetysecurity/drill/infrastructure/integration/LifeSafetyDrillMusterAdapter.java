package gh.edu.clet.sfl.safetysecurity.drill.infrastructure.integration;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.drill.application.port.DrillMusterPort;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillErrorCode;
import gh.edu.clet.sfl.safetysecurity.drill.domain.exception.DrillException;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.BaselinePerson;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.contract.DrillMusterControl;
import gh.edu.clet.sfl.safetysecurity.lifesafety.domain.exception.LifeSafetyException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one S175 class that knows S162a exists - SRS-SFL-S175-02: "Drill roll-call reuses the S160/S160a check-in/out
 * and muster mechanism". In-process, through S162a's published {@link DrillMusterControl}.
 */
@Component
public class LifeSafetyDrillMusterAdapter implements DrillMusterPort {

    private final DrillMusterControl muster;

    public LifeSafetyDrillMusterAdapter(DrillMusterControl muster) {
        this.muster = muster;
    }

    @Override
    public Baseline baseline(String siteCode, Instant takenAt) {
        DrillMusterControl.Baseline b = muster.baseline(siteCode, takenAt);
        return new Baseline(b.persons().stream()
                .map(p -> new BaselinePerson(p.personRef(), p.displayName(), p.source())).toList(),
                b.accessDataAsOf(), b.takenAt());
    }

    @Override
    public UUID open(String siteCode, String assemblyZone, UUID drillId, ActorContext actor) {
        return muster.open(siteCode, assemblyZone, drillId, actor);
    }

    @Override
    public CheckIn checkIn(UUID musterSessionId, String personRef, ActorContext actor) {
        try {
            DrillMusterControl.CheckedIn c = muster.checkIn(musterSessionId, personRef, actor);
            return new CheckIn(c.personRef(), c.checkedInAt());
        } catch (LifeSafetyException e) {
            throw new DrillException(DrillErrorCode.DRILL_INVALID_STATE_TRANSITION,
                    Map.of("musterSessionId", musterSessionId.toString(), "s162aCode", e.errorCode().name()));
        }
    }

    @Override
    public List<CheckIn> checkIns(UUID musterSessionId) {
        return muster.checkIns(musterSessionId).stream().map(c -> new CheckIn(c.personRef(), c.checkedInAt()))
                .toList();
    }

    @Override
    public void close(UUID musterSessionId, ActorContext actor) {
        muster.close(musterSessionId, actor);
    }
}

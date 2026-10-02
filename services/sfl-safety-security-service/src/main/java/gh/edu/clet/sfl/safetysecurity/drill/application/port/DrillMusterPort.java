package gh.edu.clet.sfl.safetysecurity.drill.application.port;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.safetysecurity.drill.domain.model.BaselinePerson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S175's view of S162a's muster and the S160/S160a occupancy behind it - SRS-SFL-S175-02: "Drill roll-call reuses
 * the S160/S160a check-in/out and muster mechanism". Implemented by the one adapter that knows S162a exists.
 */
public interface DrillMusterPort {

    Baseline baseline(String siteCode, Instant takenAt);

    UUID open(String siteCode, String assemblyZone, UUID drillId, ActorContext actor);

    CheckIn checkIn(UUID musterSessionId, String personRef, ActorContext actor);

    List<CheckIn> checkIns(UUID musterSessionId);

    void close(UUID musterSessionId, ActorContext actor);

    record Baseline(List<BaselinePerson> persons, Instant accessDataAsOf, Instant takenAt) {
    }

    record CheckIn(String personRef, Instant checkedInAt) {
    }
}

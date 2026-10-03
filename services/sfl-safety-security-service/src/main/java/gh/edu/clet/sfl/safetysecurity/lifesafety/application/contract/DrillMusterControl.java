package gh.edu.clet.sfl.safetysecurity.lifesafety.application.contract;

import gh.edu.clet.sfl.common.security.ActorContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S162a's muster, offered to Phase 2 S175 - SRS-SFL-S175-02: "Drill roll-call reuses the S160/S160a check-in/out
 * and muster mechanism", so a drill validates the real roll-call process rather than a paper exercise.
 *
 * <p>Published in S162a's own {@code contract} package; S175 reaches it through one adapter. A drill muster is
 * a muster session like any other, with two differences: it is opened by a drill, not a fire event, and it is
 * never the session a real fire event joins - a fire during a drill opens its own.
 *
 * <p>Not permission-checked against S162a's matrix: S175 authorises its operator for the drill first.
 */
public interface DrillMusterControl {

    /**
     * Everyone who should be accounted for at the site right now - the S160 and S160a baseline, with how fresh
     * the access data behind it was. S175 takes this once, at drill start, and measures the drill against it.
     */
    Baseline baseline(String siteCode, Instant takenAt);

    /** Opens a muster session at the assembly zone for this drill. */
    UUID open(String siteCode, String assemblyZone, UUID drillId, ActorContext actor);

    /** Records someone at the muster point. Refused once the session is closed. */
    CheckedIn checkIn(UUID sessionId, String personRef, ActorContext actor);

    /** Everyone checked in so far, earliest first. */
    List<CheckedIn> checkIns(UUID sessionId);

    /** Closes the session. Idempotent once closed. */
    void close(UUID sessionId, ActorContext actor);

    /** @param source {@code VISITOR}, {@code ACCESS_CONTROL} or {@code UNKNOWN} */
    record Person(String personRef, String displayName, String source) {
    }

    /** @param accessDataAsOf the newest access event behind the baseline, or null when there was none */
    record Baseline(List<Person> persons, Instant accessDataAsOf, Instant takenAt) {
    }

    record CheckedIn(String personRef, Instant checkedInAt) {
    }
}

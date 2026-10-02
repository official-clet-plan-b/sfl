package gh.edu.clet.sfl.safetysecurity.accesscontrol.application.contract;

import java.time.Instant;
import java.util.Set;

/**
 * Who S160a's access records place inside a site or zone right now, published for the muster and drill
 * roll-call baseline (S160a-06, S162a-04, Phase 2 S175-02).
 *
 * <p>Carries the time of the newest access event it was derived from, because S175-02's "Stale Baseline" is
 * about exactly that: occupancy computed from a feed that stopped an hour ago is not who is on site now.
 * Not permission-checked, for the same reason as {@code OnSiteVisitors}.
 */
public interface AccessOccupancySource {

    /** @param zoneCode one zone, or null for every zone at the site */
    OccupancySnapshot occupancy(String siteCode, String zoneCode);

    /** @param latestEventAt the newest access event considered, or null when there were none */
    record OccupancySnapshot(Set<String> personsIn, Instant latestEventAt) {
    }
}

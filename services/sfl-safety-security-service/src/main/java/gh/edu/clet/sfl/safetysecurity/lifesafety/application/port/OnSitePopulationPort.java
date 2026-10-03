package gh.edu.clet.sfl.safetysecurity.lifesafety.application.port;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SRS-SFL-S162a-04: combines the on-site visitor population (S160) and access-derived occupancy (S160a)
 * into the muster/roll-call view - the baseline every muster, real or drill (Phase 2 S175-02), measures
 * check-ins against.
 *
 * <p>Phase 1 shipped a no-op here, so every real muster had an empty baseline and could not say who was
 * missing. {@code OnSitePopulationAdapter} is the real one, built with S175.
 */
public interface OnSitePopulationPort {

    /** Everyone who should be accounted for in this zone right now, by person reference. */
    Set<String> onSitePersons(String siteCode, String zoneCode);

    /**
     * The same population with where each person came from and how fresh the access data was - what S175-02
     * needs to say whether a baseline is current ("Stale Baseline"). The default derives it from
     * {@link #onSitePersons}, with no freshness, for adapters that cannot say.
     *
     * @param zoneCode one zone, or null for the whole site
     */
    default PopulationSnapshot snapshot(String siteCode, String zoneCode, Instant takenAt) {
        return new PopulationSnapshot(onSitePersons(siteCode, zoneCode).stream()
                .map(ref -> new OnSitePerson(ref, null, Source.UNKNOWN))
                .sorted(Comparator.comparing(OnSitePerson::personRef)).toList(), null, takenAt);
    }

    enum Source { VISITOR, ACCESS_CONTROL, UNKNOWN }

    /** @param displayName known for visitors; access records carry a reference only */
    record OnSitePerson(String personRef, String displayName, Source source) {
    }

    /**
     * @param accessDataAsOf the newest access event the occupancy was derived from, or null when there was none
     */
    record PopulationSnapshot(List<OnSitePerson> persons, Instant accessDataAsOf, Instant takenAt) {

        public Set<String> personRefs() {
            return persons.stream().map(OnSitePerson::personRef).collect(Collectors.toUnmodifiableSet());
        }
    }
}

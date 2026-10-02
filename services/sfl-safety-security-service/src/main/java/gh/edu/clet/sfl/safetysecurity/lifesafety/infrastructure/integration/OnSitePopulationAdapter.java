package gh.edu.clet.sfl.safetysecurity.lifesafety.infrastructure.integration;

import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.contract.AccessOccupancySource;
import gh.edu.clet.sfl.safetysecurity.lifesafety.application.port.OnSitePopulationPort;
import gh.edu.clet.sfl.safetysecurity.visitor.application.contract.OnSiteVisitors;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The real muster baseline - SRS-SFL-S162a-04, S160a-06 and Phase 2 S175-02: everyone S160 has checked in
 * and not out, and everyone S160a's access records place inside, in one list.
 *
 * <p>Replaces the Phase 1 no-op, which gave every muster - real fire evacuations included - an empty
 * baseline, so "who has not checked in yet" was always nobody. Same deployable, so these are real in-process
 * calls through the contracts S160 and S160a publish; this is the one class in S162a that knows either exists.
 *
 * <p>A visitor and an access record for the same badge are one person: the visitor's reference is their badge
 * number where one was issued, which is what a reader records, and the visitor entry (which has a name) wins.
 * For a zone muster, a visitor counts when their visit granted that zone or was unrestricted - over-counting
 * who to look for is the safe direction for a roll-call.
 */
@Component
public class OnSitePopulationAdapter implements OnSitePopulationPort {

    private final OnSiteVisitors visitors;
    private final AccessOccupancySource access;

    public OnSitePopulationAdapter(OnSiteVisitors visitors, AccessOccupancySource access) {
        this.visitors = visitors;
        this.access = access;
    }

    @Override
    public Set<String> onSitePersons(String siteCode, String zoneCode) {
        return snapshot(siteCode, zoneCode, Instant.now()).personRefs();
    }

    @Override
    public PopulationSnapshot snapshot(String siteCode, String zoneCode, Instant takenAt) {
        String site = siteCode.strip().toUpperCase(Locale.ROOT);
        Map<String, OnSitePerson> persons = new LinkedHashMap<>();
        AccessOccupancySource.OccupancySnapshot occupancy = access.occupancy(site, zoneCode);
        occupancy.personsIn().forEach(ref -> persons.put(ref, new OnSitePerson(ref, null, Source.ACCESS_CONTROL)));
        visitors.onSite(site).stream()
                .filter(visitor -> zoneCode == null || visitor.accessZones().isEmpty()
                        || visitor.accessZones().contains(zoneCode))
                .forEach(visitor -> persons.put(visitor.personRef(),
                        new OnSitePerson(visitor.personRef(), visitor.displayName(), Source.VISITOR)));
        return new PopulationSnapshot(persons.values().stream().sorted(Comparator.comparing(OnSitePerson::personRef))
                .toList(), occupancy.latestEventAt(), takenAt);
    }
}

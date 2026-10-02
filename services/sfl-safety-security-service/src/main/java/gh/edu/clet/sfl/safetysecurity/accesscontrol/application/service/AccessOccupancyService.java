package gh.edu.clet.sfl.safetysecurity.accesscontrol.application.service;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflPermission;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.contract.AccessOccupancySource;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.port.AccessControlRepository;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessDirection;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessEvent;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessEventKind;
import gh.edu.clet.sfl.safetysecurity.accesscontrol.domain.model.AccessZone;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SRS-SFL-S160a-06: per-zone occupancy and per-person in/out state derived from access entry/exit
 * events, for muster and roll-call. A read model computed from {@link AccessEvent} rows on demand
 * rather than a maintained aggregate - S160a's own occupancy, not yet combined with S160's on-site
 * visitor population (see the implementation notes for that deliberate follow-up).
 */
@Service
public class AccessOccupancyService implements AccessOccupancySource {

    private static final int RECENT_EVENTS_PER_ZONE = 500;

    private final AccessControlRepository repository;
    private final AccessControlAccessPolicy access;

    public AccessOccupancyService(AccessControlRepository repository, AccessControlAccessPolicy access) {
        this.repository = repository;
        this.access = access;
    }

    public record ZoneOccupancy(String zoneCode, int currentlyIn, Map<String, AccessDirection> inOutStateByPerson) {
    }

    @Transactional(readOnly = true)
    public ZoneOccupancy occupancy(String siteCode, String zoneCode, ActorContext actor) {
        access.require(actor, SflPermission.ACCESS_OCCUPANCY_READ, siteCode, "AccessZone", zoneCode);
        List<AccessEvent> recent = repository.findRecentEvents(siteCode, zoneCode, null, RECENT_EVENTS_PER_ZONE);

        // Oldest-first so the last write for each person is their true current state - findRecentEvents
        // itself returns newest-first (see AccessControlRepository), so this reverses it.
        Map<String, AccessDirection> lastDirectionByPerson = new LinkedHashMap<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            AccessEvent event = recent.get(i);
            if (event.kind() == AccessEventKind.GRANTED && event.personRef() != null
                    && event.direction() != AccessDirection.UNKNOWN) {
                lastDirectionByPerson.put(event.personRef(), event.direction());
            }
        }
        int currentlyIn = (int) lastDirectionByPerson.values().stream()
                .filter(direction -> direction == AccessDirection.ENTRY).count();
        return new ZoneOccupancy(zoneCode, currentlyIn, Map.copyOf(lastDirectionByPerson));
    }

    /** SRS-SFL-S160a-06: "the muster/roll-call view combines access-derived occupancy with the on-site
     * visitor population (S160)". Combining with Visitor's roll-call is a documented follow-up (see the
     * implementation notes) - this returns S160a's own occupancy across every zone at a site, which a
     * combined muster view can be built from without S160a needing to know S160 exists. */
    @Transactional(readOnly = true)
    public List<ZoneOccupancy> muster(String siteCode, List<String> zoneCodes, ActorContext actor) {
        access.require(actor, SflPermission.ACCESS_OCCUPANCY_READ, siteCode, "AccessZone", null);
        List<AccessZone> zones = zoneCodes == null || zoneCodes.isEmpty() ? repository.findZonesBySite(siteCode)
                : repository.findZonesBySite(siteCode).stream().filter(z -> zoneCodes.contains(z.zoneCode())).toList();
        return zones.stream().map(zone -> occupancy(siteCode, zone.zoneCode(), actor)).toList();
    }

    /**
     * {@inheritDoc} The same derivation as {@link #occupancy}: a person is in when their latest granted,
     * directional event in the zone is an entry.
     */
    @Override
    @Transactional(readOnly = true)
    public OccupancySnapshot occupancy(String siteCode, String zoneCode) {
        List<String> zones = zoneCode != null ? List.of(zoneCode)
                : repository.findZonesBySite(siteCode).stream().map(AccessZone::zoneCode).toList();
        Set<String> personsIn = new HashSet<>();
        Instant latest = null;
        for (String zone : zones) {
            List<AccessEvent> recent = repository.findRecentEvents(siteCode, zone, null, RECENT_EVENTS_PER_ZONE);
            Map<String, AccessDirection> last = new LinkedHashMap<>();
            for (int i = recent.size() - 1; i >= 0; i--) {
                AccessEvent event = recent.get(i);
                if (latest == null || event.occurredAt().isAfter(latest)) {
                    latest = event.occurredAt();
                }
                if (event.kind() == AccessEventKind.GRANTED && event.personRef() != null
                        && event.direction() != AccessDirection.UNKNOWN) {
                    last.put(event.personRef(), event.direction());
                }
            }
            last.forEach((person, direction) -> {
                if (direction == AccessDirection.ENTRY) {
                    personsIn.add(person);
                }
            });
        }
        return new OccupancySnapshot(Set.copyOf(personsIn), latest);
    }
}

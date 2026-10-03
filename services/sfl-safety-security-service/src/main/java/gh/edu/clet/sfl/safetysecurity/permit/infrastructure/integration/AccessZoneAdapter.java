package gh.edu.clet.sfl.safetysecurity.permit.infrastructure.integration;

import gh.edu.clet.sfl.safetysecurity.accesscontrol.application.contract.AccessZoneDirectory;
import gh.edu.clet.sfl.safetysecurity.permit.application.port.PermitZonePort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The one S164 class that knows S160a exists: the zone a permit names is checked, in-process, through S160a's published {@link AccessZoneDirectory}. */
@Component
public class AccessZoneAdapter implements PermitZonePort {

    private final AccessZoneDirectory zones;

    public AccessZoneAdapter(AccessZoneDirectory zones) {
        this.zones = zones;
    }

    @Override
    public Optional<Zone> find(UUID zoneId, String siteCode) {
        return zones.find(zoneId, siteCode).map(z -> new Zone(z.id(), z.zoneCode(), z.name()));
    }
}

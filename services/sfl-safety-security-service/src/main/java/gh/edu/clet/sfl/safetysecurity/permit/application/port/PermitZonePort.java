package gh.edu.clet.sfl.safetysecurity.permit.application.port;

import java.util.Optional;
import java.util.UUID;

/** The S160a access-control zones a permit may name, checked in-process by the one adapter that knows S160a exists. */
public interface PermitZonePort {

    Optional<Zone> find(UUID zoneId, String siteCode);

    record Zone(UUID id, String zoneCode, String name) {
    }
}

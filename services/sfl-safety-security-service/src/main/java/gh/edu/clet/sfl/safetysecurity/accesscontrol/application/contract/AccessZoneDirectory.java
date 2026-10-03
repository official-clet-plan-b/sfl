package gh.edu.clet.sfl.safetysecurity.accesscontrol.application.contract;

import java.util.Optional;
import java.util.UUID;

/**
 * What another SSEMP module may ask S160a about access zones - published here, in the provider's own {@code contract} package.
 * The consumer written for it is S164 (Permit-to-Work: a permit names the zone its work isolates). A consumer reaches it through its
 * own port and a single adapter, never by importing S160a's services.
 */
public interface AccessZoneDirectory {

    /** The zone, if it exists at that site: a zone of another site is not found, because it does not cover this one. */
    Optional<ZoneRef> find(UUID zoneId, String siteCode);

    record ZoneRef(UUID id, String siteCode, String zoneCode, String name) {
    }
}

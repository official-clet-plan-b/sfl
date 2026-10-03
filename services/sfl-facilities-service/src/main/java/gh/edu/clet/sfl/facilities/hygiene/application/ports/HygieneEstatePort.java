package gh.edu.clet.sfl.facilities.hygiene.application.ports;

import java.util.Optional;
import java.util.UUID;

/** The S152 estate register, read-only: is this a real site, and does this room belong to it. */
public interface HygieneEstatePort {

    boolean siteExists(String siteCode);

    /** The site a room belongs to, or empty if there is no such room. */
    Optional<String> siteOfRoom(UUID roomId);
}

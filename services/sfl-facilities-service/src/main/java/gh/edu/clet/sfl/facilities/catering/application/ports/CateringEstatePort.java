package gh.edu.clet.sfl.facilities.catering.application.ports;

import java.util.Optional;
import java.util.UUID;

/** The S152 estate register, read-only: is this a real site, and does this room belong to it. */
public interface CateringEstatePort {

    boolean siteExists(String siteCode);

    Optional<String> siteOfRoom(UUID roomId);
}

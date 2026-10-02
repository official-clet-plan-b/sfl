package gh.edu.clet.sfl.fleetlogistics.assets.application;

import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import java.time.Instant;

/**
 * A reader reporting that it saw a tag at a place. Provider-neutral: whatever hardware or vendor
 * produced the read, an adapter reduces it to this before it reaches the register.
 */
public record RecordScanCommand(String tagId, LocationType locationType, String locationReference, String readerId,
        Instant occurredAt, String actor, String correlationId) {
}

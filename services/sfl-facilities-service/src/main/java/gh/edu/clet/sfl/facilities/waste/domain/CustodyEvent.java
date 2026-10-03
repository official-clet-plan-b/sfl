package gh.edu.clet.sfl.facilities.waste.domain;

import java.time.Instant;
import java.util.UUID;

public record CustodyEvent(UUID id, UUID collectionId, String siteCode, CustodyStep step, String fromParty,
        String toParty, String location, String evidenceReference, String note, String recordedBy,
        Instant occurredAt) {
}

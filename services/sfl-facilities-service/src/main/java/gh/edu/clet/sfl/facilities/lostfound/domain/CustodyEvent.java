package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.util.UUID;

/** One transfer: who had it, who has it now, where, and when. Never changed, never removed. */
public record CustodyEvent(UUID id, UUID itemId, String siteCode, String fromParty, String toParty, String location,
        Instant occurredAt, String reason, String recordedBy) {
}

package gh.edu.clet.sfl.safetysecurity.drill.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Someone on site per S160/S160a at drill start who had not checked in when roll-call closed - SRS-SFL-S175-02's
 * acceptance criterion. Recorded in the same transaction that marks the drill complete, so the gap list exists
 * before the drill does.
 *
 * @param followUp null until someone records how the gap was followed up
 */
public record RollCallGap(UUID id, UUID drillId, String siteCode, String personRef, String displayName,
        String source, GapFollowUp followUp, String followUpNotes, String followedUpBy, Instant followedUpAt) {

    public RollCallGap {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(drillId, "drillId is required");
        Objects.requireNonNull(personRef, "personRef is required");
        followUpNotes = followUpNotes == null || followUpNotes.isBlank() ? null : followUpNotes.strip();
    }

    public static RollCallGap of(UUID drillId, String siteCode, BaselinePerson person) {
        return new RollCallGap(UUID.randomUUID(), drillId, siteCode, person.personRef(), person.displayName(),
                person.source(), null, null, null, null);
    }

    public RollCallGap followedUp(GapFollowUp outcome, String notes, String by, Instant at) {
        Objects.requireNonNull(outcome, "followUp is required");
        return new RollCallGap(id, drillId, siteCode, personRef, displayName, source, outcome, notes, by, at);
    }

    public boolean open() {
        return followUp == null;
    }
}

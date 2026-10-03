package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.LocalDate;

/** How long an unclaimed item is kept, and how long a claimant's personal data is kept after the claim closes. */
public record RetentionPolicy(ItemCategory category, int unclaimedDays, int personalDataDays) {

    public LocalDate retentionUntil(LocalDate foundOn) {
        return foundOn.plusDays(unclaimedDays);
    }
}

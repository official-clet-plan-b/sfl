package gh.edu.clet.sfl.facilities.lostfound.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FoundItem(UUID id, String reference, String claimReference, String siteCode, ItemCategory category,
        String publicDescription, String privateDescription, String foundLocation, Instant foundAt,
        String finderReference, String initialCondition, ItemStatus status, boolean unsafe, String unsafeReason,
        UUID storageLocationId, LocalDate retentionUntil, Instant closedAt, String createdBy, Instant createdAt,
        Instant updatedAt, long version) {

    /** The view of the item an unauthorised reader gets: everything but what could identify its owner. */
    public FoundItem masked() {
        return new FoundItem(id, reference, claimReference, siteCode, category, publicDescription, null,
                foundLocation, foundAt, null, initialCondition, status, unsafe, null, storageLocationId,
                retentionUntil, closedAt, createdBy, createdAt, updatedAt, version);
    }

    public boolean retentionExpired(LocalDate today) {
        return retentionUntil.isBefore(today);
    }
}

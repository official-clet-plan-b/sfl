package gh.edu.clet.sfl.fleetlogistics.assets.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One change to an asset, kept after the asset has moved on.
 *
 * <p>The register itself holds only the current state - where an asset is and who has it now. Custody
 * is a chain of responsibility, and a chain that can only be read at its latest link cannot answer
 * "who had this on the day it went missing". Every location, custody and tag change writes one of
 * these, which is what makes that question answerable.
 *
 * <p>{@code sourceReference} names the reader for {@link AssetChangeSource#READER} changes and is
 * {@code null} for manual ones.
 */
public record AssetHistoryEntry(
        UUID id,
        UUID assetId,
        AssetChangeType changeType,
        String fromValue,
        String toValue,
        AssetChangeSource source,
        String sourceReference,
        String actorId,
        Instant occurredAt) {

    public AssetHistoryEntry {
        if (id == null || assetId == null || changeType == null || source == null || actorId == null
                || occurredAt == null) {
            throw new IllegalArgumentException("An asset history entry needs an id, asset, type, source, actor and time");
        }
    }
}

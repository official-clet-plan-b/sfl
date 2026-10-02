package gh.edu.clet.sfl.fleetlogistics.assets.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeSource;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetChangeType;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "asset_history", schema = "asset_visibility")
public class AssetHistoryRecord {

    @Id
    private UUID id;

    @Column(name = "asset_id", nullable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 40)
    private AssetChangeType changeType;

    @Column(name = "from_value", length = 300)
    private String fromValue;

    @Column(name = "to_value", length = 300)
    private String toValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AssetChangeSource source;

    @Column(name = "source_reference", length = 160)
    private String sourceReference;

    @Column(name = "actor_id", nullable = false, length = 160)
    private String actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AssetHistoryRecord() {
    }

    private AssetHistoryRecord(AssetHistoryEntry entry) {
        id = entry.id();
        assetId = entry.assetId();
        changeType = entry.changeType();
        fromValue = entry.fromValue();
        toValue = entry.toValue();
        source = entry.source();
        sourceReference = entry.sourceReference();
        actorId = entry.actorId();
        occurredAt = entry.occurredAt();
    }

    public static AssetHistoryRecord from(AssetHistoryEntry entry) {
        return new AssetHistoryRecord(entry);
    }

    public AssetHistoryEntry toDomain() {
        return new AssetHistoryEntry(id, assetId, changeType, fromValue, toValue, source, sourceReference, actorId,
                occurredAt);
    }
}

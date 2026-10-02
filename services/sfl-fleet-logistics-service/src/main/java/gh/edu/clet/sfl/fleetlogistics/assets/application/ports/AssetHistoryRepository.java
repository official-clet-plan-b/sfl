package gh.edu.clet.sfl.fleetlogistics.assets.application.ports;

import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import java.util.List;
import java.util.UUID;

public interface AssetHistoryRepository {

    AssetHistoryEntry save(AssetHistoryEntry entry);

    /** Newest first. */
    List<AssetHistoryEntry> findByAssetId(UUID assetId);
}

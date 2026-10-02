package gh.edu.clet.sfl.fleetlogistics.assets.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetHistoryRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetHistoryEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaAssetHistoryRepositoryAdapter implements AssetHistoryRepository {

    private final JpaAssetHistoryRepository history;

    JpaAssetHistoryRepositoryAdapter(JpaAssetHistoryRepository history) {
        this.history = history;
    }

    @Override
    public AssetHistoryEntry save(AssetHistoryEntry entry) {
        return history.save(AssetHistoryRecord.from(entry)).toDomain();
    }

    @Override
    public List<AssetHistoryEntry> findByAssetId(UUID assetId) {
        return history.findByAssetIdOrderByOccurredAtDesc(assetId).stream()
                .map(AssetHistoryRecord::toDomain).toList();
    }
}

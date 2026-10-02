package gh.edu.clet.sfl.fleetlogistics.assets.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaAssetHistoryRepository extends JpaRepository<AssetHistoryRecord, UUID> {

    List<AssetHistoryRecord> findByAssetIdOrderByOccurredAtDesc(UUID assetId);
}

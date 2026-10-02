package gh.edu.clet.sfl.fleetlogistics.assets.infrastructure.persistence;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;

interface JpaAssetReferenceRepository extends JpaRepository<AssetReferenceRecord, UUID> {

    Optional<AssetReferenceRecord> findByAssetCode(String assetCode);

    Optional<AssetReferenceRecord> findFirstByExternalReferenceIgnoreCase(String externalReference);

    List<AssetReferenceRecord> findBySiteCodeOrderByAssetCodeAsc(String siteCode);

    List<AssetReferenceRecord> findAllByOrderBySiteCodeAscAssetCodeAsc();

    List<AssetReferenceRecord> findBySiteCodeInOrderBySiteCodeAscAssetCodeAsc(Set<String> siteCodes);

    List<AssetReferenceRecord> findBySiteCodeAndLocationTypeAndLocationReferenceOrderByAssetCodeAsc(
            String siteCode, LocationType locationType, String locationReference);
}
package gh.edu.clet.sfl.fleetlogistics.assets.infrastructure.persistence;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.application.ports.AssetReferenceRepository;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;
import org.springframework.stereotype.Repository;

@Repository
class JpaAssetReferenceRepositoryAdapter implements AssetReferenceRepository {

    private final JpaAssetReferenceRepository assets;

    JpaAssetReferenceRepositoryAdapter(JpaAssetReferenceRepository assets) {
        this.assets = assets;
    }

    @Override
    public AssetReference save(AssetReference assetReference) {
        return assets.save(AssetReferenceRecord.from(assetReference)).toDomain();
    }

    @Override
    public Optional<AssetReference> findById(UUID id) {
        return assets.findById(id).map(AssetReferenceRecord::toDomain);
    }

    @Override
    public Optional<AssetReference> findByAssetCode(String assetCode) {
        return assets.findByAssetCode(normalize(assetCode)).map(AssetReferenceRecord::toDomain);
    }

    @Override
    public Optional<AssetReference> findByExternalReference(String tag) {
        return tag == null || tag.isBlank()
                ? Optional.empty()
                : assets.findFirstByExternalReferenceIgnoreCase(tag.strip()).map(AssetReferenceRecord::toDomain);
    }

    @Override
    public List<AssetReference> findAll(String siteCode) {
        return siteCode == null || siteCode.isBlank()
                ? assets.findAllByOrderBySiteCodeAscAssetCodeAsc().stream().map(AssetReferenceRecord::toDomain).toList()
                : assets.findBySiteCodeOrderByAssetCodeAsc(normalize(siteCode)).stream()
                        .map(AssetReferenceRecord::toDomain).toList();
    }

    @Override
    public List<AssetReference> findAllInScope(Set<String> siteCodes) {
        if (siteCodes == null || siteCodes.isEmpty()) {
            return List.of();
        }
        Set<String> normalised = siteCodes.stream().map(this::normalize)
                .collect(Collectors.toUnmodifiableSet());
        return assets.findBySiteCodeInOrderBySiteCodeAscAssetCodeAsc(normalised).stream()
                .map(AssetReferenceRecord::toDomain).toList();
    }

    @Override
    public List<AssetReference> findByLocation(String siteCode, LocationType locationType, String locationReference) {
        return assets.findBySiteCodeAndLocationTypeAndLocationReferenceOrderByAssetCodeAsc(
                normalize(siteCode), locationType, normalize(locationReference)).stream()
                .map(AssetReferenceRecord::toDomain).toList();
    }

    private String normalize(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
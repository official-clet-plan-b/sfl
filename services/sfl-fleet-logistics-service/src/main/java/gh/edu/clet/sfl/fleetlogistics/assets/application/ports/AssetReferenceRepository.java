package gh.edu.clet.sfl.fleetlogistics.assets.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gh.edu.clet.sfl.fleetlogistics.assets.domain.AssetReference;
import gh.edu.clet.sfl.fleetlogistics.assets.domain.LocationType;

public interface AssetReferenceRepository {

    AssetReference save(AssetReference assetReference);

    Optional<AssetReference> findById(UUID id);

    Optional<AssetReference> findByAssetCode(String assetCode);

    /** The asset carrying this physical tag, compared without regard to case. */
    Optional<AssetReference> findByExternalReference(String tag);

    List<AssetReference> findAll(String siteCode);

    /**
     * The register narrowed to a set of sites, filtered in SQL.
     *
     * <p>Added when AVAMP gained authorisation. The alternative - loading the register and filtering
     * in memory - reads every asset at every site out of the database to then discard most of them,
     * which is both wasteful and the shape of a filter somebody later removes as redundant.
     *
     * <p>An empty set returns nothing. That is the fail-closed half: an actor with no site scope
     * holds no sites, and returning everything for "no scope" is precisely the leak this closes.
     */
    List<AssetReference> findAllInScope(Set<String> siteCodes);

    List<AssetReference> findByLocation(String siteCode, LocationType locationType, String locationReference);
}
package gh.edu.clet.sfl.fleetlogistics.assets.application;

/** A tag already identifies a different asset, so it cannot be given to this one. */
public class DuplicateAssetTagException extends RuntimeException {

    public DuplicateAssetTagException(String tag, String assetCode) {
        super("Tag " + tag + " is already assigned to asset " + assetCode);
    }
}

package gh.edu.clet.sfl.fleetlogistics.assets.domain;

/** What changed on an asset, as recorded in its history. */
public enum AssetChangeType {
    REGISTERED,
    TAG_ASSIGNED,
    MOVED,
    CUSTODY_CHANGED,
    EVIDENCE_LINKED
}

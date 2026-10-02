package gh.edu.clet.sfl.fleetlogistics.fleet.domain.model;

/** Whether a vehicle can be located right now. */
public enum VehicleTrackingStatus {
    /** Its latest report is recent enough to trust as its position. */
    TRACKED,
    /** It has reported before, but not recently - the last known position may be wrong. */
    STALE,
    /** It has never reported: no tracker, or a tracker nothing has heard from. */
    UNTRACKED
}

package gh.edu.clet.sfl.fleetlogistics.fleet.domain.policy;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleTrackingStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a vehicle's last report still counts as knowing where it is.
 *
 * <p>"Has ever reported" is not the same as "can be found". A tracker that went quiet three days ago
 * is worse than none at all, because the screen would go on showing a confident last position. So a
 * report older than {@code staleAfter} is {@code STALE} - visible, but flagged - and only a vehicle
 * with no report at all is {@code UNTRACKED}.
 *
 * <p>A report stamped slightly in the future (a tracker clock running ahead) is treated as current
 * rather than rejected: the position is still the latest we have.
 */
public final class VehicleTrackingPolicy {

    private VehicleTrackingPolicy() {
    }

    public static VehicleTrackingStatus classify(Instant latestReportAt, Instant now, Duration staleAfter) {
        if (latestReportAt == null) {
            return VehicleTrackingStatus.UNTRACKED;
        }
        return Duration.between(latestReportAt, now).compareTo(staleAfter) > 0
                ? VehicleTrackingStatus.STALE
                : VehicleTrackingStatus.TRACKED;
    }
}

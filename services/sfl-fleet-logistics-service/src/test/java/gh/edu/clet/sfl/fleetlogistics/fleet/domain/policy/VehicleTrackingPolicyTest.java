package gh.edu.clet.sfl.fleetlogistics.fleet.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleTrackingStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VehicleTrackingPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);

    @Test
    void a_vehicle_that_never_reported_is_untracked() {
        assertThat(VehicleTrackingPolicy.classify(null, NOW, FIFTEEN_MINUTES))
                .isEqualTo(VehicleTrackingStatus.UNTRACKED);
    }

    @Test
    void a_recent_report_is_tracked() {
        assertThat(VehicleTrackingPolicy.classify(NOW.minusSeconds(60), NOW, FIFTEEN_MINUTES))
                .isEqualTo(VehicleTrackingStatus.TRACKED);
    }

    @Test
    void a_report_exactly_at_the_limit_is_still_tracked_and_one_second_past_it_is_stale() {
        assertThat(VehicleTrackingPolicy.classify(NOW.minus(FIFTEEN_MINUTES), NOW, FIFTEEN_MINUTES))
                .isEqualTo(VehicleTrackingStatus.TRACKED);
        assertThat(VehicleTrackingPolicy.classify(NOW.minus(FIFTEEN_MINUTES).minusSeconds(1), NOW, FIFTEEN_MINUTES))
                .isEqualTo(VehicleTrackingStatus.STALE);
    }

    @Test
    void a_tracker_that_went_quiet_days_ago_is_stale_not_tracked() {
        assertThat(VehicleTrackingPolicy.classify(NOW.minus(Duration.ofDays(3)), NOW, FIFTEEN_MINUTES))
                .isEqualTo(VehicleTrackingStatus.STALE);
    }

    @Test
    void a_report_stamped_slightly_in_the_future_counts_as_current() {
        assertThat(VehicleTrackingPolicy.classify(NOW.plusSeconds(30), NOW, FIFTEEN_MINUTES))
                .isEqualTo(VehicleTrackingStatus.TRACKED);
    }
}

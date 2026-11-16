package gh.edu.clet.sfl.fleetlogistics.fleet.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import gh.edu.clet.sfl.fleetlogistics.fleet.application.query.FleetLiveMapQueryService.Movement;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleTrackingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FleetLiveMapMotionTest {

    private static final UUID VEHICLE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-11-16T10:00:00Z");

    private static VehicleLocationSnapshot at(double lat, double lon, long secondsAfter) {
        return new VehicleLocationSnapshot(UUID.randomUUID(), VEHICLE, new SiteCode("CLET-HQ"),
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), null, T0.plusSeconds(secondsAfter), "DRIVER_APP",
                UUID.randomUUID(), null);
    }

    @Test
    void aVehicleCoveringAKilometreInAMinuteHeadingNorthIsMovingAtSixtyKph() {
        // 0.008993 degrees of latitude is ~1 km.
        var motion = FleetLiveMapQueryService.motion(
                List.of(at(5.6037, -0.1870, 0), at(5.612693, -0.1870, 60)), VehicleTrackingStatus.TRACKED);
        assertThat(motion.movement()).isEqualTo(Movement.MOVING);
        assertThat(motion.speedKph()).isCloseTo(60.0, within(1.0));
        assertThat(motion.headingDegrees()).isCloseTo(0.0, within(1.0));
    }

    @Test
    void aVehicleHeadingEastHasABearingOfNinety() {
        var motion = FleetLiveMapQueryService.motion(
                List.of(at(5.6037, -0.1870, 0), at(5.6037, -0.1770, 60)), VehicleTrackingStatus.TRACKED);
        assertThat(motion.headingDegrees()).isCloseTo(90.0, within(1.0));
    }

    @Test
    void gpsJitterOnAParkedVehicleIsIdle() {
        var motion = FleetLiveMapQueryService.motion(
                List.of(at(5.6037, -0.1870, 0), at(5.60371, -0.18701, 60)), VehicleTrackingStatus.TRACKED);
        assertThat(motion.movement()).isEqualTo(Movement.IDLE);
    }

    @Test
    void aSingleReportIsIdleAndAStaleOneIsOffline() {
        assertThat(FleetLiveMapQueryService.motion(List.of(at(5.6, -0.18, 0)), VehicleTrackingStatus.TRACKED)
                .movement()).isEqualTo(Movement.IDLE);
        assertThat(FleetLiveMapQueryService.motion(
                List.of(at(5.6037, -0.1870, 0), at(5.612693, -0.1870, 60)), VehicleTrackingStatus.STALE)
                .movement()).isEqualTo(Movement.OFFLINE);
    }
}

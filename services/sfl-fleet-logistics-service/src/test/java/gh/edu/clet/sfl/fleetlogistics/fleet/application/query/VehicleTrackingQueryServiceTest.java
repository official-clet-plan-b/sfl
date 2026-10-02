package gh.edu.clet.sfl.fleetlogistics.fleet.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gh.edu.clet.sfl.common.security.ActorContext;
import gh.edu.clet.sfl.common.security.SflRole;
import gh.edu.clet.sfl.common.security.SiteScopedPrincipal;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleLocationRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.VehicleRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.FleetAccessPolicy;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.SiteCode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Vehicle;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleLocationSnapshot;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.VehicleTrackingStatus;
import gh.edu.clet.sfl.fleetlogistics.fleet.support.FleetFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VehicleTrackingQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final SiteCode HQ = SiteCode.of("CLET-HQ");

    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final VehicleLocationRepository locations = mock(VehicleLocationRepository.class);
    private final VehicleTrackingQueryService service = new VehicleTrackingQueryService(vehicles, locations,
            new FleetAccessPolicy(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));

    private static ActorContext actor(Set<SflRole> roles) {
        return new ActorContext(new SiteScopedPrincipal("u@clet.edu.gh", "u@clet.edu.gh", roles, Set.of("CLET-HQ"),
                false), "corr-test");
    }

    private static VehicleRepository.VehicleSearchCriteria criteria() {
        return new VehicleRepository.VehicleSearchCriteria("CLET-HQ", null, null, null, null, null, null, 0, 100,
                null);
    }

    private static VehicleLocationSnapshot report(Vehicle vehicle, Instant at) {
        return new VehicleLocationSnapshot(UUID.randomUUID(), vehicle.id(), HQ, new BigDecimal("5.60370"),
                new BigDecimal("-0.18700"), null, at, "tracker-co", UUID.randomUUID(), "corr");
    }

    @Test
    void every_vehicle_on_the_page_is_classified_from_one_batched_lookup() {
        Vehicle live = FleetFixtures.vehicle(UUID.randomUUID(), "GT-1-26", HQ);
        Vehicle quiet = FleetFixtures.vehicle(UUID.randomUUID(), "GT-2-26", HQ);
        Vehicle bare = FleetFixtures.vehicle(UUID.randomUUID(), "GT-3-26", HQ);
        when(vehicles.search(any(), any())).thenReturn(
                new VehicleRepository.VehiclePage(List.of(live, quiet, bare), 0, 100, 3, 1, "x"));
        when(locations.findLatestByVehicles(anyCollection())).thenReturn(Map.of(
                live.id(), report(live, NOW.minusSeconds(120)),
                quiet.id(), report(quiet, NOW.minus(Duration.ofDays(2)))));

        VehicleTrackingQueryService.TrackingPage page =
                service.overview(criteria(), actor(Set.of(SflRole.FLEET_MANAGER)));

        assertThat(page.content()).extracting(row -> row.vehicle().id(), VehicleTrackingQueryService.VehicleTracking::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(live.id(), VehicleTrackingStatus.TRACKED),
                        org.assertj.core.groups.Tuple.tuple(quiet.id(), VehicleTrackingStatus.STALE),
                        org.assertj.core.groups.Tuple.tuple(bare.id(), VehicleTrackingStatus.UNTRACKED));
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.staleAfter()).isEqualTo(Duration.ofMinutes(15));
        verify(locations).findLatestByVehicles(List.of(live.id(), quiet.id(), bare.id()));
    }

    @Test
    void a_fleet_larger_than_thirty_is_not_truncated() {
        List<Vehicle> fleet = java.util.stream.IntStream.range(0, 45)
                .mapToObj(i -> FleetFixtures.vehicle(UUID.randomUUID(), "GT-" + i + "-26", HQ)).toList();
        when(vehicles.search(any(), any())).thenReturn(new VehicleRepository.VehiclePage(fleet, 0, 100, 45, 1, "x"));
        when(locations.findLatestByVehicles(anyCollection())).thenReturn(Map.of());

        assertThat(service.overview(criteria(), actor(Set.of(SflRole.FLEET_MANAGER))).content()).hasSize(45);
    }

    @Test
    void an_actor_without_vehicle_read_is_refused_before_anything_is_loaded() {
        assertThatThrownBy(() -> service.overview(criteria(), actor(Set.of(SflRole.IFIMP_REQUESTER))))
                .isInstanceOf(RuntimeException.class);
        verify(vehicles, never()).search(any(), any());
        verify(locations, never()).findLatestByVehicles(anyCollection());
    }
}

package gh.edu.clet.sfl.fleetlogistics.fleet.infrastructure.persistence;

import gh.edu.clet.sfl.fleetlogistics.fleet.application.port.TripRepository;
import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DateTimeRange;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link TripRepository}. */
@Component
class JpaTripRepositoryAdapter implements TripRepository {

    private final TripJpaRepository trips;

    JpaTripRepositoryAdapter(TripJpaRepository trips) {
        this.trips = trips;
    }

    @Override
    @Transactional
    public Trip save(Trip trip) {
        TripEntity entity = trips.findById(trip.id())
                .map(existing -> {
                    existing.applyFrom(trip);
                    return existing;
                })
                .orElseGet(() -> TripEntity.from(trip));
        return trips.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Trip> findById(UUID id) {
        return trips.findById(id).map(TripEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Trip> findVehicleConflicts(UUID vehicleId, DateTimeRange period, UUID excludingTripId) {
        if (vehicleId == null || period == null) {
            return List.of();
        }
        return trips.findVehicleConflicts(vehicleId, period.start(), period.end(), excludingTripId).stream()
                .map(TripEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Trip> findDriverConflicts(UUID driverId, DateTimeRange period, UUID excludingTripId) {
        if (driverId == null || period == null) {
            return List.of();
        }
        return trips.findDriverConflicts(driverId, period.start(), period.end(), excludingTripId).stream()
                .map(TripEntity::toDomain)
                .toList();
    }

    /**
     * A prefix as a LIKE pattern, or null for "no filter". The caller's text is data, not a pattern: a
     * percent or underscore in it is escaped so it matches itself rather than everything.
     */
    private static String likePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        return prefix.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Override
    @Transactional(readOnly = true)
    public TripPage search(TripSearchCriteria criteria, SiteScopeFilter scope) {
        Page<TripEntity> page = trips.search(
                scope.allSites(), scopeList(scope), normalise(criteria.siteCode()), criteria.status(),
                criteria.vehicleId(), criteria.driverId(), criteria.operatingMode(), criteria.from(),
                criteria.to(), likePrefix(criteria.purposePrefix()),
                JpaVehicleRepositoryAdapter.pageRequest(criteria.page(), criteria.size(),
                        criteria.sort() == null ? "plannedStart,desc" : criteria.sort()));

        return new TripPage(page.getContent().stream().map(TripEntity::toDomain).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages(), page.getSort().toString());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Trip> findAllInScope(SiteScopeFilter scope) {
        return trips.findAllInScope(scope.allSites(), scopeList(scope)).stream()
                .map(TripEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Trip> findLiveTripsEndingBefore(Instant threshold) {
        return trips.findLiveTripsEndingBefore(threshold).stream().map(TripEntity::toDomain).toList();
    }

    private static List<String> scopeList(SiteScopeFilter scope) {
        return scope.allSites() ? List.of("*") : List.copyOf(scope.sites());
    }

    private static String normalise(String siteCode) {
        return siteCode == null || siteCode.isBlank() ? null : siteCode.strip().toUpperCase(Locale.ROOT);
    }
}

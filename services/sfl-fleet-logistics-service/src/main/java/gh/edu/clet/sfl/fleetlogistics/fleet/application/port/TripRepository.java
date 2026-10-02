package gh.edu.clet.sfl.fleetlogistics.fleet.application.port;

import gh.edu.clet.sfl.fleetlogistics.fleet.application.service.SiteScopeFilter;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.DateTimeRange;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.OperatingMode;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.Trip;
import gh.edu.clet.sfl.fleetlogistics.fleet.domain.model.TripStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port for trips and assignments (SRS-SFL-S166-02). */
public interface TripRepository {

    Trip save(Trip trip);

    Optional<Trip> findById(UUID id);

    /**
     * Live trips holding {@code vehicleId} over a period that overlaps {@code period}.
     *
     * @param excludingTripId the trip being edited, so reassigning a trip does not conflict with itself
     */
    List<Trip> findVehicleConflicts(UUID vehicleId, DateTimeRange period, UUID excludingTripId);

    /** Live trips holding {@code driverId} over a period that overlaps {@code period}. */
    List<Trip> findDriverConflicts(UUID driverId, DateTimeRange period, UUID excludingTripId);

    TripPage search(TripSearchCriteria criteria, SiteScopeFilter scope);

    List<Trip> findAllInScope(SiteScopeFilter scope);

    /** Live trips whose planned end has passed - used by the overdue-trip indicator. */
    List<Trip> findLiveTripsEndingBefore(Instant threshold);

    record TripSearchCriteria(
            String siteCode,
            TripStatus status,
            UUID vehicleId,
            UUID driverId,
            OperatingMode operatingMode,
            Instant from,
            Instant to,
            int page,
            int size,
            String sort,
            String purposePrefix) {

        /** A search with no purpose filter - every caller that predates it. */
        public TripSearchCriteria(String siteCode, TripStatus status, UUID vehicleId, UUID driverId,
                OperatingMode operatingMode, Instant from, Instant to, int page, int size, String sort) {
            this(siteCode, status, vehicleId, driverId, operatingMode, from, to, page, size, sort, null);
        }
    }

    record TripPage(List<Trip> content, int page, int size, long totalElements, int totalPages, String sort) {
    }
}

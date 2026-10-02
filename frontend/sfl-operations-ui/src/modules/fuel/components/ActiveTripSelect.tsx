import { useMemo } from 'react';
import { TripResponse } from 'modules/fleet/api/dto';
import { tripsApi } from 'modules/fleet/api/fleetApi';
import { SelectField, type SelectOption } from 'modules/fuel/components/fuelFields';
import { useApiQuery } from 'shared/hooks/useApiQuery';

/**
 * The trips a driver could be buying fuel for.
 *
 * <h2>Why a trip picker is the identity mechanism</h2>
 *
 * <p>A fuel transaction needs a vehicle and a driver, and the old form asked for both from lists of
 * every vehicle and every driver at the site. For a driver capturing their own purchase that is three
 * questions they should never be asked: they know which journey they are on, and the journey already
 * records who is driving what.
 *
 * <p>So the trip is chosen and the rest follows. This is not only convenience - it removes the
 * possibility of attributing fuel to a colleague or a vehicle you are not driving, and it switches on
 * the TRIP_MATCH and logbook reconciliation rules that a transaction with no trip simply skips.
 *
 * <p>The narrowing is the service's, not this component's. `GET /fleet/trips` resolves the caller
 * through `DriverScopeResolver` and returns only their own trips when they hold no supervising
 * permission, so a driver's list here is already theirs. Filtering client-side would be a display
 * convention over records that had already crossed the wire.
 *
 * <h2>"Active" means what a driver would mean by it</h2>
 *
 * <p>Planned, acknowledged and in-progress: the journeys that are current or about to be. Completed
 * trips are excluded deliberately - a fuel purchase against a trip that ended last month is either a
 * mistake or the thing an investigation is looking for, and it should not be one item down a
 * dropdown. The fuel officer's form can still reach any trip.
 */

/** Trip statuses a driver can legitimately be refuelling against. */
const ACTIVE_TRIP_STATUSES = ['PLANNED', 'SCHEDULED', 'ASSIGNED', 'DISPATCHED', 'IN_PROGRESS'];

const TRIP_WINDOW = 100;

/**
 * The signed-in driver's current trips.
 *
 * <p>Exposed as a hook as well as a component because the capture form needs the chosen trip's
 * vehicle and driver, not just its id - prefilling is the entire point.
 */
export const useDriverTrips = (siteCode: string) => {
  const trips = useApiQuery(
    (signal) =>
      siteCode
        ? tripsApi.search({ siteCode, size: TRIP_WINDOW, sort: 'plannedStart,desc' }, signal)
        : Promise.resolve(undefined),
    [siteCode],
  );

  const active = useMemo(
    () => (trips.data?.content ?? []).filter((trip) => ACTIVE_TRIP_STATUSES.includes(trip.status)),
    [trips.data],
  );

  return { ...trips, active };
};

interface ActiveTripSelectProps {
  siteCode: string;
  trips: TripResponse[];
  loading: boolean;
  value: string;
  onChange: (tripId: string) => void;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  /** Lets a supervising actor record fuel with no trip attached. Never offered to a driver. */
  allowNone?: boolean;
}

export const ActiveTripSelect = ({
  siteCode,
  trips,
  loading,
  allowNone,
  helperText,
  ...rest
}: ActiveTripSelectProps) => {
  const options = useMemo<SelectOption[]>(
    () =>
      trips.map((trip) => ({
        value: trip.id,
        // The route is what a driver recognises; the trip number is what everyone else searches by.
        label: `${trip.tripNumber} · ${trip.origin} → ${trip.destination}`,
      })),
    [trips],
  );

  return (
    <SelectField
      {...rest}
      label="Trip"
      options={options}
      allowEmpty={allowNone}
      emptyLabel="No trip"
      disabled={rest.disabled || loading || (!allowNone && options.length === 0)}
      helperText={
        helperText ??
        (!siteCode
          ? 'Choose a site first.'
          : loading
            ? 'Loading your trips…'
            : options.length === 0
              ? 'You have no active trip at this site. Ask dispatch to assign one before recording fuel against it.'
              : 'Choosing the trip fills in the vehicle.')
      }
    />
  );
};

export default ActiveTripSelect;

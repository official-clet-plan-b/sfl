import { useMemo } from 'react';
import { driversApi, tripsApi, vehiclesApi } from 'modules/fleet/api/fleetApi';
import { SelectInput, type SelectOption } from 'modules/fleet/components/formFields';
import { useApiQuery } from 'shared/hooks/useApiQuery';

/**
 * Vehicle, driver and trip pickers backed by the fleet register.
 *
 * Fuel requests carry `vehicleId`, `driverId` and `tripId` as bare UUIDs, and
 * `FuelFleetReferencePort.resolve` refuses anything it cannot find. Asking an operator to paste a
 * UUID is how that refusal happens: the identifiers are not on any paperwork, and a mistyped one is
 * a round trip for a message that names a field the operator never saw. These select out of the
 * fleet registers for the same site instead, so the dashboard can only offer references that exist.
 *
 * The lists are scoped to the site the form is submitting for, and reload when it changes. A vehicle
 * or driver at a site the actor cannot read simply is not offered - the fleet search already applies
 * the actor's own scope.
 */

const REFERENCE_WINDOW = 200;

interface ReferenceSelectProps {
  siteCode: string;
  value: string;
  onChange: (value: string) => void;
  label?: string;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  allowEmpty?: boolean;
  emptyLabel?: string;
  className?: string;
}

export const VehicleSelect = ({
  siteCode,
  label = 'Vehicle',
  helperText,
  ...rest
}: ReferenceSelectProps) => {
  const vehicles = useApiQuery(
    (signal) =>
      siteCode
        ? vehiclesApi.search({ siteCode, size: REFERENCE_WINDOW }, signal)
        : Promise.resolve(undefined),
    [siteCode],
  );

  const options = useMemo<SelectOption[]>(
    () =>
      (vehicles.data?.content ?? []).map((vehicle) => ({
        value: vehicle.id,
        label: `${vehicle.registrationNumber} · ${vehicle.make} ${vehicle.model}`,
      })),
    [vehicles.data],
  );

  return (
    <SelectInput
      {...rest}
      label={label}
      options={options}
      disabled={rest.disabled || vehicles.loading || options.length === 0}
      helperText={helperText ?? emptyHint(siteCode, vehicles.loading, options.length, 'vehicles')}
    />
  );
};

export const DriverSelect = ({
  siteCode,
  label = 'Driver',
  helperText,
  ...rest
}: ReferenceSelectProps) => {
  const drivers = useApiQuery(
    (signal) =>
      siteCode
        ? driversApi.search({ siteCode, size: REFERENCE_WINDOW }, signal)
        : Promise.resolve(undefined),
    [siteCode],
  );

  const options = useMemo<SelectOption[]>(
    () =>
      (drivers.data?.content ?? []).map((driver) => ({
        value: driver.id,
        // Eligibility is shown but never used to filter: an ineligible driver is exactly the case
        // reconciliation's DRIVER_ELIGIBLE rule exists to catch, and hiding them here would hide
        // the transaction that needs recording.
        label: `${driver.displayName} · ${driver.staffReference} (${driver.eligibilityStatus.toLowerCase()})`,
      })),
    [drivers.data],
  );

  return (
    <SelectInput
      {...rest}
      label={label}
      options={options}
      disabled={rest.disabled || drivers.loading || options.length === 0}
      helperText={helperText ?? emptyHint(siteCode, drivers.loading, options.length, 'drivers')}
    />
  );
};

export const TripSelect = ({
  siteCode,
  label = 'Trip',
  helperText,
  ...rest
}: ReferenceSelectProps) => {
  const trips = useApiQuery(
    (signal) =>
      siteCode
        ? tripsApi.search({ siteCode, size: REFERENCE_WINDOW }, signal)
        : Promise.resolve(undefined),
    [siteCode],
  );

  const options = useMemo<SelectOption[]>(
    () =>
      (trips.data?.content ?? []).map((trip) => ({
        value: trip.id,
        label: `${trip.tripNumber} · ${trip.origin} → ${trip.destination}`,
      })),
    [trips.data],
  );

  // Optional by default, because a fuel transaction genuinely may not belong to a trip. Callers
  // that need one - evidence has to hang off a real record - pass `allowEmpty={false}`, and the
  // hint follows, so the field never reads "optional" while the form refuses a blank.
  const optional = rest.allowEmpty ?? true;

  return (
    <SelectInput
      {...rest}
      label={label}
      options={options}
      allowEmpty={optional}
      emptyLabel={rest.emptyLabel ?? 'No trip'}
      disabled={rest.disabled || trips.loading}
      helperText={
        helperText ??
        (optional
          ? 'Optional. Linking a trip enables the trip-match and logbook rules.'
          : emptyHint(siteCode, trips.loading, options.length, 'trips'))
      }
    />
  );
};

const emptyHint = (
  siteCode: string,
  loading: boolean,
  count: number,
  noun: string,
): string | undefined => {
  if (!siteCode) {
    return 'Choose a site first.';
  }
  if (loading) {
    return `Loading ${noun}…`;
  }
  if (count === 0) {
    return `No ${noun} are registered at this site. Register one under Fleet first.`;
  }
  return undefined;
};

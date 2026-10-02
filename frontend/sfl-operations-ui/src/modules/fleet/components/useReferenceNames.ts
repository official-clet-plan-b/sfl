import { driversApi, vehiclesApi } from 'modules/fleet/api/fleetApi';
import { useApiQuery } from 'shared/hooks/useApiQuery';

/**
 * Names for the ids a record carries.
 *
 * A trip or a workflow item holds a vehicle id and a driver id and nothing readable, so the site's
 * vehicles and drivers are fetched once and indexed, rather than once per row. A miss - a vehicle
 * outside the fetched page - is reported as undefined and the caller falls back to what it has.
 */
export const useReferenceNames = (siteCode: string) => {
  const vehicles = useApiQuery(
    (signal) =>
      vehiclesApi
        .search({ siteCode: siteCode || undefined, size: 200 }, signal)
        .then((page) => new Map(page.content.map((vehicle) => [vehicle.id, vehicle]))),
    [siteCode],
  );
  const drivers = useApiQuery(
    (signal) =>
      driversApi
        .search({ siteCode: siteCode || undefined, size: 200 }, signal)
        .then((page) => new Map(page.content.map((driver) => [driver.id, driver]))),
    [siteCode],
  );
  return {
    vehicles: vehicles.data,
    drivers: drivers.data,
    refetch: () => {
      vehicles.refetch();
      drivers.refetch();
    },
  };
};

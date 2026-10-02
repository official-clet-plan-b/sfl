import type { TrackedVehicle, TrackingStatus } from './phase2Api';

export interface TrackingSummary {
  tracked: number;
  stale: number;
  untracked: number;
}

/** How many of the listed vehicles are in each state. */
export const summariseTracking = (vehicles: TrackedVehicle[]): TrackingSummary =>
  vehicles.reduce<TrackingSummary>(
    (summary, vehicle) => {
      const key = vehicle.trackingStatus.toLowerCase() as Lowercase<TrackingStatus>;
      return { ...summary, [key]: summary[key] + 1 };
    },
    { tracked: 0, stale: 0, untracked: 0 },
  );

const UNITS: [limit: number, seconds: number, name: string][] = [
  [60, 1, 'second'],
  [3600, 60, 'minute'],
  [86400, 3600, 'hour'],
  [Infinity, 86400, 'day'],
];

/** "3 minutes ago" - how old a report is, which is what decides whether to trust the position. */
export const reportAge = (recordedAt: string | null, now: Date = new Date()): string => {
  if (!recordedAt) {
    return 'Never reported';
  }
  const elapsed = Math.max(0, Math.floor((now.getTime() - new Date(recordedAt).getTime()) / 1000));
  if (elapsed < 10) {
    return 'Just now';
  }
  const [, seconds, name] = UNITS.find(([limit]) => elapsed < limit) ?? UNITS[UNITS.length - 1];
  const count = Math.floor(elapsed / seconds);
  return `${count} ${name}${count === 1 ? '' : 's'} ago`;
};

export const formatPosition = (vehicle: TrackedVehicle): string =>
  vehicle.latitude == null || vehicle.longitude == null
    ? 'No position reported'
    : `${vehicle.latitude.toFixed(5)}, ${vehicle.longitude.toFixed(5)}`;

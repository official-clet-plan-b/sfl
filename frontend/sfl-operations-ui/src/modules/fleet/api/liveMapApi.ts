import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export type Movement = 'MOVING' | 'IDLE' | 'OFFLINE';
export type LiveTrackingStatus = 'TRACKED' | 'STALE' | 'UNTRACKED';

export interface TrailPoint {
  latitude: number;
  longitude: number;
  recordedAt: string;
}

export interface LiveTrip {
  tripId: string;
  tripNumber: string;
  origin: string;
  destination: string;
  driverId: string | null;
  driverName: string | null;
}

export interface LiveVehicle {
  vehicleId: string;
  registrationNumber: string;
  make: string;
  model: string;
  siteCode: string;
  trackingStatus: LiveTrackingStatus;
  movement: Movement;
  speedKph: number | null;
  headingDegrees: number | null;
  latitude: number | null;
  longitude: number | null;
  recordedAt: string | null;
  sourceSystem: string | null;
  trail: TrailPoint[];
  trip: LiveTrip | null;
}

export interface LiveFleetMap {
  vehicles: LiveVehicle[];
  totalVehicles: number;
  staleAfterSeconds: number;
  trailMinutes: number;
  generatedAt: string;
}

export const liveMapApi = {
  /** Every active vehicle in scope with its position, movement, recent trail and current trip. */
  live: (siteCode: string | undefined, trailMinutes: number, signal?: AbortSignal) =>
    apiClient.get<LiveFleetMap>(
      '/api/v1/fleet/vehicles/tracking/live',
      { siteCode: siteCode || undefined, trailMinutes } as QueryParams,
      signal,
    ),
};

export interface MovementSummary {
  moving: number;
  idle: number;
  offline: number;
  noPosition: number;
}

export const summariseMovement = (vehicles: LiveVehicle[]): MovementSummary =>
  vehicles.reduce<MovementSummary>(
    (summary, vehicle) => {
      if (vehicle.latitude === null || vehicle.longitude === null) {
        summary.noPosition += 1;
      } else if (vehicle.movement === 'MOVING') {
        summary.moving += 1;
      } else if (vehicle.movement === 'IDLE') {
        summary.idle += 1;
      } else {
        summary.offline += 1;
      }
      return summary;
    },
    { moving: 0, idle: 0, offline: 0, noPosition: 0 },
  );

const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

/** 0-360 degrees to a compass point, e.g. 95 -> "E". */
export const compassPoint = (degrees: number | null): string | null =>
  degrees === null ? null : COMPASS[Math.round((((degrees % 360) + 360) % 360) / 45) % 8];

/** How long ago a report was made, in words. */
export const ageOf = (recordedAt: string | null, now: number = Date.now()): string => {
  if (!recordedAt) {
    return 'Never reported';
  }
  const seconds = Math.max(0, Math.round((now - new Date(recordedAt).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.round(hours / 24)} days ago`;
};

export const MOVEMENT_LABEL: Record<Movement, string> = {
  MOVING: 'Moving',
  IDLE: 'Stopped',
  OFFLINE: 'Not reporting',
};

/** Marker colours - CLET success green, amber and grey. */
export const MOVEMENT_COLOUR: Record<Movement, string> = {
  MOVING: '#12805c',
  IDLE: '#d97706',
  OFFLINE: '#667085',
};

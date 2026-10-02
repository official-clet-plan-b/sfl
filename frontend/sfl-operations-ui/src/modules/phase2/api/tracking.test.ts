import { describe, expect, it } from 'vitest';
import type { TrackedVehicle } from './phase2Api';
import { formatPosition, reportAge, summariseTracking } from './tracking';

const vehicle = (overrides: Partial<TrackedVehicle> = {}): TrackedVehicle => ({
  vehicleId: 'v-1',
  registrationNumber: 'GT-1-26',
  make: 'Toyota',
  model: 'Hilux',
  siteCode: 'CLET-HQ',
  trackingStatus: 'TRACKED',
  latitude: 5.6037,
  longitude: -0.187,
  recordedAt: '2026-10-02T11:58:00Z',
  sourceSystem: 'TRACKER-CO',
  ...overrides,
});

describe('summariseTracking', () => {
  it('counts each state, including the ones that are absent', () => {
    const summary = summariseTracking([
      vehicle(),
      vehicle({ trackingStatus: 'STALE' }),
      vehicle({ trackingStatus: 'UNTRACKED', latitude: null, longitude: null, recordedAt: null }),
      vehicle({ trackingStatus: 'UNTRACKED', latitude: null, longitude: null, recordedAt: null }),
    ]);
    expect(summary).toEqual({ tracked: 1, stale: 1, untracked: 2 });
  });

  it('is all zeroes for an empty fleet', () => {
    expect(summariseTracking([])).toEqual({ tracked: 0, stale: 0, untracked: 0 });
  });
});

describe('reportAge', () => {
  const now = new Date('2026-10-02T12:00:00Z');

  it.each([
    ['2026-10-02T11:59:55Z', 'Just now'],
    ['2026-10-02T11:59:00Z', '1 minute ago'],
    ['2026-10-02T11:55:00Z', '5 minutes ago'],
    ['2026-10-02T11:00:00Z', '1 hour ago'],
    ['2026-10-02T07:00:00Z', '5 hours ago'],
    ['2026-10-01T12:00:00Z', '1 day ago'],
    ['2026-09-29T12:00:00Z', '3 days ago'],
  ])('reads %s as "%s"', (recordedAt, expected) => {
    expect(reportAge(recordedAt, now)).toBe(expected);
  });

  it('says so when a vehicle has never reported', () => {
    expect(reportAge(null, now)).toBe('Never reported');
  });

  it('treats a report stamped slightly in the future as current', () => {
    expect(reportAge('2026-10-02T12:00:30Z', now)).toBe('Just now');
  });
});

describe('formatPosition', () => {
  it('shows five decimal places', () => {
    expect(formatPosition(vehicle())).toBe('5.60370, -0.18700');
  });

  it('does not invent coordinates for a silent vehicle', () => {
    expect(formatPosition(vehicle({ latitude: null, longitude: null }))).toBe('No position reported');
  });

  it('shows a real position at zero latitude', () => {
    expect(formatPosition(vehicle({ latitude: 0, longitude: 0 }))).toBe('0.00000, 0.00000');
  });
});

import { useEffect, useRef, useState } from 'react';
import { tripsApi } from 'modules/fleet/api/fleetApi';

/** How often to ask the device for a fresh position and send it on, while a trip is in progress. */
const REPORT_INTERVAL_MS = 20_000;

export type LocationReportingStatus =
  | 'inactive'
  | 'active'
  | 'denied'
  | 'unsupported'
  | 'error';

/**
 * Reports the device's position to a trip, on an interval, for as long as `active` is true.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>There is no connected telematics vendor (S167, Phase 2), so a driver's own device is the only
 * source of a live position until one exists - see {@link TripRouteMap}, which reads what this writes.
 * `FLEET_TRIP_LOCATION_REPORT_OWN` is the permission; `active` is the caller's job to compute from
 * "this is my trip, and it is `IN_PROGRESS`" - this hook does not re-derive either.
 *
 * <h2>What `active` toggling off does, and does not, undo</h2>
 *
 * <p>It stops asking the device for new positions. It does not, and cannot, delete what was already
 * reported - a position already on the record is a fact about where the vehicle was, not a live value
 * to retract. Turning `active` false is simply "stop reporting now", at trip close or on leaving the
 * screen (the cleanup function), not an erase.
 *
 * <h2>Failure is local, not fatal</h2>
 *
 * <p>Permission denied, no GPS, one failed submission - none of it throws into the caller, and none of
 * it stops the trip. {@link LocationReportingStatus} is the one piece of state a screen needs to tell a
 * driver "your position is not being shared right now" rather than claiming success it cannot back up.
 */
export const useOwnLocationReporting = (tripId: string, active: boolean): LocationReportingStatus => {
  // Tracks only the outcome of an actual report attempt; whether reporting is even running right
  // now is derived below from `active`/`unsupported`, not stored here, so there is nothing to set
  // synchronously inside the effect body itself.
  const [reportOutcome, setReportOutcome] = useState<'pending' | 'active' | 'denied' | 'error'>('pending');
  // Avoids reporting a position from a request that was already in flight when the previous one
  // resolved - two overlapping submissions for the same trip is not a useful second data point.
  const reporting = useRef(false);

  const unsupported = typeof navigator === 'undefined' || !('geolocation' in navigator);

  useEffect(() => {
    if (!active || unsupported) {
      return undefined;
    }

    let cancelled = false;

    const reportOnce = () => {
      if (reporting.current) {
        return;
      }
      reporting.current = true;
      navigator.geolocation.getCurrentPosition(
        (position) => {
          reporting.current = false;
          if (cancelled) {
            return;
          }
          void tripsApi
            .reportLocation(tripId, {
              latitude: position.coords.latitude,
              longitude: position.coords.longitude,
            })
            .then(() => {
              if (!cancelled) {
                setReportOutcome('active');
              }
            })
            .catch(() => {
              // One rejected submission - a stale trip version, a momentary network drop - is not
              // reported as a hard failure; the next tick tries again on its own.
              if (!cancelled) {
                setReportOutcome('error');
              }
            });
        },
        () => {
          reporting.current = false;
          if (!cancelled) {
            setReportOutcome('denied');
          }
        },
        { enableHighAccuracy: true, timeout: 15_000, maximumAge: 10_000 },
      );
    };

    reportOnce();
    const interval = setInterval(reportOnce, REPORT_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [tripId, active, unsupported]);

  if (!active) {
    return 'inactive';
  }
  if (unsupported) {
    return 'unsupported';
  }
  return reportOutcome === 'pending' ? 'inactive' : reportOutcome;
};

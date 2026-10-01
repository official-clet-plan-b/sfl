import L from 'leaflet';
import { useEffect, useRef, useState } from 'react';
import { tripsApi } from 'modules/fleet/api/fleetApi';
import { TripResponse } from 'modules/fleet/api/dto';
import Alert from 'shared/components/Alert';
import { formatDateTime } from 'shared/components/format';
import { geocodeAddress } from 'shared/places/openStreetMapPlaces';
import { fetchDrivingRoute, Route } from 'shared/maps/osrmRouting';

/*
 * Leaflet's default marker icon is a CSS background-image path relative to its own stylesheet, which
 * a bundler does not preserve - the icon silently fails to load and a pin shows as a broken image.
 * Pointing it at the bundled asset URLs, once, module-wide, is the documented fix.
 */
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

delete (L.Icon.Default.prototype as { _getIconUrl?: unknown })._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});

/** A plain, undecorated dot for the live vehicle marker - distinct from the pin at each end of the route. */
const vehicleIcon = L.divIcon({
  className: '',
  html: '<span class="block h-4 w-4 rounded-full border-2 border-white bg-brand-500 shadow-theme-md"></span>',
  iconSize: [16, 16],
  iconAnchor: [8, 8],
});

/** How often to ask for the vehicle's latest position while a trip is in progress. */
const POLL_INTERVAL_MS = 20_000;

interface TripRouteMapProps {
  trip: TripResponse;
}

/**
 * The route and, once the trip is under way, the vehicle's live position - the screen both a dispatcher
 * and the driver see, exactly like an Uber or Bolt trip screen, and built the same deliberate way that
 * precedent is: open map tiles (OpenStreetMap), open routing (OSRM), no vendor key anywhere.
 *
 * <h2>Where the live position comes from</h2>
 *
 * <p>There is no connected telematics vendor (S167, Phase 2). The dot on this map is the assigned
 * driver's own device, reporting through {@link tripsApi.reportLocation} on this same screen - see
 * {@link TripDetailPage}'s `useOwnLocationReporting`. Once S167 exists this map changes nothing: the
 * read side already cannot tell a driver-reported position from a vendor-fed one, by design.
 *
 * <h2>Why the planned route and the live position are fetched independently</h2>
 *
 * <p>Origin and destination are free text (`VARCHAR(200)`, unchanged - see `PlaceField`'s own docblock,
 * which named this exact moment as the one to add coordinates) and have to be geocoded once, client-
 * side, on load. The vehicle's position is polled on a timer. Neither failing should take down the
 * other: a route that cannot be computed still leaves the live dot meaningful, and a stale or absent
 * position still leaves the planned route worth showing.
 */
const TripRouteMap = ({ trip }: TripRouteMapProps) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<L.Map | null>(null);
  const vehicleMarkerRef = useRef<L.Marker | null>(null);
  const routeLayerRef = useRef<L.Polyline | null>(null);

  const [route, setRoute] = useState<Route | null>(null);
  const [routeUnavailable, setRouteUnavailable] = useState(false);
  const [lastPosition, setLastPosition] = useState<{ latitude: number; longitude: number; recordedAt: string } | null>(
    null,
  );

  // The map itself: created once per mount, destroyed on unmount. Re-running this for every prop
  // change would tear down and rebuild the tile layer on every poll tick.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) {
      return undefined;
    }
    const map = L.map(containerRef.current, { scrollWheelZoom: false }).setView([5.6037, -0.187], 7);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(map);
    mapRef.current = map;
    return () => {
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // The planned route: geocode both ends once, then ask OSRM for the path between them. Re-runs only
  // if the trip's own origin/destination strings change - not on every poll tick.
  useEffect(() => {
    let cancelled = false;

    void (async () => {
      setRoute(null);
      setRouteUnavailable(false);
      const [from, to] = await Promise.all([
        geocodeAddress(trip.origin),
        geocodeAddress(trip.destination),
      ]);
      if (cancelled) {
        return;
      }
      if (!from || !to) {
        setRouteUnavailable(true);
        return;
      }
      const computed = await fetchDrivingRoute(from, to);
      if (cancelled) {
        return;
      }
      if (!computed) {
        setRouteUnavailable(true);
        return;
      }
      setRoute(computed);

      const map = mapRef.current;
      if (!map) {
        return;
      }
      L.marker([from.latitude, from.longitude]).addTo(map).bindPopup(`Origin: ${trip.origin}`);
      L.marker([to.latitude, to.longitude]).addTo(map).bindPopup(`Destination: ${trip.destination}`);
      const line = L.polyline(
        computed.points.map((point) => [point.latitude, point.longitude]),
        { color: '#465fff', weight: 4 },
      ).addTo(map);
      routeLayerRef.current = line;
      map.fitBounds(line.getBounds(), { padding: [32, 32] });
    })();

    return () => {
      cancelled = true;
    };
  }, [trip.origin, trip.destination]);

  // The live position: only while the trip is actually in progress - before or after, there is
  // nothing for a device to be reporting.
  useEffect(() => {
    if (trip.status !== 'IN_PROGRESS') {
      return undefined;
    }
    let cancelled = false;

    const poll = async () => {
      const location = await tripsApi.latestLocation(trip.id).catch(() => null);
      // A projection row can carry a null coordinate (nothing in this feature produces one, but the
      // response type is shared with the vendor-telematics projection, which guards against malformed
      // vendor payloads this way) - treated the same as no position reported yet.
      if (cancelled || !location || location.latitude === null || location.longitude === null) {
        return;
      }
      setLastPosition({
        latitude: location.latitude,
        longitude: location.longitude,
        recordedAt: location.recordedAt,
      });

      const map = mapRef.current;
      if (!map) {
        return;
      }
      const position: [number, number] = [location.latitude, location.longitude];
      if (vehicleMarkerRef.current) {
        vehicleMarkerRef.current.setLatLng(position);
      } else {
        vehicleMarkerRef.current = L.marker(position, { icon: vehicleIcon, zIndexOffset: 1000 }).addTo(map);
      }
    };

    void poll();
    const interval = setInterval(() => void poll(), POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [trip.id, trip.status]);

  return (
    <div className="space-y-3">
      {trip.status === 'PLANNED' && (
        <Alert variant="info">A route will show once a vehicle and driver are assigned.</Alert>
      )}
      {routeUnavailable && (
        <Alert variant="warning">
          The planned route could not be drawn right now. This does not affect the trip itself.
        </Alert>
      )}
      <div ref={containerRef} className="h-80 w-full overflow-hidden rounded-xl border border-gray-200" />
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-theme-xs text-gray-500">
        {route && (
          <span>
            Planned route: {(route.distanceMetres / 1000).toFixed(1)} km,
            {' '}
            {Math.round(route.durationSeconds / 60)} min
          </span>
        )}
        {trip.status === 'IN_PROGRESS' && (
          <span>
            {lastPosition
              ? `Last position reported ${formatDateTime(lastPosition.recordedAt)}`
              : 'No position reported yet.'}
          </span>
        )}
      </div>
    </div>
  );
};

export default TripRouteMap;

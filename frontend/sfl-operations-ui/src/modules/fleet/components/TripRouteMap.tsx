import L from 'leaflet';
import { useEffect, useRef, useState } from 'react';
import { tripsApi } from 'modules/fleet/api/fleetApi';
import { TripResponse } from 'modules/fleet/api/dto';
import { Banner } from '@rfdtech/components';
import { formatDateTime } from 'shared/components/format';
import { geocodeAddress } from 'shared/places/openStreetMapPlaces';
import { fetchDrivingRoute, Route, RoutePoint } from 'shared/maps/osrmRouting';

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
 * Places typed by people are often more specific than the map knows ("GSL campus, Makola"). Try the
 * full text first, then drop the most specific part each time ("Makola"), so the route is drawn to
 * the right area instead of not at all.
 */
/**
 * CLET's own sites, by the names staff type for them. The public map does not know "GSL campus" or
 * "CLET HQ", and dropping to the next part ("Makola") lands on the wrong side of Accra - so these
 * resolve here, before the map is asked.
 */
const GHANA_SCHOOL_OF_LAW: RoutePoint = { latitude: 5.5466313, longitude: -0.2055789 };
const KNOWN_PLACES: Array<[RegExp, RoutePoint]> = [
  [/^\s*(gsl|clet)\b/i, GHANA_SCHOOL_OF_LAW],
  [/^\s*ghana school of law\b/i, GHANA_SCHOOL_OF_LAW],
];

export const knownPlace = (address: string): RoutePoint | null =>
  KNOWN_PLACES.find(([pattern]) => pattern.test(address))?.[1] ?? null;

const geocodeLoosely = async (address: string): Promise<RoutePoint | null> => {
  const known = knownPlace(address);
  if (known) {
    return known;
  }
  const parts = address.split(',').map((part) => part.trim()).filter(Boolean);
  for (let start = 0; start < parts.length; start += 1) {
    const found = await geocodeAddress(parts.slice(start).join(', ')).catch(() => null);
    if (found) {
      return found;
    }
  }
  return null;
};

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
  const [lastPosition, setLastPosition] = useState<{
    latitude: number;
    longitude: number;
    recordedAt: string;
  } | null>(null);

  // The map itself: created once per mount, destroyed on unmount. Re-running this for every prop
  // change would tear down and rebuild the tile layer on every poll tick.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) {
      return undefined;
    }
    // City zoom over Accra, where every CLET site is - not the whole of West Africa.
    const map = L.map(containerRef.current, { scrollWheelZoom: false }).setView(
      [5.6037, -0.187],
      12,
    );
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
        geocodeLoosely(trip.origin),
        geocodeLoosely(trip.destination),
      ]);
      if (cancelled) {
        return;
      }
      const computed = from && to ? await fetchDrivingRoute(from, to) : null;
      if (cancelled) {
        return;
      }
      const map = mapRef.current;
      if (!map) {
        return;
      }
      // Pin whatever was found. A missing road route is not an error the operator can act on, so it
      // is never shown as one: the ends are pinned, joined by a dashed line when both are known.
      if (from) {
        L.marker([from.latitude, from.longitude]).addTo(map).bindPopup(`Origin: ${trip.origin}`);
      }
      if (to) {
        L.marker([to.latitude, to.longitude]).addTo(map).bindPopup(`Destination: ${trip.destination}`);
      }
      if (computed) {
        setRoute(computed);
        const line = L.polyline(
          computed.points.map((point) => [point.latitude, point.longitude]),
          { color: '#465fff', weight: 4 },
        ).addTo(map);
        routeLayerRef.current = line;
        map.fitBounds(line.getBounds(), { padding: [32, 32] });
        return;
      }
      setRouteUnavailable(true);
      if (from && to) {
        const line = L.polyline(
          [
            [from.latitude, from.longitude],
            [to.latitude, to.longitude],
          ],
          { color: '#465fff', weight: 3, dashArray: '6 8', opacity: 0.7 },
        ).addTo(map);
        routeLayerRef.current = line;
        map.fitBounds(line.getBounds(), { padding: [48, 48] });
      } else if (from || to) {
        const only = (from ?? to) as RoutePoint;
        map.setView([only.latitude, only.longitude], 14);
      }
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
        // First position and no route drawn to frame the map: show the vehicle up close.
        if (!routeLayerRef.current) {
          map.setView(position, 14);
        }
        vehicleMarkerRef.current = L.marker(position, {
          icon: vehicleIcon,
          zIndexOffset: 1000,
        }).addTo(map);
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
        <Banner
          variant="info"
          heading={<>A route will show once a vehicle and driver are assigned.</>}
        />
      )}
      <div
        ref={containerRef}
        className="h-80 w-full overflow-hidden rounded-xl border border-gray-200"
      />
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-theme-xs text-gray-500">
        {routeUnavailable && <span>Pins show the start and end of the trip.</span>}
        {route && (
          <span>
            Planned route: {(route.distanceMetres / 1000).toFixed(1)} km,{' '}
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

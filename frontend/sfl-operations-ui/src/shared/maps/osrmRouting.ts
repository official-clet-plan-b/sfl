/**
 * Driving routes over OSRM's public demo server - open source, no key, same posture as Nominatim.
 *
 * <h2>The same production caveat as the places module</h2>
 *
 * <p>`router.project-osrm.org` is free, keyless and meant for evaluation and light use, not for
 * scaled production traffic - there is no published rate limit the way Nominatim states one, but the
 * expectation is the same. For CLET's own volume (a handful of active trips at a time, each polled a
 * few times a minute) this is well within reasonable use. Moving to a self-hosted OSRM instance or a
 * paid OSM-based routing provider is a one-line change: nothing outside this file knows which server
 * answered.
 */

export interface RoutePoint {
  latitude: number;
  longitude: number;
}

export interface Route {
  /** The path, in order, for drawing a polyline. */
  points: RoutePoint[];
  /** Metres. */
  distanceMetres: number;
  /** Seconds. */
  durationSeconds: number;
}

interface OsrmResponse {
  code?: string;
  routes?: Array<{
    distance?: number;
    duration?: number;
    geometry?: { coordinates?: [number, number][] };
  }>;
}

const ENDPOINT = 'https://router.project-osrm.org/route/v1/driving';

/**
 * The driving route between two points, or `null` if none could be computed.
 *
 * <p>Never throws: a route is a nice-to-have overlay on a live map, not something a trip screen
 * should break over. A caller that gets `null` still has the origin/destination markers and the live
 * position to show.
 */
export const fetchDrivingRoute = async (from: RoutePoint, to: RoutePoint): Promise<Route | null> => {
  const coordinates = `${from.longitude},${from.latitude};${to.longitude},${to.latitude}`;
  const url = `${ENDPOINT}/${coordinates}?overview=full&geometries=geojson`;

  try {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    if (!response.ok) {
      return null;
    }
    const body = (await response.json()) as OsrmResponse;
    const route = body.routes?.[0];
    const coords = route?.geometry?.coordinates;
    if (!route || !coords || coords.length === 0) {
      return null;
    }
    return {
      // GeoJSON orders a coordinate pair as [longitude, latitude] - the opposite of this module's own
      // RoutePoint and of Leaflet's [lat, lng] - so every pair is flipped here, once, rather than at
      // every call site that draws one.
      points: coords.map(([longitude, latitude]) => ({ latitude, longitude })),
      distanceMetres: route.distance ?? 0,
      durationSeconds: route.duration ?? 0,
    };
  } catch {
    return null;
  }
};

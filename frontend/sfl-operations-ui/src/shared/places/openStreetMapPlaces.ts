/**
 * Place suggestions over OpenStreetMap's Nominatim search, replacing Google Places.
 *
 * <h2>Why this replaced Google Places rather than fixing the key</h2>
 *
 * <p>No `VITE_GOOGLE_MAPS_API_KEY` has ever been set in either tracked env file - `.env.production`
 * even carries its own scar tissue about it (a stale empty assignment that used to override a real key
 * with nothing, fixed by commenting the line out rather than setting it). The feature has therefore
 * always run in its documented fallback: free text with recent-value suggestions, silently, for every
 * environment that has ever built this dashboard. Nominatim needs no key at all, which does not just
 * swap the provider - it removes the whole class of "forgot to provision a key" failure this was in.
 *
 * <h2>Same contract as the module this replaces</h2>
 *
 * <p>`PlaceField.tsx` is unchanged: same `PlaceSuggestion`/`PlaceLookup` shapes, same
 * `placesConfigured`/`newSessionToken`/`fetchPlaceSuggestions` exports. `placesConfigured` now always
 * answers `true` - there is nothing left to be unconfigured - and `newSessionToken` is a no-op:
 * Nominatim has no per-session billing, so there is nothing to spend or renew. The caller still awaits
 * it and still passes the result through unchanged, which costs nothing and keeps this swap to one file
 * plus its import.
 *
 * <h2>The usage policy this must not violate</h2>
 *
 * <p>The public instance (`nominatim.openstreetmap.org`) is free and keyless, and its policy is
 * correspondingly strict: at most one request per second, and a way to identify the calling application
 * (the policy asks for a `User-Agent` or a valid `Referer`). A browser `fetch()` cannot set `User-Agent`
 * - it is a forbidden header - so this relies on the `Referer` the browser sends by default; nothing
 * here may set `referrerPolicy: 'no-referrer'`. `nextAllowedAt` below is the one-request-per-second
 * guard, kept in this module rather than trusted to the 300ms debounce already in `PlaceField.tsx`:
 * continuous typing past that debounce can still exceed one request a second, and a second field open
 * in another tab shares nothing with the first's timer.
 *
 * <p>This public instance is the right choice for CLET's own traffic (a handful of staff typing
 * addresses) and the wrong one to scale past without changing anything here: self-hosting Nominatim, or
 * pointing `ENDPOINT` at a paid OSM-based provider (Geoapify, LocationIQ, MapTiler), is a one-line
 * change precisely because the contract above never leaked which provider answers it.
 */

export interface PlaceSuggestion {
  placeId: string;
  /** The full formatted description - what is stored. */
  description: string;
  /** The distinctive part, shown first. */
  primary: string;
  /** The disambiguating part - town, region - shown quieter. */
  secondary: string;
  /**
   * Resolved coordinates, kept (unlike the Google Places module this replaces) because the route-
   * tracking screens need them: a trip's origin/destination are free text (`VARCHAR(200)`, unchanged),
   * but drawing a planned route needs somewhere to start and end that is not a string.
   */
  latitude: number;
  longitude: number;
}

export interface PlaceLookup {
  suggestions: PlaceSuggestion[];
  /** A short, actionable reason the lookup could not run. Null when it ran. */
  unavailable: string | null;
}

const ENDPOINT = 'https://nominatim.openstreetmap.org/search';

/** Always true: there is no key to be missing. Kept so `PlaceField.tsx` needed no change at all. */
export const placesConfigured = (): boolean => true;

/** No-op: Nominatim has no session-billing concept for `PlaceField.tsx` to spend. */
export const newSessionToken = async (): Promise<unknown | null> => null;

/** Logged in full exactly once, same reasoning as the module this replaces. */
let reported = false;
const reportOnce = (reason: string, detail?: unknown) => {
  if (reported) {
    return;
  }
  reported = true;
  console.warn(`[places] Address suggestions are unavailable: ${reason}`, detail ?? '');
};

/** The one-request-per-second floor the public instance's usage policy requires. */
let nextAllowedAt = 0;

interface NominatimResult {
  place_id?: number;
  display_name?: string;
  lat?: string;
  lon?: string;
}

/**
 * The one request Nominatim ever receives from this module - suggestions-while-typing and a one-shot
 * geocode both call this, so the one-request-per-second floor and the error handling exist in one
 * place rather than two copies that could drift.
 */
const lookup = async (query: string, limit: number): Promise<PlaceLookup> => {
  const now = Date.now();
  if (now < nextAllowedAt) {
    // Asked again too soon after the last call. Refusing locally - rather than sending it and letting
    // Nominatim reject it - is what keeps this module within the usage policy even when a caller's own
    // debounce does not: see the module docblock.
    return { suggestions: [], unavailable: null };
  }
  nextAllowedAt = now + 1000;

  const url = new URL(ENDPOINT);
  url.searchParams.set('q', query);
  url.searchParams.set('format', 'jsonv2');
  url.searchParams.set('countrycodes', 'gh');
  url.searchParams.set('addressdetails', '0');
  url.searchParams.set('limit', String(limit));

  try {
    const response = await fetch(url, {
      headers: { Accept: 'application/json' },
      // Default referrer policy, deliberately left alone: it is the identification Nominatim's usage
      // policy asks for in place of a User-Agent a browser will not let this code set.
    });
    if (response.status === 429) {
      const reason = 'the suggestion service is rate-limited right now';
      reportOnce(reason);
      return { suggestions: [], unavailable: reason };
    }
    if (!response.ok) {
      const reason = `the suggestion service answered with an error (${response.status})`;
      reportOnce(reason);
      return { suggestions: [], unavailable: reason };
    }
    const results = (await response.json()) as NominatimResult[];
    return {
      suggestions: results
        .filter((result) => result.display_name && result.lat && result.lon)
        .map((result) => {
          const description = result.display_name as string;
          const commaIndex = description.indexOf(',');
          return {
            placeId: result.place_id ? String(result.place_id) : description,
            description,
            primary: commaIndex === -1 ? description : description.slice(0, commaIndex),
            secondary: commaIndex === -1 ? '' : description.slice(commaIndex + 1).trim(),
            latitude: Number.parseFloat(result.lat as string),
            longitude: Number.parseFloat(result.lon as string),
          };
        }),
      unavailable: null,
    };
  } catch (error) {
    const reason = 'the suggestion service could not be reached';
    reportOnce(reason, error);
    return { suggestions: [], unavailable: reason };
  }
};

/**
 * Suggestions for what has been typed so far.
 *
 * <p>Biased to Ghana (`countrycodes=gh`), matching the Google Places module this replaces - CLET
 * operates four examination centres and a headquarters, all domestic. Returns an empty list rather
 * than throwing for every failure, so a form never breaks because a suggestion service did.
 */
export const fetchPlaceSuggestions = async (
  input: string,
  _sessionToken?: unknown,
): Promise<PlaceLookup> => {
  const query = input.trim();
  if (query.length < 3) {
    // Below three characters the suggestions are noise, and every request counts against the rate floor.
    return { suggestions: [], unavailable: null };
  }
  return lookup(query, 5);
};

/**
 * Resolves one already-chosen address - a trip's `origin` or `destination` - to coordinates.
 *
 * <p>Not the interactive suggestions path: this is for a route map asking "where is this place",
 * once, for a string a driver or dispatcher already committed to. Shares {@link lookup}'s throttle, so
 * a screen that geocodes both ends of a trip on load does not itself exceed the usage policy.
 */
export const geocodeAddress = async (address: string): Promise<PlaceSuggestion | null> => {
  const query = address.trim();
  if (query.length === 0) {
    return null;
  }
  const { suggestions } = await lookup(query, 1);
  return suggestions[0] ?? null;
};

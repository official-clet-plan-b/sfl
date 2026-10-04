/**
 * The signed-in session: the token, who it says you are, and where it is kept.
 *
 * <h2>Why this file exists at all</h2>
 *
 * A1 turned authentication on across every service - the resource server, the JWT actor resolvers,
 * the realm, the 403-versus-401 distinction. **None of it was reachable from the browser.** The API
 * client sent `X-SFL-User`, `X-SFL-Roles` and `X-SFL-Sites` and no `Authorization` header at any
 * point, so the dashboard worked against a service running with `SFL_SECURITY_ENABLED=false` and
 * received a blanket 401 from one running with the default. This is the missing half.
 *
 * <h2>Why the identity comes out of the token and not out of a form</h2>
 *
 * The obvious shortcut is to keep sending the `X-SFL-*` headers and just gate the UI behind a login
 * screen. That would be theatre of exactly the kind ADR 0007 refuses: the headers are caller-supplied,
 * so a "signed-in driver" could still assert `SFL_ADMIN` by editing local storage. The roles and site
 * scopes below are read from the **token's own claims** - `realm_access.roles` and `site_scopes`,
 * the same two the services read - so what the sidebar believes and what the service enforces come
 * from one signed source.
 *
 * <h2>Where the token is kept, and the honest tradeoff</h2>
 *
 * `sessionStorage`, not `localStorage` and not a cookie.
 *
 * - Against `localStorage`: the session dies with the tab, which is the correct default for a shared
 *   operations workstation where the previous user walked away.
 * - Against an `HttpOnly` cookie, which would genuinely be safer against XSS: that needs the token
 *   issued and refreshed by a backend-for-frontend this platform does not have, and a same-site
 *   cookie does not survive the dashboard being served from 8093 while talking to 8092. Choosing
 *   `sessionStorage` is choosing to accept that a successful XSS can read the token.
 *
 * That is a real risk and it is written down rather than glossed: the mitigation is that the token is
 * short-lived, the refresh token is held in memory only for the tab's lifetime, and every service
 * authorises every call independently.
 */

const STORAGE_KEY = 'sfl.session.v1';

export interface SflSession {
  accessToken: string;
  refreshToken: string | null;
  /** Seconds since the epoch, from the token's own `exp`. */
  expiresAt: number;
  username: string;
  displayName: string;
  email: string | null;
  roles: string[];
  siteScopes: string[];
}

interface OidcClaims {
  sub?: string;
  exp?: number;
  preferred_username?: string;
  name?: string;
  given_name?: string;
  family_name?: string;
  email?: string;
  realm_access?: { roles?: string[] };
  roles?: string[];
  'urn:zitadel:iam:org:project:roles'?: Record<string, unknown>;
  [claim: string]: unknown;
  site_scopes?: string[] | string;
}

/**
 * Reads a JWT payload without verifying it.
 *
 * **Deliberately unverified, and that is not a shortcut.** Verification is the service's job and it
 * does it on every call against the realm's public keys. A browser cannot verify a signature in any
 * meaningful sense - it would be checking a token it was handed against a key it was also handed.
 * What this is for is deciding which nav items to draw, and a tampered token buys nothing: the
 * services refuse it.
 */
export const decodeClaims = (token: string): OidcClaims | null => {
  const payload = token.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    // base64url → base64, then decode as UTF-8: names carry accents and `atob` alone mangles them.
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as OidcClaims;
  } catch {
    return null;
  }
};

/** Site scopes arrive as a list or as one comma-separated string, depending on the mapper. */
const parseScopes = (value: string[] | string | undefined): string[] => {
  if (Array.isArray(value)) {
    return value.flatMap((entry) => entry.split(',')).map((entry) => entry.trim()).filter(Boolean);
  }
  if (typeof value === 'string') {
    return value.split(',').map((entry) => entry.trim()).filter(Boolean);
  }
  return [];
};

const parseRoles = (claims: OidcClaims): string[] => {
  if (claims.realm_access?.roles) {
    return claims.realm_access.roles;
  }
  if (claims.roles) {
    return claims.roles;
  }
  return Object.keys(claims['urn:zitadel:iam:org:project:roles'] ?? {});
};

export const sessionFromTokens = (
  accessToken: string,
  refreshToken: string | null,
): SflSession | null => {
  const claims = decodeClaims(accessToken);
  if (!claims || !claims.exp) {
    return null;
  }
  const username = claims.preferred_username ?? claims.sub ?? 'unknown';
  const fullName =
    claims.name ??
    [claims.given_name, claims.family_name].filter(Boolean).join(' ').trim() ??
    '';
  return {
    accessToken,
    refreshToken,
    expiresAt: claims.exp,
    username,
    displayName: fullName || username,
    email: claims.email ?? null,
    roles: parseRoles(claims),
    siteScopes: parseScopes(claims.site_scopes),
  };
};

/** A minute of headroom, so a call is never sent with a token that expires mid-flight. */
const EXPIRY_SKEW_SECONDS = 60;

export const isExpired = (session: SflSession, now = Date.now()): boolean =>
  session.expiresAt - EXPIRY_SKEW_SECONDS <= Math.floor(now / 1000);

let current: SflSession | null = null;
let loaded = false;

/**
 * The session for this tab, or null.
 *
 * An expired session is treated as absent **and cleared**, so a stale token cannot sit in storage
 * producing 401s that look like a permissions problem.
 */
export const readSession = (): SflSession | null => {
  if (!loaded) {
    loaded = true;
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      current = raw ? (JSON.parse(raw) as SflSession) : null;
    } catch {
      current = null;
    }
  }
  if (current && isExpired(current)) {
    clearSession();
    return null;
  }
  return current;
};

export const writeSession = (session: SflSession): void => {
  current = session;
  loaded = true;
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Private browsing can refuse storage. The session still works for this page load; it simply
    // will not survive a refresh, which is better than refusing to sign in at all.
  }
};

export const clearSession = (): void => {
  current = null;
  loaded = true;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing to do - see writeSession.
  }
};

export const isSignedIn = (): boolean => readSession() !== null;

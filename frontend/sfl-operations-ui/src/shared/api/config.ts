import { readSession } from 'shared/auth/session';
import { readActorOverride } from 'shared/dev/actorOverride';

/**
 * Runtime configuration for the SFL API clients.
 *
 * In local development the services accept the `X-SFL-*` actor headers (see
 * `FleetActorResolver`); in production the same `ActorContext` is built from the OIDC/JWT
 * principal instead and these headers are ignored. Everything is env-driven so no environment
 * detail is compiled into a component.
 */

/**
 * The development actor override, when one is set.
 *
 * Read once, at module scope, because everything derived from the actor is also computed once at
 * module scope - see `shared/dev/actorOverride.ts` for why applying an override reloads the page.
 * A blank field falls through to the environment default rather than sending an empty header.
 */
const actorOverride = readActorOverride();

const readEnv = (key: string, fallback: string): string => {
  const value = import.meta.env[key as keyof ImportMetaEnv] as string | undefined;
  return value === undefined || value === '' ? fallback : value;
};

/** Distinguishes "not set" from "deliberately empty", which is how same-origin is requested. */
const readOptionalEnv = (key: string, fallback: string): string => {
  const value = import.meta.env[key as keyof ImportMetaEnv] as string | undefined;
  return value === undefined ? fallback : value;
};

export interface SflActorConfig {
  user: string;
  displayName: string;
  roles: string;
  sites: string;
  sourceChannel: string;
}

/**
 * Base URL of the Fleet service.
 *
 * An empty value means same origin - which is what the embedded build uses, because the Spring Boot
 * service serves both the API and this dashboard. `npm run dev` points at `http://localhost:8093`
 * instead, and the service allows `http://localhost:5005` as a CORS origin.
 */
export const fleetApiBaseUrl = readOptionalEnv('VITE_FLEET_API_BASE_URL', 'http://localhost:8093');

/**
 * Base URL of the Safety, Security & Emergency service (SSEMP).
 *
 * `sfl-safety-security-service`, port **8092**. It serves `/api/v1/emergency/**` for S174 and will
 * serve S160-S163 from the same origin as they are built - one base URL for a whole programme
 * rather than for one system.
 *
 * S174 was its own deployable on 8095 until the platform was consolidated to three services. The
 * API paths did not move, so nothing in `emergencyApi.ts` changed; only the origin did. That is the
 * property worth preserving in any future consolidation - a path is a contract, a port is a
 * deployment detail.
 *
 * The service allows `http://localhost:8093` (the bundled dashboard's origin) and
 * `http://localhost:5005` (`npm run dev`), so both work over CORS without a proxy. Behind a
 * gateway this becomes a same-origin path prefix and nothing else changes.
 */
export const safetySecurityApiBaseUrl = readOptionalEnv(
  'VITE_SAFETY_SECURITY_API_BASE_URL',
  'http://localhost:8092',
);

/**
 * Base URL of the Facilities service.
 *
 * S152 CAFM/IWMS is the third service the dashboard talks to - `sfl-facilities-service`, port 8091,
 * the `facilities` schema and its own permission matrix. It is also the IFIMP host: S153 maintenance
 * and S159 room booking will arrive in the same service behind this same origin, so this is one base
 * URL for a whole programme rather than for one system.
 *
 * The facilities service allows `http://localhost:8093` (the bundled dashboard's origin) and
 * `http://localhost:5005` (`npm run dev`) as CORS origins. Both had to be added: its default list was
 * written before this dashboard existed and allowed neither, so every call would have failed in a
 * browser while working perfectly in curl.
 */
export const facilitiesApiBaseUrl = readOptionalEnv(
  'VITE_FACILITIES_API_BASE_URL',
  'http://localhost:8091',
);

/*
  `defaultRoles` used to live here: a seven-role actor the bundle fell back to when `VITE_SFL_ROLES`
  was unset. It has been removed rather than emptied, because the fallback itself was the problem -
  an unauthenticated browser inherited a fleet manager's entitlement from a constant in the source,
  and the sidebar it produced looked exactly like a signed-in one. The dev server on 5005 gets its
  roles from `.env`; everything else gets them from the session.
*/

/**
 * Where the realm lives, and which client the dashboard signs in as.
 *
 * The issuer is the only provider-specific setting. The authentication client uses its OIDC discovery
 * document to resolve authorization, token and logout endpoints, so the same bundle can use Zitadel,
 * Keycloak, or another conforming provider without changing application code.
 */
export const iamIssuer = readEnv('VITE_SFL_IAM_ISSUER', 'http://localhost:8080/realms/sfl');
export const iamClientId = readEnv('VITE_SFL_IAM_CLIENT_ID', 'sfl-operations-ui');


/**
 * The actor, from the strongest source available.
 *
 * Three sources in a deliberate order, and the order is the whole point:
 *
 * 1. **The signed-in session**, when there is one. Roles and site scopes come from the token's own
 *    claims - the same `realm_access.roles` and `site_scopes` the services read - so the sidebar and
 *    the service cannot disagree about who you are.
 * 2. **The development actor switcher**, for header-based local work with security off.
 * 3. **The environment**, which is the fallback that has always been here.
 *
 * The headers are still sent in every case, and that is not redundant: with
 * `SFL_SECURITY_ENABLED=false` they are the only identity there is, and with security on the
 * services ignore them entirely in favour of the JWT. Sending both means one build works against
 * either, and there is no mode where the headers can *override* a token - the resolver prefers the
 * verified principal, which is what makes this safe rather than merely convenient.
 */
const sessionActor = (): SflActorConfig | null => {
  const session = readSession();
  if (!session) {
    return null;
  }
  return {
    user: session.username,
    displayName: session.displayName,
    roles: session.roles.join(','),
    sites: session.siteScopes.join(',') || 'CLET-HQ',
    sourceChannel: 'WEB',
  };
};

/**
 * Who this browser is acting as.
 *
 * <h2>A session, or nobody</h2>
 *
 * <p>The build-time fallback below is a **development** convenience and nothing more. It predates
 * sign-in, and while it stood in production builds it meant an unauthenticated browser still had an
 * actor - a fleet operator holding seven roles from a constant in the source - so the dashboard rendered a
 * fleet manager's console for somebody who had not signed in. The services would refuse the calls,
 * but the screens were there and the roles came from a file rather than from a person.
 *
 * <p>So it applies only where a developer needs it: the Vite dev server on 5005, where `.env`
 * supplies the values. Production builds no longer carry them (`.env.production`), and without a
 * session the actor holds no roles and no sites - entitled to nothing, offered nothing, and sent to
 * sign-in by `RequireSession` before any of that is visible.
 */
const developmentFallbackActor = (): SflActorConfig => ({
  user: actorOverride?.user || readEnv('VITE_SFL_USER', ''),
  displayName: actorOverride?.displayName || readEnv('VITE_SFL_DISPLAY_NAME', ''),
  roles: actorOverride?.roles || readEnv('VITE_SFL_ROLES', ''),
  sites: actorOverride?.sites || readEnv('VITE_SFL_SITES', ''),
  sourceChannel: 'WEB',
});

export const sflActor: SflActorConfig = sessionActor() ?? developmentFallbackActor();

/**
 * Development fallback switch.
 *
 * Kept `false` by default and named explicitly: no screen may present mock data as if it came from
 * the service. See `docs/fleet/S166_UI_Gap_Report.md` for the endpoints this affects.
 */
export const useDevelopmentFallback =
  readEnv('VITE_FLEET_DEV_FALLBACK', 'false').toLowerCase() === 'true';

/** Default page size for register tables. */
export const defaultPageSize = 25;

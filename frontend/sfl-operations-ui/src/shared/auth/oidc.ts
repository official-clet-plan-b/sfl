import { iamClientId, iamIssuer } from 'shared/api/config';
import { SflSession, clearSession, readSession, sessionFromTokens, writeSession } from './session';

/**
 * Signing in against the realm.
 *
 * <h2>Why the password grant rather than a redirect</h2>
 *
 * The configured client must permit the password grant for this current in-app login form. Endpoint
 * URLs come from OIDC discovery, so the provider can be changed by configuration.
 *
 * It is worth being clear that this is **not** the flow to ship to production. The OAuth working
 * group deprecates the resource-owner password grant for public clients, for a reason that applies
 * here: the dashboard handles the user's actual password, so it cannot support multi-factor,
 * step-up, or an external identity provider, and every one of those is a plausible CLET requirement.
 * Authorization Code with PKCE is the flow that replaces it, and it needs a redirect URI, a callback
 * route and PKCE state - none of which exists yet.
 *
 * The grant is used here because the realm already enables it and it makes A1's authentication
 * reachable from a browser today. `docs/frontend/` records the migration to PKCE as owed work rather
 * than pretending this is finished.
 *
 * <h2>Errors say which of the two things went wrong</h2>
 *
 * "Cannot sign in" covers two completely different situations - the credentials are wrong, or the
 * identity provider is not running - and on a developer laptop it is nearly always the second. They
 * are reported separately, because telling somebody their password is wrong when the provider is simply
 * down sends them to reset a password that was fine.
 */

const TIMEOUT_MS = 15_000;

export type SignInFailure =
  | { reason: 'credentials'; message: string }
  | { reason: 'unreachable'; message: string }
  | { reason: 'disabled'; message: string }
  | { reason: 'unexpected'; message: string };

export type SignInResult = { ok: true; session: SflSession } | ({ ok: false } & SignInFailure);

interface OidcDiscovery {
  token_endpoint?: string;
  end_session_endpoint?: string;
  revocation_endpoint?: string;
}

let discoveryPromise: Promise<OidcDiscovery> | null = null;

const discover = async (): Promise<OidcDiscovery> => {
  if (!discoveryPromise) {
    discoveryPromise = fetch(`${iamIssuer.replace(/\/$/, '')}/.well-known/openid-configuration`)
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`OIDC discovery failed with HTTP ${response.status}`);
        }
        return (await response.json()) as OidcDiscovery;
      })
      .catch((error) => {
        discoveryPromise = null;
        throw error;
      });
  }
  return discoveryPromise;
};

const form = (fields: Record<string, string>): string =>
  Object.entries(fields)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');

interface TokenResponse {
  access_token?: string;
  refresh_token?: string;
  error?: string;
  error_description?: string;
}

/**
 * Exchanges an email and password for a session.
 *
 * Never throws: every caller is a form, and a form that throws takes the page with it.
 */
export const signIn = async (email: string, password: string): Promise<SignInResult> => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);

  let response: Response;
  try {
    const discovery = await discover();
    if (!discovery.token_endpoint) {
      throw new Error('OIDC discovery did not provide a token endpoint');
    }
    response = await fetch(discovery.token_endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({
        grant_type: 'password',
        client_id: iamClientId,
        username: email.trim(),
        password,
        scope: 'openid profile email',
      }),
      signal: controller.signal,
    });
  } catch {
    // A network-level failure means the provider or its discovery endpoint is unavailable.
    return {
      ok: false,
      reason: 'unreachable',
      message:
        `Could not reach the identity provider at ${iamIssuer}. ` +
        'Start the configured identity provider, or run the service with SFL_SECURITY_ENABLED=false for header-based local development.',
    };
  } finally {
    clearTimeout(timer);
  }

  let body: TokenResponse = {};
  try {
    body = (await response.json()) as TokenResponse;
  } catch {
    body = {};
  }

  if (!response.ok) {
    if (response.status === 401 || body.error === 'invalid_grant') {
      return {
        ok: false,
        reason: 'credentials',
        message: 'That email and password do not match an account.',
      };
    }
    if (body.error === 'invalid_client' || body.error === 'unauthorized_client') {
      // The realm exists but the client is misconfigured - a deployment problem, not a user one.
      return {
        ok: false,
        reason: 'disabled',
        message:
          `The realm refused this client (${iamClientId}). ` +
          'Check that the client exists and has direct access grants enabled.',
      };
    }
    return {
      ok: false,
      reason: 'unexpected',
      message: body.error_description ?? `Sign-in failed (HTTP ${response.status}).`,
    };
  }

  if (!body.access_token) {
    return { ok: false, reason: 'unexpected', message: 'The realm returned no access token.' };
  }

  const session = sessionFromTokens(body.access_token, body.refresh_token ?? null);
  if (!session) {
    return { ok: false, reason: 'unexpected', message: 'The access token could not be read.' };
  }

  writeSession(session);
  return { ok: true, session };
};

/**
 * Ends the session here, and tells the realm to end it there too.
 *
 * The local clear happens **first and unconditionally**. If the realm is unreachable, the user is
 * still signed out of this browser, which is the half that matters to the person standing at the
 * workstation.
 */
export const signOut = async (): Promise<void> => {
  const session = readSession();
  clearSession();
  if (!session?.refreshToken) {
    return;
  }
  try {
    const discovery = await discover();
    const logoutEndpoint = discovery.end_session_endpoint ?? discovery.revocation_endpoint;
    if (!logoutEndpoint) {
      return;
    }
    await fetch(logoutEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({ client_id: iamClientId, refresh_token: session.refreshToken }),
    });
  } catch {
    // Best effort. The token expires on its own, and this browser has already forgotten it.
  }
};

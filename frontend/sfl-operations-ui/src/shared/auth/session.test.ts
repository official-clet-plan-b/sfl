import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearSession, decodeClaims, isExpired, readSession, sessionFromTokens, writeSession } from './session';

/**
 * The session, and specifically the claim reading.
 *
 * This is worth testing because of what depends on it. The roles this returns drive programme
 * entitlement, system entitlement and every permission-gated control in the sidebar. Before the login
 * page those came from a comma-separated environment variable that a human typed; they now come from
 * a token, and a parser that silently returns `[]` on a shape it did not expect would hide every
 * screen from a correctly-signed-in operator - a failure that looks exactly like a permissions
 * problem and is not one.
 */

/** Builds a token the way Keycloak does: base64url, no padding, unverified signature. */
const tokenWith = (claims: Record<string, unknown>): string => {
  const encode = (value: unknown) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.signature-not-checked-here`;
};

const inAnHour = () => Math.floor(Date.now() / 1000) + 3600;

beforeEach(() => {
  clearSession();
  sessionStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('decodeClaims', () => {
  it('reads a base64url payload with no padding', () => {
    // Keycloak strips the padding. `atob` requires it, so the decoder has to put it back - this is
    // the case that fails if it does not.
    const claims = decodeClaims(tokenWith({ preferred_username: 'fleet.manager', exp: 1 }));
    expect(claims?.preferred_username).toBe('fleet.manager');
  });

  it('survives non-ASCII in a display name', () => {
    // Decoding with atob alone mangles anything above U+007F, and CLET names carry accents.
    const claims = decodeClaims(tokenWith({ name: 'Yaa Asantewaa - Fleet', exp: 1 }));
    expect(claims?.name).toBe('Yaa Asantewaa - Fleet');
  });

  it('returns null rather than throwing on a token that is not one', () => {
    expect(decodeClaims('not-a-token')).toBeNull();
    expect(decodeClaims('')).toBeNull();
    expect(decodeClaims('a.b.c')).toBeNull();
  });
});

describe('sessionFromTokens', () => {
  it('takes roles from realm_access, which is what the services read', () => {
    const session = sessionFromTokens(
      tokenWith({
        exp: inAnHour(),
        preferred_username: 'fleet.manager',
        email: 'fleetmanager@clet.gh',
        realm_access: { roles: ['FLEET_MANAGER', 'offline_access'] },
        site_scopes: ['CLET-HQ'],
      }),
      'refresh-token',
    );

    expect(session?.username).toBe('fleet.manager');
    expect(session?.email).toBe('fleetmanager@clet.gh');
    expect(session?.roles).toEqual(['FLEET_MANAGER']);
    expect(session?.siteScopes).toEqual(['CLET-HQ']);
  });

  it('accepts site_scopes as a list or as one comma-separated string', () => {
    /*
      Both shapes are real: a Keycloak user-attribute mapper emits a list when `multivalued` is set
      and a single joined string when it is not, and the realm has been edited by hand. Reading only
      one shape would give an operator an empty site scope, which the services treat as "no sites"
      and which presents as an empty dashboard rather than as an error.
    */
    const asList = sessionFromTokens(
      tokenWith({ exp: inAnHour(), site_scopes: ['CLET-HQ', 'CLET-KUMASI'] }), null);
    const asString = sessionFromTokens(
      tokenWith({ exp: inAnHour(), site_scopes: 'CLET-HQ, CLET-KUMASI' }), null);

    expect(asList?.siteScopes).toEqual(['CLET-HQ', 'CLET-KUMASI']);
    expect(asString?.siteScopes).toEqual(['CLET-HQ', 'CLET-KUMASI']);
  });

  it('falls back through name, then given/family, then username', () => {
    expect(sessionFromTokens(tokenWith({ exp: inAnHour(), name: 'Ama Mensah' }), null)?.displayName)
      .toBe('Ama Mensah');
    expect(sessionFromTokens(
      tokenWith({ exp: inAnHour(), given_name: 'Ama', family_name: 'Mensah' }), null)?.displayName)
      .toBe('Ama Mensah');
    expect(sessionFromTokens(
      tokenWith({ exp: inAnHour(), preferred_username: 'ama.mensah' }), null)?.displayName)
      .toBe('ama.mensah');
  });

  it('refuses a token with no expiry', () => {
    // No exp means nothing can decide the session is stale, so it would be held forever.
    expect(sessionFromTokens(tokenWith({ preferred_username: 'x' }), null)).toBeNull();
  });
});

describe('expiry', () => {
  it('treats a token expiring within the skew window as already expired', () => {
    /*
      A minute of headroom, so a request is never sent with a token that expires while in flight.
      Without it the failure is a 401 on a call the operator had every right to make, which reads as
      a permissions problem.
    */
    const session = sessionFromTokens(tokenWith({ exp: Math.floor(Date.now() / 1000) + 30 }), null);
    expect(session && isExpired(session)).toBe(true);
  });

  it('accepts a token with real time left', () => {
    const session = sessionFromTokens(tokenWith({ exp: inAnHour() }), null);
    expect(session && isExpired(session)).toBe(false);
  });
});

describe('storage', () => {
  /*
    These re-import the module rather than calling clearSession, because the thing being tested is
    what happens on a **page load** that finds a token already in storage. `readSession` caches on
    first read by design - everything derived from the actor is computed once at module scope, which
    is what lets the sidebar and route guards be synchronous - so a fresh import is the only honest
    way to simulate arriving with storage already populated.
  */
  const freshModule = async () => {
    vi.resetModules();
    return import('./session');
  };

  it('reads a session left in storage by a previous page load', async () => {
    const seed = sessionFromTokens(
      tokenWith({
        exp: inAnHour(),
        preferred_username: 'abena.boateng',
        realm_access: { roles: ['FLEET_DRIVER'] },
      }),
      null,
    )!;
    sessionStorage.setItem('sfl.session.v1', JSON.stringify(seed));

    const { readSession: read } = await freshModule();
    expect(read()?.roles).toEqual(['FLEET_DRIVER']);
    expect(read()?.username).toBe('abena.boateng');
  });

  it('clears an expired session rather than returning it', async () => {
    // A stale token left in storage produces 401s that look like a permissions problem, so finding
    // one is treated as finding nothing - and it is removed on the way out rather than left to do
    // it again on the next load.
    const stale = sessionFromTokens(tokenWith({ exp: inAnHour() }), null)!;
    sessionStorage.setItem(
      'sfl.session.v1',
      JSON.stringify({ ...stale, expiresAt: Math.floor(Date.now() / 1000) - 10 }),
    );

    const { readSession: read } = await freshModule();
    expect(read()).toBeNull();
    expect(sessionStorage.getItem('sfl.session.v1')).toBeNull();
  });

  it('survives storage being unavailable, as it is in private browsing', async () => {
    // Refusing to sign in because storage is blocked would be worse than a session that does not
    // survive a refresh.
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    const session = sessionFromTokens(tokenWith({ exp: inAnHour() }), null)!;

    expect(() => writeSession(session)).not.toThrow();
    expect(readSession()?.expiresAt).toBe(session.expiresAt);
    setItem.mockRestore();
  });
});

describe('identity provider roles', () => {
  it('are not SFL roles and never reach the session or the header', () => {
    const session = sessionFromTokens(
      tokenWith({
        exp: inAnHour(),
        preferred_username: 'abena.boateng',
        realm_access: { roles: ['default-roles-sfl', 'offline_access', 'uma_authorization', 'FLEET_DRIVER'] },
      }),
      null,
    );
    expect(session?.roles).toEqual(['FLEET_DRIVER']);
  });
});

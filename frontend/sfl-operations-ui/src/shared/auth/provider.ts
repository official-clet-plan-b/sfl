import { signIn as seededSignIn } from './signIn';
import { signIn as oidcSignIn, signOut as oidcSignOut } from './oidc';
import { clearSession } from './session';
import type { SflSession } from './session';

/**
 * How this browser signs somebody in, without naming who does it.
 *
 * <h2>Why the indirection exists</h2>
 *
 * <p>Two paths have always been present: a seeded sign-in for local work against services running
 * with security off, and a token exchange against an identity provider for everything else. They
 * were reachable only by importing one file or the other, so "which one is in use" was a property of
 * whichever import somebody had written - not a decision anybody could see or change.
 *
 * <p>One environment variable decides it now, and nothing downstream knows a provider exists.
 * `session.ts` stores both kinds identically and the API client reads only the session, so the whole
 * of the dashboard is already provider-blind; this is the last place that was not.
 *
 * <h2>Naming no vendor, on purpose</h2>
 *
 * <p>The OIDC path is configured by issuer URL and client id and mentions no product anywhere in the
 * code path. Swapping the provider is then a change to `VITE_SFL_IAM_ISSUER` and the realm seed under
 * `deploy/idp/`, not a change to the application - which is the difference between a migration and a
 * refactor when that day comes.
 */
export type AuthProviderName = 'seeded' | 'oidc';

export interface SignInOutcome {
  ok: boolean;
  session?: SflSession;
  message?: string;
}

/**
 * `seeded` only where a developer asks for it.
 *
 * Defaulting to `oidc` is the safer failure: a build that forgot to set this gets the real path and
 * fails loudly against a missing issuer, rather than quietly accepting a password that is printed on
 * the sign-in page.
 */
export const authProvider = (): AuthProviderName =>
  (import.meta.env.VITE_AUTH_PROVIDER as AuthProviderName) === 'seeded' ? 'seeded' : 'oidc';

/**
 * True only on a developer's own machine. The seeded demo accounts share one published password, so
 * they must never let anybody in on a server that real people use - even if a build there was
 * misconfigured with `VITE_AUTH_PROVIDER=seeded`.
 */
export const isLocalDevelopmentHost = (hostname: string = window.location.hostname): boolean => {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  return (
    host === 'localhost' ||
    host === '127.0.0.1' ||
    host === '::1' ||
    host.endsWith('.localhost')
  );
};

export const signInWithConfiguredProvider = async (
  email: string,
  password: string,
): Promise<SignInOutcome> => {
  if (authProvider() === 'seeded') {
    if (!isLocalDevelopmentHost()) {
      return {
        ok: false,
        message: 'Demo accounts only work on a local development machine. Sign in with your own account.',
      };
    }
    const result = seededSignIn(email, password);
    return result.ok
      ? { ok: true, session: result.session }
      : { ok: false, message: result.message };
  }
  const result = await oidcSignIn(email, password);
  return result.ok ? { ok: true, session: result.session } : { ok: false, message: result.message };
};

export const signOutOfConfiguredProvider = async (): Promise<void> => {
  if (authProvider() === 'seeded') {
    clearSession();
    return;
  }
  await oidcSignOut();
};

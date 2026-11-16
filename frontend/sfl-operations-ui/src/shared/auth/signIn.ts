import {
  SEEDED_PASSWORD,
  SeededAccount,
  accountIsForeignToPlatform,
  findAccount,
} from './accounts';
import { servingPlatformName } from 'shared/platform';
import { SflSession, clearSession, writeSession } from './session';

/**
 * Signing in against the seeded accounts.
 *
 * <h2>What happens, in order</h2>
 *
 * The email is matched against `accounts.ts`, the password against one shared constant, and on a
 * match the account becomes the actor for this browser session - username, display name, roles and
 * site scopes. Those four are what the API client sends as `X-SFL-*` on every request, so the portal
 * that opens next is the one that account's roles entitle it to.
 *
 * <h2>The failure cases are separated on purpose</h2>
 *
 * An unknown email and a wrong password are told apart here and reported the same way, which is a
 * deliberate inversion of the usual advice. Normally a sign-in form says "those details did not
 * match" for both, so an attacker cannot enumerate accounts. That reasoning does not apply to a form
 * whose entire account list is printed on the page beneath it - and pretending otherwise would cost a
 * developer the one piece of information they need, which is whether they typed the address wrong or
 * the password wrong.
 *
 * <h2>Why there is no token</h2>
 *
 * See `accounts.ts`. This is a development sign-in against services running with
 * `SFL_SECURITY_ENABLED=false`, where the actor is whatever the headers claim. `oidc.ts` is the
 * path that issues a real token, and both write the same session shape so nothing downstream cares
 * which one was used.
 */

export type SignInFailure =
  | { reason: 'unknown-account'; message: string }
  | { reason: 'wrong-platform'; message: string }
  | { reason: 'wrong-password'; message: string }
  | { reason: 'incomplete'; message: string };

export type SignInResult = { ok: true; session: SflSession } | ({ ok: false } & SignInFailure);

/** Far enough out that a working session never expires mid-shift. */
const SESSION_HOURS = 12;

const sessionFor = (account: SeededAccount): SflSession => ({
  // No token: this session was not issued by an identity provider and must not look as though it
  // was. `client.ts` sends an Authorization header only when this is non-empty, so a development
  // session sends the X-SFL-* headers alone - which is exactly what the open services read.
  accessToken: '',
  refreshToken: null,
  expiresAt: Math.floor(Date.now() / 1000) + SESSION_HOURS * 3600,
  username: account.username,
  displayName: account.displayName,
  email: account.email,
  roles: account.roles,
  siteScopes: account.sites,
});

export const signIn = (email: string, password: string): SignInResult => {
  if (!email.trim() || !password) {
    return { ok: false, reason: 'incomplete', message: 'Enter an email address and a password.' };
  }

  const account = findAccount(email);
  if (!account) {
    return {
      ok: false,
      reason: 'unknown-account',
      message: 'Invalid username/email or password.',
    };
  }

  /*
    Refused before the password is even checked, because the password is not what is wrong.

    A driver signing in on the facilities service used to succeed and land on an empty dashboard,
    which reads as a broken deployment rather than as an account in the wrong place. Saying so here
    costs nothing: this sign-in only runs on a developer's own machine, so nothing is disclosed by
    naming the mismatch, and the alternative is a developer filing a bug against the wrong system.
  */
  if (accountIsForeignToPlatform(account)) {
    return {
      ok: false,
      reason: 'wrong-platform',
      message: `This account has no access to ${servingPlatformName()}. Sign in on the service that carries its work, or use the unified portal.`,
    };
  }

  if (password !== SEEDED_PASSWORD) {
    return {
      ok: false,
      reason: 'wrong-password',
      message: 'Invalid username/email or password.',
    };
  }

  const session = sessionFor(account);
  writeSession(session);
  return { ok: true, session };
};

/** Ends the session for this browser. There is nothing remote to tell. */
export const signOut = (): void => clearSession();

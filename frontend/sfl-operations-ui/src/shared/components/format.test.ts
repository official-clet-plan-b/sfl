import { beforeEach, describe, expect, it } from 'vitest';
import { clearSession, sessionFromTokens, writeSession } from 'shared/auth/session';
import { isCurrentUser, personName } from './format';

const tokenWith = (claims: Record<string, unknown>): string => {
  const encode = (value: unknown) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.sig`;
};

const SUBJECT = '0b7d3a5e-1c2f-4e8a-9b6d-5f4c3b2a1908';

beforeEach(() => {
  clearSession();
  writeSession(
    sessionFromTokens(
      tokenWith({
        sub: SUBJECT,
        preferred_username: 'fleet.manager',
        name: 'Fleet Manager',
        exp: Math.floor(Date.now() / 1000) + 3600,
      }),
      null,
    )!,
  );
});

describe('isCurrentUser', () => {
  it('recognises the signed-in person by token subject or sign-in name', () => {
    // Services record the subject; older rows carry the username. Both are the same person.
    expect(isCurrentUser(SUBJECT)).toBe(true);
    expect(isCurrentUser('Fleet.Manager')).toBe(true);
  });

  it('is false for anyone else, or for nobody', () => {
    expect(isCurrentUser('abena.boateng')).toBe(false);
    expect(isCurrentUser(null)).toBe(false);
  });
});

describe('personName', () => {
  it('names the signed-in person and hides ids that cannot be named', () => {
    expect(personName(SUBJECT)).toBe('Fleet Manager');
    expect(personName('7a1c2d3e-4f50-6172-8394-a5b6c7d8e9f0')).toBeNull();
  });
});

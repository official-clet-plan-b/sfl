import { afterEach, describe, expect, it, vi } from 'vitest';
import { isLocalDevelopmentHost, signInWithConfiguredProvider } from './provider';
import { SEEDED_PASSWORD } from './accounts';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('isLocalDevelopmentHost', () => {
  it('accepts only a developer machine', () => {
    expect(isLocalDevelopmentHost('localhost')).toBe(true);
    expect(isLocalDevelopmentHost('127.0.0.1')).toBe(true);
    expect(isLocalDevelopmentHost('[::1]')).toBe(true);
    expect(isLocalDevelopmentHost('sfl.localhost')).toBe(true);
    expect(isLocalDevelopmentHost('sfl.uat.clet.edu.gh')).toBe(false);
    expect(isLocalDevelopmentHost('localhost.example.com')).toBe(false);
    expect(isLocalDevelopmentHost('10.0.0.5')).toBe(false);
  });
});

describe('seeded demo sign-in', () => {
  it('is refused away from localhost even when a build turned it on', async () => {
    vi.stubEnv('VITE_AUTH_PROVIDER', 'seeded');
    const location = vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      hostname: 'sfl.uat.clet.edu.gh',
    } as Location);
    const result = await signInWithConfiguredProvider('fleetmanager@clet.gh', SEEDED_PASSWORD);
    location.mockRestore();
    expect(result.ok).toBe(false);
    expect(result.message).toMatch(/local development machine/);
  });
});

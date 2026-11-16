import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('shared/platform', () => ({ servingPlatform: () => 'ALL' }));

const get = vi.fn();
vi.mock('shared/api/client', () => ({ apiClient: { get: (...args: unknown[]) => get(...args) } }));

import { changedByName, isSystemEnabled, loadFeatureFlags, setFeatureFlagsForTest } from './featureFlags';

describe('feature flags', () => {
  afterEach(() => {
    get.mockReset();
    setFeatureFlagsForTest(null);
  });

  it('treats everything as off until loaded', () => {
    expect(isSystemEnabled('S166')).toBe(false);
  });

  it('switches on only what the services report as on', async () => {
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/fleet/features') return { S166: true, S168a: true, S171: false };
      if (path === '/api/v1/facilities/features') return { S152: false };
      return { S174: true };
    });
    await loadFeatureFlags();
    expect(isSystemEnabled('S166')).toBe(true);
    expect(isSystemEnabled('S168A')).toBe(true);
    expect(isSystemEnabled('S171')).toBe(false);
    expect(isSystemEnabled('S152')).toBe(false);
    expect(isSystemEnabled('S174')).toBe(true);
  });

  it('treats the systems of a service that does not answer as off', async () => {
    get.mockImplementation(async (path: string) => {
      if (path === '/api/v1/fleet/features') return { S166: true };
      throw new Error('unreachable');
    });
    await loadFeatureFlags();
    expect(isSystemEnabled('S166')).toBe(true);
    expect(isSystemEnabled('S159')).toBe(false);
  });
});

describe('changedByName', () => {
  it('shows the person, not the identity provider subject', () => {
    expect(changedByName('SFL Administrator (a648be83-05cb-4874-9d6d-04e7ec68b97c)')).toBe('SFL Administrator');
    expect(changedByName('dev.admin')).toBe('dev.admin');
    expect(changedByName(null)).toBe('an administrator');
  });
});

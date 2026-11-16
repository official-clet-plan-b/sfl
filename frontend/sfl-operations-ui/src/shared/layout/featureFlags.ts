import { useSyncExternalStore } from 'react';
import { apiClient } from 'shared/api/client';
import { ServingPlatform, servingPlatform } from 'shared/platform';

/**
 * Which systems this deployment has switched on - asked of the services at runtime, and changeable
 * from the dashboard by a platform administrator.
 *
 * Each service owns the flags for the systems it hosts. The deployment sets defaults
 * (`SFL_FEATURES_ENABLED_SYSTEMS` / `SFL_FEATURES_DISABLED_SYSTEMS`); an administrator's choice on
 * the Modules screen is saved in the service's database and overrides the default straight away - no
 * restart, no redeploy, no rebuild. The service refuses calls into a switched-off system; this module
 * only keeps the dashboard's menu and route guard in step with it.
 *
 * A service that does not answer contributes nothing, so its systems are treated as off: a portal
 * deployed with only the fleet service shows only fleet screens.
 */

const TIMEOUT_MS = 2500;

export type FeatureService = 'fleet' | 'safetySecurity' | 'facilities';

interface Source {
  path: string;
  service: FeatureService;
}

const SOURCE_FOR: Record<'IFIMP' | 'SSEMP' | 'FTLMP', Source> = {
  FTLMP: { path: '/api/v1/fleet/features', service: 'fleet' },
  SSEMP: { path: '/api/v1/emergency/features', service: 'safetySecurity' },
  IFIMP: { path: '/api/v1/facilities/features', service: 'facilities' },
};

const SOURCE_BY_SERVICE: Record<FeatureService, Source> = {
  fleet: SOURCE_FOR.FTLMP,
  safetySecurity: SOURCE_FOR.SSEMP,
  facilities: SOURCE_FOR.IFIMP,
};

/** One system as the service reports it. */
export interface SystemFlag {
  code: string;
  label: string;
  enabled: boolean;
  /** What the deployment's environment says, before any administrator override. */
  defaultEnabled: boolean;
  /** `true` when an administrator has changed it from the dashboard. */
  overridden: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
  /** Systems whose API this one keeps reachable while it is on. */
  requires: string[];
  service: FeatureService;
}

const sourcesFor = (platform: ServingPlatform): Source[] => {
  if (platform === 'ALL') {
    return Object.values(SOURCE_FOR);
  }
  const source = SOURCE_FOR[platform as 'IFIMP' | 'SSEMP' | 'FTLMP'];
  return source ? [source] : [];
};

type RawFlags = Array<Omit<SystemFlag, 'service'>> | Record<string, boolean>;

/** Accepts the detailed list and the older `{code: enabled}` map, so a newer UI works against an older service. */
const normalise = (raw: RawFlags | null | undefined, service: FeatureService): SystemFlag[] => {
  if (!raw) {
    return [];
  }
  if (Array.isArray(raw)) {
    return raw.map((flag) => ({ ...flag, requires: flag.requires ?? [], service }));
  }
  return Object.entries(raw).map(([code, enabled]) => ({
    code,
    label: code,
    enabled: Boolean(enabled),
    defaultEnabled: Boolean(enabled),
    overridden: false,
    updatedBy: null,
    updatedAt: null,
    requires: [],
    service,
  }));
};

let flags: SystemFlag[] | null = null;
let unreachable: FeatureService[] = [];
let version = 0;
const listeners = new Set<() => void>();

const publish = (next: SystemFlag[]) => {
  flags = next;
  version += 1;
  listeners.forEach((listener) => listener());
};

const fetchOne = async (source: Source): Promise<SystemFlag[] | null> => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const result = await apiClient.get<RawFlags>(
      source.path,
      undefined,
      controller.signal,
      source.service === 'fleet' ? undefined : source.service,
    );
    return normalise(result, source.service);
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
};

export const loadFeatureFlags = async (): Promise<void> => {
  const sources = sourcesFor(servingPlatform());
  const results = await Promise.all(sources.map(fetchOne));
  unreachable = sources.filter((_, index) => results[index] === null).map((source) => source.service);
  publish(results.flatMap((result) => result ?? []));
};

/** Whether a system is switched on in this deployment. `false` until the flags have loaded. */
export const isSystemEnabled = (code: string): boolean =>
  flags?.some((flag) => flag.enabled && flag.code.toUpperCase() === code.toUpperCase()) ?? false;

/** Every system the reachable services reported, for the Modules screen. */
export const systemFlags = (): SystemFlag[] => flags ?? [];

/** Services that did not answer at the last load. */
export const unreachableFeatureServices = (): FeatureService[] => unreachable;

const replaceService = (service: FeatureService, next: SystemFlag[]) =>
  publish([...(flags ?? []).filter((flag) => flag.service !== service), ...next]);

/** Switches a system on or off for everyone, immediately. Platform administrators only. */
export const setSystemEnabled = async (flag: SystemFlag, enabled: boolean): Promise<void> => {
  const source = SOURCE_BY_SERVICE[flag.service];
  const result = await apiClient.put<RawFlags>(
    `${source.path}/${encodeURIComponent(flag.code)}`,
    { enabled },
    { service: flag.service === 'fleet' ? undefined : flag.service },
  );
  replaceService(flag.service, normalise(result, flag.service));
};

/** Drops an administrator's override so the deployment default applies again. */
export const resetSystemFlag = async (flag: SystemFlag): Promise<void> => {
  const source = SOURCE_BY_SERVICE[flag.service];
  const result = await apiClient.delete<RawFlags>(`${source.path}/${encodeURIComponent(flag.code)}`, {
    service: flag.service === 'fleet' ? undefined : flag.service,
  });
  replaceService(flag.service, normalise(result, flag.service));
};

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** Re-renders the caller whenever the flags change, so the menu follows a toggle at once. */
export const useFeatureFlagsVersion = (): number => useSyncExternalStore(subscribe, () => version);

/** For tests: set the switched-on systems directly. `null` resets to "not loaded". */
export const setFeatureFlagsForTest = (codes: string[] | null): void => {
  if (codes === null) {
    flags = null;
    return;
  }
  publish(
    codes.map((code) => ({
      code,
      label: code,
      enabled: true,
      defaultEnabled: true,
      overridden: false,
      updatedBy: null,
      updatedAt: null,
      requires: [],
      service: 'fleet' as const,
    })),
  );
};

/** "Ama Mensah (3f2c…)" -> "Ama Mensah": the service records name and subject; people read the name. */
export const changedByName = (updatedBy: string | null | undefined): string =>
  updatedBy ? updatedBy.replace(/\s*\([^()]*\)$/, '') || updatedBy : 'an administrator';

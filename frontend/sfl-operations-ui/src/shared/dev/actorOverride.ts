/**
 * A development-only actor override, held in session storage.
 *
 * ## Why this exists
 *
 * The dashboard sends its actor as `X-SFL-*` headers taken from build-time `VITE_SFL_*` variables, so
 * changing role meant editing `.env` and restarting Vite. The pages this dashboard replaces each
 * carried an actor box on the page, which made that a five-second job.
 *
 * That barely mattered while every user saw the same navigation. It matters now: ADR 0005 made the
 * sidebar and the route guard depend on the actor's roles, so "does a driver see CCTV?" and "does a
 * facilities manager see fleet?" are questions somebody needs to be able to answer repeatedly. ADR
 * 0006 made this a precondition of retiring those pages, rather than a convenience to add later.
 *
 * ## Why session storage, and why a reload
 *
 * Session storage, so it dies with the tab. An override that survived a browser restart would
 * eventually be forgotten and mistaken for the real default.
 *
 * Applying it **reloads the page**, because programme entitlement, the navigation sections and the
 * site list are all module-level constants computed once at import. Making them reactive would mean
 * threading the actor through `programmeModel`, `programmes` and `navigation`, and a half-updated
 * entitlement is precisely the state in which a role check stops being worth running. A reload
 * recomputes everything from one source, which is also what signing in as somebody else really does.
 *
 * ## What ships
 *
 * {@link devToolsEnabled} is `import.meta.env.DEV`, which the bundler replaces with a literal. The
 * switcher panel is referenced only behind that literal, so a production build drops it. These few
 * resolver lines may survive as unreachable code; they read session storage and nothing else.
 *
 * **This was never a security boundary and this does not make it one.** The services authorise every
 * call from the headers they receive; typing a role here changes what this dashboard *asks for*, not
 * what it is allowed to have. In production the actor comes from the OIDC principal and these
 * headers are ignored entirely.
 */

/** `true` only in a development build. A production bundle compiles the switcher out. */
export const devToolsEnabled: boolean = import.meta.env.DEV;

const STORAGE_KEY = 'sfl.dev.actor-override';

export interface ActorOverride {
  user: string;
  displayName: string;
  /** Comma-separated `SflRole` names, exactly as the `X-SFL-Roles` header carries them. */
  roles: string;
  /** Comma-separated site codes for `X-SFL-Sites`. */
  sites: string;
  /**
   * Programme codes to preview (dev only; there is no build-time equivalent).
   *
   * Empty means "derive from the roles", which is the real behaviour. Setting it is how to look at
   * one programme's screens without inventing a role list to justify it.
   */
  programmes: string;
  /**
   * System codes to preview (dev only). Systems switched off by the deployment's feature flags stay hidden.
   *
   * The finer grain. Empty derives from the roles, which is what a real sign-in does - the presets
   * leave it blank for that reason and narrow by role instead.
   */
  systems: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;

const text = (value: unknown): string => (typeof value === 'string' ? value : '');

/**
 * The stored override, or `undefined`.
 *
 * Returns `undefined` in a production build without touching storage, and on anything unreadable -
 * a corrupt entry falls back to the environment defaults rather than half-applying.
 */
export const readActorOverride = (): ActorOverride | undefined => {
  if (!devToolsEnabled) {
    return undefined;
  }
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return undefined;
    }
    const parsed: unknown = JSON.parse(raw);
    if (!isRecord(parsed)) {
      return undefined;
    }
    return {
      user: text(parsed.user),
      displayName: text(parsed.displayName),
      roles: text(parsed.roles),
      sites: text(parsed.sites),
      programmes: text(parsed.programmes),
      systems: text(parsed.systems),
    };
  } catch {
    // Storage can be unavailable (private mode, a blocked origin) and the entry can be malformed.
    // Neither is worth surfacing: the environment defaults are a correct answer.
    return undefined;
  }
};

/** Stores the override. The caller reloads; this does not, so it can be tested without one. */
export const writeActorOverride = (override: ActorOverride): void => {
  if (!devToolsEnabled) {
    return;
  }
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(override));
  } catch {
    // Nothing useful to do. The switcher reports the failure by simply not taking effect.
  }
};

export const clearActorOverride = (): void => {
  if (!devToolsEnabled) {
    return;
  }
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // As above.
  }
};

/** `true` when an override is in force - the badge that stops it being mistaken for the default. */
export const actorOverridden: boolean = readActorOverride() !== undefined;

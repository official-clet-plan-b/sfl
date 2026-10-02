import { lazy, Suspense, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import {
  AppBody,
  AppHeader,
  AppHeaderActions,
  AppHeaderSearch,
  AppHeaderTitle,
  AppLayout,
  AppSidebar,
  ProfilePopover,
  Sidebar,
  SidebarBrand,
  SidebarCollapse,
  SidebarContent,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarLink,
  SidebarNav,
  type AppHeaderSearchDataGroup,
} from '@rfdtech/components';
import { sflActor } from 'shared/api/config';
import { readSession } from 'shared/auth/session';
import { signOut } from 'shared/auth/signIn';
import { actorOverridden, devToolsEnabled } from 'shared/dev/actorOverride';
import Icon from 'shared/components/Icon';
import { entitledSections, type NavItem } from './navigation';
import { permissionFailure } from './actorPermissions';
import { portalLabel } from './programmes';
import {
  FONT_SCALE_MAX,
  FONT_SCALE_MIN,
  FONT_SCALE_STEP,
  useSystemPreferences,
} from './SystemPreferences';

/**
 * The development actor switcher, loaded on demand - and only in a development build.
 *
 * `import.meta.env.DEV` is written out here rather than the `devToolsEnabled` re-export on purpose.
 * Vite substitutes that expression with a literal `false` before Rollup runs, so the whole ternary
 * folds and the dynamic import disappears with it: no chunk is emitted and the panel never reaches a
 * production bundle. Guarding the *render* is not enough - the `lazy(() => import(...))` at module
 * scope is a real edge in the module graph whatever the JSX does with the result.
 */
const ActorSwitcher = import.meta.env.DEV
  ? lazy(() => import('shared/dev/ActorSwitcher'))
  : null;

const initials = (name: string): string =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('') || 'SF';

const words = (code: string): string => code.replace(/_/g, ' ').toLowerCase();

const roles = sflActor.roles
  .split(',')
  .map((role) => role.trim())
  .filter(Boolean);

const sites = sflActor.sites
  .split(',')
  .map((site) => site.trim())
  .filter(Boolean);

const FontScaleMenu = ({ embedded = false }: { embedded?: boolean }) => {
  const { fontScale, setFontScale, resetFontScale } = useSystemPreferences();
  const percentage = Math.round(fontScale * 100);

  return (
    <details className="relative" open={embedded || undefined}>
      <summary
        className={embedded ? 'sr-only' : 'flex h-9 cursor-pointer list-none items-center gap-1.5 rounded-md px-2 text-theme-sm font-medium text-gray-700 hover:bg-gray-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-700'}
        aria-label="System preferences"
      >
        <span aria-hidden="true" className="text-base leading-none">A</span>
        <span className="hidden sm:inline">Text size</span>
      </summary>
      <div className={embedded ? 'mt-4 w-full' : 'absolute top-11 right-0 z-50 w-64 rounded-lg border border-gray-200 bg-white p-4 shadow-theme-lg'}>
        <div className="flex items-center justify-between gap-3">
          <p className="text-theme-sm font-semibold text-gray-900">System preferences</p>
          <span className="text-theme-xs font-medium text-gray-600">{percentage}%</span>
        </div>
        <label className="mt-3 block text-theme-xs text-gray-600" htmlFor="font-scale">
          Text size
        </label>
        <div className="mt-2 flex items-center gap-2">
          <span className="text-xs" aria-hidden="true">A</span>
          <input
            id="font-scale"
            type="range"
            min={FONT_SCALE_MIN}
            max={FONT_SCALE_MAX}
            step={FONT_SCALE_STEP}
            value={fontScale}
            onChange={(event) => setFontScale(Number(event.target.value))}
            className="min-w-0 flex-1 accent-teal-700"
          />
          <span className="text-lg" aria-hidden="true">A</span>
        </div>
        <button
          type="button"
          className="mt-3 text-theme-xs font-medium text-teal-800 underline underline-offset-2"
          onClick={resetFontScale}
        >
          Reset to 100%
        </button>
      </div>
    </details>
  );
};

/** Whether `item` is the current destination - `matchPrefix` also claims its child routes. */
const isCurrent = (item: NavItem, pathname: string): boolean =>
  item.matchPrefix
    ? pathname === item.to || pathname.startsWith(`${item.to}/`)
    : pathname === item.to;

/**
 * The shell every dashboard screen renders inside: the CLET 2.4 layout from `@rfdtech/components` -
 * a full-height navy rail beside a plain header over the content column.
 *
 * **The rail is filtered by programme entitlement.** A fleet operator sees fleet, fuel and dispatch;
 * they do not see emergency mass notification, which is SSEMP. See `programmes.ts` and ADR 0005 -
 * and note that this is a usability control, never the enforcement point: every service authorises
 * every call on its own.
 */
const AppShell = () => {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const sections = useMemo(() => entitledSections(), []);
  /** Null when nobody has signed in - the header-based development actor is then in force. */
  const session = readSession();
  const [switcherOpen, setSwitcherOpen] = useState(false);
  const [preferencesOpen, setPreferencesOpen] = useState(false);
  /*
    Read once per render rather than held in state: it is decided before the first paint and cannot
    change without a reload, so subscribing to it would be machinery for a value that never moves.
  */
  const permissionsUnavailable = permissionFailure();

  /*
    Every group starts folded except the one holding the current screen (or the first group when the
    current screen is in none) - the rail reads as a short list rather than the whole product.
  */
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    const current = sections.find((section) => section.items.some((item) => isCurrent(item, pathname)));
    const first = current ?? sections[0];
    return new Set(first ? [first.heading] : []);
  });

  const toggleGroup = (heading: string, open: boolean) =>
    setExpanded((previous) => {
      const next = new Set(previous);
      if (open) {
        next.add(heading);
      } else {
        next.delete(heading);
      }
      return next;
    });

  const [query, setQuery] = useState('');
  const searchGroups: AppHeaderSearchDataGroup[] = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) {
      return [];
    }
    return [
      {
        heading: 'Pages',
        items: sections
          .flatMap((section) => section.items)
          .filter((item) => item.label.toLowerCase().includes(needle))
          .map((item) => ({
            value: item.to,
            label: item.label,
            onSelect: () => navigate(item.to),
          })),
      },
    ];
  }, [navigate, query, sections]);

  const primaryRole = roles[0] ? words(roles[0]) : 'operator';
  const handleSignOut = () => {
    signOut();
    window.location.assign(`${import.meta.env.BASE_URL.replace(/\/$/, '')}/login`);
  };

  return (
    <AppLayout>
      <AppHeader variant="plain">
        {/* The library paints the system title in its gold secondary-text token; the CLET design shows it
            in the body text colour, so it is set explicitly here. */}
        <AppHeaderTitle style={{ color: 'var(--clet-text)' }}>{portalLabel()}</AppHeaderTitle>
        <AppHeaderActions>
          <span
            className="hidden items-center gap-1.5 rounded-full bg-gray-100 px-3 py-1.5 text-theme-xs font-medium text-gray-800 md:inline-flex"
            title="Site scope sent on every request (X-SFL-Sites)"
          >
            <Icon name="map-pin" size={14} />
            {sites.length === 1 ? sites[0] : `${sites.length} sites`}
          </span>
          <AppHeaderSearch
            collapsible
            placeholder="Search pages"
            data={searchGroups}
            onSearch={setQuery}
            showEmpty
            emptyLabel="No matching pages"
          />
          <ProfilePopover
            variant="full"
            side="bottom"
            align="end"
            user={{
              name: sflActor.displayName,
              role: primaryRole,
              email: sflActor.user,
              initials: initials(sflActor.displayName),
            }}
            hideThemeAction
            items={
              devToolsEnabled
                ? [
                    {
                      icon: <Icon name="user" size={20} />,
                      label: actorOverridden ? 'Change actor (override active)' : 'Change actor',
                      onClick: () => setSwitcherOpen(true),
                    },
                  ]
                : []
            }
            onSignOut={session ? handleSignOut : undefined}
            noConfirmSignOut
          />
        </AppHeaderActions>
      </AppHeader>

      <AppSidebar>
        <Sidebar variant="brand" mobileHeader={<SidebarBrand />}>
          <SidebarHeader>
            <SidebarBrand />
            <SidebarCollapse />
          </SidebarHeader>
          <SidebarContent>
            <SidebarNav aria-label="Sections">
              {sections.map((section) => (
                <SidebarGroup
                  key={section.heading}
                  collapsible
                  expanded={expanded.has(section.heading)}
                  onExpandedChange={(open) => toggleGroup(section.heading, open)}
                >
                  <SidebarGroupLabel>{section.heading}</SidebarGroupLabel>
                  {section.items.map((item) => (
                    <SidebarLink
                      key={item.to}
                      asChild
                      active={isCurrent(item, pathname)}
                      icon={<Icon name={item.icon} size={18} />}
                    >
                      <Link to={item.to}>{item.label}</Link>
                    </SidebarLink>
                  ))}
                </SidebarGroup>
              ))}

              <SidebarGroup>
                <SidebarGroupLabel>System</SidebarGroupLabel>
                <SidebarLink
                  asChild
                  icon={<Icon name="settings" size={18} />}
                >
                  <button type="button" onClick={() => setPreferencesOpen(true)}>
                    System preferences
                  </button>
                </SidebarLink>
                {session && (
                  <SidebarLink asChild icon={<Icon name="log-out" size={18} />}>
                    <button type="button" onClick={handleSignOut}>Logout</button>
                  </SidebarLink>
                )}
              </SidebarGroup>

              {/*
                An empty rail has two causes and they are not the same conversation. "Your roles
                grant nothing" is about the account; "the service did not answer" is about the
                deployment. The second used to be indistinguishable from the first, so an operator
                whose service was simply not running was told their roles were short - and went
                looking for an administrator instead of for the process.
              */}
              {sections.length === 0 && (
                <div className="px-3 py-4">
                  <p className="text-theme-sm font-medium text-white/85">
                    {permissionsUnavailable ? 'Permissions unavailable' : 'No programme assigned'}
                  </p>
                  <p className="mt-1 text-theme-xs text-white/60">
                    {permissionsUnavailable ??
                      'Your roles do not grant access to any SFL programme, so there is nothing to show here. Ask for the role that covers the work you need to do.'}
                  </p>
                </div>
              )}
            </SidebarNav>
          </SidebarContent>
        </Sidebar>
      </AppSidebar>

      <AppBody>
        {/*
          SC 2.4.1 Bypass Blocks. The rail is a run of links before any page content, and a keyboard
          or screen-reader user should not have to walk them on every navigation.
        */}
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-[999999] focus:rounded-lg focus:bg-white focus:px-4 focus:py-2.5 focus:text-theme-sm focus:font-medium focus:text-brand-900"
        >
          Skip to main content
        </a>
        <div id="main-content" tabIndex={-1} className="focus:outline-none">
          {/*
            Permissions could not be loaded, so nothing is being offered.

            This exists because the alternative was worse and invisible. The dashboard used to treat
            an unanswered permission lookup as "allow everything", which meant a service being down
            presented as a driver holding the whole fleet office - and one service being up
            presented as every other platform's controls silently vanishing. Neither said anything.
            Failing closed is only defensible if the operator is told, and this is where they are
            told.
          */}
          {permissionsUnavailable && (
            <div
              role="status"
              className="mb-5 rounded-lg border border-warning-300 bg-warning-50 px-4 py-3"
            >
              <p className="text-theme-sm font-semibold text-warning-800">
                Your permissions could not be loaded
              </p>
              <p className="mt-1 text-theme-sm text-warning-700">{permissionsUnavailable}</p>
            </div>
          )}
          <Outlet />
        </div>
      </AppBody>

      {ActorSwitcher && switcherOpen && (
        // No fallback surface: the chunk is local and the panel is modal, so a spinner behind a
        // backdrop that has not rendered yet would be the only thing on screen.
        <Suspense fallback={null}>
          <ActorSwitcher open onClose={() => setSwitcherOpen(false)} />
        </Suspense>
      )}

      {preferencesOpen && (
        <div className="fixed inset-0 z-[100] flex items-start justify-end bg-black/30 p-4" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setPreferencesOpen(false); }}>
          <section className="mt-14 w-[min(360px,calc(100vw-2rem))] rounded-xl border border-gray-200 bg-white p-5 shadow-theme-lg" role="dialog" aria-modal="true" aria-labelledby="system-preferences-title">
            <div className="flex items-center justify-between gap-4">
              <h2 id="system-preferences-title" className="text-base font-semibold text-gray-900">System preferences</h2>
              <button type="button" className="text-sm text-gray-500" onClick={() => setPreferencesOpen(false)} aria-label="Close system preferences">×</button>
            </div>
            <FontScaleMenu embedded />
            {session && (
              <button type="button" className="mt-5 w-full rounded-md border border-gray-300 px-3 py-2 text-left text-sm font-medium text-gray-800" onClick={handleSignOut}>
                Logout
              </button>
            )}
          </section>
        </div>
      )}
    </AppLayout>
  );
};

export default AppShell;

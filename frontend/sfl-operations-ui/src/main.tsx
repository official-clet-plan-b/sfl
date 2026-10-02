import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ThemeProvider } from '@rfdtech/components';
import App from './App';
import { loadActorPermissions } from 'shared/layout/actorPermissions';
import { loadActorSites } from 'shared/layout/actorSites';
import { loadServingPlatform } from 'shared/platform';
import './index.css';

const container = document.getElementById('root');

if (!container) {
  throw new Error('The #root element is missing from index.html.');
}

const render = () =>
  createRoot(container).render(
    <StrictMode>
      <ThemeProvider defaultTheme="light">
        <App />
      </ThemeProvider>
    </StrictMode>,
  );

/**
 * Learn which platform this origin serves, ask that service what this actor may do, then render.
 *
 * The order matters and is the whole fix: the permission source is chosen by platform, so asking
 * before the platform is known would ask the wrong service - or, as the previous version did, ask all
 * three from an origin that only owns one, and report a fleet service as unreachable to somebody
 * looking at facilities.
 *
 * The navigation, the route guard and the landing destination are synchronous, so both answers have
 * to be in hand before the first paint - a sidebar that renders wide and then narrows looks like a
 * bug rather than a permission. Neither call rejects and both carry their own timeout, so a hung or
 * missing service delays the paint briefly and then gets out of the way. When permissions cannot be
 * answered nothing is offered and the shell says so; see `actorPermissions.ts`.
 *
 * `loadActorSites` joins them for the same reason and resolves against the same platform answer: a
 * `*` site scope has to become real site codes before a filter or a create dialog reads it, and both
 * are synchronous. It is a no-op for the actors whose scope is already a list, which is most of them.
 * Run alongside the permission load rather than after it - neither needs the other's answer, and
 * two round trips in series would double the delay before the first paint for no gain.
 */
loadServingPlatform()
  .then(() => Promise.all([loadActorPermissions(), loadActorSites()]))
  .finally(render);

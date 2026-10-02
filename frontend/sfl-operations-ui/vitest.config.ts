import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import tsconfigPaths from 'vite-tsconfig-paths';

/**
 * Test configuration for the SFL Operations dashboard.
 *
 * Separate from `vite.config.ts` on purpose: that file computes `base` from the build mode and
 * stamps a build time into `define`, neither of which a test run should inherit - a suite whose
 * behaviour depends on the mode it was started in is a suite that will eventually disagree with CI.
 *
 * `tsconfigPaths` is what lets a test import `shared/...` and `modules/...` the same way the
 * application does, so a test file sits beside the code it covers without relative-path noise.
 */
export default defineConfig({
  plugins: [react(), tsconfigPaths()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: false,
    restoreMocks: true,

    /*
     * `@rfdtech/components` imports its own stylesheet from JavaScript. Node cannot load a `.css`
     * file, so left external the package fails at import time ("Unknown file extension .css") in
     * every test that touches a screen. Inlining sends it through Vite, which stubs the CSS the
     * same way it does for the application's own.
     */
    server: { deps: { inline: ['@rfdtech/components'] } },

    /*
     * The actor a test runs as, stated here rather than inherited from a file that is not in git.
     *
     * With no session, `sflActor` falls back to `VITE_SFL_*`, and `.env` - which supplies them on a
     * developer machine - is gitignored. So `SiteSelect.defaultSite` was `CLET-HQ` locally and the
     * empty string on CI, and every form that opens on the default site failed `required('Site')`
     * there. The submit then did nothing at all, which surfaced as "issue was called 0 times" and
     * reads exactly like a broken request handler.
     *
     * That is a whole class of test passing for a reason not present in the repository. Setting it
     * here makes the suite depend on committed configuration instead, and a checkout is enough to
     * reproduce what CI does.
     */
    env: {
      VITE_SFL_SITES: 'CLET-HQ',
    },

    /*
     * Twenty seconds, against a default of five.
     *
     * These are not long-running tests; they are ordinary ones on a slow machine. A dialog test
     * renders a data table, a modal and two flatpickr calendars, then drives it a keystroke at a
     * time - and `userEvent` re-renders React on every one of them. The fuel-card issue test takes
     * about 0.8s on a developer machine and ran out of budget on a GitHub runner, which is the same
     * work at three to five times the wall clock.
     *
     * The failure that produces is actively misleading: vitest reports "Test timed out in 5000ms"
     * with no indication of which interaction was still going, and the first reading is that the
     * application hung. It cost two CI rounds here to establish that nothing was wrong with the code
     * at all.
     *
     * A generous ceiling costs nothing on a passing run - a test that succeeds in 0.8s still takes
     * 0.8s - and it only ever spends the extra on a run that was going to fail anyway. Keep any
     * `waitFor` timeout comfortably below this, or the wait cannot report its own assertion before
     * the test is killed.
     */
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
});

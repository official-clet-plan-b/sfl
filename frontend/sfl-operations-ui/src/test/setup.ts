import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

/**
 * Node 26 exposes an experimental global `localStorage` accessor of its own. In a jsdom worker it
 * shadows jsdom's working storage object with `undefined`, so tests fail before rendering anything.
 * Pin the test global to the browser object the configured environment owns.
 */
const storageValues = new Map<string, string>();
const testStorage: Storage = {
  get length() { return storageValues.size; },
  clear: () => storageValues.clear(),
  getItem: (key) => storageValues.get(key) ?? null,
  key: (index) => [...storageValues.keys()][index] ?? null,
  removeItem: (key) => { storageValues.delete(key); },
  setItem: (key, value) => { storageValues.set(key, String(value)); },
};
Object.defineProperty(window, 'localStorage', { configurable: true, value: testStorage });
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: testStorage });

/**
 * jsdom has no pointer-capture API, and Radix Select (behind the library's Dropdown) calls it when a
 * list opens - without these stubs opening any dropdown in a test throws before the option renders.
 */
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.setPointerCapture ??= () => {};
Element.prototype.releasePointerCapture ??= () => {};
Element.prototype.scrollIntoView ??= () => {};

/**
 * Test bootstrap.
 *
 * `cleanup` after every test is what keeps one test's DOM out of the next one's queries - without
 * it a `getByText` can match a node the previous test rendered and the failure appears in the wrong
 * file entirely.
 */
afterEach(() => {
  cleanup();
});

/**
 * jsdom implements neither of these, and both are used by the dashboard's layout code.
 *
 * Stubbing them here rather than in each test keeps the failure - "matchMedia is not a function" -
 * out of tests that have nothing to do with responsive behaviour.
 */
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }),
});

if (!window.ResizeObserver) {
  window.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

/**
 * jsdom has no layout, so it ships no `scrollIntoView`. The shared Select keeps the highlighted
 * row in view when the list opens, which means every test that opens a listbox would otherwise
 * die on "node?.scrollIntoView is not a function" - a failure about scrolling, in tests that are
 * about choosing an option.
 */
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView() {};
}

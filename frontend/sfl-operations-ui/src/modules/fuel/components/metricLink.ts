import type { KeyboardEvent } from 'react';

/**
 * Makes a metric card a link to the register behind it.
 *
 * The library card is a plain block, so the keyboard and role that a clickable card needs are added
 * here: it is reachable by Tab, Enter or Space follows it, and it is announced as a link rather than
 * as inert text.
 */
export const metricLink = (onActivate: () => void) => ({
  role: 'link' as const,
  tabIndex: 0,
  className: 'cursor-pointer',
  onClick: onActivate,
  onKeyDown: (event: KeyboardEvent) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onActivate();
    }
  },
});

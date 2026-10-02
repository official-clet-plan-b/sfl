/**
 * Chart colours, resolved from the `@rfdtech/components` tokens.
 *
 * Recharts writes these straight into SVG presentation attributes, and the browser resolves
 * `var(--clet-*)` there exactly as it does in a stylesheet - so a chart follows the theme (and the
 * dark scheme) with the rest of the dashboard instead of carrying a second copy of the palette that
 * had to be kept in step by hand.
 *
 * The categorical order follows the fleet dashboard in Figma: a navy series with a blue accent beside
 * it. Navy, blue and a gold accent are far apart in hue and lightness, so they stay separable in
 * greyscale and to a viewer with deuteranopia (SC 1.4.1 - colour is never the only cue; every series
 * is also labelled). A panel that wants six series usually wants a table.
 *
 * The names are older than the tokens: `teal` is the blue accent, `tealMid` its lighter step. They
 * stay because modules reference them, and renaming eight call sites would hide what changed here.
 */

export const chartColors = {
  navy: 'var(--clet-primary)',
  navyMid: 'color-mix(in srgb, var(--clet-primary) 62%, white)',
  teal: 'var(--clet-info)',
  tealMid: 'color-mix(in srgb, var(--clet-info) 55%, white)',
  gold: 'var(--clet-secondary)',
  goldSoft: 'color-mix(in srgb, var(--clet-secondary) 55%, white)',
  success: 'var(--clet-success)',
  warning: 'var(--clet-warning)',
  error: 'var(--clet-error)',
  grey: 'var(--clet-text-muted)',
  greyLine: 'var(--clet-border-subtle)',
  text: 'var(--clet-text-muted)',
} as const;

/** Categorical sequence for series that carry no inherent status meaning. */
export const seriesColors = [
  chartColors.navy,
  chartColors.teal,
  chartColors.navyMid,
  chartColors.gold,
  chartColors.grey,
] as const;

/**
 * Tone-consistent with `Badge`: ready / caution / blocked read the same everywhere. The stacked
 * availability bar in the fleet design is navy / blue / red, which is `neutral`-free by construction:
 * `active` is the blue and `accent` the navy.
 */
export const toneColors = {
  ready: chartColors.success,
  caution: chartColors.warning,
  blocked: chartColors.error,
  neutral: chartColors.grey,
  active: chartColors.teal,
  accent: chartColors.navy,
} as const;

export const chartFont = 'var(--clet-font-body)';

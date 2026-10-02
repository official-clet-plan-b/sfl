import { ReactNode, useId, useState } from 'react';
import {
  Area,
  AreaChart as RechartsArea,
  Bar,
  BarChart as RechartsBar,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { chartColors, seriesColors } from './palette';

/**
 * The dashboard's charts, on Recharts.
 *
 * <h2>Why the exported shape did not change</h2>
 *
 * `AreaChart`, `BarChart`, `DonutChart` and `Sparkline` keep the props they had under ApexCharts -
 * `categories` and `series` for the first two, `labels`/`values`/`colors` for the donut. Eight module
 * files import them and none needed editing. A migration that also redesigned the API would have
 * meant reviewing eight dashboards for two unrelated reasons at once, and any rendering difference
 * would have been impossible to attribute.
 *
 * <h2>What Recharts buys, concretely</h2>
 *
 * It renders React elements rather than driving an imperative chart instance into a container div,
 * and that is what makes the interaction work rather than merely exist:
 *
 * - **Hover reaches the whole plot.** The shared cursor tracks the nearest category across every
 *   series, so a four-series area chart reads all four values at one pointer position. The Apex
 *   version needed `shared: true, intersect: false` to approximate this and still missed between
 *   points.
 * - **The tooltip is a component**, so it uses the dashboard's own type scale, spacing and status
 *   colours instead of a second styling system that had to be kept in step by hand.
 * - **Legends toggle series.** Clicking a legend entry hides that series and rescales the axis,
 *   which is the one interaction people actually reach for on a multi-series chart.
 * - **Resize is native.** `ResponsiveContainer` observes the element; the sidebar collapsing no
 *   longer leaves a chart at its old width until something else forces a reflow.
 *
 * <h2>Colours come from the library tokens</h2>
 *
 * `palette.ts` hands Recharts `var(--clet-*)` strings, which the browser resolves inside SVG
 * attributes, so the plot is themed by the same tokens as everything around it. Where the dashboard
 * once restated the palette as hex literals, a theme change now reaches the charts too.
 */

export interface Series {
  name: string;
  data: number[];
  color?: string;
}

interface CategorySeriesProps {
  categories: string[];
  series: Series[];
  height?: number;
  /** Whole numbers only - counts of vehicles, trips and defects are never fractional. */
  integerAxis?: boolean;
  stacked?: boolean;
  horizontal?: boolean;
  showLegend?: boolean;
}

const defaultColor = (index: number): string => seriesColors[index % seriesColors.length];

/** Recharts wants one object per category; the callers hold parallel arrays, so pivot here. */
const toRows = (categories: string[], series: Series[]): Record<string, string | number>[] =>
  categories.map((category, index) => {
    const row: Record<string, string | number> = { category };
    series.forEach((entry) => {
      row[entry.name] = entry.data[index] ?? 0;
    });
    return row;
  });

const axisTick = { fill: chartColors.text, fontSize: 12 };

const integerFormatter = (value: number) => String(Math.round(value));

interface TooltipEntry {
  name?: string | number;
  value?: string | number | (string | number)[];
  color?: string;
}

/**
 * The shared tooltip.
 *
 * Built rather than configured so it carries the dashboard's own type scale and card treatment, and
 * so a zero is shown rather than dropped - on an operations chart "0 defects" is a reading, and a
 * missing row reads as missing data.
 */
const ChartTooltip = ({
  active,
  payload,
  label,
}: {
  active?: boolean;
  payload?: TooltipEntry[];
  label?: string | number;
}): ReactNode => {
  if (!active || !payload?.length) {
    return null;
  }
  return (
    <div className="rounded-lg border border-border bg-background px-3 py-2 shadow-lg">
      <p className="mb-1 text-xs font-semibold text-foreground">{label}</p>
      <ul className="space-y-0.5">
        {payload.map((entry) => (
          <li key={String(entry.name)} className="flex items-center gap-2 text-xs">
            <span
              aria-hidden="true"
              className="h-2 w-2 shrink-0 rounded-full"
              style={{ backgroundColor: entry.color }}
            />
            <span className="text-muted-foreground">{entry.name}</span>
            <span className="ml-auto font-semibold text-foreground tabular-nums">
              {String(entry.value)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
};

/**
 * Series visibility, driven by the legend.
 *
 * Kept per chart instance rather than lifted, because hiding a series is a reading aid for the
 * person looking at it - not state any other part of the screen should react to.
 */
const useHiddenSeries = () => {
  const [hidden, setHidden] = useState<string[]>([]);
  const toggle = (name: string) =>
    setHidden((current) =>
      current.includes(name) ? current.filter((entry) => entry !== name) : [...current, name],
    );
  return { hidden, toggle };
};

const legendProps = (hidden: string[], toggle: (name: string) => void) => ({
  // Dots above the plot and flush left, as in the fleet design - the reading starts at the legend.
  verticalAlign: 'top' as const,
  align: 'left' as const,
  wrapperStyle: { paddingBottom: 12, paddingLeft: 4 },
  height: 36,
  iconType: 'circle' as const,
  iconSize: 8,
  onClick: (entry: { value?: string }) => entry.value && toggle(entry.value),
  formatter: (value: string) => (
    <span
      className="cursor-pointer text-xs font-medium"
      style={{ color: hidden.includes(value) ? chartColors.grey : 'var(--clet-text)' }}
    >
      {value}
    </span>
  ),
});

/** Trend over time. Used where the measure is continuous and the shape matters more than the value. */
export const AreaChart = ({
  categories,
  series,
  height = 260,
  integerAxis = true,
  showLegend = true,
}: CategorySeriesProps) => {
  const { hidden, toggle } = useHiddenSeries();
  const rows = toRows(categories, series);

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RechartsArea data={rows} margin={{ top: 8, right: 12, left: 0, bottom: 0 }}>
        <defs>
          {series.map((entry, index) => {
            const colour = entry.color ?? defaultColor(index);
            return (
              <linearGradient key={entry.name} id={`fill-${entry.name}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={colour} stopOpacity={0.28} />
                <stop offset="95%" stopColor={colour} stopOpacity={0.02} />
              </linearGradient>
            );
          })}
        </defs>
        <CartesianGrid stroke={chartColors.greyLine} vertical={false} />
        <XAxis dataKey="category" tick={axisTick} tickLine={false} axisLine={false} />
        <YAxis
          tick={axisTick}
          tickLine={false}
          axisLine={false}
          width={40}
          allowDecimals={!integerAxis}
          tickFormatter={integerAxis ? integerFormatter : undefined}
        />
        {/* A faint vertical rule under the pointer, so the reading is anchored to a category. */}
        <Tooltip content={<ChartTooltip />} cursor={{ stroke: chartColors.grey, strokeWidth: 1 }} />
        {showLegend && <Legend {...legendProps(hidden, toggle)} />}
        {series.map((entry, index) => (
          <Area
            key={entry.name}
            type="monotone"
            dataKey={entry.name}
            hide={hidden.includes(entry.name)}
            stroke={entry.color ?? defaultColor(index)}
            strokeWidth={2}
            fill={`url(#fill-${entry.name})`}
            // Dots only on hover: a 30-point series with a marker per point is noise, but the
            // hovered point must be identifiable.
            dot={false}
            activeDot={{ r: 4, strokeWidth: 2, stroke: 'var(--clet-bg)' }}
          />
        ))}
      </RechartsArea>
    </ResponsiveContainer>
  );
};

/** Comparison across categories. */
export const BarChart = ({
  categories,
  series,
  height = 260,
  integerAxis = true,
  stacked = false,
  horizontal = false,
  showLegend = true,
}: CategorySeriesProps) => {
  const { hidden, toggle } = useHiddenSeries();
  const rows = toRows(categories, series);

  return (
    <ResponsiveContainer width="100%" height={height}>
      <RechartsBar
        data={rows}
        layout={horizontal ? 'vertical' : 'horizontal'}
        margin={{ top: 8, right: 12, left: 0, bottom: 0 }}
        barCategoryGap={series.length > 1 ? '20%' : '38%'}
      >
        <CartesianGrid
          stroke={chartColors.greyLine}
          // The grid lines belong on the value axis, and which axis that is swaps with the layout.
          vertical={horizontal}
          horizontal={!horizontal}
        />
        {horizontal ? (
          <>
            <XAxis
              type="number"
              tick={axisTick}
              tickLine={false}
              axisLine={false}
              allowDecimals={!integerAxis}
              tickFormatter={integerAxis ? integerFormatter : undefined}
            />
            <YAxis
              type="category"
              dataKey="category"
              tick={axisTick}
              tickLine={false}
              axisLine={false}
              width={120}
            />
          </>
        ) : (
          <>
            <XAxis dataKey="category" tick={axisTick} tickLine={false} axisLine={false} />
            <YAxis
              tick={axisTick}
              tickLine={false}
              axisLine={false}
              width={40}
              allowDecimals={!integerAxis}
              tickFormatter={integerAxis ? integerFormatter : undefined}
            />
          </>
        )}
        <Tooltip content={<ChartTooltip />} cursor={{ fill: 'var(--clet-surface-subtle)' }} />
        {showLegend && <Legend {...legendProps(hidden, toggle)} />}
        {series.map((entry, index) => (
          <Bar
            key={entry.name}
            dataKey={entry.name}
            hide={hidden.includes(entry.name)}
            stackId={stacked ? 'stack' : undefined}
            fill={entry.color ?? defaultColor(index)}
            // A stacked column is one bar, so only its last segment takes the rounded cap - the
            // availability bar in the design is a single navy/blue/red column, not three pills.
            radius={
              stacked
                ? index === series.length - 1
                  ? horizontal
                    ? [0, 4, 4, 0]
                    : [4, 4, 0, 0]
                  : 0
                : horizontal
                  ? [0, 4, 4, 0]
                  : [4, 4, 0, 0]
            }
            maxBarSize={40}
          />
        ))}
      </RechartsBar>
    </ResponsiveContainer>
  );
};

interface DonutChartProps {
  labels: string[];
  values: number[];
  colors: string[];
  height?: number;
  /** Shown in the middle of the ring - usually the total the slices add up to. */
  centreLabel?: string;
}

/** Composition of a whole - readiness mix, workflow status mix. */
export const DonutChart = ({
  labels,
  values,
  colors,
  height = 260,
  centreLabel = 'Total',
}: DonutChartProps) => {
  const total = values.reduce((sum, value) => sum + value, 0);
  const rows = labels.map((label, index) => ({ name: label, value: values[index] ?? 0 }));

  /*
    The ring's own diameter, not the caller's `height` budget directly. Callers still pass the
    260-280 that used to be the whole chart-plus-legend box, and a donut that size leaves a
    three-or-four-row legend beside it nowhere to sit in a one-in-three-column card - the label
    column collapses to nothing before the ring gives up an inch. Capping the diameter and putting
    the legend beneath rather than beside it means the ring is never the reason the card doesn't
    fit; only the row count grows the total height, which the grid's `h-full` card already absorbs.
  */
  const ringSize = Math.max(140, Math.min(height, 200));
  const outerRadius = Math.round(ringSize / 2) - 4;
  const innerRadius = Math.round(outerRadius * 0.62);

  return (
    <div className="flex flex-col items-center gap-5">
      <div className="relative shrink-0" style={{ height: ringSize, width: ringSize }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={rows}
              dataKey="value"
              nameKey="name"
              innerRadius={innerRadius}
              outerRadius={outerRadius}
              paddingAngle={1}
              stroke="none"
              // Enlarging the hovered slice is the whole interaction here: a donut has no axis to
              // anchor a cursor to, so the slice itself has to acknowledge the pointer.
              activeShape={{ outerRadius: outerRadius + 6 }}
            >
              {rows.map((row, index) => (
                <Cell key={row.name} fill={colors[index] ?? defaultColor(index)} />
              ))}
            </Pie>
            <Tooltip content={<ChartTooltip />} />
          </PieChart>
        </ResponsiveContainer>

        {/*
          The centre total is absolutely positioned rather than drawn into the SVG, so it inherits
          the dashboard's font stack and tabular figures. `pointer-events-none` keeps it out of the
          way of the slice hover underneath it - without that, the middle of the chart swallows the
          pointer.
        */}
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-xs text-muted-foreground">
            {centreLabel}
          </span>
          <span className="text-title-sm font-bold text-primary tabular-nums">
            {total}
          </span>
        </div>
      </div>

      {/*
        A row per slice rather than the plotting kit's own legend, because a name and a dot answer
        "what is this colour" but not "is it a lot" - the two questions a composition chart exists
        to answer. Value and share sit in their own columns so every row's numbers line up under
        the next, which a legend's run-on text can never do.
      */}
      <ul className="w-full max-w-xs min-w-0 space-y-3">
        {rows.map((row, index) => {
          const share = total > 0 ? Math.round((row.value / total) * 100) : 0;
          return (
            <li key={row.name} className="flex items-center gap-2.5">
              <span
                aria-hidden="true"
                className="h-2.5 w-2.5 shrink-0 rounded-full"
                style={{ backgroundColor: colors[index] ?? defaultColor(index) }}
              />
              <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground">
                {row.name}
              </span>
              <span className="text-sm font-semibold text-foreground tabular-nums">
                {row.value}
              </span>
              <span className="w-10 shrink-0 text-right text-xs text-muted-foreground tabular-nums">
                {share}%
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
};

interface SparklineProps {
  values: number[];
  colour?: string;
  height?: number;
}

/** A bare trend line for a KPI card. No axes, no tooltip - shape only. */
export const Sparkline = ({ values, colour = chartColors.navy, height = 42 }: SparklineProps) => {
  // `colour` is a token expression now, not a hex, so it cannot be spliced into an id.
  const gradientId = `spark-${useId().replace(/:/g, '')}`;
  return (
    <ResponsiveContainer width="100%" height={height}>
      <RechartsArea
        data={values.map((value, index) => ({ index, value }))}
        margin={{ top: 2, right: 0, left: 0, bottom: 0 }}
      >
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={colour} stopOpacity={0.3} />
            <stop offset="100%" stopColor={colour} stopOpacity={0} />
          </linearGradient>
        </defs>
        <Area
          type="monotone"
          dataKey="value"
          stroke={colour}
          strokeWidth={2}
          fill={`url(#${gradientId})`}
          dot={false}
          isAnimationActive={false}
        />
      </RechartsArea>
    </ResponsiveContainer>
  );
};

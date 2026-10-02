import type { KeyboardEvent, ReactNode } from 'react';
import { MetricCard } from '@rfdtech/components';
import Icon, { type IconName } from 'shared/components/Icon';

export type StatTone = 'neutral' | 'good' | 'caution' | 'critical' | 'accent';

/**
 * Tone touches the icon beside the caption and never the figure: a row where every number is a
 * different colour makes the two that need attention indistinguishable from the six that do not.
 * The caption is where a measure says it is in trouble, in words as well as colour.
 */
const toneColour: Record<StatTone, string> = {
  neutral: 'text-[var(--clet-text-secondary)]',
  accent: 'text-[var(--clet-primary)]',
  good: 'text-[var(--clet-success-text)]',
  caution: 'text-[var(--clet-warning-text)]',
  critical: 'text-[var(--clet-error-text)]',
};

interface StatMetricProps {
  label: string;
  value: number | string;
  icon: IconName;
  tone?: StatTone;
  caption?: ReactNode;
  /** Makes the whole card a control that shows the records behind the figure. */
  onClick?: () => void;
  loading?: boolean;
}

/** A headline figure: a soft `MetricCard`, optionally a button that drills into its records. */
const StatMetric = ({ label, value, icon, tone = 'neutral', caption, onClick, loading }: StatMetricProps) => {
  const press = onClick
    ? {
        role: 'button' as const,
        tabIndex: 0,
        'aria-label': `${label}: ${value}. Show the records behind this figure.`,
        onClick,
        onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            onClick();
          }
        },
        className: 'cursor-pointer',
      }
    : {};

  return (
    <MetricCard
      variant="soft"
      label={label}
      value={value}
      loading={loading}
      description={typeof caption === 'string' ? caption : undefined}
      descriptionAdornment={
        <span className={toneColour[tone]} aria-hidden="true">
          <Icon name={icon} size={16} />
        </span>
      }
      {...press}
    />
  );
};

export default StatMetric;

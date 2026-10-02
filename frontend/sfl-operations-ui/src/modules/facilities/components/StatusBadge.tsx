import { Badge } from '@rfdtech/components';
import type { BadgeProps } from '@rfdtech/components';
import { humaniseCode } from './facilitiesFormat';
import type { Tone } from './facilitiesFormat';

/**
 * Tone → library variant. `accent` is the one mode that is neither good nor bad - a centre running
 * under examination rules - so it takes the outline treatment rather than borrowing a status colour.
 */
const variants: Record<Tone, NonNullable<BadgeProps['variant']>> = {
  ready: 'success',
  caution: 'warning',
  blocked: 'error',
  neutral: 'default',
  active: 'primary',
  accent: 'outline',
};

/**
 * The tone of a word no caller judged for itself: record lifecycle and the criticality scale.
 *
 * Anything unmapped is neutral rather than coloured, so an enum value added later reads as ordinary
 * until somebody decides what it means.
 */
const tones: Record<string, Tone> = {
  ACTIVE: 'ready',
  INACTIVE: 'neutral',
  SUSPENDED: 'blocked',
  ARCHIVED: 'neutral',
  LOW: 'neutral',
  MEDIUM: 'active',
  HIGH: 'caution',
  CRITICAL: 'blocked',
  READY: 'ready',
  DEGRADED: 'caution',
  BLOCKED: 'blocked',
};

interface StatusBadgeProps {
  /** The enum value, as the service sends it. It is humanised for display unless `label` is given. */
  value: string | null | undefined;
  /** Overrides the lookup when the caller has judged the value in context. */
  tone?: Tone;
  label?: string;
  size?: BadgeProps['size'];
}

/** A status word as a library `Badge`, coloured by what the status means. */
const StatusBadge = ({ value, tone, label, size }: StatusBadgeProps) => (
  <Badge variant={variants[tone ?? (value ? (tones[value] ?? 'neutral') : 'neutral')]} size={size}>
    {label ?? humaniseCode(value)}
  </Badge>
);

export default StatusBadge;

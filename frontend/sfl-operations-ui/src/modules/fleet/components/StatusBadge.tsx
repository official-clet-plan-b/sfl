import { ReactNode } from 'react';
import { Badge, type BadgeVariant } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

export type Tone = 'ready' | 'caution' | 'blocked' | 'neutral' | 'active' | 'accent';

const toneVariants: Record<Tone, BadgeVariant> = {
  ready: 'success',
  caution: 'warning',
  blocked: 'error',
  neutral: 'default',
  active: 'primary',
  accent: 'outline',
};

/**
 * One table maps every backend status value to a tone, so "OVERDUE" reads the same on the
 * dashboard, the register and the detail page. Anything unmapped falls back to neutral rather than
 * inventing a colour, which keeps an unknown future enum value legible instead of alarming.
 */
const statusTones: Record<string, Tone> = {
  // Readiness
  READY: 'ready',
  CONDITIONALLY_READY: 'caution',
  NOT_READY: 'blocked',

  // Vehicle lifecycle / service / availability
  ACTIVE: 'ready',
  INACTIVE: 'neutral',
  SUSPENDED: 'blocked',
  ARCHIVED: 'neutral',
  IN_SERVICE: 'ready',
  DUE: 'caution',
  OVERDUE: 'blocked',
  OUT_OF_SERVICE: 'blocked',
  AVAILABLE: 'ready',
  RESERVED: 'accent',
  ASSIGNED: 'active',
  IN_USE: 'active',
  UNAVAILABLE: 'blocked',

  // Driver
  ELIGIBLE: 'ready',
  CONDITIONAL: 'caution',
  INELIGIBLE: 'blocked',

  // Trips
  PLANNED: 'neutral',
  IN_PROGRESS: 'active',
  ON_HOLD: 'caution',
  COMPLETED: 'ready',
  CANCELLED: 'neutral',

  // Inspections
  PASSED: 'ready',
  PASSED_WITH_DEFECTS: 'caution',
  FAILED: 'blocked',
  DRAFT: 'neutral',
  SUBMITTED: 'active',
  ACCEPTED: 'ready',
  REJECTED: 'blocked',

  // Defects and workflow severity
  ADVISORY: 'neutral',
  MINOR: 'neutral',
  MODERATE: 'caution',
  MAJOR: 'caution',
  CRITICAL: 'blocked',

  // Compliance
  EXPIRING: 'caution',
  EXPIRED: 'blocked',
  SUPERSEDED: 'neutral',
  REVOKED: 'blocked',

  // Workflow status
  OPEN: 'active',
  ESCALATED: 'blocked',
  CLOSED: 'ready',
  REOPENED: 'caution',

  // Workflow priority
  LOW: 'neutral',
  MEDIUM: 'active',
  HIGH: 'caution',
  URGENT: 'blocked',

  // Integration inbox
  PROCESSED: 'ready',
  DEAD_LETTER: 'blocked',

  // Blocker severity
  WARNING: 'caution',
  BLOCKING: 'blocked',

  // Driver acknowledgement
  CONFIRMED: 'ready',
};

export const toneFor = (value: string | null | undefined): Tone =>
  value ? (statusTones[value] ?? 'neutral') : 'neutral';

interface StatusBadgeProps {
  value: string | null | undefined;
  /** Overrides the tone lookup when context changes the meaning of a value. */
  tone?: Tone;
  label?: string;
}

/** A status word in the library's `Badge`; colour is never the only carrier, the word is in it. */
const StatusBadge = ({ value, tone, label }: StatusBadgeProps) => (
  <Badge variant={toneVariants[tone ?? toneFor(value)]}>{label ?? humanise(value)}</Badge>
);

export default StatusBadge;

/** A tab label with the count of what is behind it, for the `Tabs` triggers. */
export const tabLabel = (label: string, count?: number): ReactNode => (
  <>
    {label}
    {count !== undefined && <Badge size="sm">{count}</Badge>}
  </>
);

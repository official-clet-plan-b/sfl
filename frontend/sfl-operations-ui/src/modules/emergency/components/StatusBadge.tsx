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
 * One table maps every backend status value used by the safety and security screens to a tone, so
 * "OVERDUE" reads the same on the dashboard, the register and the detail page. Anything unmapped
 * falls back to neutral rather than inventing a colour, which keeps an unknown future enum value
 * legible instead of alarming.
 *
 * Where context changes what a value means - `ACTIVE` is "fine" for a record and a live broadcast
 * for an activation - the caller passes `tone` and states its own reading instead of editing this.
 */
const statusTones: Record<string, Tone> = {
  // Record lifecycle
  ACTIVE: 'ready',
  INACTIVE: 'neutral',
  SUSPENDED: 'blocked',
  ARCHIVED: 'neutral',
  DRAFT: 'neutral',
  SUBMITTED: 'active',
  ACCEPTED: 'ready',
  REJECTED: 'blocked',
  APPROVED: 'ready',
  PLANNED: 'neutral',
  IN_PROGRESS: 'active',
  COMPLETED: 'ready',
  CANCELLED: 'neutral',
  EXPIRING: 'caution',
  EXPIRED: 'blocked',
  SUPERSEDED: 'neutral',
  REVOKED: 'blocked',
  FAILED: 'blocked',
  PASSED: 'ready',

  // Severity and priority
  MINOR: 'neutral',
  MODERATE: 'caution',
  MAJOR: 'caution',
  CRITICAL: 'blocked',
  LOW: 'neutral',
  MEDIUM: 'active',
  HIGH: 'caution',
  URGENT: 'blocked',

  // Workflow
  OPEN: 'active',
  ESCALATED: 'blocked',
  CLOSED: 'ready',
  REOPENED: 'caution',
  WARNING: 'caution',
  BLOCKING: 'blocked',
  PROCESSED: 'ready',
  DEAD_LETTER: 'blocked',

  // Emergency notification
  PENDING_APPROVAL: 'caution',
  ACTIVATING: 'active',
  BREAK_GLASS_ACTIVE: 'blocked',
  ALL_CLEAR_PENDING: 'caution',
  SENDING: 'active',
  QUEUED: 'neutral',
  SENT: 'active',
  ROUTINE: 'neutral',
  BREAK_GLASS: 'blocked',
  DEGRADED: 'caution',
  RUNNING: 'active',

  // Visitor management
  PRE_REGISTERED: 'caution',
  CONFIRMED: 'ready',
  CHECKED_IN: 'active',
  CHECKED_OUT: 'ready',
  NO_SHOW: 'blocked',
  MEETING: 'neutral',
  INTERVIEW: 'accent',
  EVENT: 'active',
  DELIVERY: 'neutral',
  MAINTENANCE_CONTRACTOR: 'caution',

  // HSE incident and near-miss management
  TRIAGE: 'caution',
  INVESTIGATING: 'active',
  EMERGENCY: 'blocked',
  VERIFIED: 'ready',
  NEAR_MISS: 'caution',
  INCIDENT: 'blocked',
};

export const toneFor = (value: string | null | undefined): Tone =>
  value ? (statusTones[value] ?? 'neutral') : 'neutral';

interface StatusBadgeProps {
  value: string | null | undefined;
  /** Overrides the tone lookup when context changes the meaning of a value. */
  tone?: Tone;
  label?: string;
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * A status as a library `Badge`. The status word is always in the badge, so colour is never the
 * only carrier of meaning.
 */
const StatusBadge = ({ value, tone, label, size = 'sm', className }: StatusBadgeProps) => (
  <Badge variant={toneVariants[tone ?? toneFor(value)]} size={size} className={className}>
    {label ?? humanise(value)}
  </Badge>
);

export default StatusBadge;

import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

export type Tone = 'ready' | 'caution' | 'blocked' | 'neutral' | 'active' | 'accent';

/**
 * Tone as the library's badge variants: success, warning, error, primary, outline and the neutral
 * default. Colour is never the only carrier - the status word is always in the badge (SC 1.4.1).
 */
const toneVariant = {
  ready: 'success',
  caution: 'warning',
  blocked: 'error',
  neutral: 'default',
  active: 'primary',
  accent: 'outline',
} as const;

/**
 * One table maps every backend status value this module shows to a tone, so "OVERDUE" reads the same
 * on the dashboard, the registers and the detail pages. Anything unmapped falls back to neutral
 * rather than inventing a colour, which keeps an unknown future enum value legible instead of alarming.
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

  // Fuel transaction status and lifecycle (S168). RECONCILED is the settled end state; EXCEPTION is
  // the one that puts a case on somebody's queue, so it takes the same tone as a failed inspection.
  RECEIVED: 'neutral',
  VALIDATING: 'active',
  // MATCHED and RECONCILED are shared with dispatch, where they mean a clean return or scan
  // outcome and a reconciled consignment. Both readings are settled states, so one tone serves.
  MATCHED: 'ready',
  RECONCILED: 'ready',
  EXCEPTION: 'blocked',
  VOIDED: 'neutral',

  // Driver logbook. RETURNED is amber because the record is back with the driver to correct, not
  // because anything failed; RESUBMITTED reads the same as SUBMITTED, which is what it is.
  UNDER_REVIEW: 'active',
  RETURNED: 'caution',
  RESUBMITTED: 'active',
  APPROVED: 'ready',

  // Dispatch (S171). STAGED is work in hand; DELIVERED is a settled end.
  STAGED: 'active',
  DELIVERED: 'ready',
  SEALED: 'active',
  // Seal state - a compromised seal is the thing an operator must not miss.
  INTACT: 'ready',
  BROKEN: 'blocked',
  REPLACED: 'caution',
  MISSING: 'blocked',
  // Receipt and return outcomes.
  CLEAN: 'ready',
  VARIANCE: 'blocked',
  DISCREPANCY: 'blocked',
  MISMATCH: 'blocked',
  UNREGISTERED: 'caution',
  // Manifest item return standing.
  OUTSTANDING: 'blocked',
  // Scan batch.
  PARTIAL: 'caution',
  // Sensitivity - SECRET is not an alarm, but it must never read as ordinary.
  ORDINARY: 'neutral',
  CONFIDENTIAL: 'caution',
  SECRET: 'blocked',
  // Direction.
  INBOUND: 'active',
  OUTBOUND: 'accent',
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

const StatusBadge = ({ value, tone, label, size = 'sm', className }: StatusBadgeProps) => (
  <Badge variant={toneVariant[tone ?? toneFor(value)]} size={size} className={className}>
    {label ?? humanise(value)}
  </Badge>
);

export default StatusBadge;

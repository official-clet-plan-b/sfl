import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

type Tone = 'success' | 'warning' | 'error' | 'primary' | 'outline' | 'default';

/**
 * One table maps every S170 status, severity and link state to a tone so "overdue" and "critical"
 * read the same on every tab. Colour is never the only carrier: the word is always in the badge.
 */
const tones: Record<string, Tone> = {
  // control
  SCHEDULED: 'default',
  DUE: 'warning',
  OVERDUE: 'error',
  IN_PROGRESS: 'primary',
  COMPLETED: 'success',
  MISSED: 'error',
  CANCELLED: 'default',
  // finding
  OPEN: 'warning',
  AWAITING_VERIFICATION: 'primary',
  CLOSED: 'success',
  // action and evidence
  VERIFIED: 'success',
  REJECTED: 'error',
  SUBMITTED: 'primary',
  ACCEPTED: 'success',
  // severity
  LOW: 'default',
  MEDIUM: 'outline',
  HIGH: 'warning',
  CRITICAL: 'error',
  // links
  NOT_REQUIRED: 'default',
  PENDING_MANUAL: 'warning',
  LINKED: 'success',
  RAISED: 'success',
  // escalation
  OWNER: 'outline',
  HSE: 'warning',
  LEADERSHIP: 'error',
};

export const HygieneBadge = ({ value, label }: { value: string | null | undefined; label?: string }) => (
  <Badge variant={tones[value ?? ''] ?? 'default'} size="sm">{label ?? humanise(value)}</Badge>
);

export const controlTypes = ['AUDIT', 'PEST_VISIT', 'STATUTORY_CHECK'] as const;
export const riskCategories = ['FOOD_SAFETY', 'PEST', 'WASTE', 'SANITATION', 'WATER', 'GENERAL'] as const;
export const frequencies = ['ONE_OFF', 'WEEKLY', 'FORTNIGHTLY', 'MONTHLY', 'QUARTERLY', 'BIANNUAL', 'ANNUAL'] as const;
export const severities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export const controlStatuses = ['SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'MISSED', 'CANCELLED'] as const;
export const findingStatuses = ['OPEN', 'IN_PROGRESS', 'AWAITING_VERIFICATION', 'CLOSED'] as const;

export const options = (values: readonly string[]) => values.map((value) => ({ value, label: humanise(value) }));

/** What a link state means to the person looking at it - never "linked" for something that is not. */
export const linkText = (state: string, reference?: string | null, system = ''): string => {
  switch (state) {
    case 'NOT_REQUIRED': return 'Not required';
    case 'PENDING_MANUAL': return `Pending - ${system} has not confirmed it`;
    case 'LINKED':
    case 'RAISED': return reference ? `${system} ${reference}` : 'Done';
    default: return humanise(state);
  }
};

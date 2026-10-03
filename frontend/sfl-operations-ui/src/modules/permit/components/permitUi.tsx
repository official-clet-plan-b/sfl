import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

type Tone = 'success' | 'warning' | 'error' | 'primary' | 'outline' | 'default';

/** One table maps every S164 status to a tone. The word is always in the badge. */
const tones: Record<string, Tone> = {
  DRAFT: 'default', SUBMITTED: 'warning', ISOLATION_VERIFIED: 'warning', STAGE1_APPROVED: 'warning', ACTIVE: 'success', SUSPENDED: 'error', RESUMPTION_PENDING: 'warning',
  WORK_COMPLETE: 'primary', CLOSED: 'default', REJECTED: 'error', CANCELLED: 'default',
  LOW: 'success', MEDIUM: 'warning', HIGH: 'error', CRITICAL: 'error',
  REQUIRED: 'warning', VERIFIED: 'success', REMOVED: 'outline',
  PENDING: 'warning', APPROVED: 'success', QUEUED: 'warning', DELIVERED: 'success', FAILED: 'error', OPEN: 'warning', REVIEWED: 'success',
  NEARING_EXPIRY: 'warning', OVERDUE: 'error',
};

export const PermitBadge = ({ value, label }: { value: string | null | undefined; label?: string }) => (
  <Badge variant={tones[value ?? ''] ?? 'default'} size="sm">{label ?? humanise(value)}</Badge>
);

export const statuses = ['DRAFT', 'SUBMITTED', 'ISOLATION_VERIFIED', 'STAGE1_APPROVED', 'ACTIVE', 'SUSPENDED', 'RESUMPTION_PENDING', 'WORK_COMPLETE', 'CLOSED', 'REJECTED', 'CANCELLED'] as const;
export const riskLevels = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export const workRoles = ['SUPERVISOR', 'OPERATIVE', 'FIRE_WATCH', 'STANDBY'] as const;
export const isolationKinds = ['ELECTRICAL', 'MECHANICAL', 'GAS_FUEL', 'PRESSURE', 'ZONE_ACCESS', 'OTHER'] as const;
export const originSystems = ['NONE', 'S153', 'S176'] as const;
export const evidenceKinds = ['PHOTO', 'CHECKLIST', 'OTHER'] as const;

export const options = (values: readonly string[]) => values.map((value) => ({ value, label: humanise(value) }));

/** The approval stage in words an authoriser recognises. */
export const stageLabel = (stage: string | null | undefined): string => (stage === 'SAFETY_SIGN_OFF' ? 'Safety sign-off' : stage === 'ISSUING_AUTHORITY' ? 'Issuing authority' : '-');

/** An ISO instant for a `datetime-local`-style input: the dialogs hold local text and send ISO. */
export const hoursFromNow = (hours: number): string => {
  const date = new Date(Date.now() + hours * 3_600_000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
};

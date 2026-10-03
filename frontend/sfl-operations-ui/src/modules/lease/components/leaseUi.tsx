import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

type Tone = 'success' | 'warning' | 'error' | 'primary' | 'outline' | 'default';

/** One table maps every S177 status to a tone. The word is always in the badge. */
const tones: Record<string, Tone> = {
  DRAFT: 'default', IN_REVIEW: 'warning', ACTIVE: 'success', EXPIRED: 'error', TERMINATED: 'default', ARCHIVED: 'default',
  PROPOSED: 'warning', LEGAL_REVIEW: 'error', APPROVED: 'success', REJECTED: 'error', WITHDRAWN: 'default',
  RAISED: 'success', PENDING_MANUAL: 'warning',
  OPEN: 'warning', DONE: 'success', WAIVED: 'outline',
  UNRESOLVED: 'warning', VERIFIED: 'success',
  OWNER: 'outline', MANAGER: 'warning', DIRECTOR: 'error', LEGAL: 'error',
};

export const LeaseBadge = ({ value, label }: { value: string | null | undefined; label?: string }) => (
  <Badge variant={tones[value ?? ''] ?? 'default'} size="sm">{label ?? humanise(value)}</Badge>
);

export const kinds = ['LEASE', 'TENANCY'] as const;
export const directions = ['INBOUND', 'OUTBOUND'] as const;
export const renewalTypes = ['NONE', 'OPTION', 'AUTO'] as const;
export const agreementStatuses = ['DRAFT', 'IN_REVIEW', 'ACTIVE', 'EXPIRED', 'TERMINATED', 'ARCHIVED'] as const;
export const amendmentKinds = ['RENT_CHANGE', 'TERM_CHANGE', 'RENEWAL', 'TERMINATION'] as const;
export const amendmentStatuses = ['PROPOSED', 'LEGAL_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'] as const;
export const obligationKinds = ['RENEWAL', 'NOTICE', 'RENT_REVIEW', 'INSURANCE', 'COMPLIANCE', 'DOCUMENT_EXPIRY', 'PAYMENT', 'OTHER'] as const;
export const documentKinds = ['SIGNED_AGREEMENT', 'APPROVAL_EVIDENCE', 'INSURANCE_CERTIFICATE', 'COMPLIANCE_CERTIFICATE', 'NOTICE', 'TERMINATION_NOTICE', 'OTHER'] as const;
export const obligationStatuses = ['OPEN', 'DONE', 'WAIVED'] as const;

export const options = (values: readonly string[]) => values.map((value) => ({ value, label: humanise(value) }));

/** Money as the service sent it, or a plain statement that the viewer is not shown it - never a blank or a zero that reads as a figure. */
export const money = (value: number | null | undefined, currency: string | null | undefined, visible = true): string =>
  !visible ? 'Not shown' : value == null ? 'None' : `${currency ?? ''} ${Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`.trim();

/** What the amendment would change, in words. */
export const describeAmendment = (m: { kind: string; newEndDate: string | null; newAnnualRent: number | null; newDepositAmount: number | null; newNoticeDays: number | null; newRentReviewDate: string | null; effectiveOn: string | null }, currency?: string | null): string => {
  const parts: string[] = [];
  if (m.newEndDate) parts.push(`end date ${m.newEndDate}`);
  if (m.newAnnualRent != null) parts.push(`annual rent ${money(m.newAnnualRent, currency)}`);
  if (m.newDepositAmount != null) parts.push(`deposit ${money(m.newDepositAmount, currency)}`);
  if (m.newNoticeDays != null) parts.push(`notice ${m.newNoticeDays} days`);
  if (m.newRentReviewDate) parts.push(`rent review ${m.newRentReviewDate}`);
  if (m.kind === 'TERMINATION' && m.effectiveOn) parts.push(`ends ${m.effectiveOn}`);
  return parts.length ? humanise(m.kind) + ': ' + parts.join(', ') : humanise(m.kind);
};

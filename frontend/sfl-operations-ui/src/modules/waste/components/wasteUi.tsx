import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

type Tone = 'success' | 'warning' | 'error' | 'primary' | 'outline' | 'default';

/** One table maps every S178 status to a tone so "missed" and "open" read the same everywhere. The word is always in the badge. */
const tones: Record<string, Tone> = {
  SCHEDULED: 'default', COLLECTED: 'primary', HANDED_OVER: 'primary', DESTINATION_CONFIRMED: 'primary', CLOSED: 'success',
  MISSED: 'error', CANCELLED: 'default',
  SUBMITTED: 'primary', ACCEPTED: 'success', REJECTED: 'error',
  OPEN: 'warning', IN_PROGRESS: 'primary', RESOLVED: 'success',
  APPROVED: 'success', SUSPENDED: 'error',
  MEASURED: 'success', ESTIMATED: 'warning',
  HAZARDOUS: 'error', PENDING_MANUAL: 'warning', RAISED: 'success', LINKED: 'success',
};

export const WasteBadge = ({ value, label }: { value: string | null | undefined; label?: string }) => (
  <Badge variant={tones[value ?? ''] ?? 'default'} size="sm">{label ?? humanise(value)}</Badge>
);

export const categories = ['GENERAL', 'RECYCLABLE', 'ORGANIC', 'ELECTRONIC', 'CHEMICAL', 'MEDICAL', 'CONSTRUCTION', 'OTHER'] as const;
export const destinationTypes = ['RECYCLER', 'REUSE', 'COMPOSTING', 'TREATMENT', 'LANDFILL'] as const;
export const collectionStatuses = ['SCHEDULED', 'COLLECTED', 'HANDED_OVER', 'DESTINATION_CONFIRMED', 'CLOSED', 'MISSED', 'CANCELLED'] as const;
export const exceptionTypes = ['MISSED_COLLECTION', 'CONTAMINATION', 'MISSING_CERTIFICATE', 'MISSING_RECEIVING_EVIDENCE', 'SPILL', 'UNAPPROVED_CARRIER', 'UNAPPROVED_DESTINATION'] as const;
export const exceptionStatuses = ['OPEN', 'IN_PROGRESS', 'RESOLVED'] as const;
export const evidenceKinds = ['MANIFEST', 'RECEIVING', 'CERTIFICATE', 'PHOTO'] as const;

export const options = (values: readonly string[]) => values.map((value) => ({ value, label: humanise(value) }));

/** A quantity as entered, with the kilograms it normalises to; an estimate is never shown as a plain figure. */
export const quantityText = (c: { quantity: number | null; unit: string | null; quantityKg: number | null }): string =>
  c.quantity == null ? '-' : `${c.quantity} ${c.unit ?? ''}${c.unit && c.unit !== 'KG' ? ` (${c.quantityKg} kg)` : ''}`;

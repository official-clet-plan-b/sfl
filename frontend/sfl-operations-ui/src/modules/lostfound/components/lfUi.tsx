import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

type Tone = 'success' | 'warning' | 'error' | 'primary' | 'outline' | 'default';

/** One table maps every S179 status to a tone. The word is always in the badge. */
const tones: Record<string, Tone> = {
  REGISTERED: 'warning', STORED: 'primary', ISOLATED: 'error', RELEASED: 'success', DISPOSED: 'default', HANDED_TO_AUTHORITIES: 'default',
  RECEIVED: 'warning', VERIFIED: 'primary', APPROVED: 'primary', REFUSED: 'error', WITHDRAWN: 'default',
  UNSAFE_ITEM: 'error', COMPETING_CLAIMS: 'warning', RETENTION_EXPIRED: 'warning', PENDING_MANUAL: 'warning', LINKED: 'success', NOT_REQUIRED: 'default',
  SECURE: 'outline',
};

export const LfBadge = ({ value, label }: { value: string | null | undefined; label?: string }) => (
  <Badge variant={tones[value ?? ''] ?? 'default'} size="sm">{label ?? humanise(value)}</Badge>
);

export const categories = ['DOCUMENT', 'ELECTRONICS', 'JEWELLERY', 'CASH_VALUABLES', 'CLOTHING', 'BAG', 'KEYS', 'MEDICAL', 'OTHER'] as const;
export const itemStatuses = ['REGISTERED', 'STORED', 'ISOLATED', 'RELEASED', 'DISPOSED', 'HANDED_TO_AUTHORITIES'] as const;
export const claimStatuses = ['RECEIVED', 'VERIFIED', 'APPROVED', 'REFUSED', 'RELEASED', 'WITHDRAWN'] as const;
export const verificationMethods = ['ID_DOCUMENT', 'STAFF_ID', 'VISITOR_RECORD', 'KNOWN_TO_STAFF'] as const;
export const evidenceKinds = ['PHOTO', 'VERIFICATION', 'RELEASE_RECEIPT', 'DISPOSAL_AUTHORISATION', 'AUTHORITY_RECEIPT'] as const;

export const options = (values: readonly string[]) => values.map((value) => ({ value, label: humanise(value) }));

/** The categories that must be kept in secure storage, and whose detail can identify the owner - mirrored from the service for the form's hints only. */
export const secureCategories: readonly string[] = ['DOCUMENT', 'ELECTRONICS', 'JEWELLERY', 'CASH_VALUABLES', 'MEDICAL'];

import { Badge } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';

type Tone = 'success' | 'warning' | 'error' | 'primary' | 'outline' | 'default';

/** One table maps every S172 status to a tone. The word is always in the badge. */
const tones: Record<string, Tone> = {
  DRAFT: 'default', PENDING_APPROVAL: 'warning', APPROVED: 'primary', CONFIRMED: 'primary', DELIVERED: 'primary', RECONCILED: 'success', CLOSED: 'success', CANCELLED: 'default',
  OPEN: 'warning', SUBSTITUTED: 'success', WAIVED: 'outline', RESOLVED: 'success',
  PASS: 'success', FAIL: 'error',
  SUSPENDED: 'error', RETIRED: 'default',
  PENDING_FINANCE: 'warning', RECORDED: 'outline', NOT_STARTED: 'default',
  PENDING_MANUAL: 'warning', LINKED: 'success', NOT_REQUIRED: 'default',
  FOOD_SAFETY_INCIDENT: 'error',
};

export const CateringBadge = ({ value, label }: { value: string | null | undefined; label?: string }) => (
  <Badge variant={tones[value ?? ''] ?? 'default'} size="sm">{label ?? humanise(value)}</Badge>
);

export const allergens = ['CELERY', 'CEREALS_GLUTEN', 'CRUSTACEANS', 'EGGS', 'FISH', 'LUPIN', 'MILK', 'MOLLUSCS', 'MUSTARD', 'NUTS', 'PEANUTS', 'SESAME', 'SOYA', 'SULPHITES'] as const;
export const dietaryTags = ['VEGETARIAN', 'VEGAN', 'HALAL', 'KOSHER', 'GLUTEN_FREE', 'DAIRY_FREE', 'NUT_FREE'] as const;
export const contextTypes = ['EVENT', 'BOOKING', 'EXAMINATION', 'ROUTINE'] as const;
export const serviceStatuses = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'DELIVERED', 'RECONCILED', 'CLOSED', 'CANCELLED'] as const;
export const exceptionTypes = ['SHORTAGE', 'SUBSTITUTION', 'FOOD_SAFETY_INCIDENT', 'SERVICE_EXCEPTION'] as const;
export const varianceKinds = ['QUANTITY', 'COST', 'SUBSTITUTION'] as const;
export const evidenceKinds = ['DELIVERY_NOTE', 'INVOICE', 'CERTIFICATE', 'TEMPERATURE_LOG', 'PHOTO'] as const;

export const options = (values: readonly string[]) => values.map((value) => ({ value, label: humanise(value) }));

/** The allergen and diet labels of a dish, in words - shown before approval and service, never hidden behind a click. */
export const labelsOf = (item: { allergens: string[]; dietaryTags: string[]; allergensDeclared: boolean }): string => {
  if (!item.allergensDeclared) return 'Allergens not declared';
  const contains = item.allergens.length ? `Contains ${item.allergens.map(humanise).join(', ').toLowerCase()}` : 'No declared allergens';
  return item.dietaryTags.length ? `${contains} · ${item.dietaryTags.map(humanise).join(', ')}` : contains;
};

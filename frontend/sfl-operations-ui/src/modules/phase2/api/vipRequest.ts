import type { CreateTripRequest } from 'modules/fleet/api/dto';

/**
 * How a VIP request is recognised.
 *
 * The trip record has no field for "this is an executive request", so the request is marked in its
 * purpose text and the service is asked for trips starting with this prefix. It is a stopgap: a real
 * flag on the trip is the proper fix, and when it exists this file is the only place to change.
 */
export const VIP_PURPOSE_PREFIX = 'VIP transport: ';
const CHASE_CAR_MARKER = ' · chase-car requested';

/** The trip purpose is limited to 500 characters by the service. */
export const PRINCIPAL_MAX = 200;
export const DESTINATION_MAX = 200;

export const vipPurpose = (principal: string, chaseCar: boolean): string =>
  `${VIP_PURPOSE_PREFIX}${principal.trim()}${chaseCar ? CHASE_CAR_MARKER : ''}`;

export interface ParsedVipPurpose {
  principal: string;
  chaseCar: boolean;
}

/** The inverse of `vipPurpose`; null for a trip that was not raised through the portal. */
export const parseVipPurpose = (purpose: string): ParsedVipPurpose | null => {
  if (!purpose.startsWith(VIP_PURPOSE_PREFIX)) {
    return null;
  }
  const rest = purpose.slice(VIP_PURPOSE_PREFIX.length);
  const chaseCar = rest.endsWith(CHASE_CAR_MARKER);
  return { principal: chaseCar ? rest.slice(0, -CHASE_CAR_MARKER.length) : rest, chaseCar };
};

export interface VipRequestDraft {
  principal: string;
  destination: string;
  /** Local date-time as `YYYY-MM-DDTHH:mm`, the form the date-time field produces. */
  start: string;
  end: string;
  chaseCar: boolean;
}

export type VipRequestErrors = Partial<Record<'principal' | 'destination' | 'start' | 'end', string>>;

const parse = (local: string): number | null => {
  if (!local) {
    return null;
  }
  const time = new Date(local).getTime();
  return Number.isNaN(time) ? null : time;
};

/** Everything the service would refuse, caught before the request is sent. */
export const validateVipRequest = (draft: VipRequestDraft, now: Date = new Date()): VipRequestErrors => {
  const errors: VipRequestErrors = {};
  if (!draft.principal.trim()) {
    errors.principal = 'Say who is travelling.';
  } else if (draft.principal.trim().length > PRINCIPAL_MAX) {
    errors.principal = `Keep this under ${PRINCIPAL_MAX} characters.`;
  }
  if (!draft.destination.trim()) {
    errors.destination = 'Say where they are going.';
  } else if (draft.destination.trim().length > DESTINATION_MAX) {
    errors.destination = `Keep this under ${DESTINATION_MAX} characters.`;
  }
  const start = parse(draft.start);
  const end = parse(draft.end);
  if (start === null) {
    errors.start = 'Choose when the trip starts.';
  } else if (start < now.getTime()) {
    errors.start = 'The trip must start in the future.';
  }
  if (end === null) {
    errors.end = 'Choose when the trip ends.';
  } else if (start !== null && end <= start) {
    errors.end = 'The trip must end after it starts.';
  }
  return errors;
};

/** The create-trip request for a draft. Deliberately names no vehicle and no driver. */
export const toTripRequest = (draft: VipRequestDraft, siteCode: string): CreateTripRequest => ({
  siteCode,
  purpose: vipPurpose(draft.principal, draft.chaseCar),
  origin: 'CLET campus',
  destination: draft.destination.trim(),
  operatingMode: 'ROUTINE',
  plannedStart: new Date(draft.start).toISOString(),
  plannedEnd: new Date(draft.end).toISOString(),
  vehicleId: null,
  driverId: null,
});

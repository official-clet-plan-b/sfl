import dayjs from 'dayjs';
import { decodeClaims, readSession } from 'shared/auth/session';

/** Display helpers. Everything renders in the operator's local zone; the wire stays ISO-8601 UTC. */

export const formatDateTime = (value: string | null | undefined): string =>
  value ? dayjs(value).format('DD MMM YYYY HH:mm') : '-';

export const formatDate = (value: string | null | undefined): string =>
  value ? dayjs(value).format('DD MMM YYYY') : '-';

export const formatNumber = (value: number | null | undefined): string =>
  value === null || value === undefined ? '-' : value.toLocaleString();

export const formatOdometer = (value: number | null | undefined, unit = 'KILOMETRES'): string =>
  value === null || value === undefined
    ? '-'
    : `${value.toLocaleString()} ${unit === 'MILES' ? 'mi' : 'km'}`;

/** "in 12 days" / "9 days ago" - the phrasing compliance and licence expiry screens need. */
export const formatDaysRemaining = (days: number | null | undefined): string => {
  if (days === null || days === undefined) {
    return '-';
  }
  if (days === 0) {
    return 'Expires today';
  }
  return days > 0
    ? `${days} day${days === 1 ? '' : 's'} left`
    : `${Math.abs(days)} day${days === -1 ? '' : 's'} overdue`;
};

/** `datetime-local` input value from an ISO instant, and back. */
export const toLocalInputValue = (value: string | null | undefined): string =>
  value ? dayjs(value).format('YYYY-MM-DDTHH:mm') : '';

export const fromLocalInputValue = (value: string): string =>
  value ? dayjs(value).toISOString() : '';

export const nowLocalInputValue = (offsetHours = 0): string =>
  dayjs().add(offsetHours, 'hour').format('YYYY-MM-DDTHH:mm');

export const todayIsoDate = (): string => dayjs().format('YYYY-MM-DD');

const OPAQUE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A person's name for "by ..." lines, never a system id.
 *
 * Records keep who acted as an identity-provider id, which is a meaningless string of hex to the
 * reader. The signed-in person is named from their own session, `known` names others the page
 * already holds (e.g. the assigned driver), a readable username is kept as it is, and an id that
 * cannot be named returns null so the caller leaves the "by" out rather than printing it.
 */
/** Whether an actor id recorded by a service (the token subject, or the sign-in name) is the person signed in. */
export const isCurrentUser = (actorId: string | null | undefined): boolean => {
  const session = readSession();
  if (!actorId || !session) {
    return false;
  }
  const subject = decodeClaims(session.accessToken)?.sub;
  return actorId === subject || actorId.toLowerCase() === session.username?.toLowerCase();
};

export const personName = (
  actorId: string | null | undefined,
  known: Record<string, string> = {},
): string | null => {
  if (!actorId) {
    return null;
  }
  if (isCurrentUser(actorId)) {
    const session = readSession();
    return session?.displayName || session?.username || null;
  }
  const match = Object.entries(known).find(([id]) => id.toLowerCase() === actorId.toLowerCase());
  if (match) {
    return match[1];
  }
  return OPAQUE_ID.test(actorId) ? null : actorId;
};

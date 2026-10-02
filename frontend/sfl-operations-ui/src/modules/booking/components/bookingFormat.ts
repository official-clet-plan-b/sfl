import type { Tone } from 'modules/facilities/components/facilitiesFormat';
import type { Booking } from '../api/dto';
import type { BookingStatus, SetupTaskStatus } from '../api/enums';

/**
 * How S159 values are shown. Tone and wording only - no derivation.
 *
 * Whether a booking holds its space, what window it occupies and whether a setup task is overdue are
 * all decided by the service and arrive on the record. Nothing here recomputes them.
 */

/**
 * `IN_USE` and `RESERVED` are already in the shared status table, mapped for a vehicle and a fuel
 * card. Both readings happen to be right here, but relying on that would mean a future edit to the
 * fleet's tone silently recolours the room diary - so S159 states its own, the way the emergency
 * module does for `ACTIVE`.
 */
export const bookingStatusTone = (status: BookingStatus): Tone => {
  switch (status) {
    case 'CONFIRMED':
    case 'COMPLETED':
      return 'ready';
    case 'IN_USE':
      return 'active';
    case 'REQUESTED':
      return 'caution';
    case 'REJECTED':
    case 'CANCELLED':
      return 'neutral';
    // The one an operator must not miss: a room held all afternoon and never used.
    case 'NO_SHOW':
      return 'blocked';
    default:
      return 'neutral';
  }
};

/** `SKIPPED` is amber, not green: a deliberate omission is still an omission somebody must see. */
export const setupTaskTone = (status: SetupTaskStatus): Tone => {
  switch (status) {
    case 'DONE':
      return 'ready';
    case 'SKIPPED':
      return 'caution';
    case 'PENDING':
      return 'active';
    default:
      return 'neutral';
  }
};

const timeOf = (value: Date) =>
  value.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });

const dateOf = (value: Date) =>
  value.toLocaleDateString(undefined, { day: '2-digit', month: 'short' });

/** `03 Aug 09:00 – 11:00` inside one day, with both dates when the booking spans days. */
export const formatWindow = (startsAt: string, endsAt: string): string => {
  const start = new Date(startsAt);
  const end = new Date(endsAt);
  return start.toDateString() === end.toDateString()
    ? `${dateOf(start)} ${timeOf(start)} – ${timeOf(end)}`
    : `${dateOf(start)} ${timeOf(start)} – ${dateOf(end)} ${timeOf(end)}`;
};

/**
 * How much wider the occupied window is than the booking itself.
 *
 * Shown because it is the single most surprising thing about this module to somebody reading a diary:
 * a two-hour lecture with a thirty-minute setup buffer blocks the hall for two and a half, and the
 * next booking is refused on the buffer rather than on the lecture. Saying so on the record is
 * cheaper than the support call.
 *
 * Both figures come off the booking; nothing here adds minutes to a timestamp.
 */
export const bufferSummary = (booking: Booking): string | null => {
  const { setupMinutes, teardownMinutes } = booking;
  if (setupMinutes === 0 && teardownMinutes === 0) {
    return null;
  }
  const parts: string[] = [];
  if (setupMinutes > 0) {
    parts.push(`${setupMinutes} min before`);
  }
  if (teardownMinutes > 0) {
    parts.push(`${teardownMinutes} min after`);
  }
  return `Holds the space ${parts.join(' and ')}`;
};

/** `datetime-local` wants `YYYY-MM-DDTHH:mm` in local time; the wire carries UTC ISO. */
export const toLocalInput = (iso: string): string => {
  const value = new Date(iso);
  const offset = value.getTimezoneOffset() * 60_000;
  return new Date(value.getTime() - offset).toISOString().slice(0, 16);
};

export const fromLocalInput = (local: string): string => new Date(local).toISOString();

/** The next whole hour, local, as a `datetime-local` value - the sensible default for a new booking. */
export const nextHourLocalInput = (now = new Date()): string => {
  const start = new Date(now);
  start.setMinutes(0, 0, 0);
  start.setHours(start.getHours() + 1);
  return toLocalInput(start.toISOString());
};

export const plusHoursLocalInput = (localValue: string, hours: number): string => {
  const value = new Date(localValue);
  value.setHours(value.getHours() + hours);
  return toLocalInput(value.toISOString());
};

/**
 * Whether a window is well formed, answered before a request is sent.
 *
 * Only the two rules that need no context: an end after its start, and both fields present. Whether
 * the window has passed, whether it sits inside the site's booking horizon and whether the space is
 * free are decisions with context - the service owns all three, and guessing at them here would
 * produce a screen that refuses bookings the estate would have taken.
 */
export const windowProblem = (startsLocal: string, endsLocal: string): string | null => {
  if (!startsLocal || !endsLocal) {
    return 'Give a start and an end.';
  }
  if (new Date(endsLocal).getTime() <= new Date(startsLocal).getTime()) {
    return 'The end must be after the start.';
  }
  return null;
};

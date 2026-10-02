import { sflActor } from 'shared/api/config';
import { allowed, disabled, hidden } from 'shared/layout/controlState';
import type { ControlState } from 'shared/layout/controlState';
import { permits } from 'shared/layout/actorPermissions';
import type { Booking, SetupTask } from './dto';
import type { BookingStatus } from './enums';
import { TERMINAL_STATUSES } from './enums';

/**
 * What each control may do, decided once.
 *
 * <h2>The rule this module follows, and why it is not the obvious one</h2>
 *
 * **A permission denial hides the control; a state or data shortfall disables it with the reason.**
 * S153 paid for that distinction: a technician was shown a Close button disabled with "You do not have
 * permission" - permanently, on every job, forever. A control somebody will never be allowed to press
 * is noise; a control they cannot press *yet* is information.
 *
 * So every function here returns one of three things - allowed, hidden, or disabled with a sentence -
 * and the pages render that rather than deciding for themselves.
 *
 * <h2>`FACILITIES_BOOKING_CANCEL` is misnamed, and this file must not repeat the mistake</h2>
 *
 * The permission reads as "may cancel". `BookingApplicationService.requireMayAct` uses it as the
 * **"may act on somebody else's booking"** grant, and routes cancellation, rescheduling, start and
 * completion through it identically. Anything you requested, you may move, start, complete and cancel
 * with no permission beyond `FACILITIES_BOOKING_REQUEST`; anything you did not, you may touch only
 * holding that one grant.
 *
 * That is why every function below takes the booking rather than asking a permission in isolation.
 * Gating reschedule on `FACILITIES_BOOKING_REQUEST` - the reading the names suggest - would offer a
 * requester the control on a hall booked by the registry, and the service would refuse it.
 *
 * <h2>Nothing here recomputes what the service derives</h2>
 *
 * `holdsTheSpace`, `occupiedFrom` and `occupiedTo` come down the wire. The status rules below are the
 * *display* half of rules the service enforces properly; a screen offering a transition the service
 * will refuse is a bug, but a screen refusing one the service would allow is worse, because it is
 * invisible.
 */

/*
  The three-state model is shared by the facilities controls.
  caller. Re-exported here rather than re-declared, so `ControlState` remains one type across the
  application and this module's imports did not have to change.
*/
export type { ControlState } from 'shared/layout/controlState';
export { allowed, hidden };

export const isTerminal = (status: BookingStatus): boolean => TERMINAL_STATUSES.includes(status);

/** Sentence case for a status inside running prose - `IN_USE` reads badly mid-sentence. */
const spoken = (status: string): string => status.toLowerCase().replace(/_/g, ' ');

/** Who the services will see this browser as. The same value the client sends as `X-SFL-User`. */
export const currentActor = (): string => sflActor.user;

export const isOwnBooking = (booking: Booking): boolean =>
  booking.requestedBy.toLowerCase() === currentActor().toLowerCase();

/**
 * Whether this actor may act on this booking at all - the client half of `requireMayAct`.
 *
 * Used by reschedule, start, complete and cancel, because the service uses one rule for all four.
 */
const mayAct = (booking: Booking): boolean =>
  isOwnBooking(booking) || permits('FACILITIES_BOOKING_CANCEL');

/**
 * Approve or reject.
 *
 * There is no `APPROVED` state in this domain - approval is an event recorded as a `BookingApproval`,
 * and a booking needing none is confirmed at request. So `REQUESTED` already means "waiting on a
 * decision", and `approvalRequired` is not re-tested here.
 *
 * The self-approval refusal is not a permission and is not negotiable: an approver deciding on their
 * own request is the one thing separation of duties exists to stop, and administrators are not exempt.
 * Disabled rather than hidden, because the actor plainly holds the authority - what they lack is
 * distance from this particular request, and saying so is the difference between a rule and a bug.
 */
export const canDecide = (booking: Booking): ControlState => {
  if (!permits('FACILITIES_BOOKING_APPROVE')) {
    return hidden;
  }
  if (booking.status !== 'REQUESTED') {
    return disabled(`A ${spoken(booking.status)} booking has already been decided.`);
  }
  if (isOwnBooking(booking)) {
    return disabled('You cannot approve your own booking request. Ask a colleague to decide it.');
  }
  return allowed;
};

/** Move a booking. Allowed while it still holds its space, refused once people are in the room. */
export const canReschedule = (booking: Booking): ControlState => {
  if (!mayAct(booking)) {
    return hidden;
  }
  if (isTerminal(booking.status)) {
    return disabled(`A ${spoken(booking.status)} booking cannot be moved.`);
  }
  if (booking.status === 'IN_USE') {
    return disabled('This booking is in use. Complete it and raise a new one rather than moving it.');
  }
  return allowed;
};

/** Somebody has arrived and taken the room. Also what stops the no-show sweep releasing the space. */
export const canStart = (booking: Booking): ControlState => {
  if (!mayAct(booking)) {
    return hidden;
  }
  if (booking.status !== 'CONFIRMED') {
    return disabled(`Only a confirmed booking can start; this one is ${spoken(booking.status)}.`);
  }
  return allowed;
};

export const canComplete = (booking: Booking): ControlState => {
  if (!mayAct(booking)) {
    return hidden;
  }
  if (booking.status !== 'IN_USE') {
    return disabled(`Only a booking in use can be completed; this one is ${spoken(booking.status)}.`);
  }
  return allowed;
};

export const canCancel = (booking: Booking): ControlState => {
  if (!mayAct(booking)) {
    return hidden;
  }
  if (isTerminal(booking.status)) {
    return disabled(`A ${spoken(booking.status)} booking cannot be cancelled.`);
  }
  return allowed;
};

/**
 * Book into a space readiness would otherwise refuse.
 *
 * Separate from requesting because it is a different authority and creates a different obligation -
 * the reason is recorded against the booking and is what an auditor reads later. Note that
 * `FACILITIES_READINESS_OVERRIDE` does **not** imply it: the matrix withholds booking override from
 * `IFIMP_MAINTENANCE_SUPERVISOR` deliberately.
 */
export const canOverrideReadiness = (): ControlState =>
  permits('FACILITIES_BOOKING_OVERRIDE') ? allowed : hidden;

export const canRequest = (): ControlState =>
  permits('FACILITIES_BOOKING_REQUEST') ? allowed : hidden;

export const canManageResources = (): ControlState =>
  permits('FACILITIES_RESOURCE_MANAGE') ? allowed : hidden;

export const canResolveSetupTask = (task: SetupTask): ControlState => {
  if (!permits('FACILITIES_SETUP_TASK_MANAGE')) {
    return hidden;
  }
  if (task.status !== 'PENDING') {
    return disabled(`This task is already ${spoken(task.status)}.`);
  }
  return allowed;
};

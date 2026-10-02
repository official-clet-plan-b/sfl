import { permits } from 'shared/layout/actorPermissions';
import type { Tone } from 'modules/emergency/components/StatusBadge';
import type { NotificationActivation } from './dto';
import type { ActivationStatus } from './enums';

/**
 * The S174 activation state machine, transcribed from `NotificationActivation`.
 *
 * The service is the authority: every transition here is also checked there, and a refusal comes
 * back as `EMERGENCY_INVALID_STATE_TRANSITION` with the domain's own wording. This exists so an
 * action an operator cannot take is not offered in the first place - an emergency dashboard that
 * shows a live "Send" button which will be refused is worse than useless when it matters.
 *
 * Where a rule is stated twice - once here and once in the service - the service's text is what the
 * operator is shown on failure, so the two can never disagree in front of them.
 */

export interface Rule {
  /** The states the transition is allowed from. */
  from: readonly ActivationStatus[];
  /** Why it is unavailable, in the operator's terms. */
  unavailable: string;
}

export const ACTIVATION_RULES: Record<
  | 'submit'
  | 'approve'
  | 'reject'
  | 'cancel'
  | 'activate'
  | 'degradedFallback'
  | 'allClear'
  | 'close'
  | 'reopen',
  Rule
> = {
  submit: {
    from: ['DRAFT'],
    unavailable: 'Only a draft can be submitted for approval.',
  },
  approve: {
    from: ['PENDING_APPROVAL'],
    unavailable: 'Only an activation awaiting approval can be approved.',
  },
  reject: {
    from: ['PENDING_APPROVAL'],
    unavailable: 'Only an activation awaiting approval can be rejected.',
  },
  cancel: {
    from: ['DRAFT', 'PENDING_APPROVAL', 'APPROVED'],
    unavailable: 'Only a draft, submitted or approved activation can be cancelled before it is sent.',
  },
  activate: {
    from: ['APPROVED'],
    unavailable: 'An activation must be approved before it can be sent.',
  },
  degradedFallback: {
    from: ['ACTIVE', 'BREAK_GLASS_ACTIVE', 'PARTIALLY_DELIVERED', 'ESCALATED'],
    unavailable: 'Degraded fallback can only be recorded for a live broadcast.',
  },
  allClear: {
    from: ['ACTIVE', 'BREAK_GLASS_ACTIVE', 'PARTIALLY_DELIVERED', 'ESCALATED'],
    unavailable: 'An all-clear can only follow a live broadcast.',
  },
  close: {
    from: [
      'ALL_CLEAR_PENDING',
      'ACTIVE',
      'BREAK_GLASS_ACTIVE',
      'PARTIALLY_DELIVERED',
      'ESCALATED',
      'REOPENED',
    ],
    unavailable: 'Only a live or all-clear activation can be closed.',
  },
  reopen: {
    from: ['CLOSED'],
    unavailable: 'Only a closed activation can be reopened.',
  },
};

export type ActivationAction = keyof typeof ACTIVATION_RULES;

export const canTransition = (
  activation: NotificationActivation | undefined,
  action: ActivationAction,
): boolean => Boolean(activation) && ACTIVATION_RULES[action].from.includes(activation!.status);

export const whyUnavailable = (action: ActivationAction): string =>
  ACTIVATION_RULES[action].unavailable;

/**
 * After-action approval is the one transition without a source-state list.
 *
 * The domain refuses it only on a terminal activation - closed or cancelled - because its whole
 * purpose is to be recorded after a break-glass send, at whatever point the approver reaches it.
 */
export const canRecordAfterAction = (activation: NotificationActivation | undefined): boolean =>
  Boolean(activation) && activation!.status !== 'CLOSED' && activation!.status !== 'CANCELLED';

/** `NotificationActivation.open()` - not closed, cancelled or rejected. */
export const activationOpen = (activation: NotificationActivation): boolean =>
  activation.status !== 'CLOSED' &&
  activation.status !== 'CANCELLED' &&
  activation.status !== 'REJECTED';

/** `NotificationActivation.active()` - a broadcast is out and has not been stood down. */
export const activationLive = (activation: NotificationActivation): boolean =>
  activation.status === 'ACTIVE' ||
  activation.status === 'BREAK_GLASS_ACTIVE' ||
  activation.status === 'PARTIALLY_DELIVERED' ||
  activation.status === 'ESCALATED';

/** Waiting on somebody: submitted and not yet approved or rejected. */
export const awaitingApproval = (activation: NotificationActivation): boolean =>
  activation.status === 'PENDING_APPROVAL';

/**
 * A break-glass activation that has not had its after-the-fact approval recorded.
 *
 * This is the S174 debt an operator has to clear: the broadcast already went out without approval,
 * and closure is blocked until somebody with `EMERGENCY_AFTER_ACTION_APPROVE` accounts for it.
 */
export const afterActionOutstanding = (activation: NotificationActivation): boolean =>
  activation.mode === 'BREAK_GLASS' &&
  !activation.afterActionApprovedBy &&
  activation.status !== 'CANCELLED';

export interface ClosureBlocker {
  label: string;
  detail: string;
  cleared: boolean;
}

/**
 * The three conditions `NotificationActivation.close` checks, stated separately.
 *
 * The domain raises one message for all of them - "closure reason, delivery/acknowledgement summary
 * and evidence are required" - which tells an operator what closure needs but not which part is
 * missing. Splitting them is the difference between a dialog that can be completed and one that has
 * to be guessed at.
 *
 * The delivery and acknowledgement summaries are not listed: the service composes both from the
 * channel counters at the moment of closure, so there is nothing for an operator to supply.
 */
export const closureBlockers = (
  activation: NotificationActivation,
  entered: { reason: string; evidenceStorageReference: string; retentionClass: string },
): ClosureBlocker[] => [
  {
    label: 'Closure reason',
    detail: 'A stated reason is required on the record.',
    cleared: entered.reason.trim().length > 0,
  },
  {
    label: 'Closure evidence',
    detail: 'A storage reference for the closure summary is required.',
    cleared: entered.evidenceStorageReference.trim().length > 0,
  },
  {
    label: 'Retention class',
    detail: 'Governed evidence cannot be filed without one.',
    cleared: entered.retentionClass.trim().length > 0,
  },
  {
    label: 'After-the-fact approval',
    detail: 'A break-glass broadcast sent without approval must be accounted for before closure.',
    cleared: activation.mode !== 'BREAK_GLASS' || Boolean(activation.afterActionApprovedBy),
  },
];

/**
 * Break-glass eligibility, as `BreakGlassPolicy` decides it.
 *
 * Either the template or the scenario is enough - they are OR-ed, not AND-ed. The dialog shows this
 * because an operator choosing a template marked "not break-glass eligible" alongside a scenario
 * that is would otherwise think the send is about to be refused when it is not.
 */
export const breakGlassEligible = (
  templateEligible: boolean,
  scenarioEligible: boolean,
): boolean => templateEligible || scenarioEligible;

/**
 * The tone an activation status is read in.
 *
 * Stated here rather than taken from the shared table because one value means something different
 * in this module: `ACTIVE` is `ready` everywhere else in the dashboard - an active vehicle, an
 * active licence - and here it means a live emergency broadcast is out over every channel. Green
 * would be the wrong thing for an operator to see at a glance, so this module says so once and the
 * shared table is left alone for every other screen that reads it correctly.
 */
const activationTones: Record<ActivationStatus, Tone> = {
  DRAFT: 'neutral',
  PENDING_APPROVAL: 'caution',
  APPROVED: 'ready',
  REJECTED: 'blocked',
  ACTIVATING: 'active',
  ACTIVE: 'blocked',
  BREAK_GLASS_ACTIVE: 'blocked',
  PARTIALLY_DELIVERED: 'caution',
  ESCALATED: 'blocked',
  ALL_CLEAR_PENDING: 'caution',
  CLOSED: 'ready',
  CANCELLED: 'neutral',
  FAILED: 'blocked',
  REOPENED: 'caution',
};

export const activationTone = (status: ActivationStatus): Tone => activationTones[status];

/* --------------------------------------------------------------------- permission-gated controls */

/**
 * What this actor may do in S174, as opposed to what the activation's state allows.
 *
 * <h2>Two different questions, and they must look different</h2>
 *
 * <p>Everything above answers "does the record's state permit this" - and the right response to a no
 * is a **disabled** control with {@link whyUnavailable} beside it, because the state will change.
 * These answer "may this person, ever", and the right response to a no is to **hide** the control:
 * an audience manager will never hold break-glass, and a permanently greyed button is a question
 * they cannot answer.
 *
 * <h2>Why they were missing</h2>
 *
 * <p>The emergency screens gated on state alone, so every role that could open a page was offered
 * every control on it - compose an activation, manage audiences, edit templates, start a drill. The
 * service refused each one, which is the service working and the screen having wasted the operator's
 * time to get there. The same gap existed across fleet, fuel and dispatch; this is the S174 half.
 *
 * <p>Never the enforcement point. `permits` fails open when the services could not be asked, so a
 * failed lookup shows the control rather than hiding the application.
 */
export const canCreateActivations = (): boolean => permits('EMERGENCY_ACTIVATION_CREATE');
export const canApproveActivations = (): boolean => permits('EMERGENCY_ACTIVATION_APPROVE');
export const canSendActivations = (): boolean => permits('EMERGENCY_ACTIVATION_SEND');

/**
 * Send an all-clear.
 *
 * <p>Separate from sending the activation itself, and deliberately so: standing an emergency down is
 * a different decision from declaring one, and the matrix grants them apart.
 */
export const canSendAllClear = (): boolean => permits('EMERGENCY_ALL_CLEAR_SEND');

/**
 * Break glass - send without prior approval.
 *
 * <p>The most consequential grant in S174 and the narrowest. A control for it must never be shown to
 * somebody who does not hold it.
 */
export const canBreakGlass = (): boolean => permits('EMERGENCY_BREAK_GLASS_SEND');

export const canManageAudiences = (): boolean => permits('EMERGENCY_AUDIENCE_MANAGE');
export const canManageTemplates = (): boolean => permits('EMERGENCY_TEMPLATE_MANAGE');

/** Scenarios and drills: a drill is a scenario exercised, so both sit behind the scenario grant. */
export const canManageScenarios = (): boolean => permits('EMERGENCY_SCENARIO_MANAGE');

export const canApproveAfterAction = (): boolean => permits('EMERGENCY_AFTER_ACTION_APPROVE');
export const canExportEmergencyEvidence = (): boolean => permits('EMERGENCY_EVIDENCE_EXPORT');
export const canReplayEmergencyIntegration = (): boolean => permits('EMERGENCY_INTEGRATION_REPLAY');

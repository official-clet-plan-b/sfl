import type { DrillCorrectiveAction, DrillDetail, DrillPlan, ExpectationInput, PlanRequest } from './dto';
import type { DrillStatus } from './enums';

/**
 * S175's state guards, transcribed from the domain so a screen only offers what the service will accept
 * (playbook §9.12). Each names the Java it mirrors.
 */
export const drillWorkflow = {
  /** `DrillStatus.planEditable`. */
  canRevise: (status: DrillStatus) => status === 'PLANNED' || status === 'SCHEDULED' || status === 'POSTPONED',
  /** `DrillStatus.ALLOWED`: PLANNED and POSTPONED go to SCHEDULED. */
  canSchedule: (status: DrillStatus) => status === 'PLANNED' || status === 'POSTPONED',
  canPostpone: (status: DrillStatus) => status === 'SCHEDULED',
  canCancel: (status: DrillStatus) => status === 'PLANNED' || status === 'SCHEDULED' || status === 'POSTPONED',
  /** `DrillExecutionService.start`. */
  canStart: (status: DrillStatus) => status === 'SCHEDULED',
  /** `checkIn` and `closeRollCall`. */
  isRunning: (status: DrillStatus) => status === 'IN_PROGRESS',
  /** `DrillReviewService.requireUnderReview`; also `followUpGap`. */
  isUnderReview: (status: DrillStatus) => status === 'COMPLETED',
  /** `DrillCorrectiveActionService.open`. */
  canRaiseAction: (status: DrillStatus) => status === 'COMPLETED' || status === 'REVIEWED',
  /** `DrillReviewService.close`. */
  canClose: (status: DrillStatus) => status === 'REVIEWED',
  /** `CapaStatus.isTerminal`. */
  actionOpen: (action: DrillCorrectiveAction) => action.status === 'OPEN' || action.status === 'IN_PROGRESS',
};

/**
 * What `ExecutionReadinessPolicy.requireReady` will refuse, in its own words, so the planner fixes every
 * problem in a pass. The drill-template check is S174's and happens on the server.
 */
export const readinessProblems = (plan: Pick<PlanRequest, 'drillType' | 'scheduledFor' | 'assemblyZone' | 'notificationTemplateId' | 'audienceGroupIds' | 'recipientZoneIds' | 'expectations'>): string[] => {
  const problems: string[] = [];
  if (plan.drillType === 'COMBINED' && new Set(plan.expectations.map((e) => e.module)).size < 2) {
    problems.push('A combined drill names at least two participating modules.');
  }
  plan.expectations.forEach((e: ExpectationInput) => {
    if (!e.successCriterion?.trim()) {
      problems.push(`${e.module}: say how success is measured for "${e.expectation}".`);
    }
  });
  if (!plan.scheduledFor) problems.push('Choose when the drill will run.');
  if (!plan.assemblyZone?.trim()) problems.push('Name the assembly point the roll-call is taken at.');
  if (!plan.notificationTemplateId) problems.push('Choose the S174 drill template the notification is sent with.');
  if (plan.audienceGroupIds.length === 0 && plan.recipientZoneIds.length === 0) {
    problems.push('Choose at least one S174 audience group or zone to notify.');
  }
  return problems;
};

export const planToRequest = (plan: DrillPlan): PlanRequest => ({
  drillType: plan.drillType,
  title: plan.title,
  scenario: plan.scenario ?? undefined,
  expectedParticipants: plan.expectedParticipants ?? undefined,
  assemblyZone: plan.assemblyZone ?? undefined,
  scheduledFor: plan.scheduledFor ?? undefined,
  notificationTemplateId: plan.notificationTemplateId ?? undefined,
  audienceGroupIds: plan.audienceGroupIds,
  recipientZoneIds: plan.recipientZoneIds,
  expectations: plan.expectations.map((e) => ({
    module: e.module,
    expectation: e.expectation,
    successCriterion: e.successCriterion ?? '',
  })),
});

/**
 * What `ReviewCompletenessPolicy.requireSubmittable` will refuse, in the order it checks, so the review
 * screen lists everything left to do.
 */
export const submitBlockers = (detail: DrillDetail): string[] => {
  const blockers: string[] = [];
  if (!detail.review?.summary?.trim()) blockers.push("Record the after-action review's summary.");
  const openGaps = detail.gaps.filter((gap) => gap.followUp === null).length;
  if (openGaps) blockers.push(`Follow up ${openGaps} roll-call gap${openGaps === 1 ? '' : 's'}.`);
  const unjudged = detail.drill.plan.expectations.filter((e) => e.outcome === null).length;
  if (unjudged) blockers.push(`Record whether ${unjudged} module expectation${unjudged === 1 ? ' was' : 's were'} met.`);
  if (detail.unactionedFindingIds.length) {
    blockers.push(`Unactioned Finding: ${detail.unactionedFindingIds.length} finding${detail.unactionedFindingIds.length === 1 ? ' needs' : 's need'} a corrective action or a no-action justification.`);
  }
  return blockers;
};

/** Notification-to-muster time as minutes and seconds - S175-03's timing. */
export const formatDuration = (seconds: number | null | undefined): string => {
  if (seconds === null || seconds === undefined) return '-';
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes ? `${minutes} min ${rest} s` : `${rest} s`;
};

/** Participation as a whole percentage of the baseline; '-' with no baseline. */
export const participation = (checkedIn: number | null | undefined, baseline: number | null | undefined): string =>
  !baseline || checkedIn === null || checkedIn === undefined ? '-' : `${Math.round((checkedIn / baseline) * 100)}%`;

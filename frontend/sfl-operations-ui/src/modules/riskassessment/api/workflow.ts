import type { AssessmentSummary, HazardInput, ReviewFlag, VersionView } from './dto';
import type { Likelihood, RiskLevel, Severity } from './enums';
import { likelihoods, severities } from './enums';

/**
 * S165's state guards, transcribed line by line from the domain so a screen only offers what the
 * service will accept (playbook §9.12). Each names the Java it mirrors.
 */
export const riskWorkflow = {
  /** `RiskAssessmentAuthoringService.editDraft` -> `requireDraft`. */
  canEditDraft: (assessment: AssessmentSummary) => assessment.draftVersion !== null,
  /** `publish` -> `requireDraft`. */
  canPublish: (assessment: AssessmentSummary) => assessment.draftVersion !== null,
  /** `openRevision`: a current version to revise, and `RiskAssessment.draftOpened` refuses a second draft. */
  canOpenRevision: (assessment: AssessmentSummary) =>
    assessment.currentVersion !== null && assessment.draftVersion === null,
  /** `RiskAssessmentReviewService.signOff`: "Nothing has been published to sign off." */
  canSignOff: (assessment: AssessmentSummary) => assessment.currentVersion !== null,
  /** `ReviewFlag.requireNotCleared`, for both defer and complete. */
  canWorkFlag: (flag: ReviewFlag) => flag.status !== 'CLEARED',
};

/** The open draft and the current version, out of a detail's newest-first list. */
export const draftOf = (versions: VersionView[]) => versions.find((view) => view.version.status === 'DRAFT') ?? null;
export const currentOf = (versions: VersionView[]) => versions.find((view) => view.version.status === 'PUBLISHED') ?? null;

// ---- previews: what the service will decide, shown before submission (playbook §11.20) ------------

/**
 * The score `RiskScore.score()` computes - each axis's 1-based step, multiplied. Used only to preview
 * a draft being typed; every stored figure on screen is the service's own.
 */
export const previewScore = (likelihood: Likelihood, severity: Severity): number =>
  (likelihoods.indexOf(likelihood) + 1) * (severities.indexOf(severity) + 1);

/** `RiskScore.level()`'s bands - S163's provisional matrix, 4 / 9 / 15. */
export const previewLevel = (score: number): RiskLevel => {
  if (score <= 4) return 'LOW';
  if (score <= 9) return 'MEDIUM';
  if (score <= 15) return 'HIGH';
  return 'CRITICAL';
};

/** The level a draft will publish at: the highest residual band, or null with no hazards. */
export const previewAssessmentLevel = (hazards: HazardInput[]): RiskLevel | null => {
  if (hazards.length === 0) return null;
  const highest = Math.max(...hazards.map((hazard) => previewScore(hazard.residualLikelihood, hazard.residualSeverity)));
  return previewLevel(highest);
};

/**
 * What `PublishPolicy` will refuse, named the way it names it - "#2 Ladder access" - so the author fixes
 * every one in a pass rather than meeting them a refusal at a time.
 */
export const publishBlockers = (hazards: HazardInput[]): string[] => {
  if (hazards.length === 0) {
    return ['Record at least one hazard before publishing this assessment.'];
  }
  return hazards
    .map((hazard, index) => (hazard.controls.length === 0 ? `#${index + 1} ${hazard.description || 'Untitled hazard'}` : null))
    .filter((entry): entry is string => entry !== null);
};

/** `RiskLevel.requiresIndependentReviewer()`. */
export const needsIndependentReviewer = (level: RiskLevel | null) => level === 'HIGH' || level === 'CRITICAL';

/** `SignOffPolicy.requireAcceptable`: at HIGH and CRITICAL the author may not sign off alone. Case-insensitive, as the service compares. */
export const signOffRefusedFor = (level: RiskLevel | null, authorId: string | null, reviewerId: string) =>
  needsIndependentReviewer(level) &&
  authorId !== null &&
  authorId.trim().toLowerCase() === reviewerId.trim().toLowerCase();

/** `ReviewFlag.defer`: a named reason and a date strictly after today (the service compares to the instant). */
export const deferralProblem = (reason: string, until: string, today: string): string | null => {
  if (!reason.trim()) return 'Name the reason for deferring.';
  if (!until) return 'Choose the date the flag comes back.';
  if (until <= today) return 'The date must be in the future.';
  return null;
};

/** `ReviewIntervalService.requireShorterForHigherRisk`, over the full proposed set. */
export const intervalProblem = (
  intervals: { riskLevel: RiskLevel; intervalDays: number; reminderLeadDays: number }[],
): string | null => {
  const order: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  const sorted = [...intervals].sort((a, b) => order.indexOf(a.riskLevel) - order.indexOf(b.riskLevel));
  for (const interval of sorted) {
    if (!Number.isInteger(interval.intervalDays) || interval.intervalDays < 1) {
      return `${interval.riskLevel}: the interval must be at least one day.`;
    }
    if (interval.reminderLeadDays < 0 || interval.reminderLeadDays >= interval.intervalDays) {
      return `${interval.riskLevel}: the reminder must fall inside the interval.`;
    }
  }
  for (let index = 1; index < sorted.length; index++) {
    if (sorted[index].intervalDays > sorted[index - 1].intervalDays) {
      return `${sorted[index].riskLevel} would be reviewed less often than ${sorted[index - 1].riskLevel}. A higher risk level must be reviewed at least as often.`;
    }
  }
  return null;
};

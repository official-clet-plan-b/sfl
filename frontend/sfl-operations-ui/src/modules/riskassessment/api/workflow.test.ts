import { describe, expect, it } from 'vitest';
import type { AssessmentSummary, HazardInput } from './dto';
import {
  deferralProblem,
  intervalProblem,
  previewAssessmentLevel,
  previewLevel,
  previewScore,
  publishBlockers,
  riskWorkflow,
  signOffRefusedFor,
} from './workflow';

const hazard = (residualLikelihood: HazardInput['residualLikelihood'], residualSeverity: HazardInput['residualSeverity'], controls = 1): HazardInput => ({
  hazardType: 'HOT_WORK_FIRE',
  description: 'Sparks',
  inherentLikelihood: 'LIKELY',
  inherentSeverity: 'MAJOR',
  residualLikelihood,
  residualSeverity,
  controls: Array.from({ length: controls }, () => ({ controlType: 'ADMINISTRATIVE' as const, description: 'Fire watch' })),
});

const summary = (currentVersion: number | null, draftVersion: number | null) => ({ currentVersion, draftVersion }) as AssessmentSummary;

describe('S165 workflow guards, transcribed from the domain', () => {
  it('offers revision only with a current version and no open draft', () => {
    expect(riskWorkflow.canOpenRevision(summary(1, null))).toBe(true);
    expect(riskWorkflow.canOpenRevision(summary(1, 2))).toBe(false);
    expect(riskWorkflow.canOpenRevision(summary(null, 1))).toBe(false);
  });
  it('offers publish and edit only while a draft is open, and sign-off only once something is published', () => {
    expect(riskWorkflow.canPublish(summary(null, 1))).toBe(true);
    expect(riskWorkflow.canEditDraft(summary(1, null))).toBe(false);
    expect(riskWorkflow.canSignOff(summary(null, 1))).toBe(false);
    expect(riskWorkflow.canSignOff(summary(1, 2))).toBe(true);
  });
});

describe('previews match RiskScore and PublishPolicy', () => {
  it('scores and bands as the service does', () => {
    expect(previewScore('UNLIKELY', 'MINOR')).toBe(4);
    expect(previewLevel(4)).toBe('LOW');
    expect(previewLevel(9)).toBe('MEDIUM');
    expect(previewLevel(15)).toBe('HIGH');
    expect(previewLevel(16)).toBe('CRITICAL');
    expect(previewAssessmentLevel([hazard('RARE', 'MINOR'), hazard('POSSIBLE', 'MAJOR')])).toBe('HIGH');
    expect(previewAssessmentLevel([])).toBeNull();
  });
  it('names every uncontrolled hazard, numbered from one', () => {
    expect(publishBlockers([hazard('RARE', 'MINOR'), hazard('RARE', 'MINOR', 0)])).toEqual(['#2 Sparks']);
    expect(publishBlockers([])).toHaveLength(1);
  });
});

describe('sign-off, deferral and interval previews', () => {
  it('refuses the author signing off alone only at HIGH and CRITICAL', () => {
    expect(signOffRefusedFor('HIGH', 'hse.officer', 'HSE.Officer')).toBe(true);
    expect(signOffRefusedFor('MEDIUM', 'hse.officer', 'hse.officer')).toBe(false);
    expect(signOffRefusedFor('CRITICAL', 'hse.officer', 'hse.director')).toBe(false);
  });
  it('needs a reason and a future date to defer', () => {
    expect(deferralProblem('', '2026-10-20', '2026-10-02')).not.toBeNull();
    expect(deferralProblem('Waiting on log', '2026-10-02', '2026-10-02')).not.toBeNull();
    expect(deferralProblem('Waiting on log', '2026-10-20', '2026-10-02')).toBeNull();
  });
  it('refuses a cycle where higher risk is reviewed less often', () => {
    const ok = [
      { riskLevel: 'LOW' as const, intervalDays: 365, reminderLeadDays: 30 },
      { riskLevel: 'CRITICAL' as const, intervalDays: 30, reminderLeadDays: 7 },
    ];
    expect(intervalProblem(ok)).toBeNull();
    expect(intervalProblem([{ ...ok[0] }, { ...ok[1], intervalDays: 400 }])).toContain('less often');
    expect(intervalProblem([{ ...ok[1], reminderLeadDays: 30 }])).toContain('inside the interval');
  });
});

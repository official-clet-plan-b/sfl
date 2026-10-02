import { describe, expect, it } from 'vitest';
import type { DrillDetail, PlanRequest } from './dto';
import { drillWorkflow, formatDuration, participation, readinessProblems, submitBlockers } from './workflow';

const ready: PlanRequest = {
  drillType: 'FIRE',
  title: 'Block A evacuation',
  assemblyZone: 'BLOCK-A',
  scheduledFor: '2026-10-10T09:00:00Z',
  notificationTemplateId: 't1',
  audienceGroupIds: ['a1'],
  recipientZoneIds: [],
  expectations: [],
};

describe('readinessProblems (ExecutionReadinessPolicy)', () => {
  it('passes a complete plan', () => {
    expect(readinessProblems(ready)).toEqual([]);
  });

  it('asks a combined drill for two modules and a success measure for each', () => {
    const problems = readinessProblems({
      ...ready,
      drillType: 'COMBINED',
      expectations: [{ module: 'S166_FLEET', expectation: 'Vehicle ready', successCriterion: ' ' }],
    });
    expect(problems).toContain('A combined drill names at least two participating modules.');
    expect(problems.some((p) => p.includes('say how success is measured'))).toBe(true);
  });

  it('names every missing scheduling field', () => {
    expect(readinessProblems({ ...ready, scheduledFor: undefined, assemblyZone: '', notificationTemplateId: undefined, audienceGroupIds: [] }))
      .toHaveLength(4);
  });
});

describe('drillWorkflow (DrillStatus.ALLOWED)', () => {
  it('only starts a scheduled drill and only closes a reviewed one', () => {
    expect(drillWorkflow.canStart('SCHEDULED')).toBe(true);
    expect(drillWorkflow.canStart('PLANNED')).toBe(false);
    expect(drillWorkflow.canClose('COMPLETED')).toBe(false);
    expect(drillWorkflow.canClose('REVIEWED')).toBe(true);
    expect(drillWorkflow.canRevise('IN_PROGRESS')).toBe(false);
  });
});

describe('submitBlockers (ReviewCompletenessPolicy)', () => {
  it('lists everything left before a review can be submitted', () => {
    const detail = {
      drill: { plan: { expectations: [{ outcome: null }] } },
      review: null,
      gaps: [{ followUp: null }, { followUp: 'NOT_ON_SITE' }],
      unactionedFindingIds: ['f1'],
    } as unknown as DrillDetail;
    expect(submitBlockers(detail)).toEqual([
      "Record the after-action review's summary.",
      'Follow up 1 roll-call gap.',
      'Record whether 1 module expectation was met.',
      'Unactioned Finding: 1 finding needs a corrective action or a no-action justification.',
    ]);
  });
});

describe('formatting', () => {
  it('formats timing and participation', () => {
    expect(formatDuration(245)).toBe('4 min 5 s');
    expect(formatDuration(null)).toBe('-');
    expect(participation(9, 12)).toBe('75%');
    expect(participation(0, 0)).toBe('-');
  });
});

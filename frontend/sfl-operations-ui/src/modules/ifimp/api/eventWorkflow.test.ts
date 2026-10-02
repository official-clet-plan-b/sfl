import { describe, expect, it } from 'vitest';
import { eventSetupActionAvailability, EventSetupAction } from './eventWorkflow';

const state = (action: EventSetupAction, status: string, extra = {}) =>
  eventSetupActionAvailability(action, { task: { id: 'event-1', status }, ...extra });

describe('event setup workflow actions', () => {
  it('offers preparation actions for open events', () => {
    expect(state('resources', 'OPEN')).toEqual({ visible: true });
    expect(state('template', 'OPEN')).toEqual({ visible: true });
    expect(state('risk', 'OPEN')).toEqual({ visible: true });
  });

  it('blocks confirmation when a triggered assessment is not current', () => {
    expect(state('confirm', 'OPEN', {
      riskTriggers: ['HIGHER_RISK_CATEGORY'],
      riskAssessmentCurrent: false,
      riskAssessmentDetail: 'A current S165 assessment is required.',
    })).toEqual({
      visible: true,
      disabledReason: 'A current S165 assessment is required.',
    });
  });

  it('requires confirmation before completion', () => {
    expect(state('complete', 'OPEN')).toEqual({
      visible: true,
      disabledReason: 'Confirm readiness before completing this event setup.',
    });
    expect(state('complete', 'CONFIRMED')).toEqual({ visible: true });
  });

  it('offers reconciliation only after completion and no actions after cancellation', () => {
    expect(state('reconcile', 'COMPLETED')).toEqual({ visible: true });
    expect(state('confirm', 'COMPLETED').visible).toBe(false);
    expect(state('resources', 'CANCELLED').visible).toBe(false);
  });
});

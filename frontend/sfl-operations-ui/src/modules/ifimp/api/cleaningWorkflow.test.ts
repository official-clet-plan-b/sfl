import { describe, expect, it } from 'vitest';
import { cleaningTaskActionAvailability, CleaningTaskAction } from './cleaningWorkflow';

const state = (action: CleaningTaskAction, status: string) =>
  cleaningTaskActionAvailability(action, { id: 'task-1', status });

describe('cleaning task workflow actions', () => {
  it('requires assignment before an open task can start', () => {
    expect(state('assign', 'OPEN')).toEqual({ visible: true });
    expect(state('start', 'OPEN')).toEqual({
      visible: true,
      disabledReason: 'Assign this task before starting it.',
    });
    expect(state('complete', 'OPEN').disabledReason).toContain('Assign and start');
  });

  it('allows an assigned task to start but not complete immediately', () => {
    expect(state('start', 'ASSIGNED')).toEqual({ visible: true });
    expect(state('complete', 'ASSIGNED')).toEqual({
      visible: true,
      disabledReason: 'Start this task before completing it.',
    });
  });

  it('allows only completion and cancellation while work is in progress', () => {
    expect(state('assign', 'IN_PROGRESS').visible).toBe(false);
    expect(state('start', 'IN_PROGRESS').visible).toBe(false);
    expect(state('complete', 'IN_PROGRESS')).toEqual({ visible: true });
    expect(state('cancel', 'IN_PROGRESS')).toEqual({ visible: true });
  });

  it('offers feedback only after completion and no mutation after cancellation', () => {
    expect(state('feedback', 'COMPLETED')).toEqual({ visible: true });
    expect(state('cancel', 'COMPLETED').visible).toBe(false);
    expect(state('feedback', 'CANCELLED').visible).toBe(false);
  });
});

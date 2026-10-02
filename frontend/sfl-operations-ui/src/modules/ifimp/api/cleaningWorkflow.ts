import { IfimpRecord } from './ifimpPhase2Api';

export type CleaningTaskAction = 'assign' | 'start' | 'complete' | 'cancel' | 'feedback';

export interface CleaningTaskActionAvailability {
  visible: boolean;
  disabledReason?: string;
}

type CleaningTaskStatus = 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

const statusOf = (record: IfimpRecord): CleaningTaskStatus | undefined => {
  const status = String(record.status ?? '').toUpperCase();
  return ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].includes(status)
    ? (status as CleaningTaskStatus)
    : undefined;
};

/**
 * Mirrors the cleaning task state machine for presentation only. The service remains authoritative,
 * but the screen must not offer a transition that it already knows the service will refuse.
 */
export const cleaningTaskActionAvailability = (
  action: CleaningTaskAction,
  record: IfimpRecord,
): CleaningTaskActionAvailability => {
  const status = statusOf(record);

  if (action === 'assign') {
    return { visible: status === 'OPEN' || status === 'ASSIGNED' };
  }
  if (action === 'start') {
    if (status === 'OPEN') {
      return { visible: true, disabledReason: 'Assign this task before starting it.' };
    }
    return { visible: status === 'ASSIGNED' };
  }
  if (action === 'complete') {
    if (status === 'OPEN') {
      return { visible: true, disabledReason: 'Assign and start this task before completing it.' };
    }
    if (status === 'ASSIGNED') {
      return { visible: true, disabledReason: 'Start this task before completing it.' };
    }
    return { visible: status === 'IN_PROGRESS' };
  }
  if (action === 'cancel') {
    return { visible: status === 'OPEN' || status === 'ASSIGNED' || status === 'IN_PROGRESS' };
  }
  return { visible: status === 'COMPLETED' };
};

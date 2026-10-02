import { IfimpRecord } from './ifimpPhase2Api';

export type EventSetupAction = 'resources' | 'template' | 'risk' | 'confirm' | 'complete' | 'reconcile';

export interface EventSetupActionAvailability {
  visible: boolean;
  disabledReason?: string;
}

type EventSetupStatus = 'OPEN' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED';

const taskOf = (record: IfimpRecord): IfimpRecord => {
  const task = record.task;
  return task !== null && typeof task === 'object' && !Array.isArray(task)
    ? task as IfimpRecord
    : record;
};

const statusOf = (record: IfimpRecord): EventSetupStatus | undefined => {
  const status = String(taskOf(record).status ?? '').toUpperCase();
  return ['OPEN', 'CONFIRMED', 'COMPLETED', 'CANCELLED'].includes(status)
    ? status as EventSetupStatus
    : undefined;
};

/** Keeps the event action bar aligned with the S173 state machine and its fail-closed risk rule. */
export const eventSetupActionAvailability = (
  action: EventSetupAction,
  record: IfimpRecord,
): EventSetupActionAvailability => {
  const status = statusOf(record);

  if (action === 'resources' || action === 'template' || action === 'risk') {
    return { visible: status === 'OPEN' || status === 'CONFIRMED' };
  }
  if (action === 'confirm') {
    if (status !== 'OPEN') return { visible: false };
    const triggers = Array.isArray(record.riskTriggers) ? record.riskTriggers : [];
    if (triggers.length > 0 && record.riskAssessmentCurrent !== true) {
      return {
        visible: true,
        disabledReason: String(record.riskAssessmentDetail ?? 'Link a current risk assessment before confirming readiness.'),
      };
    }
    return { visible: true };
  }
  if (action === 'complete') {
    if (status === 'OPEN') {
      return { visible: true, disabledReason: 'Confirm readiness before completing this event setup.' };
    }
    return { visible: status === 'CONFIRMED' };
  }
  return { visible: status === 'COMPLETED' };
};

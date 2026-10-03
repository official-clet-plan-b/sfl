import { Timeline, TimelineData, TimelineFooter, TimelineItem, TimelineTitle } from '@rfdtech/components';
import { formatDateTime } from 'shared/components/format';
import { humanise } from 'modules/fleet/api/enums';

export interface Step { id: string; title: string; detail?: string | null; footer: string; tone?: 'primary' | 'success' | 'error' }

/** A list of dated steps, oldest first: the chain of custody, or a record's state history. */
const WasteTimeline = ({ steps, empty }: { steps: Step[]; empty: string }) => {
  if (steps.length === 0) return <p className="text-theme-sm text-gray-600">{empty}</p>;
  return (
    <Timeline>
      {steps.map((step, index) => (
        <TimelineItem key={step.id} isLast={index === steps.length - 1} mode={step.tone ?? 'primary'}>
          <TimelineTitle as="h3">{step.title}</TimelineTitle>
          {step.detail && <TimelineData>{step.detail}</TimelineData>}
          <TimelineFooter>{step.footer}</TimelineFooter>
        </TimelineItem>
      ))}
    </Timeline>
  );
};

export const historySteps = (history: Array<{ id: string; subjectType: string; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; occurredAt: string }>): Step[] =>
  history.map((entry) => ({
    id: entry.id,
    title: `${humanise(entry.subjectType)} · ${entry.fromStatus ? `${humanise(entry.fromStatus)} → ` : ''}${humanise(entry.toStatus)}`,
    detail: entry.reason,
    footer: `${formatDateTime(entry.occurredAt)} · by ${entry.actor}`,
    tone: ['MISSED', 'REJECTED'].includes(entry.toStatus) ? 'error' : ['CLOSED', 'ACCEPTED', 'RESOLVED'].includes(entry.toStatus) ? 'success' : 'primary',
  }));

export default WasteTimeline;

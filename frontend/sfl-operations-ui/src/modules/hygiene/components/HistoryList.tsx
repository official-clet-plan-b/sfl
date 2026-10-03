import { Timeline, TimelineData, TimelineFooter, TimelineItem, TimelineTitle } from '@rfdtech/components';
import { formatDateTime } from 'shared/components/format';
import { humanise } from 'modules/fleet/api/enums';
import type { HistoryEntry } from '../api/hygieneApi';

const mode = (status: string) => {
  if (['MISSED', 'REJECTED', 'CRITICAL'].includes(status)) return 'error' as const;
  if (['COMPLETED', 'CLOSED', 'VERIFIED', 'ACCEPTED'].includes(status)) return 'success' as const;
  return 'primary' as const;
};

/** The append-only state history, oldest first, so it reads as the story of the record. */
const HistoryList = ({ history }: { history: HistoryEntry[] }) => {
  if (history.length === 0) {
    return <p className="text-theme-sm text-gray-600">No recorded activity yet.</p>;
  }
  return (
    <Timeline>
      {history.map((entry, index) => (
        <TimelineItem key={entry.id} isLast={index === history.length - 1} mode={mode(entry.toStatus)}>
          <TimelineTitle as="h3">
            {humanise(entry.subjectType)} · {entry.fromStatus ? `${humanise(entry.fromStatus)} → ` : ''}{humanise(entry.toStatus)}
          </TimelineTitle>
          {entry.reason && <TimelineData>{entry.reason}</TimelineData>}
          <TimelineFooter>{formatDateTime(entry.occurredAt)} · by {entry.actor}</TimelineFooter>
        </TimelineItem>
      ))}
    </Timeline>
  );
};

export default HistoryList;

import {
  Timeline,
  TimelineData,
  TimelineFooter,
  TimelineItem,
  TimelineTitle,
  type TimelineItemMode,
} from '@rfdtech/components';
import { formatDateTime } from 'shared/components/format';

export interface TimelineEntry {
  id: string;
  title: string;
  detail?: string | null;
  actor?: string | null;
  occurredAt: string;
  tone?: 'default' | 'accent' | 'danger';
}

const modes: Record<NonNullable<TimelineEntry['tone']>, TimelineItemMode> = {
  default: 'primary',
  accent: 'warning',
  danger: 'error',
};

interface FleetTimelineProps {
  entries: TimelineEntry[];
  emptyMessage?: string;
}

/**
 * Append-only history rendered as the library's `Timeline`.
 *
 * The service exposes transitions and comments as an immutable sequence, so this never offers an
 * edit affordance - the record is the audit trail.
 */
const FleetTimeline = ({
  entries,
  emptyMessage = 'No recorded activity yet.',
}: FleetTimelineProps) => {
  if (entries.length === 0) {
    return <p className="text-theme-sm opacity-70">{emptyMessage}</p>;
  }

  return (
    <Timeline>
      {entries.map((entry) => (
        <TimelineItem key={entry.id} mode={modes[entry.tone ?? 'default']}>
          <TimelineTitle>{entry.title}</TimelineTitle>
          <TimelineData>{formatDateTime(entry.occurredAt)}</TimelineData>
          {entry.detail && <TimelineData>{entry.detail}</TimelineData>}
          {entry.actor && <TimelineFooter>by {entry.actor}</TimelineFooter>}
        </TimelineItem>
      ))}
    </Timeline>
  );
};

export default FleetTimeline;

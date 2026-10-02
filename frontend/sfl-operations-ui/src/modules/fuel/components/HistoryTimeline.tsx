import { useMemo } from 'react';
import { FuelAuditEvent } from 'modules/fuel/api/dto';
import { humanise } from 'modules/fleet/api/enums';
import { Timeline, TimelineData, TimelineFooter, TimelineItem, TimelineTitle } from '@rfdtech/components';
import { formatDateTime } from 'shared/components/format';

interface TimelineEntry {
  id: string;
  title: string;
  detail?: string;
  actor?: string | null;
  occurredAt: string;
  tone?: 'default' | 'accent' | 'danger';
}

/**
 * A fuel record's real transition history, read from the hash-chained audit log.
 *
 * This replaces a timeline this application used to reconstruct from the record's own timestamps,
 * which
 * could show a logbook's creation and approval but nothing in between - the fuel aggregates had no
 * history endpoint, and the fleet audit search that would have served one returned 500 on every
 * call. Both are fixed, so these are the actual recorded transitions, in chain order, with the actor
 * who made each.
 *
 * The status a transition moved the record *to* is pulled out of the audit entry's `afterValue`,
 * which is the post-image the service canonicalised when it wrote the entry. It is read
 * defensively: the shape is the aggregate's own JSON and a future field rename should quieten this
 * line, not break the screen.
 */

const statusOf = (value: unknown): string | undefined => {
  if (typeof value === 'string') {
    try {
      return statusOf(JSON.parse(value) as unknown);
    } catch {
      return undefined;
    }
  }
  if (typeof value === 'object' && value !== null) {
    const status = (value as { status?: unknown }).status;
    return typeof status === 'string' ? status : undefined;
  }
  return undefined;
};

/** Actions that deserve a colour: the ones that end or escalate a record's life. */
const toneFor = (action: string): TimelineEntry['tone'] => {
  if (['CANCEL', 'ESCALATE', 'REOPEN'].includes(action)) {
    return 'danger';
  }
  if (['CLOSE', 'CREATE'].includes(action)) {
    return 'accent';
  }
  return 'default';
};

interface HistoryTimelineProps {
  events: FuelAuditEvent[] | undefined;
  /** Names the aggregate in the empty message - "logbook", "case", "transaction". */
  recordNoun: string;
}

const HistoryTimeline = ({ events, recordNoun }: HistoryTimelineProps) => {
  const entries = useMemo<TimelineEntry[]>(() => {
    // The service returns newest first, which is right for a queue and wrong for a story.
    const ordered = [...(events ?? [])].sort((left, right) => left.sequenceNo - right.sequenceNo);
    return ordered.map((event) => {
      const after = statusOf(event.afterValue);
      const before = statusOf(event.beforeValue);
      const movement = after && before && after !== before ? `${humanise(before)} → ${humanise(after)}` : after ? humanise(after) : undefined;
      return {
        id: event.id,
        title: movement ? `${humanise(event.action)} · ${movement}` : humanise(event.action),
        detail: `Source channel ${humanise(event.sourceChannel).toLowerCase()}`,
        actor: event.actorDisplayName ?? event.actorId,
        occurredAt: event.occurredAt,
        tone: toneFor(event.action),
      };
    });
  }, [events]);

  if (entries.length === 0) {
    return <p className="text-theme-sm text-gray-600">No recorded activity for this {recordNoun} yet.</p>;
  }

  return (
    <Timeline>
      {entries.map((entry, index) => (
        <TimelineItem
          key={entry.id}
          isLast={index === entries.length - 1}
          mode={entry.tone === 'danger' ? 'error' : entry.tone === 'accent' ? 'warning' : 'primary'}
        >
          <TimelineTitle as="h3">{entry.title}</TimelineTitle>
          <TimelineData>{entry.detail}</TimelineData>
          <TimelineFooter>
            {formatDateTime(entry.occurredAt)}{entry.actor ? ` · by ${entry.actor}` : ''}
          </TimelineFooter>
        </TimelineItem>
      ))}
    </Timeline>
  );
};

export default HistoryTimeline;

import { useEffect, useState } from 'react';
import { Banner, Button, MetricCards } from '@rfdtech/components';
import { useNotifier } from 'shared/components/Notifier';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import Panel from 'modules/emergency/components/Panel';
import StatMetric from 'modules/emergency/components/StatMetric';
import { TextField } from 'modules/emergency/components/FormFields';
import { drillApi } from '../api/drillApi';
import type { RollCallView } from '../api/dto';
import { participation } from '../api/workflow';

/** How often the live roll-call refreshes - check-ins arrive from the muster point, not only from this screen. */
const REFRESH_MS = 10_000;

interface RollCallPanelProps {
  drillId: string;
  /** Whether this actor may check people in (`DRILL_EXECUTE`). */
  canCheckIn: boolean;
  onOutstanding?: (count: number) => void;
}

/**
 * SRS-SFL-S175-02 on the day: who is still outstanding against the baseline, refreshed as people arrive.
 * Checking someone in here goes through S162a's muster - the same check-in a real evacuation records.
 */
const RollCallPanel = ({ drillId, canCheckIn, onOutstanding }: RollCallPanelProps) => {
  const notify = useNotifier();
  const query = useApiQuery<RollCallView>((signal) => drillApi.rollCall(drillId, signal), [drillId]);
  const [personRef, setPersonRef] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const { refetch } = query;

  useEffect(() => {
    const timer = setInterval(refetch, REFRESH_MS);
    return () => clearInterval(timer);
  }, [refetch]);

  const outstanding = query.data?.outstanding.length;
  useEffect(() => {
    if (outstanding !== undefined) onOutstanding?.(outstanding);
  }, [outstanding, onOutstanding]);

  const checkIn = async (ref: string) => {
    if (!ref.trim()) return;
    setSubmitting(true);
    try {
      await drillApi.checkIn(drillId, ref.trim());
      setPersonRef('');
      refetch();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  const view = query.data;
  const notification = view?.notification;
  return (
    <Panel title="Live roll-call" subtitle={view ? `Baseline taken ${formatDateTime(view.execution.baselineTakenAt)} · refreshes every ${REFRESH_MS / 1000} s` : undefined}>
      {view?.execution.baselineStale && (
        <div className="mb-4">
          <Banner variant="warning" heading="Stale Baseline" subtext={`The access data behind this baseline was ${view.execution.baselineAccessDataAsOf ? `last updated ${formatDateTime(view.execution.baselineAccessDataAsOf)}` : 'missing'}. This drill will not fully validate roll-call accuracy.`} />
        </div>
      )}
      <MetricCards>
        <StatMetric label="On site at start" value={view?.baseline.length ?? 0} icon="users" loading={query.initialising} />
        <StatMetric label="Checked in" value={view ? view.baseline.length - view.outstanding.length : 0} icon="check-circle" tone="good" caption={view ? participation(view.baseline.length - view.outstanding.length, view.baseline.length) : undefined} loading={query.initialising} />
        <StatMetric label="Outstanding" value={view?.outstanding.length ?? 0} icon="alert-triangle" tone={view?.outstanding.length ? 'caution' : 'good'} loading={query.initialising} />
        <StatMetric label="Notification delivered" value={notification ? `${notification.deliveredCount}/${notification.targetCount}` : '-'} icon="megaphone" caption={notification ? `${notification.acknowledgedCount} acknowledged · ${notification.activationNumber}` : 'S174 status unavailable'} loading={query.initialising} />
      </MetricCards>

      {canCheckIn && (
        <form className="mt-5 flex items-end gap-2" onSubmit={(event) => { event.preventDefault(); void checkIn(personRef); }}>
          <TextField label="Check in at the assembly point" value={personRef} maxLength={160} onChange={setPersonRef} placeholder="Badge number or visitor badge" className="max-w-sm" />
          <Button type="submit" variant="primary" disabled={submitting || !personRef.trim()}>Check in</Button>
        </form>
      )}

      <div className="mt-5 grid gap-5 lg:grid-cols-2">
        <div>
          <p className="mb-2 font-semibold text-gray-900">Outstanding</p>
          {view?.outstanding.length ? (
            <ul className="divide-y divide-[var(--clet-border-subtle)] rounded-md border border-[var(--clet-border-subtle)]">
              {view.outstanding.map((person) => (
                <li key={person.personRef} className="flex items-center justify-between gap-2 px-3 py-2 text-theme-sm">
                  <span>
                    <span className="font-medium text-gray-900">{person.displayName ?? person.personRef}</span>
                    <span className="ml-2 text-theme-xs text-gray-600">{person.personRef} · {person.source === 'VISITOR' ? 'visitor' : person.source === 'ACCESS_CONTROL' ? 'access record' : 'unknown source'}</span>
                  </span>
                  {canCheckIn && <Button size="sm" variant="outline" disabled={submitting} onClick={() => void checkIn(person.personRef)}>Check in</Button>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-theme-sm text-gray-600">{view ? 'Everyone in the baseline is accounted for.' : 'Loading…'}</p>
          )}
        </div>
        <div>
          <p className="mb-2 font-semibold text-gray-900">Checked in</p>
          {view?.checkIns.length ? (
            <ul className="max-h-72 divide-y divide-[var(--clet-border-subtle)] overflow-auto rounded-md border border-[var(--clet-border-subtle)]">
              {[...view.checkIns].reverse().map((checkIn) => (
                <li key={`${checkIn.personRef}-${checkIn.checkedInAt}`} className="flex justify-between px-3 py-2 text-theme-sm">
                  <span>{checkIn.personRef}{view.unexpected.includes(checkIn.personRef) && <span className="ml-2 text-theme-xs text-[var(--clet-warning-text)]">not in baseline</span>}</span>
                  <span className="tabular-nums text-gray-600">{formatDateTime(checkIn.checkedInAt)}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-theme-sm text-gray-600">Nobody has checked in yet.</p>
          )}
          {view && view.unexpected.length > 0 && (
            <p className="mt-2 text-theme-xs text-gray-600">{view.unexpected.length} checked in without being in the baseline - a visitor who never signed in, or a badge that never registered. Worth a finding.</p>
          )}
        </div>
      </div>
    </Panel>
  );
};

export default RollCallPanel;

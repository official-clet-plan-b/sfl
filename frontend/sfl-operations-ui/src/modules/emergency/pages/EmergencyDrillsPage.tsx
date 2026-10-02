import { useMemo, useState } from 'react';
import type { DrillRun } from 'modules/emergency/api/dto';
import { drillsApi } from 'modules/emergency/api/emergencyApi';
import DrillPerformanceChart, { DrillBar } from 'modules/emergency/charts/DrillPerformanceChart';
import { formatElapsed, percentOf, percentValue } from 'modules/emergency/components/emergencyFormat';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import { CompleteDrillDialog, StartDrillDialog } from 'modules/emergency/dialogs/drillDialogs';
import { DerivedNote } from 'modules/fuel/components/Provenance';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useClampPage, useServerPage } from 'shared/hooks/useServerPage';
import { emergencyPaths } from 'shared/layout/navigation';
import { canManageScenarios } from 'modules/emergency/api/workflow';
import { Button, Banner, type TableColumn } from '@rfdtech/components';
import DataTable from 'shared/components/DataTable';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatMetric from 'modules/emergency/components/StatMetric';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import { CellStack } from 'modules/emergency/components/RegisterTable';
import Icon from 'shared/components/Icon';

/**
 * Notification drills and what they revealed.
 *
 * A drill is the only evidence that the notification path works. It exercises activation without
 * broadcasting anything, and its three recorded figures - reached, acknowledged, elapsed - are the
 * performance record SRS-SFL-S174-05 asks for.
 *
 * The acknowledgement rate the domain computes is against the **target**, not against those
 * actually reached. Both are shown, because the difference between them is the difference between
 * "people ignored us" and "we never got to them", and those have different fixes.
 */
const EmergencyDrillsPage = () => {
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [starting, setStarting] = useState(false);
  const [completing, setCompleting] = useState<DrillRun | null>(null);

  const records = useSiteRecords(siteCode);
  const paging = useServerPage(siteCode);
  const query = useApiQuery(
    (signal) => drillsApi.search({ siteCode, page: paging.page, size: paging.size }, signal),
    [siteCode, paging.page, paging.size],
  );

  useClampPage(paging.page, query.data?.totalPages, paging.setPage);

  const all = useMemo(() => query.data?.content ?? [], [query.data]);
  const running = useMemo(() => all.filter((drill) => drill.status === 'RUNNING'), [all]);
  const completed = useMemo(
    () =>
      all
        .filter((drill) => drill.status === 'COMPLETED')
        .sort((left, right) => left.startedAt.localeCompare(right.startedAt)),
    [all],
  );

  const bars = useMemo<DrillBar[]>(
    () =>
      completed.slice(-8).map((drill) => ({
        label: drill.drillNumber.replace(/^DRILL-/, ''),
        acknowledged: drill.acknowledgedRecipients,
        reachedOnly: Math.max(drill.reachedRecipients - drill.acknowledgedRecipients, 0),
        missed: Math.max(drill.targetRecipients - drill.reachedRecipients, 0),
      })),
    [completed],
  );

  const latest = completed[completed.length - 1];
  const averageAck = completed.length
    ? Math.round(
        completed.reduce(
          (total, drill) => total + percentValue(drill.acknowledgedRecipients, drill.targetRecipients),
          0,
        ) / completed.length,
      )
    : 0;
  const slowest = completed.reduce<DrillRun | undefined>(
    (worst, drill) =>
      (drill.activationMillis ?? 0) > (worst?.activationMillis ?? -1) ? drill : worst,
    undefined,
  );

  const columns = useMemo<TableColumn<DrillRun>[]>(
    () => [
      {
        id: 'drill',
        header: 'Drill',
        width: 280,
        cell: ({ row }) => (
          <CellStack
            primary={row.drillNumber}
            secondary={
              row.scenarioId ? records.scenarioName(row.scenarioId) : 'No scenario recorded'
            }
          />
        ),
      },
      {
        id: 'target',
        header: 'Target',
        width: 100,
        align: 'right',
        cell: ({ row }) => formatNumber(row.targetRecipients),
      },
      {
        id: 'reached',
        header: 'Reached',
        width: 130,
        align: 'right',
        cell: ({ row }) =>
          row.status === 'RUNNING' ? (
            <span className="text-gray-500">-</span>
          ) : (
            `${formatNumber(row.reachedRecipients)} · ${percentOf(row.reachedRecipients, row.targetRecipients)}`
          ),
      },
      {
        id: 'acknowledged',
        header: 'Acknowledged',
        width: 150,
        align: 'right',
        cell: ({ row }) => {
          if (row.status === 'RUNNING') {
            return <span className="text-gray-500">-</span>;
          }
          const rate = percentValue(row.acknowledgedRecipients, row.targetRecipients);
          return (
            <span className={rate < 80 ? 'font-semibold text-warning-800' : undefined}>
              {formatNumber(row.acknowledgedRecipients)} · {rate}%
            </span>
          );
        },
      },
      {
        id: 'elapsed',
        header: 'Elapsed',
        width: 110,
        align: 'right',
        cell: ({ row }) => formatElapsed(row.activationMillis),
      },
      {
        id: 'started',
        header: 'Started',
        width: 170,
        cell: ({ row }) => formatDateTime(row.startedAt),
      },
      {
        id: 'status',
        header: 'Status',
        width: 130,
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
      {
        id: 'action',
        header: '',
        width: 140,
        align: 'right',
        cell: ({ row }) =>
          row.status === 'RUNNING' ? (
            <Button
              size="sm"
              variant="outline"
             
              onClick={() => setCompleting(row)}
            ><Icon name="check-circle" size={14} aria-hidden="true" />
              Complete
            </Button>
          ) : null,
      },
    ],
    [records],
  );

  return (
    <div>
      <PageHeading
        title="Notification drills"
        subtitle="Rehearsals of the activation path, and what each one revealed."
        crumbs={[{ label: 'Emergency', to: emergencyPaths.dashboard }, { label: 'Drills' }]}
        actions={
          <>
            {/* A drill is a scenario exercised, so it follows the scenario grant. */}
            {canManageScenarios() && (
              <Button variant="primary" onClick={() => setStarting(true)}><Icon name="target" size={14} aria-hidden="true" />
                Start drill
              </Button>
            )}
            <Button variant="outline" onClick={query.refetch}><Icon name="refresh" size={14} aria-hidden="true" />
              Refresh
            </Button>
          </>
        }
      />

      <div className="mb-5">
        <Panel>
          <div className="max-w-xs">
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
          </div>
        </Panel>
      </div>

      <DataState
        loading={query.initialising}
        error={query.error}
        onRetry={query.refetch}
        minHeight={320}
      >
        <div className="space-y-5">
          {running.length > 0 && (
            <Banner
              variant="info"
              heading={`${running.length} drill${running.length === 1 ? ' is' : 's are'} still running`}
  subtext={<>A drill records nothing until it is completed. Complete it from its row with the
              figures observed, or it contributes nothing to the performance record.</>} />
          )}

          {completed.length === 0 && running.length === 0 && (
            <Banner variant="warning" heading="This site has never rehearsed its notification path"
  subtext={<>Every figure elsewhere in this module describes a broadcast that was sent. A drill is
              the only thing that tests whether one would arrive.</>} />
          )}

          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatMetric
              label="Drills completed"
              value={formatNumber(completed.length)}
              icon="target"
              caption={running.length > 0 ? `${running.length} still running` : 'All figures filed'}
            />
            <StatMetric
              label="Average acknowledgement"
              value={completed.length ? `${averageAck}%` : '-'}
              icon="check-circle"
              tone={completed.length && averageAck < 80 ? 'caution' : 'neutral'}
              caption="Against target, across every completed drill"
            />
            <StatMetric
              label="Last drill"
              value={
                latest ? percentOf(latest.acknowledgedRecipients, latest.targetRecipients) : '-'
              }
              icon="activity"
              caption={
                latest?.completedAt
                  ? `Completed ${formatDateTime(latest.completedAt)}`
                  : 'None completed'
              }
            />
            <StatMetric
              label="Slowest activation"
              value={formatElapsed(slowest?.activationMillis)}
              icon="clock"
              tone={(slowest?.activationMillis ?? 0) > 300000 ? 'caution' : 'neutral'}
              caption={slowest ? slowest.drillNumber : 'No completed drill'}
            />
          </div>

          {bars.length > 0 && (
            <Panel
              title="What each drill reached"
              subtitle="Every bar is its own target - the green segment is the part that closed"
            >
              <DrillPerformanceChart bars={bars} />
              <DerivedNote>
                Bucketed here from the drill records: acknowledged and reached are recorded by the
                service, never-reached is the target less those reached. The last eight completed
                drills are shown.
              </DerivedNote>
            </Panel>
          )}

          <Panel title="Drill register">
            <DataTable
              rows={[...running, ...[...completed].reverse()]}
              columns={columns}
              getRowId={(row) => row.id}
              loading={query.loading}
              caption="Drills at this site, with target, reached and acknowledged counts, elapsed time, start time and status."
              emptyMessage="No drill has been run at this site."
            />
            <div className="px-5 pt-2 pb-4">
              <DerivedNote>
                Acknowledgement is shown against the target, which is how the service computes it. A
                drill that reached half the site and had every one of them reply still reports fifty
                per cent - that is the honest reading, because the other half was never told.
              </DerivedNote>
            </div>
          </Panel>
        </div>
      </DataState>

      {starting && (
        <StartDrillDialog
          open
          defaultSiteCode={siteCode}
          records={records}
          onClose={() => setStarting(false)}
          onSaved={(drill) => {
            notifySuccess(
              `${drill.drillNumber} started.`,
              'Complete it with the observed figures when the rehearsal is over.',
            );
            query.refetch();
          }}
        />
      )}

      {completing && (
        <CompleteDrillDialog
          open
          drill={completing}
          onClose={() => setCompleting(null)}
          onSaved={(drill) => {
            notifySuccess(
              `${drill.drillNumber} completed.`,
              `${percentOf(drill.acknowledgedRecipients, drill.targetRecipients)} acknowledged in ${formatElapsed(drill.activationMillis)}.`,
            );
            query.refetch();
          }}
        />
      )}
    </div>
  );
};

export default EmergencyDrillsPage;

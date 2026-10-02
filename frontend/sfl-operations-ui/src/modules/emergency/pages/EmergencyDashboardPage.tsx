import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { RefreshCw, Zap } from 'lucide-react';
import {
  Banner,
  Badge,
  Button,
  MetricCards,
  PageSection,
  Tabs,
  TabsList,
  TabsTrigger,
  type TableColumn,
} from '@rfdtech/components';
import type { NotificationActivation } from 'modules/emergency/api/dto';
import {
  activationsApi,
  emergencyDashboardApi,
  drillsApi,
} from 'modules/emergency/api/emergencyApi';
import { activationLive, awaitingApproval, canBreakGlass } from 'modules/emergency/api/workflow';
import { ActivationStatusChip } from 'modules/emergency/components/EmergencyFields';
import { formatElapsed, percentOf } from 'modules/emergency/components/emergencyFormat';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack } from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import { DerivedNote } from 'modules/fuel/components/Provenance';
import { humanise } from 'modules/fleet/api/enums';
import { BarChart, DonutChart } from 'shared/charts/Charts';
import { toneColors } from 'shared/charts/palette';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { emergencyPaths } from 'shared/layout/navigation';

/**
 * The emergency notification dashboard.
 *
 * Seven counts come from the service, and they are seven exception counts - active broadcasts,
 * break-glass sends, failed recipients, outstanding acknowledgements, escalations, all-clears
 * pending closure and completed drills. That is the right emphasis for a mass notification system:
 * in normal operation every figure here is zero, and any figure that is not is something somebody
 * has to act on.
 *
 * `stale` is the service's own verdict against a per-site freshness threshold, not this screen's,
 * so it is reported rather than recomputed. Everything under the counts is derived from the
 * activation register and says so.
 */
const EmergencyDashboardPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [outstandingTab, setOutstandingTab] = useState<'activations' | 'drill'>('activations');

  const dashboard = useApiQuery(
    (signal) => emergencyDashboardApi.dashboard(siteCode, signal),
    [siteCode],
  );
  /**
   * Four questions, four server-side queries.
   *
   * Each list is the service's own answer rather than one window sieved four ways, so a count in a
   * panel header is the site's count and not the page's.
   */
  const activations = useApiQuery(
    (signal) => activationsApi.search({ siteCode, openOnly: true, size: 50 }, signal),
    [siteCode],
  );
  const afterAction = useApiQuery(
    (signal) => activationsApi.search({ siteCode, afterActionOutstanding: true, size: 25 }, signal),
    [siteCode],
  );
  const drills = useApiQuery(
    (signal) => drillsApi.search({ siteCode, status: 'COMPLETED', size: 10 }, signal),
    [siteCode],
  );

  const all = useMemo(() => activations.data?.content ?? [], [activations.data]);
  const live = useMemo(() => all.filter(activationLive), [all]);
  const pending = useMemo(() => all.filter(awaitingApproval), [all]);
  const outstandingAfterAction = useMemo(() => afterAction.data?.content ?? [], [afterAction.data]);
  const allClearPending = useMemo(
    () => all.filter((activation) => activation.status === 'ALL_CLEAR_PENDING'),
    [all],
  );

  const completedDrills = useMemo(() => drills.data?.content ?? [], [drills.data]);
  const lastDrill = completedDrills[0];

  const counts = dashboard.data;

  /** The same seven counts as the cards above, read as bars rather than tiles. */
  const exceptionBars = useMemo(
    () =>
      counts
        ? [
            { label: 'Active activations', value: counts.activeActivationCount, critical: true },
            { label: 'Break-glass sends', value: counts.breakGlassCount },
            { label: 'Failed recipients', value: counts.failedRecipientCount, critical: true },
            { label: 'Acknowledgements outstanding', value: counts.ackPendingCount },
            { label: 'Escalated', value: counts.escalatedCount, critical: true },
            { label: 'All-clear pending', value: counts.allClearPendingCount },
          ]
        : [],
    [counts],
  );

  /**
   * The same three lists behind "Live and pending activations" below, as a composition rather than
   * a table - an operator reads how the open register divides at a glance before scanning rows.
   */
  const activationMix = useMemo(
    () => [
      { name: 'Live', value: live.length, tone: 'blocked' as const },
      { name: 'Awaiting approval', value: pending.length, tone: 'caution' as const },
      { name: 'Pending closure', value: allClearPending.length, tone: 'ready' as const },
    ],
    [live.length, pending.length, allClearPending.length],
  );

  const columns = useMemo<TableColumn<NotificationActivation>[]>(
    () => [
      {
        id: 'activation',
        header: 'Activation',
        width: 260,
        cell: ({ row }) => (
          <CellStack
            primary={row.activationNumber}
            secondary={row.incidentReference ?? 'No incident reference'}
          />
        ),
      },
      { id: 'mode', header: 'Mode', width: 130, cell: ({ row }) => <StatusBadge value={row.mode} /> },
      {
        id: 'priority',
        header: 'Priority',
        width: 110,
        cell: ({ row }) => <StatusBadge value={row.priority} />,
      },
      {
        id: 'channels',
        header: 'Channels',
        width: 110,
        align: 'right',
        cell: ({ row }) => formatNumber(row.channels.length),
      },
      {
        id: 'sent',
        header: 'Time to send',
        width: 130,
        align: 'right',
        cell: ({ row }) => formatElapsed(row.fastLaneMillis),
      },
      {
        id: 'status',
        header: 'Status',
        width: 170,
        align: 'right',
        cell: ({ row }) => <ActivationStatusChip status={row.status} />,
      },
    ],
    [],
  );

  const outstandingCount = live.length + pending.length + allClearPending.length;

  return (
    <>
      <PageHeading
        title="Emergency notifications"
        subtitle="Live broadcasts, approvals outstanding and the obligations each one left behind."
        crumbs={[{ label: 'Emergency' }, { label: 'Dashboard' }]}
        meta={
          counts ? (
            <span className="text-theme-xs text-[var(--clet-text-secondary)]">
              Service counts generated {formatDateTime(counts.generatedAt)}
              {counts.sourceUpdatedAt
                ? ` · source last changed ${formatDateTime(counts.sourceUpdatedAt)}`
                : ' · no source activity recorded'}
            </span>
          ) : undefined
        }
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            {/* The break-glass page hides its own send control too; this stops the journey earlier, so
                nobody is walked to a screen whose one purpose they may not carry out. */}
            {canBreakGlass() && (
              <Button variant="primary-destructive" onClick={() => navigate(emergencyPaths.breakGlass)}>
                <Zap size={14} strokeWidth={1.5} aria-hidden /> Break glass
              </Button>
            )}
            <Button
              variant="outline"
              onClick={() => {
                dashboard.refetch();
                activations.refetch();
                drills.refetch();
              }}
            >
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh
            </Button>
          </>
        }
      />

      <DataState loading={false} error={dashboard.error} onRetry={dashboard.refetch} minHeight={320}>
        {counts?.stale && (
          <PageSection>
            <Banner
              variant="warning"
              heading="These counts are older than the freshness threshold"
              subtext="The service reports its own source data as stale for this site. Figures below may lag what has actually happened - check the activation register directly before acting on a zero."
            />
          </PageSection>
        )}

        {live.length > 0 && (
          <PageSection>
            <Banner
              variant="danger"
              heading={`${live.length} broadcast${live.length === 1 ? ' is' : 's are'} live at this site`}
              subtext="A live activation has gone out and has not been stood down. Send the all-clear once the emergency is over - closure needs evidence and cannot be done from here."
              action={
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => navigate(`${emergencyPaths.activations}?activations.f_status=ACTIVE`)}
                >
                  Open the register
                </Button>
              }
            />
          </PageSection>
        )}

        {outstandingAfterAction.length > 0 && (
          <PageSection>
            <Banner
              variant="warning"
              heading={`${outstandingAfterAction.length} break-glass broadcast${outstandingAfterAction.length === 1 ? '' : 's'} not yet accounted for`}
              subtext="Each of these went out without approval and cannot be closed until somebody with the after-action approval permission records a justification against it."
            />
          </PageSection>
        )}

        <PageSection>
          <MetricCards>
            <StatMetric
              label="Active activations"
              value={formatNumber(counts?.activeActivationCount ?? 0)}
              icon="siren"
              tone={(counts?.activeActivationCount ?? 0) > 0 ? 'critical' : 'neutral'}
              caption="Live, escalated or awaiting closure"
              loading={dashboard.initialising}
              onClick={() => navigate(emergencyPaths.activations)}
            />
            <StatMetric
              label="Break-glass sends"
              value={formatNumber(counts?.breakGlassCount ?? 0)}
              icon="zap"
              tone={(counts?.breakGlassCount ?? 0) > 0 ? 'caution' : 'neutral'}
              caption="Sent without prior approval, all time"
              loading={dashboard.initialising}
            />
            <StatMetric
              label="Failed recipients"
              value={formatNumber(counts?.failedRecipientCount ?? 0)}
              icon="alert-triangle"
              tone={(counts?.failedRecipientCount ?? 0) > 0 ? 'critical' : 'neutral'}
              caption="Provider reported a failure or expiry"
              loading={dashboard.initialising}
            />
            <StatMetric
              label="Acknowledgements outstanding"
              value={formatNumber(counts?.ackPendingCount ?? 0)}
              icon="clock"
              tone={(counts?.ackPendingCount ?? 0) > 0 ? 'caution' : 'neutral'}
              caption="Recipients targeted who have not replied"
              loading={dashboard.initialising}
            />
          </MetricCards>
        </PageSection>

        <PageSection>
          <MetricCards>
            <StatMetric
              label="Escalated"
              value={formatNumber(counts?.escalatedCount ?? 0)}
              icon="alert-circle"
              tone={(counts?.escalatedCount ?? 0) > 0 ? 'critical' : 'neutral'}
              caption="Acknowledgement SLA breached"
              loading={dashboard.initialising}
            />
            <StatMetric
              label="All-clear pending closure"
              value={formatNumber(counts?.allClearPendingCount ?? 0)}
              icon="check-circle"
              tone={(counts?.allClearPendingCount ?? 0) > 0 ? 'caution' : 'neutral'}
              caption="Stood down, evidence not yet filed"
              loading={dashboard.initialising}
            />
            <StatMetric
              label="Drills completed"
              value={formatNumber(counts?.drillCount ?? 0)}
              icon="target"
              caption="Rehearsals with figures recorded"
              loading={dashboard.initialising}
              onClick={() => navigate(emergencyPaths.drills)}
            />
            <StatMetric
              label="Awaiting approval"
              value={formatNumber(pending.length)}
              icon="user-plus"
              tone={pending.length > 0 ? 'caution' : 'neutral'}
              caption="Submitted, nobody has decided yet"
              loading={activations.initialising}
              onClick={() => navigate(`${emergencyPaths.activations}?activations.f_status=PENDING_APPROVAL`)}
            />
          </MetricCards>
        </PageSection>

        {counts && (
          <PageSection>
            <div className="grid gap-5 xl:grid-cols-3">
              <Panel className="xl:col-span-2" title="Exceptions" subtitle="The counts above, by kind">
                <BarChart
                  height={270}
                  horizontal
                  stacked
                  integerAxis={false}
                  categories={exceptionBars.map((bar) => bar.label)}
                  series={[
                    {
                      name: 'Blocking',
                      data: exceptionBars.map((bar) => (bar.critical ? bar.value : 0)),
                      color: toneColors.blocked,
                    },
                    {
                      name: 'Needs attention',
                      data: exceptionBars.map((bar) => (bar.critical ? 0 : bar.value)),
                      color: toneColors.caution,
                    },
                  ]}
                />
              </Panel>

              <Panel title="Open activations" subtitle="How the open register divides">
                <DonutChart
                  height={280}
                  centreLabel="Open"
                  labels={activationMix.map((slice) => slice.name)}
                  values={activationMix.map((slice) => slice.value)}
                  colors={activationMix.map((slice) => toneColors[slice.tone])}
                />
              </Panel>
            </div>
          </PageSection>
        )}

        <PageSection>
          <Panel
            title={outstandingTab === 'activations' ? 'Live and pending activations' : 'Last completed drill'}
            subtitle={
              outstandingTab === 'activations'
                ? 'Everything at this site that is not yet closed'
                : 'What the notification path achieved when it was last rehearsed'
            }
          >
            <Tabs
              variant="pill"
              value={outstandingTab}
              onValueChange={(value) => setOutstandingTab(value as 'activations' | 'drill')}
            >
              <TabsList>
                <TabsTrigger value="activations">
                  Live and pending <Badge size="sm">{outstandingCount}</Badge>
                </TabsTrigger>
                <TabsTrigger value="drill">Last completed drill</TabsTrigger>
              </TabsList>
            </Tabs>
            {outstandingTab === 'activations' ? (
              <>
                <DataState loading={false} error={activations.error} onRetry={activations.refetch}>
                  <RegisterTable
                    paramPrefix="outstanding"
                    framed={false}
                    columns={columns}
                    rows={[...live, ...pending, ...allClearPending]}
                    rowKey={(row) => row.id}
                    loading={activations.initialising}
                    onRowClick={(row) => navigate(emergencyPaths.activationDetail(row.id))}
                    emptyTitle="Nothing outstanding"
                    emptyDescription="No activation at this site is live, awaiting approval or awaiting closure."
                  />
                </DataState>
                <DerivedNote>
                  Counted from the activation register rather than published by the service - the
                  dashboard endpoint returns totals, not the records behind them.
                </DerivedNote>
              </>
            ) : (
              <DataState
                loading={drills.initialising}
                error={drills.error}
                onRetry={drills.refetch}
                empty={!lastDrill}
                emptyTitle="No drill has been completed"
                emptyHint="Start one from the drills screen - an untested notification path is an assumption."
                minHeight={140}
              >
                {lastDrill && (
                  <MetricCards>
                    <StatMetric
                      label="Reached"
                      value={percentOf(lastDrill.reachedRecipients, lastDrill.targetRecipients)}
                      icon="users"
                      caption={`${formatNumber(lastDrill.reachedRecipients)} of ${formatNumber(lastDrill.targetRecipients)}`}
                    />
                    <StatMetric
                      label="Acknowledged"
                      value={percentOf(lastDrill.acknowledgedRecipients, lastDrill.targetRecipients)}
                      icon="check-circle"
                      tone={
                        lastDrill.acknowledgedRecipients / Math.max(lastDrill.targetRecipients, 1) < 0.8
                          ? 'caution'
                          : 'good'
                      }
                      caption={`${formatNumber(lastDrill.acknowledgedRecipients)} replied`}
                    />
                    <StatMetric
                      label="Elapsed"
                      value={formatElapsed(lastDrill.activationMillis)}
                      icon="clock"
                      caption="Start to last recipient"
                    />
                    <StatMetric
                      label="Drill"
                      value={lastDrill.drillNumber}
                      icon="target"
                      caption={
                        lastDrill.completedAt
                          ? `Completed ${formatDateTime(lastDrill.completedAt)}`
                          : humanise(lastDrill.status)
                      }
                      onClick={() => navigate(emergencyPaths.drills)}
                    />
                  </MetricCards>
                )}
              </DataState>
            )}
          </Panel>
        </PageSection>
      </DataState>
    </>
  );
};

export default EmergencyDashboardPage;

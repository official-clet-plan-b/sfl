import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import {
  Button,
  EmptyState,
  HeroBanner,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  type TableColumn,
} from '@rfdtech/components';
import { Plus, RefreshCw } from 'lucide-react';
import {
  DailyFuelTotals,
  DriverLogbook,
  FuelAnomalyCase,
  FuelTransaction,
} from 'modules/fuel/api/dto';
import { humanise } from 'modules/fleet/api/enums';
import {
  driverLogbooksApi,
  fuelAnomaliesApi,
  fuelDashboardApi,
  fuelTransactionsApi,
} from 'modules/fuel/api/fuelApi';
import AnomalyMixChart, { AnomalyBar } from 'modules/fuel/charts/AnomalyMixChart';
import ReconciliationChart from 'modules/fuel/charts/ReconciliationChart';
import SpendChart, { SpendPoint } from 'modules/fuel/charts/SpendChart';
import { DerivedNote } from 'modules/fuel/components/Provenance';
import {
  currencyCodeOf,
  formatDueIn,
  formatMoney,
  formatQuantity,
} from 'modules/fuel/components/fuelFormat';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { sflActor } from 'shared/api/config';
import { canCaptureFuel } from 'modules/fleet/api/access';
import { CaptureTransactionDialog } from 'modules/fuel/dialogs/transactionDialogs';
import { CellStack, ErrorBanner, FuelBadge, Panel } from 'modules/fuel/components/fuelUi';
import { metricLink } from 'modules/fuel/components/metricLink';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';

const SPEND_DAYS = 14;

/**
 * Lays the service's daily totals onto a fixed window.
 *
 * The arithmetic is the service's - this only supplies the days it had nothing to report, so the
 * axis stays a full fortnight. A gap in the line would read as missing data rather than a quiet day,
 * which is a presentation problem and is why it is solved here rather than in a query.
 */
const toSpendPoints = (totals: DailyFuelTotals[], days: number): SpendPoint[] => {
  const byDay = new Map(totals.map((total) => [total.day, total]));
  const start = dayjs()
    .startOf('day')
    .subtract(days - 1, 'day');

  return Array.from({ length: days }, (_unused, offset) => {
    const day = start.add(offset, 'day');
    const total = byDay.get(day.format('YYYY-MM-DD'));
    return {
      label: day.format('D MMM'),
      spend: total?.totalCost ?? 0,
      volume: total?.quantity ?? 0,
    };
  });
};

/**
 * The Fuel & Driver Logbooks workspace.
 *
 * Every indicator and every chart here is counted by the service across the whole site. That was not
 * true when this screen was first built: the dashboard endpoint returned five transaction figures,
 * so the anomaly, logbook and reconciliation counts had to be derived from whatever list this
 * application could fetch, and were captioned to say so.
 *
 * The last two derivations went with `/dashboard/daily-totals` and `/dashboard/anomaly-counts`. The
 * spend trend was bucketed in the browser from one page of transactions, and the by-type breakdown
 * counted a page of the anomaly queue - both correct for a quiet site and both silently short for a
 * busy one. The one remaining caption is on reconciliation, where a single figure really is a
 * remainder of two others.
 *
 * `siteCode` is required by every fuel endpoint, so this page is single-site by construction. There
 * is no "all sites" option, because there is no query that would answer it.
 */
const FuelDashboardPage = () => {
  const navigate = useNavigate();
  const [attentionTab, setAttentionTab] = useState<'cases' | 'reconciliation'>('cases');
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [capturing, setCapturing] = useState(false);
  const { notifySuccess } = useNotifier();

  const windowStart = useMemo(
    () =>
      dayjs()
        .startOf('day')
        .subtract(SPEND_DAYS - 1, 'day')
        .toISOString(),
    [],
  );
  const windowEnd = useMemo(() => dayjs().endOf('day').toISOString(), []);

  const snapshot = useApiQuery(
    (signal) => fuelDashboardApi.snapshot(siteCode, signal),
    [siteCode],
  );

  const dailyTotals = useApiQuery(
    (signal) => fuelDashboardApi.dailyTotals(siteCode, windowStart, windowEnd, signal),
    [siteCode, windowStart, windowEnd],
  );

  const anomalyCounts = useApiQuery(
    (signal) => fuelDashboardApi.anomalyCounts(siteCode, signal),
    [siteCode],
  );

  /**
   * One transaction, for the currency and the quantity unit.
   *
   * The aggregate carries neither, and both belong to the site rather than to the row - a site
   * transacts in one currency and dispenses in one unit. This used to be the whole spend window, a
   * page of records fetched so two labels could be read off the first one.
   */
  const currencySample = useApiQuery(
    (signal) => fuelTransactionsApi.search({ siteCode, size: 1 }, signal),
    [siteCode],
  );

  /**
   * The six most pressing open cases.
   *
   * Six, not the largest page the service allows. It used to ask for two hundred because the by-type
   * chart counted them here; the chart is a real aggregate now, so this query is back to being what
   * the list beside it needs.
   */
  const anomalies = useApiQuery(
    (signal) => fuelAnomaliesApi.search({ siteCode, openOnly: true, size: 6 }, signal),
    [siteCode],
  );

  const logbooks = useApiQuery(
    (signal) => driverLogbooksApi.search({ siteCode, status: 'SUBMITTED', size: 6 }, signal),
    [siteCode],
  );

  const unreconciled = useApiQuery(
    (signal) => fuelTransactionsApi.search({ siteCode, status: 'RECEIVED', size: 6 }, signal),
    [siteCode],
  );

  const data = snapshot.data;

  const openAnomalies = useMemo(() => anomalies.data?.content ?? [], [anomalies.data]);
  const pendingReviews = useMemo(() => logbooks.data?.content ?? [], [logbooks.data]);

  /** The currency and unit the site actually transacts in, taken from its own records. */
  const currencyCode = currencyCodeOf(currencySample.data?.content?.[0]?.currency);
  const quantityUnit = currencySample.data?.content?.[0]?.quantityUnit ?? '';

  const spendPoints = useMemo(
    () => toSpendPoints(dailyTotals.data ?? [], SPEND_DAYS),
    [dailyTotals.data],
  );

  const reconciliationSlices = useMemo(() => {
    if (!data) {
      return [];
    }
    const outstanding = Math.max(data.transactionCount - data.reconciledCount - data.exceptionCount, 0);
    return [
      { name: 'Reconciled', value: data.reconciledCount, tone: 'ready' as const },
      { name: 'In exception', value: data.exceptionCount, tone: 'blocked' as const },
      { name: 'Not yet reconciled', value: outstanding, tone: 'neutral' as const },
    ];
  }, [data]);

  /**
   * Open cases by type, across the site.
   *
   * There is no urgent/ordinary split any more. The aggregate does not carry one, and mixing a
   * site-wide total with an urgency count read off one page of the queue would have produced a chart
   * whose two halves counted different things. The urgent figures are on the indicators above, and
   * the queue beside this chart shows each case's own SLA.
   */
  const anomalyBars = useMemo<AnomalyBar[]>(
    () =>
      Object.entries(anomalyCounts.data ?? {})
        .sort((left, right) => right[1] - left[1])
        .slice(0, 8)
        .map(([type, count]) => ({ label: humanise(type), value: count })),
    [anomalyCounts.data],
  );

  const anomalyColumns = useMemo<TableColumn<FuelAnomalyCase>[]>(
    () => [
      {
        id: 'case',
        header: 'Case',
        minWidth: 220,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.anomalyNumber} · ${humanise(row.type)}`}
            secondary={`SLA ${formatDueIn(row.slaDueAt)} · ${row.assignee ?? 'unassigned'}`}
          />
        ),
      },
      {
        id: 'severity',
        header: 'Severity',
        width: 120,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.severity} />,
      },
    ],
    [],
  );

  const logbookColumns = useMemo<TableColumn<DriverLogbook>[]>(
    () => [
      {
        id: 'logbook',
        header: 'Logbook',
        minWidth: 220,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.logbookNumber} · ${row.origin} → ${row.destination}`}
            secondary={`Submitted ${formatDateTime(row.submittedAt)}`}
          />
        ),
      },
      {
        id: 'status',
        header: 'Status',
        width: 130,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
    ],
    [],
  );

  const unreconciledColumns = useMemo<TableColumn<FuelTransaction>[]>(
    () => [
      {
        id: 'transaction',
        header: 'Transaction',
        minWidth: 220,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.vendorReference} · ${formatQuantity(row.quantity, row.quantityUnit)}`}
            secondary={`${formatDateTime(row.occurredAt)} · ${row.sourceSystem}`}
          />
        ),
      },
      {
        id: 'cost',
        header: 'Cost',
        width: 130,
        align: 'right',
        cell: ({ row }) => formatMoney(row.totalCost, row.currency),
      },
    ],
    [],
  );

  const refreshAll = () => {
    snapshot.refetch();
    dailyTotals.refetch();
    anomalyCounts.refetch();
    currencySample.refetch();
    anomalies.refetch();
    logbooks.refetch();
    unreconciled.refetch();
  };

  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning,' : hour < 17 ? 'Good afternoon,' : 'Good evening,';
  const metricsLoading = snapshot.initialising;
  const awaitingReconciliation = unreconciled.data?.totalElements ?? 0;

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Fuel dashboard</SectionTitle>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button variant="outline" onClick={refreshAll}>
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden />
              Refresh
            </Button>
            {canCaptureFuel() && (
              <Button variant="primary" onClick={() => setCapturing(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden />
                Capture transaction
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <HeroBanner greeting={greeting} name={sflActor.displayName} />
      </PageSection>

      {snapshot.error && (
        <PageSection>
          <ErrorBanner error={snapshot.error} onRetry={snapshot.refetch} />
        </PageSection>
      )}

      {/*
        Staleness is shown as a quiet note beside the figures rather than a warning banner across
        the top.

        The banner explained the platform's freshness threshold to somebody who had asked for a fuel
        summary, and it fires constantly on a site that simply has not refuelled today - so it
        trained people to scroll past the one place a real warning would appear. What is worth saying
        is when the figures were last true, which the note says in a line.
      */}
      {data?.stale && data.sourceUpdatedAt && (
        <PageSection>
          <p className="text-xs text-(--clet-text-secondary)">
            Figures as at {formatDateTime(data.sourceUpdatedAt)}.
          </p>
        </PageSection>
      )}

      {/*
       * Nine indicators, all published by the service. The split into "from the snapshot" and
       * "counted by this application" that this row used to carry is gone: the dashboard endpoint
       * now counts the anomaly, logbook and import figures itself, across the whole site rather
       * than across whatever page the dashboard happened to fetch.
       */}
      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Transactions"
            value={data ? formatNumber(data.transactionCount) : 0}
            description="All statuses, all time"
            {...metricLink(() => navigate(fuelPaths.transactions))}
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Fuel spend"
            value={data ? formatMoney(data.fuelSpend, currencyCode) : 0}
            description="Sum of recorded totals"
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Fuel volume"
            value={data ? formatQuantity(data.fuelVolume, quantityUnit) : 0}
            description="Sum of recorded quantities"
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Reconciled"
            value={data ? formatNumber(data.reconciledCount) : 0}
            description="Passed every policy rule"
            {...metricLink(() => navigate(`${fuelPaths.transactions}?status=RECONCILED`))}
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="In exception"
            value={data ? formatNumber(data.exceptionCount) : 0}
            description="Failed at least one rule"
            {...metricLink(() => navigate(`${fuelPaths.transactions}?status=EXCEPTION`))}
          />
        </MetricCards>
      </PageSection>

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Open anomaly cases"
            value={data ? formatNumber(data.openAnomalies) : 0}
            description={`${formatNumber(data?.unassignedAnomalies ?? 0)} unassigned`}
            {...metricLink(() => navigate(fuelPaths.anomalies))}
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Breaching SLA"
            value={data ? formatNumber(data.anomaliesBreachingSla) : 0}
            description={`${formatNumber(data?.materialOpenAnomalies ?? 0)} material`}
            {...metricLink(() => navigate(fuelPaths.anomalies))}
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Logbooks awaiting review"
            value={data ? formatNumber(data.pendingLogbookReviews) : 0}
            description={`${formatNumber(data?.draftLogbooks ?? 0)} still in draft`}
            {...metricLink(() => navigate(`${fuelPaths.logbooks}?status=SUBMITTED`))}
          />
          <MetricCard
            variant="soft"
            loading={metricsLoading}
            label="Awaiting reconciliation"
            value={data ? formatNumber(data.awaitingReconciliation) : 0}
            description="Received but not yet run"
            {...metricLink(() => navigate(fuelPaths.reconciliation))}
          />
        </MetricCards>
      </PageSection>

      <PageSection>
        <div className="grid gap-6 xl:grid-cols-3">
          <Panel
            className="xl:col-span-2"
            title="Fuel spend and volume"
            description={`By day · last ${SPEND_DAYS} days`}
          >
            {dailyTotals.error ? (
              <ErrorBanner error={dailyTotals.error} onRetry={dailyTotals.refetch} />
            ) : (
              <SpendChart points={spendPoints} currencyCode={currencyCode} unit={quantityUnit} />
            )}
          </Panel>

          <Panel title="Reconciliation standing" description="Every transaction at this site">
            <ReconciliationChart slices={reconciliationSlices} />
            {data && (
              <DerivedNote>
                Reconciled and in-exception are snapshot figures. Not-yet-reconciled is the
                remainder of the {formatNumber(data.transactionCount)} transactions the snapshot
                counts.
              </DerivedNote>
            )}
          </Panel>
        </div>
      </PageSection>

      <PageSection>
        <div className="grid gap-6 xl:grid-cols-2">
          <Panel
            title="Open anomaly cases"
            description="Oldest SLA first"
            actions={
              <Button size="sm" variant="outline" onClick={() => navigate(fuelPaths.anomalies)}>
                View all {formatNumber(data?.openAnomalies ?? openAnomalies.length)}
              </Button>
            }
          >
            {anomalies.error && <ErrorBanner error={anomalies.error} onRetry={anomalies.refetch} />}
            <Table paramPrefix="fuel-dash-anomalies" variant="soft">
              <TableContent
                variant="soft"
                columns={anomalyColumns}
                data={openAnomalies}
                rowKey={(row) => row.id}
                loading={anomalies.initialising}
                onRowClick={(row) => navigate(fuelPaths.anomalyDetail(row.id))}
                emptyContent={
                  <EmptyState
                    title="No open anomaly cases"
                    description="Nothing at this site is waiting on an explanation or a decision."
                  />
                }
              />
            </Table>
          </Panel>

          <Panel
            title="Logbooks awaiting review"
            description="With a reviewer rather than a driver"
            actions={
              <Button size="sm" variant="outline" onClick={() => navigate(fuelPaths.logbooks)}>
                View all {formatNumber(data?.pendingLogbookReviews ?? pendingReviews.length)}
              </Button>
            }
          >
            {logbooks.error && <ErrorBanner error={logbooks.error} onRetry={logbooks.refetch} />}
            <Table paramPrefix="fuel-dash-logbooks" variant="soft">
              <TableContent
                variant="soft"
                columns={logbookColumns}
                data={pendingReviews.slice(0, 6)}
                rowKey={(row) => row.id}
                loading={logbooks.initialising}
                onRowClick={(row) => navigate(fuelPaths.logbookDetail(row.id))}
                emptyContent={
                  <EmptyState
                    title="Nothing awaiting review"
                    description="No logbook at this site is submitted or under review."
                  />
                }
              />
            </Table>
          </Panel>
        </div>
      </PageSection>

      <PageSection>
        <Panel title="Cases and reconciliation">
          <Tabs
            variant="pill"
            value={attentionTab}
            onValueChange={(value) => setAttentionTab(value as 'cases' | 'reconciliation')}
          >
            <TabsList>
              <TabsTrigger value="cases">Open cases by type ({anomalyBars.length})</TabsTrigger>
              <TabsTrigger value="reconciliation">
                Awaiting reconciliation ({awaitingReconciliation})
              </TabsTrigger>
            </TabsList>

            <TabsContent value="cases">
              {anomalyCounts.error && (
                <ErrorBanner error={anomalyCounts.error} onRetry={anomalyCounts.refetch} />
              )}
              {anomalyBars.length === 0 && !anomalyCounts.initialising ? (
                <EmptyState title="No open cases" description="There is nothing to break down." />
              ) : (
                <AnomalyMixChart bars={anomalyBars} />
              )}
            </TabsContent>

            <TabsContent value="reconciliation">
              <div className="mb-2 flex justify-end">
                <Button size="sm" variant="outline" onClick={() => navigate(fuelPaths.reconciliation)}>
                  Reconcile
                </Button>
              </div>
              {unreconciled.error && (
                <ErrorBanner error={unreconciled.error} onRetry={unreconciled.refetch} />
              )}
              <Table paramPrefix="fuel-dash-unreconciled" variant="soft">
                <TableContent
                  variant="soft"
                  columns={unreconciledColumns}
                  data={unreconciled.data?.content ?? []}
                  rowKey={(row) => row.id}
                  loading={unreconciled.initialising}
                  onRowClick={(row) => navigate(fuelPaths.transactionDetail(row.id))}
                  emptyContent={
                    <EmptyState
                      title="Everything has been reconciled"
                      description="No transaction at this site is still in the received state."
                    />
                  }
                />
              </Table>
            </TabsContent>
          </Tabs>
        </Panel>
      </PageSection>

      {capturing && (
        <CaptureTransactionDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCapturing(false)}
          onSaved={(transaction) => {
            notifySuccess(
              `Transaction captured against ${transaction.vendorReference}.`,
              'It is in the received state until reconciliation runs.',
            );
            refreshAll();
          }}
        />
      )}
    </>
  );
};

export default FuelDashboardPage;

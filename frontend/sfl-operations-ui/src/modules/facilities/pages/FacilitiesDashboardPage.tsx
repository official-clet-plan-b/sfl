import { useState } from 'react';
import { useNavigate } from 'react-router';
import { AlertCircle, AlertTriangle, Calendar, Clock, Gauge, ShieldCheck, Wrench } from 'lucide-react';
import {
  Banner,
  Card,
  EmptyState,
  HeroBanner,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import { DonutChart } from 'shared/charts/Charts';
import { sflActor } from 'shared/api/config';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import { getDashboard } from '../api/facilitiesApi';
import type { DashboardExceptionRow } from '../api/dto';
import { canDrillDown } from '../api/workflow';
import StatusBadge from '../components/StatusBadge';
import {
  figureToneClass as toneClass,
  figureTone,
  formatDateTime,
  humaniseCode,
  scoreTone,
  severityTone,
} from '../components/facilitiesFormat';

/**
 * The S152-05 facilities dashboard.
 *
 * Computed live by the service from the source records, which is why the counts always reconcile to
 * the rows behind them - and why every tile here can be opened rather than only believed.
 *
 * The stale-data warning is given the top of the page, not a footnote. SRS-SFL-S152-05 requires that
 * "critical safety and examination-readiness indicators must display stale-data warnings where
 * freshness thresholds are breached", and a dashboard that shows confident numbers over readiness
 * nobody has checked in a fortnight is worse than one that shows nothing: it converts absence of
 * information into apparent good news.
 */
const FacilitiesDashboardPage = () => {
  const navigate = useNavigate();
  useBreadcrumbs([{ label: 'Facilities' }]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [exceptionTab, setExceptionTab] = useState<'unavailable' | 'stale'>('unavailable');

  const { data, loading, error, refetch } = useApiQuery(
    (signal) => getDashboard(siteCode || undefined, signal),
    [siteCode],
  );

  const drilldown = canDrillDown();

  const exceptionColumns: TableColumn<DashboardExceptionRow>[] = [
    {
      id: 'code',
      header: 'Space',
      cell: ({ row }) => <span className="font-medium text-foreground">{row.code}</span>,
    },
    { id: 'label', header: 'Name', accessorKey: 'label' },
    {
      id: 'reason',
      header: 'Why',
      cell: ({ row }) => <span className="text-muted-foreground">{row.reason}</span>,
    },
    {
      id: 'severity',
      header: 'Severity',
      align: 'right',
      width: 120,
      cell: ({ row }) => (
        <StatusBadge
          value={row.severity}
          tone={severityTone(row.severity as 'CRITICAL' | 'MAJOR' | 'MINOR' | 'ADVISORY')}
        />
      ),
    },
  ];

  /** A drilldown row opens its space. Only offered when the actor may see the underlying record. */
  const openSpace = drilldown
    ? (row: DashboardExceptionRow) => navigate(facilitiesPaths.spaceDetail(row.id))
    : undefined;

  /** One exception list: a titled section over a soft table that says what an empty list means. */
  const exceptionSection = (
    id: string,
    title: string,
    description: string,
    rows: DashboardExceptionRow[],
    emptyTitle: string,
    columns: TableColumn<DashboardExceptionRow>[] = exceptionColumns,
  ) => (
    <PageSection>
      <SectionHeader>
        <SectionTitle>{title}</SectionTitle>
        <SectionDescription>{description}</SectionDescription>
      </SectionHeader>
      <Table paramPrefix={`dashboard-${id}`} variant="soft">
        <Card bordered>
          <TableContent
            variant="soft"
            columns={columns}
            data={rows}
            rowKey={(row) => row.id}
            loading={loading}
            onRowClick={openSpace}
            aria-label={title}
            emptyContent={<EmptyState title={emptyTitle} />}
          />
        </Card>
      </Table>
    </PageSection>
  );

  const readinessFigureTone = data ? figureTone(scoreTone(data.readinessScore)) : 'neutral';

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Facilities dashboard</SectionTitle>
          <SectionDescription>Readiness, blockers and examination risk across the estate</SectionDescription>
          {data && (
            <SectionDescription>
              {data.operatingMode === 'EXAMINATION' ? 'Examination mode' : 'Routine operations'} ·
              generated {formatDateTime(data.generatedAt)}
            </SectionDescription>
          )}
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <HeroBanner
        name={sflActor.displayName}
        role={humaniseCode(sflActor.roles.split(',')[0]?.trim())}
        className="mb-[var(--clet-app-layout-body-gap)]"
      />

      <DataState loading={false} error={error} onRetry={refetch} minHeight={320}>
        {/*
          Two banners, in this order, because they answer different questions and the second is
          worthless without the first: "can I trust these numbers?" then "what do they say?"
        */}
        {data?.stale && data.staleWarning && (
          <PageSection>
            <Banner variant="warning" heading="Readiness data is stale" subtext={data.staleWarning} />
          </PageSection>
        )}

        {data?.operatingMode === 'EXAMINATION' && (
          <PageSection>
            <Banner
              variant="info"
              heading="This centre is in examination mode"
              subtext="Readiness is assessed against the examination standard and the staleness threshold is tighter. Spaces must be READY outright to host an examination."
            />
          </PageSection>
        )}

        <PageSection>
          <MetricCards>
            <MetricCard
              variant="soft"
              loading={loading}
              label="Site readiness"
              value={data ? `${data.readinessScore}%` : '-'}
              description={data ? `${data.spaces.ready} of ${data.spaces.total} spaces ready` : undefined}
              descriptionAdornment={
                <Gauge size={16} strokeWidth={2} className={toneClass[readinessFigureTone]} aria-hidden />
              }
            />
            <MetricCard
              variant="soft"
              loading={loading}
              label="Blocked spaces"
              value={data?.spaces.blocked ?? '-'}
              description={
                data
                  ? `${data.spaces.degraded} degraded, ${data.spaces.unknown} unassessed`
                  : undefined
              }
              descriptionAdornment={
                <AlertTriangle
                  size={16}
                  strokeWidth={2}
                  className={toneClass[data && data.spaces.blocked > 0 ? 'critical' : 'good']}
                  aria-hidden
                />
              }
            />
            <MetricCard
              variant="soft"
              loading={loading}
              label="Critical blockers"
              value={data?.blockers.critical ?? '-'}
              description={
                data
                  ? data.blockers.criticalBeyondEscalationWindow > 0
                    ? `${data.blockers.criticalBeyondEscalationWindow} past the escalation window`
                    : `${data.blockers.total} open in total`
                  : undefined
              }
              descriptionAdornment={
                <AlertCircle
                  size={16}
                  strokeWidth={2}
                  className={toneClass[data && data.blockers.critical > 0 ? 'critical' : 'good']}
                  aria-hidden
                />
              }
            />
            <MetricCard
              variant="soft"
              loading={loading}
              label="Examination-ready"
              value={data ? `${data.spaces.availableForExamination}/${data.spaces.examinationCapable}` : '-'}
              description={data ? 'Capable spaces currently usable' : undefined}
              descriptionAdornment={
                <ShieldCheck
                  size={16}
                  strokeWidth={2}
                  className={
                    toneClass[
                      data && data.spaces.availableForExamination < data.spaces.examinationCapable
                        ? 'caution'
                        : 'good'
                    ]
                  }
                  aria-hidden
                />
              }
            />
          </MetricCards>
        </PageSection>

        <PageSection>
          <SectionHeader>
            <SectionTitle>Readiness mix</SectionTitle>
            <SectionDescription>How the spaces at this site are currently assessed.</SectionDescription>
          </SectionHeader>
          <Card bordered className="max-w-md">
            <DonutChart
              labels={['Ready', 'Degraded', 'Blocked', 'Unknown']}
              values={data ? [data.spaces.ready, data.spaces.degraded, data.spaces.blocked, data.spaces.unknown] : [0, 0, 0, 0]}
              colors={['#15803d', '#ca8a04', '#b91c1c', '#64748b']}
              centreLabel={`${data?.spaces.total ?? 0} spaces`}
              showPercentages={false}
            />
          </Card>
        </PageSection>

        <PageSection>
          <MetricCards>
            <MetricCard
              variant="soft"
              loading={loading}
              label="Impaired assets"
              value={data?.assets.impaired ?? '-'}
              description={
                data ? `${data.assets.criticalImpaired} critical, of ${data.assets.total} assets` : undefined
              }
              descriptionAdornment={
                <Wrench
                  size={16}
                  strokeWidth={2}
                  className={toneClass[data && data.assets.criticalImpaired > 0 ? 'critical' : 'neutral']}
                  aria-hidden
                />
              }
            />
            <MetricCard
              variant="soft"
              loading={loading}
              label="Service overdue"
              value={data?.assets.serviceOverdue ?? '-'}
              description={data ? `${data.assets.serviceDueSoon} due soon` : undefined}
              descriptionAdornment={
                <Clock
                  size={16}
                  strokeWidth={2}
                  className={toneClass[data && data.assets.serviceOverdue > 0 ? 'caution' : 'good']}
                  aria-hidden
                />
              }
            />
            <MetricCard
              variant="soft"
              loading={loading}
              label="Open faults"
              value={data?.maintenance.openFaults ?? '-'}
              description={data ? `${data.maintenance.openWorkOrders} work orders open` : undefined}
              descriptionAdornment={
                <AlertCircle
                  size={16}
                  strokeWidth={2}
                  className={toneClass[data && data.maintenance.openFaults > 0 ? 'caution' : 'good']}
                  aria-hidden
                />
              }
            />
            <MetricCard
              variant="soft"
              loading={loading}
              label="Bookable now"
              value={data ? `${data.spaces.availableForBooking}/${data.spaces.bookable}` : '-'}
              description={data ? 'Bookable spaces currently available' : undefined}
              descriptionAdornment={
                <Calendar
                  size={16}
                  strokeWidth={2}
                  className={
                    toneClass[
                      data && data.spaces.availableForBooking < data.spaces.bookable ? 'caution' : 'good'
                    ]
                  }
                  aria-hidden
                />
              }
            />
          </MetricCards>
        </PageSection>

        {data && !drilldown && (
          <PageSection>
            <Banner
              variant="info"
              heading="You can see these totals but not the records behind them."
              subtext="Drilling into an exception needs the facilities dashboard drilldown permission."
            />
          </PageSection>
        )}

        {exceptionSection(
          'risk',
          'Examination readiness risk',
          'Examination-capable spaces with something standing between them and use',
          data?.examinationRisks ?? [],
          'No examination-capable space is at risk.',
        )}

        <PageSection>
          <Tabs value={exceptionTab} onValueChange={(value) => setExceptionTab(value as typeof exceptionTab)}>
            <TabsList aria-label="Facilities exceptions">
              <TabsTrigger value="unavailable">Unavailable spaces</TabsTrigger>
              <TabsTrigger value="stale">Stale readiness</TabsTrigger>
            </TabsList>
            <TabsContent value="unavailable">
              {exceptionSection('unavailable', 'Unavailable spaces', 'Bookable spaces that cannot currently be booked', data?.unavailableSpaces ?? [], 'Every bookable space is available.')}
            </TabsContent>
            <TabsContent value="stale">
              {exceptionSection(
                'stale',
                'Stale readiness',
                'Spaces not reassessed inside the configured window, or never assessed',
                data?.staleReadiness ?? [],
                'Every space has been assessed inside the window.',
                [
                  exceptionColumns[0],
                  exceptionColumns[1],
                  { id: 'reason', header: 'Last assessed', cell: ({ row }) => <span className="text-muted-foreground">{row.reason}</span> },
                  {
                    id: 'severity',
                    header: '',
                    align: 'right',
                    width: 120,
                    cell: ({ row }) => (
                      <StatusBadge
                        value={row.severity === 'MAJOR' ? 'NEVER ASSESSED' : 'OVERDUE'}
                        tone={row.severity === 'MAJOR' ? 'blocked' : 'caution'}
                        label={humaniseCode(row.severity === 'MAJOR' ? 'NEVER_ASSESSED' : 'OVERDUE')}
                      />
                    ),
                  },
                ],
              )}
            </TabsContent>
          </Tabs>
        </PageSection>
      </DataState>
    </>
  );
};

export default FacilitiesDashboardPage;

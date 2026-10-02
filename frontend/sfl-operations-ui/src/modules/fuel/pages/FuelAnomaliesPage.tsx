import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
  Button,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Tabs,
  TabsList,
  TabsTrigger,
  type TableColumn,
} from '@rfdtech/components';
import { Clock, RefreshCw } from 'lucide-react';
import { FuelAnomalyCase } from 'modules/fuel/api/dto';
import {
  ANOMALY_SEVERITIES,
  ANOMALY_STATUSES,
  ANOMALY_TYPES,
  AnomalySeverity,
  AnomalyStatus,
  AnomalyType,
} from 'modules/fuel/api/enums';
import { fuelAnomaliesApi, fuelDashboardApi } from 'modules/fuel/api/fuelApi';
import { anomalySlaBreached } from 'modules/fuel/api/workflow';
import { useClampPage, useRegisterPaging } from 'modules/fuel/components/useRegisterPaging';
import { formatDueIn } from 'modules/fuel/components/fuelFormat';
import { CellStack, ErrorBanner, FuelBadge, Panel, RegisterTable } from 'modules/fuel/components/fuelUi';
import { EnumField } from 'modules/fuel/components/fuelFields';
import { metricLink } from 'modules/fuel/components/metricLink';
import { humanise } from 'modules/fleet/api/enums';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';

const QUEUE_VIEWS = [
  { value: 'OPEN', label: 'Open cases' },
  { value: 'BREACHED', label: 'Breaching SLA' },
  { value: 'MATERIAL', label: 'Material only' },
  { value: 'UNASSIGNED', label: 'Unassigned' },
  { value: 'ALL', label: 'Every case' },
];

/**
 * The fuel anomaly queue.
 *
 * Every filter here reaches the service, including the four queue views. That matters more here
 * than anywhere else in the module: these filters were once applied in the browser over a capped
 * window, so "breaching SLA" meant "breaches among the first two hundred cases" - precisely the
 * queue an operator must not be handed. The service's default ordering is oldest SLA first, which
 * is what a queue wants.
 *
 * The four counters above the table come from the dashboard endpoint, which counts them across the
 * whole site rather than across a page.
 */
const FuelAnomaliesPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [status, setStatus] = useState<AnomalyStatus | ''>(
    (searchParams.get('status') as AnomalyStatus | null) ?? '',
  );
  const [view, setView] = useState('OPEN');
  const [type, setType] = useState<AnomalyType | ''>('');
  const [severity, setSeverity] = useState<AnomalySeverity | ''>('');

  /** The four views, expressed as the query parameters the service accepts. */
  const viewParams = useMemo(() => {
    switch (view) {
      case 'OPEN':
        return { openOnly: true };
      case 'BREACHED':
        return { openOnly: true, dueBefore: new Date().toISOString() };
      case 'MATERIAL':
        return { openOnly: true, material: true };
      case 'UNASSIGNED':
        return { openOnly: true, unassigned: true };
      case 'ALL':
        return {};
      default:
        return {};
    }
  }, [view]);

  const filterKey = `${siteCode}|${status}|${view}|${type}|${severity}`;
  const paging = useRegisterPaging('fuel-anomalies', filterKey);

  const query = useApiQuery(
    (signal) =>
      fuelAnomaliesApi.search(
        {
          siteCode,
          status: status || undefined,
          type: type || undefined,
          severity: severity || undefined,
          assignee: paging.search || undefined,
          ...viewParams,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [filterKey, paging.search, paging.page, paging.size],
  );

  useClampPage(paging.page, query.data?.totalPages, paging.setPage);

  /** Site-wide counters, published by the service rather than counted from a page. */
  const indicators = useApiQuery(
    (signal) => fuelDashboardApi.snapshot(siteCode, signal),
    [siteCode],
  );

  const columns = useMemo<TableColumn<FuelAnomalyCase>[]>(
    () => [
      {
        id: 'case',
        header: 'Case',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.anomalyNumber} · ${humanise(row.type)}`}
            secondary={row.detectedRules.map((rule) => humanise(rule)).join(', ') || 'no rule recorded'}
          />
        ),
      },
      {
        id: 'sla',
        header: 'SLA',
        width: 150,
        cell: ({ row }) => (
          <span className={anomalySlaBreached(row) ? 'font-semibold text-(--clet-error-text)' : undefined}>
            {anomalySlaBreached(row) && (
              <Clock size={13} aria-hidden className="mr-1 inline align-[-2px]" />
            )}
            {formatDueIn(row.slaDueAt)}
          </span>
        ),
      },
      {
        id: 'assignee',
        header: 'Assignee',
        width: 150,
        cell: ({ row }) =>
          row.assignee ?? <span className="text-(--clet-text-secondary)">Unassigned</span>,
      },
      {
        id: 'severity',
        header: 'Severity',
        width: 110,
        cell: ({ row }) => <FuelBadge value={row.severity} />,
      },
      {
        id: 'material',
        header: 'Material',
        width: 100,
        align: 'center',
        cell: ({ row }) =>
          row.material ? (
            <FuelBadge value="HIGH" label="Material" tone="caution" />
          ) : (
            <span className="text-(--clet-text-secondary)">-</span>
          ),
      },
      {
        id: 'escalation',
        header: 'Level',
        width: 80,
        align: 'right',
        cell: ({ row }) => row.escalationLevel,
      },
      {
        id: 'status',
        header: 'Status',
        width: 160,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
    ],
    [],
  );

  const filtersApplied = Boolean(status || type || severity || paging.search);

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Fuel anomaly cases</SectionTitle>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button
              variant="outline"
              onClick={() => {
                query.refetch();
                indicators.refetch();
              }}
            >
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden />
              Refresh
            </Button>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={indicators.initialising}
            label="Open cases"
            value={formatNumber(indicators.data?.openAnomalies ?? 0)}
            description="Neither closed nor cancelled"
            {...metricLink(() => setView('OPEN'))}
          />
          <MetricCard
            variant="soft"
            loading={indicators.initialising}
            label="Breaching SLA"
            value={formatNumber(indicators.data?.anomaliesBreachingSla ?? 0)}
            description="Past the policy’s target"
            {...metricLink(() => setView('BREACHED'))}
          />
          <MetricCard
            variant="soft"
            loading={indicators.initialising}
            label="Material"
            value={formatNumber(indicators.data?.materialOpenAnomalies ?? 0)}
            description="Surfaced to finance and audit"
            {...metricLink(() => setView('MATERIAL'))}
          />
          <MetricCard
            variant="soft"
            loading={indicators.initialising}
            label="Unassigned"
            value={formatNumber(indicators.data?.unassignedAnomalies ?? 0)}
            description="Nobody is accountable yet"
            {...metricLink(() => setView('UNASSIGNED'))}
          />
        </MetricCards>
      </PageSection>

      <PageSection>
        <Panel
          title="Anomaly cases"
          description="A case closes only with an explanation and a recorded decision, oldest SLA first."
        >
          <Tabs variant="pill" value={view || 'ALL'} onValueChange={setView}>
            <TabsList>
              {QUEUE_VIEWS.map((entry) => (
                <TabsTrigger key={entry.value} value={entry.value}>
                  {entry.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          {query.error && <ErrorBanner error={query.error} onRetry={query.refetch} className="mt-4" />}

          <RegisterTable
            paramPrefix="fuel-anomalies"
            columns={columns}
            rows={query.data?.content ?? []}
            rowKey={(row) => row.id}
            loading={query.loading}
            onRowClick={(row) => navigate(fuelPaths.anomalyDetail(row.id))}
            empty={{
              title: 'None open',
              description: 'Cases raised by reconciliation and by the overnight sweep appear here.',
              filteredTitle: 'No case matches these filters',
            }}
            filtersApplied={filtersApplied}
            totalPages={query.data?.totalPages ?? 0}
            totalItems={query.data?.totalElements ?? 0}
            pageSize={paging.size}
            searchPlaceholder="Search by assignee"
            onResetFilters={() => {
              setStatus('');
              setType('');
              setSeverity('');
            }}
            filters={
              <>
                <EnumField
                  label="Status"
                  value={status}
                  options={ANOMALY_STATUSES}
                  onChange={setStatus}
                  allowEmpty
                  emptyLabel="Any status"
                />
                <EnumField
                  label="Type"
                  value={type}
                  options={ANOMALY_TYPES}
                  onChange={setType}
                  allowEmpty
                  emptyLabel="Any type"
                />
                <EnumField
                  label="Severity"
                  value={severity}
                  options={ANOMALY_SEVERITIES}
                  onChange={setSeverity}
                  allowEmpty
                  emptyLabel="Any severity"
                />
              </>
            }
          />
        </Panel>
      </PageSection>
    </>
  );
};

export default FuelAnomaliesPage;

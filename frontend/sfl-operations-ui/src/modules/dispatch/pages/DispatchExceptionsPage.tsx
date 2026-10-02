import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Badge,
  Button,
  Input,
  MetricCard,
  MetricCards,
  PageSection,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  type TableColumn,
} from '@rfdtech/components';
import { Clock, Download, ShieldAlert } from 'lucide-react';
import { DispatchExceptionCase } from 'modules/dispatch/api/dto';
import {
  EXCEPTION_SEVERITIES,
  EXCEPTION_STATUSES,
  EXCEPTION_TYPES,
  ExceptionSeverity,
  ExceptionStatus,
  ExceptionType,
} from 'modules/dispatch/api/enums';
import { dispatchExceptionsApi, dispatchReportsApi } from 'modules/dispatch/api/dispatchApi';
import { exceptionOpen, exceptionSlaBreached } from 'modules/dispatch/api/workflow';
import CellStack from 'modules/dispatch/components/CellStack';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import {
  FilterDropdown,
  RegisterTable,
  useClampRegisterPage,
  useRegisterQuery,
} from 'modules/dispatch/components/registerTable';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { formatDueIn } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';

const PREFIX = 'exceptions';

const QUEUE_VIEWS = [
  { value: 'OPEN', label: 'Open' },
  { value: 'BREACHED', label: 'Breaching SLA' },
  { value: 'SECURITY', label: 'Security relevant' },
  { value: 'UNASSIGNED', label: 'Unassigned' },
  { value: 'ALL', label: 'All' },
];

/**
 * The dispatch exception queue.
 *
 * `GET /exceptions` accepts a site, a type and a status; severity, assignee, security relevance and
 * SLA standing are filtered here over the returned window, and each control says so. With more open
 * cases than the window holds, the "breaching SLA" view is the breaches *in the window* rather than
 * at the site. Every one of them is a server-side predicate now, so the two are the same thing.
 *
 * The stakes are higher here than on a normal queue: an open case blocks the manifest it belongs to
 * from closing, so a case nobody can see is a consignment nobody can close.
 */
const DispatchExceptionsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [view, setView] = useState('OPEN');
  const [exporting, setExporting] = useState(false);

  const table = useRegisterQuery(PREFIX);
  const { filters } = table;

  /**
   * Each view is a set of server-side predicates, not a pass over whatever came back.
   *
   * This was gap 2. "Breaching SLA" used to mean the breaches *in the loaded window*, which at a
   * busy site is a different and much smaller number than the breaches at the site - and the
   * difference only showed up when somebody went looking for a case nobody had seen.
   */
  const viewParams =
    view === 'OPEN'
      ? { openOnly: true }
      : view === 'BREACHED'
        ? { openOnly: true, dueBefore: new Date().toISOString() }
        : view === 'SECURITY'
          ? { openOnly: true, securityRelevant: true }
          : view === 'UNASSIGNED'
            ? { openOnly: true, unassigned: true }
            : {};

  const query = useApiQuery(
    (signal) =>
      dispatchExceptionsApi.search(
        {
          siteCode,
          type: (filters.type as ExceptionType) || undefined,
          status: (filters.status as ExceptionStatus) || undefined,
          severity: (filters.severity as ExceptionSeverity) || undefined,
          assignee: filters.assignee?.trim() || undefined,
          ...viewParams,
          page: table.page,
          size: table.size,
        },
        signal,
      ),
    [
      siteCode,
      filters.type,
      filters.status,
      filters.severity,
      filters.assignee,
      view,
      table.page,
      table.size,
    ],
  );

  useClampRegisterPage(table, query.data?.totalPages);

  /**
   * The four queue counts, each its own site-wide query.
   *
   * Counted by the service rather than from the page on screen: a page of twenty-five records
   * cannot tell an operator how many open cases the site has, and a header figure that silently
   * meant "on this page" is exactly the kind of number somebody plans around.
   */
  const counts = useApiQuery(
    (signal) =>
      Promise.all([
        dispatchExceptionsApi.search({ siteCode, openOnly: true, size: 1 }, signal),
        dispatchExceptionsApi.search(
          { siteCode, openOnly: true, dueBefore: new Date().toISOString(), size: 1 },
          signal,
        ),
        dispatchExceptionsApi.search({ siteCode, openOnly: true, securityRelevant: true, size: 1 }, signal),
        dispatchExceptionsApi.search({ siteCode, openOnly: true, unassigned: true, size: 1 }, signal),
      ]).then(([open, breaching, secure, unassignedCases]) => ({
        open: open.totalElements,
        breached: breaching.totalElements,
        security: secure.totalElements,
        unassigned: unassignedCases.totalElements,
      })),
    [siteCode],
  );

  const viewCount: Record<string, number | undefined> = {
    OPEN: counts.data?.open,
    BREACHED: counts.data?.breached,
    SECURITY: counts.data?.security,
    UNASSIGNED: counts.data?.unassigned,
  };

  const exportReport = async () => {
    setExporting(true);
    try {
      const fileName = await dispatchReportsApi.exceptions(siteCode);
      notifySuccess(
        `Downloaded ${fileName}.`,
        'The service exports the site’s exception cases, not the filtered view.',
      );
    } catch (error) {
      notifyError(error);
    } finally {
      setExporting(false);
    }
  };

  const columns = useMemo<TableColumn<DispatchExceptionCase>[]>(
    () => [
      {
        id: 'case',
        header: 'Case',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={row.exceptionNumber}
            secondary={`${humanise(row.type)} · ${
              row.detectedRules.map((rule) => humanise(rule)).join(', ') || 'no rule recorded'
            }`}
          />
        ),
      },
      {
        id: 'blocks',
        header: 'Manifest blocked',
        cell: ({ row }) =>
          row.dispatchId && exceptionOpen(row) ? (
            <StatusBadge value="BLOCKED" label="Manifest" tone="blocked" />
          ) : (
            <span className="text-muted-foreground">-</span>
          ),
      },
      {
        id: 'severity',
        header: 'Severity',
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            <StatusBadge value={row.severity} />
            {row.securityRelevant && (
              <ShieldAlert
                size={14}
                strokeWidth={1.75}
                className="shrink-0 text-error"
                aria-label="Security relevant"
              />
            )}
          </div>
        ),
      },
      {
        id: 'assignee',
        header: 'Assignee',
        cell: ({ row }) =>
          row.assignee ?? <span className="text-muted-foreground">Unassigned</span>,
      },
      {
        id: 'sla',
        header: 'SLA',
        cell: ({ row }) => (
          <span className={exceptionSlaBreached(row) ? 'font-semibold text-error' : undefined}>
            {exceptionSlaBreached(row) && (
              <Clock size={13} strokeWidth={1.75} className="mr-1 inline align-[-2px]" aria-hidden="true" />
            )}
            {formatDueIn(row.slaDueAt)}
          </span>
        ),
      },
      {
        id: 'status',
        header: 'Status',
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
    ],
    [],
  );

  return (
    <>
      <PageHeading
        title="Dispatch exception cases"
        subtitle="Custody gaps, receipt variances, scan mismatches and return discrepancies."
        crumbs={[{ label: 'Dispatch', to: dispatchPaths.dashboard }, { label: 'Exception cases' }]}
        actions={
          <>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button variant="outline" loading={exporting} onClick={exportReport}>
              <Download size={14} strokeWidth={1.5} aria-hidden="true" />
              Export CSV
            </Button>
          </>
        }
      />

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={counts.initialising}
            label="Open cases"
            value={counts.data?.open ?? 0}
            description="Each blocks its manifest from closing"
          />
          <MetricCard
            variant="soft"
            loading={counts.initialising}
            label="Breaching SLA"
            value={counts.data?.breached ?? 0}
            description="Past the resolution target"
          />
          <MetricCard
            variant="soft"
            loading={counts.initialising}
            label="Security relevant"
            value={counts.data?.security ?? 0}
            description="Surfaced to the security function"
          />
          <MetricCard
            variant="soft"
            loading={counts.initialising}
            label="Unassigned"
            value={counts.data?.unassigned ?? 0}
            description="Nobody is accountable yet"
          />
        </MetricCards>
      </PageSection>

      <Panel
        title="Exception cases"
        description="Custody gaps, receipt variances, scan mismatches and return discrepancies, oldest SLA first"
      >
        <Tabs variant="pill" value={view} onValueChange={setView}>
          <TabsList>
            {QUEUE_VIEWS.map((entry) => (
              <TabsTrigger key={entry.value} value={entry.value}>
                {entry.label}
                {viewCount[entry.value] !== undefined && (
                  <Badge size="sm" className="ml-1.5">
                    {viewCount[entry.value]}
                  </Badge>
                )}
              </TabsTrigger>
            ))}
          </TabsList>

          <TabsContent value={view}>
            <DataState loading={false} error={query.error} onRetry={query.refetch}>
              <RegisterTable
                paramPrefix={PREFIX}
                caption="Dispatch exception cases matching the current filters, ordered by SLA due time, with the manifest each blocks, severity, security relevance, assignee, SLA and status."
                columns={columns}
                rows={query.data?.content ?? []}
                rowKey={(row) => row.id}
                loading={query.loading}
                totalPages={query.data?.totalPages ?? 1}
                totalItems={query.data?.totalElements ?? 0}
                onRowClick={(row) => navigate(dispatchPaths.exceptionDetail(row.id))}
                searchable={false}
                filters={
                  <>
                    <FilterDropdown
                      paramPrefix={PREFIX}
                      name="type"
                      label="Type"
                      options={EXCEPTION_TYPES.map((value) => ({ value, label: humanise(value) }))}
                    />
                    <FilterDropdown
                      paramPrefix={PREFIX}
                      name="status"
                      label="Status"
                      options={EXCEPTION_STATUSES.map((value) => ({ value, label: humanise(value) }))}
                    />
                    <FilterDropdown
                      paramPrefix={PREFIX}
                      name="severity"
                      label="Severity"
                      options={EXCEPTION_SEVERITIES.map((value) => ({ value, label: humanise(value) }))}
                    />
                    <Input
                      name="assignee"
                      aria-label="Assignee"
                      placeholder="Assignee: part of a name"
                      defaultValue={filters.assignee ?? ''}
                    />
                  </>
                }
                emptyTitle="None open"
                emptyDescription="No case matches these filters."
              />
            </DataState>
          </TabsContent>
        </Tabs>
      </Panel>
    </>
  );
};

export default DispatchExceptionsPage;

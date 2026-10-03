import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import {
  Button,
  Card,
  Combobox,
  Dropdown,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  TableFilter,
  TableHeader,
  useBreadcrumbs,
  useTableState,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { FacilityFault } from '../api/dto';
import type { FacilityFaultStatus } from '../api/enums';
import { faultPriorities, faultStatuses } from '../api/enums';
import { reportFault, searchFaults } from '../api/facilitiesApi';
import { canReportFaults } from '../api/workflow';
import StatusBadge from '../components/StatusBadge';
import ReportFaultDialog from '../dialogs/ReportFaultDialog';
import {
  faultStatusTone,
  formatDateTime,
  humaniseCode,
  orDash,
  priorityTone,
} from '../components/facilitiesFormat';

/**
 * The fault register - SRS-SFL-S153-01.
 *
 * <p>This is the screen the retired static page had and the dashboard did not, and it is the last
 * thing ADR 0006 gave up when that page went.
 *
 * <p>**Overdue is a column, not a computation.** The service returns `overdue` and `slaDueAt` on
 * every fault, decided by the same clock the escalation sweep uses. A browser working it out for
 * itself would disagree with the sweep whenever a workstation clock drifted, and the sweep is the
 * one that notifies people.
 *
 * <p>A requester sees only the faults they reported. That is enforced per record by the service, so
 * this screen needs no special case: it asks for the register and receives a shorter one.
 */
/**
 * The three filters live in the URL, as the table keeps every filter. `show` defaults to open work,
 * so an absent parameter means "Open only" and a link to the register opens on what is outstanding.
 */
const defaultFilters = { show: 'open' };
const listOf = (value: string | null | undefined): string[] => (value ? value.split(',') : []);

const FaultRegisterPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Faults' }]);

  const { filters } = useTableState<Record<string, string>>({
    paramPrefix: 'faults',
    defaultFilters,
  });
  const status = listOf(filters.status);
  const priority = listOf(filters.priority);
  const openOnly = filters.show !== 'all';
  // A primitive key for the query, because `status` is a new array on every render.
  const statusKey = status.join(',');

  // The fields hold the operator's choice until the filter is applied; the URL holds what applied.
  const [statusValue, setStatusValue] = useState<string[]>(status);
  const [priorityValue, setPriorityValue] = useState<string[]>(priority);
  const [showValue, setShowValue] = useState(filters.show ?? 'open');

  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [reporting, setReporting] = useState(false);

  const faults = useApiQuery(
    (signal) =>
      searchFaults(
        {
          siteCode: siteCode || undefined,
          // One value narrows the fetch; the rest are applied below. `FaultQuery` takes a single
          // status, so a multi-select cannot be pushed down whole.
          status: (status[0] || undefined) as FacilityFaultStatus | undefined,
          openOnly: openOnly || undefined,
          limit: 100,
        },
        signal,
      ),
    [siteCode, statusKey, openOnly],
  );

  /*
    Counts describe what the service returned, not the whole register. A count claiming to be the
    site total would be a promise this screen cannot keep: a requester's view is narrowed per record
    by `FacilityFaultService.requesterFilter`, so their totals are legitimately smaller.
  */
  const fetched = faults.data?.items ?? [];
  const visible = fetched.filter(
    (fault) =>
      (status.length === 0 || status.includes(fault.status)) &&
      (priority.length === 0 || priority.includes(fault.priority)),
  );
  const tally = (pick: (fault: (typeof fetched)[number]) => string) =>
    fetched.reduce<Record<string, number>>((counts, fault) => {
      const key = pick(fault);
      counts[key] = (counts[key] ?? 0) + 1;
      return counts;
    }, {});
  const statusCounts = tally((fault) => fault.status);
  const priorityCounts = tally((fault) => fault.priority);

  const columns: TableColumn<FacilityFault>[] = [
    {
      id: 'faultNumber',
      header: 'Fault',
      width: 170,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.faultNumber}</span>,
    },
    { id: 'title', header: 'What is wrong', accessorKey: 'title' },
    {
      id: 'locationCode',
      header: 'Where',
      cell: ({ row }) => orDash(row.locationCode),
    },
    {
      id: 'priority',
      header: 'Priority',
      width: 110,
      cell: ({ row }) => <StatusBadge value={row.priority} tone={priorityTone(row.priority)} />,
    },
    {
      id: 'status',
      header: 'Status',
      width: 160,
      cell: ({ row }) => <StatusBadge value={row.status} tone={faultStatusTone(row.status)} />,
    },
    {
      id: 'slaDueAt',
      header: 'SLA',
      width: 190,
      cell: ({ row: fault }) => {
        if (!fault.slaDueAt) {
          // Untriaged, or migrated from the pre-S153 system. Either way it has no deadline yet, and
          // saying so is more use than an empty cell - a fault with no SLA never escalates.
          return <span className="text-xs text-muted-foreground">Not triaged</span>;
        }
        return fault.overdue ? (
          <span className="text-xs font-medium text-error-text">
            Overdue{fault.escalationLevel > 0 ? ` · level ${fault.escalationLevel}` : ''}
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">{formatDateTime(fault.slaDueAt)}</span>
        );
      },
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Faults</SectionTitle>
          <SectionDescription>
            Reported problems, what they are blocking, and what is late
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <SiteSelect value={siteCode} onChange={setSiteCode} />
            {canReportFaults() && (
              <Button variant="primary" onClick={() => setReporting(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Report a fault
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={faults.error} onRetry={faults.refetch}>
          <Table paramPrefix="faults" variant="soft">
            <Card bordered>
              <TableHeader>
              <TableFilter>
                  <Combobox
                    multiple
                    name="status"
                    aria-label="Status"
                    placeholder="Status"
                    value={statusValue}
                    onValueChange={setStatusValue}
                    options={faultStatuses.map((value) => ({
                      value,
                      label: `${humaniseCode(value)} (${statusCounts[value] ?? 0})`,
                    }))}
                  />
                  <Combobox
                    multiple
                    name="priority"
                    aria-label="Priority"
                    placeholder="Priority"
                    value={priorityValue}
                    onValueChange={setPriorityValue}
                    options={faultPriorities.map((value) => ({
                      value,
                      label: `${humaniseCode(value)} (${priorityCounts[value] ?? 0})`,
                    }))}
                  />
                  <Dropdown
                    name="show"
                    aria-label="Show"
                    placeholder="Open only"
                    value={showValue || null}
                    onValueChange={(next) => setShowValue(next ?? '')}
                    options={[
                      { value: 'open', label: 'Open only' },
                      { value: 'all', label: 'Everything' },
                    ]}
                  />
              </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={visible}
                rowKey={(fault) => fault.id}
                loading={faults.loading}
                onRowClick={(fault) => navigate(facilitiesPaths.faultDetail(fault.id))}
                aria-label="Faults"
                emptyContent={
                  <EmptyState
                    title={openOnly ? 'Nothing is outstanding' : 'No faults reported'}
                    description={
                      // Same caution as the work-order queue: a requester sees only the faults they
                      // reported, so a definite statement about the site would be wrong for them.
                      openOnly
                        ? 'Nothing outstanding is visible to you. Switch to Everything to include resolved and dismissed faults.'
                        : 'No faults are visible to you. A requester sees only the ones they reported themselves.'
                    }
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {reporting && (
        <ReportFaultDialog
          siteCode={siteCode}
          onClose={() => setReporting(false)}
          onSubmit={async (request) => {
            const created = await reportFault(request);
            setReporting(false);
            notify.notifySuccess(`Fault ${created.faultNumber} reported.`);
            navigate(facilitiesPaths.faultDetail(created.id));
          }}
        />
      )}
    </>
  );
};

export default FaultRegisterPage;

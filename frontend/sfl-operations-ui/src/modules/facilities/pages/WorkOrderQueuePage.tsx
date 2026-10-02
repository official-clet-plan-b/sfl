import { useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Card,
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  useBreadcrumbs,
  useTableState,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { WorkOrder } from '../api/dto';
import type { WorkOrderStatus } from '../api/enums';
import { workOrderStatuses } from '../api/enums';
import { listVendors, searchWorkOrders } from '../api/facilitiesApi';
import StatusBadge from '../components/StatusBadge';
import {
  formatDateTime,
  heldFor,
  humaniseCode,
  orDash,
  overdueBy,
  priorityTone,
  workOrderStatusTone,
} from '../components/facilitiesFormat';

/**
 * The work-order queue - SRS-SFL-S153-02.
 *
 * ## Overdue first, and why the sort is not a preference
 *
 * The service returns the queue newest-first. This screen re-sorts it so that anything past its SLA
 * is at the top, ordered by how late it is. That is the only ordering that matches what the queue is
 * for: a supervisor opening it at eight in the morning needs the overdue work in the first screen,
 * not on page three behind everything raised overnight.
 *
 * Sorting here rather than asking the service to do it is deliberate - `overdue` and
 * `minutesOverdue` both come down the wire already computed, so this is arranging the answer rather
 * than recomputing it.
 *
 * ## What a vendor sees
 *
 * A contractor sees only the work assigned to them. That is enforced per record by the service, on
 * reads and writes alike, so this screen shows the assignee filter to everybody and simply receives
 * a shorter list - offering a filter that implied otherwise would be the shell contradicting the
 * service.
 */
const WorkOrderQueuePage = () => {
  const navigate = useNavigate();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Work orders' }]);

  /*
    The search endpoint takes one value per axis, so status and vendor are single choices that live
    in the URL, as the table keeps its filters. "Outstanding only" is not a third filter: it is the
    question this screen exists to answer, asked on every visit, so it is the tab row rather than a
    field behind a popover.
  */
  const { filters } = useTableState({ paramPrefix: 'work-orders' });
  const status = filters.status ?? '';
  const vendorId = filters.vendor ?? '';
  const [statusValue, setStatusValue] = useState(status);
  const [vendorValue, setVendorValue] = useState(vendorId);

  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [openOnly, setOpenOnly] = useState(true);

  const orders = useApiQuery(
    (signal) =>
      searchWorkOrders(
        {
          siteCode: siteCode || undefined,
          status: (status || undefined) as WorkOrderStatus | undefined,
          vendorId: vendorId || undefined,
          openOnly: openOnly || undefined,
          limit: 200,
        },
        signal,
      ),
    [siteCode, status, vendorId, openOnly],
  );

  const vendors = useApiQuery(
    (signal) => listVendors(siteCode || undefined, signal),
    [siteCode],
  );

  /** Overdue first, worst first within that; everything else keeps the service's order. */
  const rows = [...(orders.data?.items ?? [])].sort((a, b) => {
    if (a.overdue !== b.overdue) {
      return a.overdue ? -1 : 1;
    }
    return (b.minutesOverdue ?? 0) - (a.minutesOverdue ?? 0);
  });

  const overdueCount = rows.filter((order) => order.overdue).length;

  const columns: TableColumn<WorkOrder>[] = [
    {
      id: 'workOrderNumber',
      header: 'Work order',
      width: 170,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.workOrderNumber}</span>,
    },
    { id: 'title', header: 'Work', accessorKey: 'title' },
    {
      id: 'workOrderType',
      header: 'Type',
      width: 120,
      cell: ({ row }) => humaniseCode(row.workOrderType),
    },
    {
      id: 'assignedTo',
      header: 'Assigned to',
      width: 150,
      cell: ({ row }) => orDash(row.assignedTo),
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
      width: 150,
      cell: ({ row: order }) => (
        <div className="flex flex-col items-start gap-0.5">
          <StatusBadge value={order.status} tone={workOrderStatusTone(order.status)} />
          {order.status === 'ON_HOLD' && order.totalHeldSeconds > 0 && (
            <span className="text-xs text-muted-foreground">{heldFor(order.totalHeldSeconds)}</span>
          )}
        </div>
      ),
    },
    {
      id: 'slaDueAt',
      header: 'SLA',
      width: 190,
      cell: ({ row: order }) =>
        order.overdue ? (
          <span className="text-xs font-medium text-error-text">
            {overdueBy(order.minutesOverdue)}
            {order.escalationLevel > 0 ? ` · level ${order.escalationLevel}` : ''}
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">{formatDateTime(order.slaDueAt)}</span>
        ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Work orders</SectionTitle>
          <SectionDescription>
            {overdueCount > 0
              ? `${overdueCount} of ${rows.length} past their SLA`
              : 'What is booked, who has it, and what is late'}
          </SectionDescription>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} />
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <Tabs
          variant="pill"
          value={openOnly ? 'open' : 'all'}
          onValueChange={(value) => setOpenOnly(value === 'open')}
        >
          <TabsList aria-label="Which work orders">
            <TabsTrigger value="open">Outstanding</TabsTrigger>
            <TabsTrigger value="all">Everything</TabsTrigger>
          </TabsList>

          <TabsContent value={openOnly ? 'open' : 'all'}>
            <DataState loading={false} error={orders.error} onRetry={orders.refetch}>
              <Table paramPrefix="work-orders" variant="soft">
                <Card bordered>
                  <TableHeader>
                    <TableFilter variant="spread">
                      <Dropdown
                        name="status"
                        aria-label="Status"
                        placeholder="All statuses"
                        clearable
                        value={statusValue || null}
                        onValueChange={(next) => setStatusValue(next ?? '')}
                        options={workOrderStatuses.map((value) => ({
                          value,
                          label: humaniseCode(value),
                        }))}
                      />
                      <Dropdown
                        name="vendor"
                        aria-label="Vendor"
                        placeholder="Any vendor"
                        clearable
                        value={vendorValue || null}
                        onValueChange={(next) => setVendorValue(next ?? '')}
                        options={(vendors.data ?? []).map((vendor) => ({
                          value: vendor.id,
                          label: vendor.name,
                        }))}
                      />
                    </TableFilter>
                  </TableHeader>
                  <TableContent
                    variant="soft"
                    columns={columns}
                    data={rows}
                    rowKey={(order) => order.id}
                    loading={orders.loading}
                    onRowClick={(order) => navigate(facilitiesPaths.workOrderDetail(order.id))}
                    aria-label="Work orders"
                    emptyContent={
                      <EmptyState
                        title={openOnly ? 'Nothing outstanding' : 'No work orders'}
                        description={
                          // Deliberately does not claim to know *why* the list is empty. A contractor
                          // sees only the work assigned to them, so "everything here is closed" would
                          // be a confident falsehood on a site with a full queue they cannot see.
                          openOnly
                            ? 'Nothing outstanding is visible to you. Switch to Everything to include closed and cancelled work.'
                            : 'Nothing here is visible to you. Work orders are raised against a reported fault, or generated by a preventive schedule - and a contractor sees only the ones assigned to them.'
                        }
                      />
                    }
                  />
                </Card>
              </Table>
            </DataState>
          </TabsContent>
        </Tabs>
      </PageSection>
    </>
  );
};

export default WorkOrderQueuePage;

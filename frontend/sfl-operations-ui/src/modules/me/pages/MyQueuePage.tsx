import { useNavigate } from 'react-router';
import type { TableColumn } from '@rfdtech/components';
import { LocalTable } from 'modules/me/components/LocalTable';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import { searchWorkOrders } from 'modules/facilities/api/facilitiesApi';
import type { WorkOrder } from 'modules/facilities/api/dto';

/**
 * The technician's and the vendor's queue - SRS §2.3 "Maintenance Technician / Vendor".
 *
 * ## The narrowing is the service's, and it is per record
 *
 * `WorkOrderApplicationService.assertVisible` refuses a work order not assigned to the actor, on
 * reads and writes alike, and `vendorFilter` narrows the list in the same breath. So this screen
 * asks for the queue and receives only what is theirs - including by id, which is the part an empty
 * list cannot prove.
 *
 * S153 recorded the reasoning: "the real boundary is **assignment**... because 'the ones assigned to
 * me' is not something a matrix can say". A vendor firm with three technicians sees three disjoint
 * queues; that is the stricter reading and the deliberate one.
 *
 * ## Why this exists when the work-order queue already does
 *
 * The operator queue is the whole site's, sorted overdue-first, with assignment controls. That is
 * the supervisor's screen and it is the wrong landing for somebody whose question is "what am I
 * doing today". Same data, same service, different first paragraph.
 */
const MyQueuePage = () => {
  const navigate = useNavigate();
  const site = defaultSite;

  const orders = useApiQuery(
    (signal) => searchWorkOrders({ siteCode: site }, signal),
    [site],
  );

  const rows = orders.data?.items ?? [];

  const columns: TableColumn<WorkOrder>[] = [
    { id: 'workOrderNumber', header: 'Job', cell: ({ row }) => row.workOrderNumber },
    { id: 'title', header: 'What', cell: ({ row }) => row.title },
    { id: 'locationCode', header: 'Where', cell: ({ row }) => row.locationCode ?? '-' },
    { id: 'priority', header: 'Priority', cell: ({ row }) => <StatusBadge value={row.priority} /> },
    { id: 'status', header: 'Status', cell: ({ row }) => <StatusBadge value={row.status} /> },
    {
      id: 'overdue',
      header: 'SLA',
      // `overdue` comes down the wire. A browser deciding for itself what is late would disagree
      // with the escalation sweep the moment a workstation clock drifted - and the sweep is the one
      // that notifies people.
      cell: ({ row }) => (row.overdue ? <StatusBadge value="OVERDUE" tone="blocked" /> : '-'),
    },
  ];

  return (
    <>
      <PageHeading
        title="My work queue"
        subtitle="The jobs assigned to you"
        crumbs={[{ label: 'My work queue' }]}
      />
      <Panel>
        <LocalTable
          paramPrefix="queue"
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={orders.loading}
          error={orders.error}
          onRetry={orders.refetch}
          caption="Jobs assigned to you"
          emptyTitle="Nothing is assigned to you"
          // Never "every job at this site is closed" - this queue is yours, and a contractor who sees
          // only their own has no way to know what else exists.
          emptyHint="Work assigned to you appears here. It does not show anybody else's."
          onRowClick={(row) => navigate(facilitiesPaths.workOrderDetail(row.id))}
        />
      </Panel>
    </>
  );
};

export default MyQueuePage;

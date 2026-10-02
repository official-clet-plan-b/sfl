import { useNavigate } from 'react-router';
import type { TableColumn } from '@rfdtech/components';
import { LocalTable } from 'modules/me/components/LocalTable';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import { searchFaults } from 'modules/facilities/api/facilitiesApi';
import type { FacilityFault } from 'modules/facilities/api/dto';

/**
 * What the Room Requester / Host raised - SRS §2.3, and the one class that is purely a consumer.
 *
 * ## Both halves are already narrowed by the service
 *
 * `FacilityFaultService.requesterFilter` and `BookingApplicationService.requesterFilter` narrow to
 * the actor when `IFIMP_REQUESTER` is their only facilities role - the same rule, written twice,
 * because faults and bookings are different aggregates. So this screen asks for the register and
 * receives a shorter one; there is no client-side filter here and there must never be, because a
 * filter in the browser is a display convention and the rows would still have crossed the boundary.
 *
 * ## A missing permission that is not a missing capability
 *
 * `IFIMP_REQUESTER` deliberately does **not** hold `FACILITIES_BOOKING_CANCEL`. That is not an
 * oversight and this screen must not read it as one: cancelling one's own booking is allowed by the
 * per-record rule in `BookingApplicationService`, which checks `requestedBy` rather than the matrix.
 * The permission gates cancelling *somebody else's*.
 *
 * ## Bookings are listed, not managed
 *
 * S159 has no operator screens yet, so there is nowhere to navigate a booking row to. Rather than
 * link into a page that does not exist, the booking panel states what it can and stops - and the
 * gap report says so plainly rather than the screen implying the feature is coming.
 */
const MyRequestsPage = () => {
  const navigate = useNavigate();
  const site = defaultSite;

  const faults = useApiQuery(
    (signal) => searchFaults({ siteCode: site }, signal),
    [site],
  );

  const columns: TableColumn<FacilityFault>[] = [
    { id: 'faultNumber', header: 'Reference', cell: ({ row }) => row.faultNumber },
    { id: 'title', header: 'What I reported', cell: ({ row }) => row.title },
    { id: 'locationCode', header: 'Where', cell: ({ row }) => row.locationCode ?? '-' },
    { id: 'priority', header: 'Priority', cell: ({ row }) => <StatusBadge value={row.priority} /> },
    { id: 'status', header: 'Status', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ];

  const rows = faults.data?.items ?? [];

  return (
    <>
      <PageHeading
        title="My requests"
        subtitle="The faults you reported and the rooms you booked"
        crumbs={[{ label: 'My requests' }]}
      />

      <Panel title="Faults I reported">
        <LocalTable
          paramPrefix="faults"
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={faults.loading}
          error={faults.error}
          onRetry={faults.refetch}
          caption="Faults you reported"
          emptyTitle="You have not reported a fault"
          // Not "there are no faults" - this list is narrowed to yours, so the wider claim is one
          // this screen has no standing to make.
          emptyHint="Faults you report appear here, with whatever the maintenance team does about them."
          onRowClick={(row) => navigate(facilitiesPaths.faultDetail(row.id))}
        />
      </Panel>

      <Panel title="My bookings">
        <p className="text-sm text-muted-foreground">
          Room and resource booking has its own screens, under Room booking in the navigation. Your
          bookings are narrowed to you by the service there; this panel is not showing an empty list
          because you have none.
        </p>
      </Panel>
    </>
  );
};

export default MyRequestsPage;

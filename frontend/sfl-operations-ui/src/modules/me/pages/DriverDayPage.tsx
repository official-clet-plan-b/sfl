import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button, type TableColumn } from '@rfdtech/components';
import { LocalTable } from 'modules/me/components/LocalTable';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { driverLogbooksApi, fuelTransactionsApi } from 'modules/fuel/api/fuelApi';
import type { DriverLogbook, FuelTransaction } from 'modules/fuel/api/dto';
import { tripsApi } from 'modules/fleet/api/fleetApi';
import type { TripResponse } from 'modules/fleet/api/dto';
import { CloseTripDialog, StartTripDialog } from 'modules/fleet/dialogs/tripDialogs';
import { useNotifier } from 'shared/components/Notifier';
import { fleetPaths } from 'shared/layout/navigation';

/**
 * A driver's day - the eight permissions `FLEET_DRIVER` actually holds, and nothing else.
 *
 * ## Why this screen is narrow on purpose
 *
 * The SRS gives the driver **no §2.3 user class**, and every `SRS-SFL-S168fuel-*` requirement is
 * written "As a Fleet or Logistics Officer". What exists is a role with eight permissions and a
 * logbook that somebody has to fill in for the anti-fraud control to have an input - so this is
 * built as the minimum surface those permissions imply and is recorded as a **Deviation** in
 * `docs/frontend/SFL_Role_Portal_Trace_Matrix.md`, with the Transportation & Logistics Unit named as
 * the owner who must confirm the user class.
 *
 * A driver holds `FLEET_VEHICLE_READ`. That is *not* a reason to show them a fleet dashboard; it is
 * what lets them see the vehicle they are driving. The register stays with the fleet office.
 *
 * ## "My logbooks" is true. "My transactions" is not.
 *
 * `FuelApplicationService.logbooks` narrows on `created_by` in SQL, and `logbook(id, actor)` refuses
 * a colleague's record by id - so the logbook list below genuinely is this driver's. Fuel
 * *transactions* are **not** narrowed per record: a driver holds `FUEL_TRANSACTION_READ` and the
 * service returns every transaction at the site. So that panel is labelled for what it is -
 * transactions recorded against vehicles, at this site - and does not claim to be personal. Saying
 * "my fuel" over a list containing a colleague's fill would be a lie the screen tells on the
 * service's behalf, and the gap is recorded in `docs/fuel/S168_Fuel_Gap_And_Conflict_Report.md`.
 *
 * ## Nothing here is computed in the browser
 *
 * Status, and whether a logbook is still open, come down the wire. A browser deciding for itself
 * what counts as outstanding would disagree with the review queue the manager sees.
 */
const DriverDayPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const site = defaultSite;
  const [completing, setCompleting] = useState<TripResponse | null>(null);
  const [starting, setStarting] = useState<TripResponse | null>(null);

  /*
    The driver's own trips, narrowed by the service and not by this screen.

    `TripQueryService.search` overrides the `driverId` filter with the actor's own for a driver-only
    actor, so this genuinely is "my assignments" - the same reason the logbook list can make that
    claim and the fuel list cannot.
  */
  const trips = useApiQuery(
    (signal) => tripsApi.search({ siteCode: site, size: 25 }, signal),
    [site],
  );

  const myTrips = trips.data?.content ?? [];
  /** What is still on you: assigned, under way, or paused. */
  const activeTrips = myTrips.filter(
    (trip) => trip.status === 'ASSIGNED' || trip.status === 'IN_PROGRESS' || trip.status === 'ON_HOLD',
  );
  const completedTrips = myTrips.filter((trip) => trip.status === 'COMPLETED');

  const logbooks = useApiQuery(
    (signal) => driverLogbooksApi.search({ siteCode: site, size: 25 }, signal),
    [site],
  );

  const transactions = useApiQuery(
    (signal) => fuelTransactionsApi.search({ siteCode: site, size: 10 }, signal),
    [site],
  );

  const open = (logbooks.data?.content ?? []).filter(
    (entry) => entry.status === 'DRAFT' || entry.status === 'RETURNED' || entry.status === 'RESUBMITTED',
  );

  const logbookColumns: TableColumn<DriverLogbook>[] = [
    { id: 'logbookNumber', header: 'Logbook', cell: ({ row }) => row.logbookNumber },
    { id: 'journeyDate', header: 'Journey', cell: ({ row }) => row.journeyDate },
    {
      id: 'route',
      header: 'Route',
      cell: ({ row }) => `${row.origin} → ${row.destination}`,
    },
    {
      id: 'status',
      header: 'Status',
      cell: ({ row }) => <StatusBadge value={row.status} />,
    },
  ];

  const tripColumns = (showAction: boolean): TableColumn<TripResponse>[] => [
    { id: 'tripNumber', header: 'Trip', cell: ({ row }) => row.tripNumber },
    { id: 'route', header: 'Route', cell: ({ row }) => `${row.origin} → ${row.destination}` },
    {
      id: 'when',
      header: 'Planned',
      cell: ({ row }) => row.plannedStart?.slice(0, 16).replace('T', ' ') ?? '-',
    },
    { id: 'status', header: 'Status', cell: ({ row }) => <StatusBadge value={row.status} /> },
    ...(showAction
      ? [
          {
            id: 'action',
            header: 'Action',
            align: 'right' as const,
            cell: ({ row }: { row: TripResponse }) => {
              // A trip still ASSIGNED is started, not closed; one still IN_PROGRESS is finished, not
              // started - offering the wrong one would be offering a transition the service refuses
              // from that state.
              if (row.status === 'ASSIGNED') {
                return (
                  <Button size="sm" variant="primary" onClick={() => setStarting(row)}>
                    Start trip
                  </Button>
                );
              }
              if (row.status === 'IN_PROGRESS') {
                return (
                  <Button size="sm" variant="primary" onClick={() => setCompleting(row)}>
                    Complete trip
                  </Button>
                );
              }
              return <span className="text-xs text-muted-foreground">On hold</span>;
            },
          },
        ]
      : []),
  ];

  const transactionColumns: TableColumn<FuelTransaction>[] = [
    { id: 'occurredAt', header: 'When', cell: ({ row }) => row.occurredAt?.slice(0, 16).replace('T', ' ') },
    { id: 'quantity', header: 'Quantity', cell: ({ row }) => `${row.quantity} ${row.quantityUnit}` },
    { id: 'station', header: 'Station', cell: ({ row }) => row.stationReference ?? '-' },
    { id: 'status', header: 'Status', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ];

  const allLogbooks = logbooks.data?.content ?? [];

  return (
    <>
      <PageHeading
        title="My driving day"
        subtitle="Your assignments, the logbooks you have open, and the fuel recorded at this site"
        crumbs={[{ label: 'My driving day' }]}
      />

      <Panel title="Your assignments">
        <LocalTable
          paramPrefix="assignments"
          columns={tripColumns(true)}
          rows={activeTrips}
          rowKey={(row) => row.id}
          loading={trips.loading}
          error={trips.error}
          onRetry={trips.refetch}
          caption="Trips assigned to you that are still on you"
          emptyTitle="No trip is waiting on you"
          emptyHint="Assignments appear here once a dispatcher gives you one."
          onRowClick={(row) => navigate(fleetPaths.tripDetail(row.id))}
        />
      </Panel>

      {completedTrips.length > 0 && (
        // Shown to the driver as their own record, and visible to the fleet office in the trip
        // register at the same moment - closing writes the trip, it does not queue anything.
        <Panel title="Completed">
          <LocalTable
            paramPrefix="completed"
            columns={tripColumns(false)}
            rows={completedTrips}
            rowKey={(row) => row.id}
            loading={false}
            caption="Trips you have completed"
            emptyTitle="No completed trips"
          />
        </Panel>
      )}

      <Panel title="Logbooks needing you">
        <LocalTable
          paramPrefix="logbooks-open"
          columns={logbookColumns}
          rows={open}
          rowKey={(row) => row.id}
          loading={logbooks.loading}
          error={logbooks.error}
          onRetry={logbooks.refetch}
          caption="Logbooks you started that are in draft or came back for correction"
          emptyTitle="No logbook is waiting on you"
          // Describes what is visible to *you*, never what exists. This list is narrowed to the
          // logbooks you created, so "there are none" would be a claim this screen cannot make.
          emptyHint="Nothing you have started is in draft or has come back for correction."
          onRowClick={(row) => navigate(`/fuel/logbooks/${row.id}`)}
        />
      </Panel>

      <Panel title="All my logbooks">
        <LocalTable
          paramPrefix="logbooks"
          columns={logbookColumns}
          rows={allLogbooks}
          rowKey={(row) => row.id}
          loading={logbooks.loading}
          error={logbooks.error}
          onRetry={logbooks.refetch}
          caption="Every logbook you have filed"
          emptyTitle="You have not filed a logbook yet"
          emptyHint="Logbooks you create appear here. This list shows only your own."
          onRowClick={(row) => navigate(`/fuel/logbooks/${row.id}`)}
        />
      </Panel>

      {/*
        This said "Not filtered to you… this is every fill recorded at {site}", which was accurate
        and is no longer: the fuel service now narrows transactions to the signed-in driver,
        server-side. The caption is replaced rather than deleted because a list that used to be
        site-wide and is now personal looks like missing data to whoever was reading it yesterday.
      */}
      <Panel title="My fuel" description={`Fills recorded against you at ${site}.`}>
        <LocalTable
          paramPrefix="fuel"
          columns={transactionColumns}
          rows={transactions.data?.content ?? []}
          rowKey={(row) => row.id}
          loading={transactions.loading}
          error={transactions.error}
          onRetry={transactions.refetch}
          caption="Fuel recorded against you"
          emptyTitle="No fuel recorded against you"
          emptyHint="Fills matched to you by the fuel provider feed appear here."
        />
      </Panel>
      {starting && (
        <StartTripDialog
          open
          trip={starting}
          onClose={() => setStarting(null)}
          onSaved={() => {
            trips.refetch();
            notifySuccess('Trip started.');
          }}
        />
      )}
      {completing && (
        <CloseTripDialog
          open
          trip={completing}
          onClose={() => setCompleting(null)}
          onSaved={() => {
            trips.refetch();
            /*
              The logbook is the point of completing the trip, not an afterthought: it is the
              record the fuel anti-fraud rules read. Saying so here, with the journey still in
              mind, is the difference between a logbook filed now and one reconstructed on Friday.
            */
            notifySuccess(
              'Trip completed.',
              'File the driver logbook for this journey while the details are fresh.',
            );
          }}
        />
      )}
    </>
  );
};

export default DriverDayPage;

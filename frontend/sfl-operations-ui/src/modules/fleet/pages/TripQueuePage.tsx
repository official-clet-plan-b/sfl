import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import { Button, PageSection } from '@rfdtech/components';
import { TripResponse, TripStatusValue } from 'modules/fleet/api/dto';
import { OPERATING_MODES, OperatingMode, humanise } from 'modules/fleet/api/enums';
import { tripsApi } from 'modules/fleet/api/fleetApi';
import FleetTable, {
  CellStack,
  FilterDropdown,
  FilterField,
  FleetColumn,
  useRegisterState,
} from 'modules/fleet/components/FleetTable';
import RegisterHeader from 'modules/fleet/components/RegisterHeader';
import StatusBadge from 'modules/fleet/components/StatusBadge';
import { DateTimeField } from 'modules/fleet/components/formFields';
import { useReferenceNames } from 'modules/fleet/components/useReferenceNames';
import { CreateTripDialog } from 'modules/fleet/dialogs/tripDialogs';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import { defaultSite } from 'shared/components/SiteSelect';
import { fromLocalInputValue } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';
import { canManageTrips } from '../api/access';

/**
 * The one-click views of the queue.
 *
 * Each is a trip status, so choosing one is the same request the status filter used to make. Ready
 * to dispatch is a trip that has its vehicle and driver; a trip still PLANNED has neither.
 */
const VIEWS: { value: string; label: string; status?: TripStatusValue }[] = [
  { value: 'all', label: 'All' },
  { value: 'planned', label: 'Planned', status: 'PLANNED' },
  { value: 'ready', label: 'Ready to dispatch', status: 'ASSIGNED' },
  { value: 'road', label: 'On the road', status: 'IN_PROGRESS' },
  { value: 'hold', label: 'On hold', status: 'ON_HOLD' },
];

const dateTimeCell = (iso: string) => (
  <CellStack primary={dayjs(iso).format('D MMM')} secondary={dayjs(iso).format('HH:mm')} />
);

/** The trip queue: plan, then work each trip through assignment, start and closure. */
const TripQueuePage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [createOpen, setCreateOpen] = useState(false);

  // The register's state lives in the URL, so a filtered, paged queue survives a reload and can be
  // linked. Every filter change returns to the first page - leaving the page index behind asks the
  // server for a page the narrowed result set no longer has, and the table comes back empty.
  const state = useRegisterState('trips');
  const { filters, setFilter } = state;
  const view = VIEWS.find((entry) => entry.value === filters.view) ?? VIEWS[0];
  const operatingMode = (filters.mode ?? '') as OperatingMode | '';
  const from = filters.from ?? '';
  const to = filters.to ?? '';
  const names = useReferenceNames(state.site);

  const query = useApiQuery(
    (signal) =>
      tripsApi.search(
        {
          siteCode: state.site || undefined,
          status: view.status,
          operatingMode: operatingMode || undefined,
          from: from ? fromLocalInputValue(from) : undefined,
          to: to ? fromLocalInputValue(to) : undefined,
          page: state.apiPage,
          size: state.pageSize,
        },
        signal,
      ),
    [state.site, view.value, operatingMode, from, to, state.apiPage, state.pageSize],
  );

  /** How many trips each view holds, so the tab says what is behind it before it is opened. */
  const counts = useApiQuery(
    async (signal) => {
      const totals = await Promise.all(
        VIEWS.map((entry) =>
          tripsApi
            .search({ siteCode: state.site || undefined, status: entry.status, size: 1 }, signal)
            .then((page) => [entry.value, page.totalElements] as const),
        ),
      );
      return Object.fromEntries(totals);
    },
    [state.site],
  );

  const columns = useMemo<FleetColumn<TripResponse>[]>(
    () => [
      {
        key: 'tripNumber',
        header: 'Trip',
        width: 200,
        cell: (row) => <CellStack primary={row.tripNumber} secondary={row.purpose} />,
      },
      {
        /*
          The route gets a column of its own.

          It was the secondary line under the trip number, sharing 200px with it, so on any realistic
          place name - "Accra HQ Motor Pool", "Regional Examination Centre" - it truncated to the
          point of being unreadable, and it was the first thing anyone scanning this register looks
          for. Origin over destination rather than side by side: two short lines survive a narrow
          column where one long one does not.
        */
        key: 'route',
        header: 'Route',
        width: 220,
        cell: (row) => (
          <CellStack
            primary={row.origin || 'Not set'}
            secondary={`to ${row.destination || 'not set'}`}
          />
        ),
      },
      {
        key: 'assignment',
        header: 'Vehicle and driver',
        width: 190,
        cell: (row) =>
          row.vehicleId && row.driverId ? (
            <CellStack
              primary={names.vehicles?.get(row.vehicleId)?.registrationNumber ?? 'Vehicle assigned'}
              secondary={names.drivers?.get(row.driverId)?.displayName ?? 'Driver assigned'}
            />
          ) : (
            <CellStack primary="Not assigned" secondary="Assign a vehicle and driver" />
          ),
      },
      {
        key: 'plannedStart',
        header: 'Departure',
        width: 110,
        cell: (row) => dateTimeCell(row.plannedStart),
      },
      {
        key: 'plannedEnd',
        header: 'Return due',
        width: 110,
        cell: (row) => dateTimeCell(row.plannedEnd),
      },
      {
        key: 'status',
        header: 'Status',
        width: 170,
        /*
          The driver's answer is a separate axis from status, shown beside it and not folded into it.
          A dispatcher's question the morning a vehicle is due out is "which of these has the driver
          not answered for", and an assigned trip and a confirmed one are both ASSIGNED.
        */
        cell: (row) => (
          <div className="flex min-w-0 flex-col items-start gap-1">
            <StatusBadge value={row.status} />
            {row.status === 'ASSIGNED' && (
              <StatusBadge
                value={row.acknowledgementState}
                label={
                  row.acknowledgementState === 'CONFIRMED'
                    ? 'Driver confirmed'
                    : row.acknowledgementState === 'DEFERRED'
                      ? 'Driver deferred'
                      : 'Awaiting reply'
                }
                tone={
                  row.acknowledgementState === 'CONFIRMED'
                    ? 'ready'
                    : row.acknowledgementState === 'DEFERRED'
                      ? 'blocked'
                      : 'caution'
                }
              />
            )}
          </div>
        ),
      },
    ],
    [names.vehicles, names.drivers],
  );

  return (
    <>
      <RegisterHeader
        title="Trips and assignments"
        siteCode={state.site}
        onSiteChange={state.setSite}
        actions={
          <>
            <Button
              variant="outline"
              aria-label="Refresh"
              title="Refresh"
              onClick={() => {
                query.refetch();
                counts.refetch();
                names.refetch();
              }}
            >
              <Icon name="refresh" size={14} aria-hidden="true" />
            </Button>
            {/* A driver reads the register and plans nothing. Planning is FLEET_TRIP_MANAGE. */}
            {canManageTrips() && (
              <Button variant="primary" onClick={() => setCreateOpen(true)}>
                <Icon name="plus" size={14} aria-hidden="true" />
                Plan a trip
              </Button>
            )}
          </>
        }
      />

      {/*
        Why the list is shorter than the site's, when it is. The server sends this on a narrowed
        list - a driver sees their own trips - and sends nothing on an unnarrowed one. Showing it is
        what stops a driver reading their own list as "the queue is nearly empty today", and what
        tells an unbound driver why theirs is empty rather than leaving them at a blank screen.
      */}
      <PageSection>
        <FleetTable
          paramPrefix="trips"
          rows={query.data?.content ?? []}
          columns={columns}
          getRowId={(row) => row.id}
          loading={query.loading}
          error={query.error}
          onRetry={query.refetch}
          onRowClick={(row) => navigate(fleetPaths.tripDetail(row.id))}
          totalElements={query.data?.totalElements ?? 0}
          pageSize={state.pageSize}
          caption="Trips"
          heading={{
            title: 'Trips',
            description:
              query.data?.scopeNotice ??
              'Plan a movement, assign a vehicle and driver, then start and close it against evidence.',
          }}
          tabs={VIEWS.map((entry) => ({
            value: entry.value,
            label: entry.label,
            count: counts.data?.[entry.value],
          }))}
          tab={view.value}
          onTabChange={(value) => setFilter('view', value === 'all' ? '' : value)}
          filters={
            <>
              <FilterDropdown
                name="mode"
                label="Operating mode"
                value={operatingMode}
                onChange={(value) => setFilter('mode', value)}
                options={OPERATING_MODES.map((value) => ({ value, label: humanise(value) }))}
              />
              <FilterField name="from" value={from}>
                <DateTimeField
                  label="From"
                  value={from}
                  onChange={(value) => setFilter('from', value)}
                />
              </FilterField>
              <FilterField name="to" value={to}>
                <DateTimeField label="To" value={to} onChange={(value) => setFilter('to', value)} />
              </FilterField>
            </>
          }
          emptyTitle="No trips match these filters"
          emptyDescription="Plan a trip to get a movement into the queue."
        />
      </PageSection>

      {/* Mounted only while open, so the dialog picks up the current site filter as its default
          and cannot reopen holding a half-typed trip from a previous attempt. */}
      {createOpen && (
        <CreateTripDialog
          open
          defaultSiteCode={state.site || defaultSite}
          onClose={() => setCreateOpen(false)}
          onSaved={() => {
            notifySuccess('Trip created.');
            query.refetch();
            counts.refetch();
          }}
        />
      )}
    </>
  );
};

export default TripQueuePage;

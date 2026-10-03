import { useState } from 'react';
import { useNavigate } from 'react-router';
import { CalendarSearch } from 'lucide-react';
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
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths, bookingPaths } from 'shared/layout/navigation';
import { humaniseCode, orDash } from 'modules/facilities/components/facilitiesFormat';
import StatusBadge from 'modules/facilities/components/StatusBadge';
import { bookingsApi } from '../api/bookingApi';
import type { Booking } from '../api/dto';
import { BOOKING_PURPOSES, BOOKING_STATUSES, HOLD_REASON_DESCRIPTIONS } from '../api/enums';
import type { BookingPurpose, BookingStatus } from '../api/enums';
import { canRequest } from '../api/workflow';
import CellStack from '../components/CellStack';
import { bookingStatusTone, formatWindow } from '../components/bookingFormat';

/**
 * The diary - SRS-SFL-S159-01.
 *
 * The register S159 has had an API for since it shipped and no screen at all.
 *
 * **A requester sees a shorter list, and this screen does nothing to make that happen.**
 * `BookingApplicationService.requesterFilter` narrows per record, on reads and on writes, so the
 * register arrives already narrowed. There is deliberately no client-side filter: one would be a
 * display convention, and the rows would still have crossed the boundary.
 *
 * **The readiness hold is a column, not a status.** S159 decided that deliberately - a confirmed
 * booking on a hall blocked on Tuesday is still a confirmed booking somebody has in their diary, and
 * moving it to an `AT_RISK` state would decide on the estate's behalf that Tuesday's leak will still
 * be there on Friday. So the status says what the booking is and the hold says what the estate
 * currently thinks of it, side by side.
 */
/**
 * Status, purpose and view live in the URL, as the table keeps every filter. An absent `show` means
 * "All bookings", so a link to the diary opens on everything the requester can see.
 */
const listOf = (value: string | null | undefined): string[] => (value ? value.split(',') : []);

const BookingDiaryPage = () => {
  const navigate = useNavigate();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Booking diary' }]);

  const { filters } = useTableState({ paramPrefix: 'bookings' });
  const status = listOf(filters.status);
  const purpose = listOf(filters.purpose);
  const scope = filters.show ?? '';
  // Primitive keys for the query, because `status` is a new array on every render.
  const statusKey = status.join(',');
  const purposeKey = purpose.join(',');

  // The fields hold the operator's choice until the filter is applied; the URL holds what applied.
  const [statusValue, setStatusValue] = useState<string[]>(status);
  const [purposeValue, setPurposeValue] = useState<string[]>(purpose);
  const [scopeValue, setScopeValue] = useState(scope);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);

  const bookings = useApiQuery(
    (signal) =>
      bookingsApi.search(
        {
          siteCode: siteCode || undefined,
          /*
            One value goes to the service; the rest are applied to the returned set below.

            `BookingQuery` takes a single status and a single purpose, so a multi-select cannot be
            pushed down whole. Sending the first narrows the fetch - which matters, the register is
            capped at 200 - and the remainder is filtered here. That is a compromise and it is worth
            naming: with more than one value selected the cap applies to a *wider* set than the
            filter shows, so a very large site could clip. Widening the query to accept a list is the
            real fix and belongs in the service.
          */
          status: (status[0] as BookingStatus) || undefined,
          purpose: (purpose[0] as BookingPurpose) || undefined,
          liveOnly: scope === 'live' ? true : undefined,
          onReadinessHold: scope === 'held' ? true : undefined,
          limit: 200,
        },
        signal,
      ),
    [siteCode, statusKey, purposeKey, scope],
  );

  const fetched = bookings.data?.items ?? [];
  const rows = fetched.filter(
    (booking) =>
      (status.length === 0 || status.includes(booking.status)) &&
      (purpose.length === 0 || purpose.includes(booking.purpose)),
  );

  /*
    Counts come from what the service returned, so they describe the data in hand rather than the
    whole register. A count that claimed to be the site total would be a promise this screen cannot
    keep - the fetch is capped and a requester's view is narrowed per record.
  */
  const countBy = (pick: (booking: Booking) => string) =>
    fetched.reduce<Record<string, number>>((tally, booking) => {
      const key = pick(booking);
      tally[key] = (tally[key] ?? 0) + 1;
      return tally;
    }, {});
  const statusCounts = countBy((booking) => booking.status);
  const purposeCounts = countBy((booking) => booking.purpose);

  const columns: TableColumn<Booking>[] = [
    {
      id: 'title',
      header: 'Booking',
      width: 260,
      cell: ({ row: booking }) => (
        <CellStack primary={booking.title} secondary={booking.bookingReference} />
      ),
    },
    {
      id: 'room',
      header: 'Space',
      width: 130,
      cell: ({ row: booking }) => orDash(booking.roomCode),
    },
    {
      id: 'window',
      header: 'When',
      width: 200,
      cell: ({ row: booking }) => (
        <CellStack
          primary={formatWindow(booking.startsAt, booking.endsAt)}
          // The occupied window, not the booked one - it is what the next requester is refused on.
          secondary={
            booking.setupMinutes > 0 || booking.teardownMinutes > 0
              ? `Holds ${formatWindow(booking.occupiedFrom, booking.occupiedTo)}`
              : undefined
          }
        />
      ),
    },
    {
      id: 'purpose',
      header: 'Purpose',
      cell: ({ row: booking }) => humaniseCode(booking.purpose),
    },
    { id: 'requestedBy', header: 'Requested by', accessorKey: 'requestedBy' },
    {
      id: 'status',
      header: 'Status',
      width: 130,
      cell: ({ row: booking }) => (
        <StatusBadge value={booking.status} tone={bookingStatusTone(booking.status)} />
      ),
    },
    {
      id: 'hold',
      header: 'Readiness',
      width: 130,
      align: 'right',
      cell: ({ row: booking }) =>
        booking.readinessHoldReason ? (
          <span title={HOLD_REASON_DESCRIPTIONS[booking.readinessHoldReason]}>
            <StatusBadge value="ON_HOLD" label="On hold" tone="blocked" />
          </span>
        ) : (
          <span className="text-muted-foreground">-</span>
        ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Booking diary</SectionTitle>
          <SectionDescription>
            Rooms and resources booked at this site, and what the estate currently thinks of each
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
            {canRequest().kind === 'allowed' && (
              <Button variant="primary" onClick={() => navigate(bookingPaths.availability)}>
                <CalendarSearch size={14} strokeWidth={1.5} aria-hidden="true" />
                Find a space
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={bookings.error} onRetry={bookings.refetch}>
          <Table paramPrefix="bookings" variant="soft">
            <Card bordered>
              <TableHeader>
                <TableFilter>
                  <Dropdown
                    name="show"
                    aria-label="Which bookings"
                    placeholder="All bookings"
                    value={scopeValue || null}
                    onValueChange={(next) => setScopeValue(next ?? '')}
                    clearable
                    options={[
                      { value: 'live', label: 'Holding a space' },
                      { value: 'held', label: 'On readiness hold' },
                    ]}
                  />
                  <Combobox
                    multiple
                    name="status"
                    aria-label="Status"
                    placeholder="Status"
                    value={statusValue}
                    onValueChange={setStatusValue}
                    options={BOOKING_STATUSES.map((value) => ({
                      value,
                      label: `${humaniseCode(value)} (${statusCounts[value] ?? 0})`,
                    }))}
                  />
                  <Combobox
                    multiple
                    name="purpose"
                    aria-label="Purpose"
                    placeholder="Purpose"
                    value={purposeValue}
                    onValueChange={setPurposeValue}
                    options={BOOKING_PURPOSES.map((value) => ({
                      value,
                      label: `${humaniseCode(value)} (${purposeCounts[value] ?? 0})`,
                    }))}
                  />
                </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={rows}
                rowKey={(booking) => booking.id}
                loading={bookings.loading}
                onRowClick={(booking) => navigate(bookingPaths.bookingDetail(booking.id))}
                aria-label="Bookings"
                emptyContent={
                  <EmptyState
                    title="No bookings match"
                    /*
                      Describes what is visible to *you*. A requester sees only the bookings they
                      raised, so "this site has no bookings" is a claim this screen is not in a
                      position to make.
                    */
                    description="Nothing visible to you matches these filters."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>
    </>
  );
};

export default BookingDiaryPage;

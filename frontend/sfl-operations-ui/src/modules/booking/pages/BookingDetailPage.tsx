import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { CalendarClock, CalendarDays, CheckCircle2, Lock } from 'lucide-react';
import {
  Button,
  Card,
  EmptyState,
  MetricCard,
  MetricCards,
  Notice,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { bookingPaths, facilitiesPaths } from 'shared/layout/navigation';
import {
  formatDateTime,
  humaniseCode,
  orDash,
  relativeTime,
} from 'modules/facilities/components/facilitiesFormat';
import { bookingsApi } from '../api/bookingApi';
import type { BookingAllocation, SetupTask } from '../api/dto';
import { HOLD_REASON_DESCRIPTIONS } from '../api/enums';
import {
  canCancel,
  canComplete,
  canDecide,
  canReschedule,
  canStart,
  isOwnBooking,
} from '../api/workflow';
import ControlButton from '../components/ControlButton';
import {
  bookingStatusTone,
  bufferSummary,
  formatWindow,
  setupTaskTone,
} from '../components/bookingFormat';
import CancelBookingDialog from '../dialogs/CancelBookingDialog';
import CompleteBookingDialog from '../dialogs/CompleteBookingDialog';
import DecideBookingDialog from '../dialogs/DecideBookingDialog';
import RescheduleBookingDialog from '../dialogs/RescheduleBookingDialog';
import StatusBadge from 'modules/facilities/components/StatusBadge';

type OpenDialog = 'decide' | 'reschedule' | 'cancel' | 'complete' | null;

/**
 * One booking, and everything that decides what happens to it.
 *
 * Four things on one screen, because somebody looking at a booking is deciding what to do about it:
 * where it stands, what the estate currently thinks of the space, what it is holding, and what has to
 * happen to the room before it starts.
 *
 * ## The readiness notice is the point of the screen
 *
 * A confirmed booking on a hall blocked on Tuesday is still confirmed and still in somebody's diary.
 * S159 marks it rather than cancelling it, deliberately - moving it to an `AT_RISK` state would
 * decide on the estate's behalf that Tuesday's leak will still be there on Friday. So the notice says
 * what is wrong and leaves the decision to a person, which is what the flag is for.
 *
 * ## Start is not ceremony
 *
 * Marking a booking in use is what stops the no-show sweep releasing the space. That is worth saying
 * on the button's own page, because "we were in the room, why did it release?" is otherwise a
 * reasonable question with an invisible answer.
 */
const BookingDetailPage = () => {
  const { bookingId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [dialog, setDialog] = useState<OpenDialog>(null);

  const booking = useApiQuery((signal) => bookingsApi.findById(bookingId, signal), [bookingId]);
  const approvals = useApiQuery((signal) => bookingsApi.approvals(bookingId, signal), [bookingId]);
  const allocations = useApiQuery(
    (signal) => bookingsApi.allocations(bookingId, signal),
    [bookingId],
  );
  const setupTasks = useApiQuery((signal) => bookingsApi.setupTasks(bookingId, signal), [bookingId]);

  const refreshAll = () => {
    booking.refetch();
    approvals.refetch();
    allocations.refetch();
    setupTasks.refetch();
  };

  const record = booking.data;

  const start = async () => {
    if (!record) {
      return;
    }
    try {
      await bookingsApi.start(bookingId, { expectedVersion: record.metadata.version });
      notify.notifySuccess('Marked in use. The no-show sweep will leave it alone.');
      refreshAll();
    } catch (cause) {
      notify.notifyError(cause);
    }
  };

  const allocationColumns: TableColumn<BookingAllocation>[] = [
    { id: 'resource', header: 'Resource', cell: ({ row }) => orDash(row.resourceCode) },
    { id: 'quantity', header: 'Quantity', align: 'right', width: 100, accessorKey: 'quantity' },
    {
      id: 'exclusive',
      header: 'Exclusive',
      width: 120,
      // Worth its own column: an exclusive resource is refused by the database rather than by
      // arithmetic, which is a materially stronger guarantee than "there are three of them".
      cell: ({ row }) => (row.exclusive ? <StatusBadge value="EXCLUSIVE" tone="accent" /> : '-'),
    },
    {
      id: 'released',
      header: 'Standing',
      width: 130,
      cell: ({ row }) =>
        row.released ? (
          <StatusBadge value="RELEASED" tone="neutral" />
        ) : (
          <StatusBadge value="HELD" tone="active" />
        ),
    },
    {
      id: 'allocatedAt',
      header: 'Allocated',
      cell: ({ row }) => `${relativeTime(row.allocatedAt)} by ${row.allocatedBy}`,
    },
  ];

  const setupColumns: TableColumn<SetupTask>[] = [
    { id: 'description', header: 'Task', accessorKey: 'description' },
    {
      id: 'dueBy',
      header: 'Needed by',
      width: 180,
      cell: ({ row }) => (
        <span className={row.overdue ? 'font-medium text-error' : undefined}>
          {row.dueBy ? formatDateTime(row.dueBy) : '-'}
        </span>
      ),
    },
    { id: 'assignedTo', header: 'Assigned', cell: ({ row }) => orDash(row.assignedTo) },
    {
      id: 'status',
      header: 'Status',
      width: 120,
      cell: ({ row }) => <StatusBadge value={row.status} tone={setupTaskTone(row.status)} />,
    },
  ];

  useBreadcrumbs([
    { label: 'Bookings', href: bookingPaths.diary },
    { label: record?.bookingReference ?? 'Booking' },
  ]);

  return (
    <>
      <DataState
        loading={booking.loading}
        error={booking.error}
        onRetry={booking.refetch}
        minHeight={280}
      >
        {record && (
          <>
            <PageSection>
              <SectionHeader>
                <SectionTitle>{record.title}</SectionTitle>
                <SectionDescription>
                  {`${record.bookingReference} · ${orDash(record.roomCode)} · ${record.siteCode}`}
                </SectionDescription>
                <SectionActions className="items-end [&_button]:whitespace-nowrap">
                  <ControlButton state={canDecide(record)} variant="primary" onClick={() => setDialog('decide')}>
                    Decide
                  </ControlButton>
                  <ControlButton state={canStart(record)} variant="outline" onClick={start}>
                    Mark in use
                  </ControlButton>
                  <ControlButton
                    state={canComplete(record)}
                    variant="outline"
                    onClick={() => setDialog('complete')}
                  >
                    Complete
                  </ControlButton>
                  <ControlButton
                    state={canReschedule(record)}
                    variant="outline"
                    onClick={() => setDialog('reschedule')}
                  >
                    Move
                  </ControlButton>
                  <ControlButton
                    state={canCancel(record)}
                    variant="outline"
                    onClick={() => setDialog('cancel')}
                  >
                    {isOwnBooking(record) ? 'Withdraw' : 'Cancel'}
                  </ControlButton>
                </SectionActions>
              </SectionHeader>
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge value={record.status} tone={bookingStatusTone(record.status)} size="md" />
                {/* Beside the status, never instead of it - the two say different things. */}
                {record.readinessHoldReason && (
                  <StatusBadge value="ON_HOLD" label="Readiness hold" tone="blocked" size="md" />
                )}
                {record.overridden && (
                  <StatusBadge value="OVERRIDDEN" label="Overridden" tone="accent" size="md" />
                )}
              </div>
            </PageSection>

            {record.readinessHoldReason && (
              <PageSection>
                <Notice variant="warning" title="The estate has a problem with this space">
                  <p className="text-sm">
                    {HOLD_REASON_DESCRIPTIONS[record.readinessHoldReason]} Held since{' '}
                    {formatDateTime(record.readinessHeldAt)}.
                  </p>
                  <p className="mt-2 text-sm">
                    The booking is still {humaniseCode(record.status).toLowerCase()} and still in
                    everybody&rsquo;s diary - the hold marks it rather than cancelling it, because
                    whether the space is fixed by then is a judgement for a person. Move it or cancel
                    it if it cannot go ahead.
                  </p>
                </Notice>
              </PageSection>
            )}

            {record.overridden && (
              <PageSection>
                <Notice variant="warning" title="Booked into a space readiness refused">
                  <p className="text-sm">{record.overrideReason}</p>
                </Notice>
              </PageSection>
            )}

            {record.status === 'NO_SHOW' && (
              <PageSection>
                <Notice variant="error" title="Nobody turned up">
                  <p className="text-sm">
                    The window closed with no attendance recorded, so the sweep released the space and
                    everything it was holding. Marking a booking in use when it starts is what
                    prevents this.
                  </p>
                </Notice>
              </PageSection>
            )}

            <PageSection>
              <MetricCards>
                <MetricCard
                  variant="soft"
                  label="Status"
                  value={humaniseCode(record.status)}
                  description={
                    record.holdsTheSpace ? 'Currently holding the space' : 'Not holding the space'
                  }
                  descriptionAdornment={
                    <CalendarDays
                      size={16}
                      strokeWidth={2}
                      className={
                        record.status === 'NO_SHOW'
                          ? 'text-error'
                          : record.holdsTheSpace
                            ? 'text-success-text'
                            : undefined
                      }
                      aria-hidden
                    />
                  }
                />
                <MetricCard
                  variant="soft"
                  label="Booked window"
                  value={formatWindow(record.startsAt, record.endsAt)}
                  description={bufferSummary(record) ?? 'No setup or teardown buffer'}
                  descriptionAdornment={<CalendarClock size={16} strokeWidth={2} aria-hidden />}
                />
                <MetricCard
                  variant="soft"
                  label="Occupied window"
                  value={formatWindow(record.occupiedFrom, record.occupiedTo)}
                  // The one figure that surprises people. This, not the booked window, is what the
                  // exclusion constraint tests and what the next requester is refused on.
                  description="What the next requester is refused on"
                  descriptionAdornment={<Lock size={16} strokeWidth={2} aria-hidden />}
                />
                <MetricCard
                  variant="soft"
                  label="Approval"
                  value={
                    record.approvalRequired
                      ? record.approvalId
                        ? 'Decided'
                        : 'Awaiting a decision'
                      : 'Not needed'
                  }
                  description={
                    record.approvalRequired
                      ? record.confirmedAt
                        ? `Confirmed ${relativeTime(record.confirmedAt)}`
                        : 'The space is held until it is decided'
                      : 'Confirmed on request by this site’s configuration'
                  }
                  descriptionAdornment={
                    <CheckCircle2
                      size={16}
                      strokeWidth={2}
                      className={
                        record.approvalRequired &&
                        !record.approvalId &&
                        record.status === 'REQUESTED'
                          ? 'text-warning-text'
                          : undefined
                      }
                      aria-hidden
                    />
                  }
                />
              </MetricCards>
            </PageSection>

            {record.description && (
              <PageSection>
                <SectionHeader>
                  <SectionTitle>Notes</SectionTitle>
                </SectionHeader>
                <Card bordered>
                  <p className="whitespace-pre-line text-sm text-foreground">{record.description}</p>
                </Card>
              </PageSection>
            )}

            {record.closureReason && (
              <PageSection>
                <SectionHeader>
                  <SectionTitle>
                    {record.status === 'COMPLETED'
                      ? 'How it finished'
                      : record.status === 'REJECTED'
                        ? 'Why it was refused'
                        : 'Why it was withdrawn'}
                  </SectionTitle>
                  <SectionDescription>
                    {formatDateTime(record.completedAt ?? record.metadata.lastModifiedAt)}
                  </SectionDescription>
                </SectionHeader>
                <Card bordered>
                  <p className="whitespace-pre-line text-sm text-foreground">{record.closureReason}</p>
                </Card>
              </PageSection>
            )}

            <PageSection>
              <SectionHeader>
                <SectionTitle>The booking</SectionTitle>
              </SectionHeader>
              <Card bordered>
                <KeyValueGrid
                  items={[
                    { label: 'Reference', value: record.bookingReference },
                    { label: 'Purpose', value: humaniseCode(record.purpose) },
                    {
                      label: 'Space',
                      value: (
                        <Button
                          variant="ghost"
                          onClick={() => navigate(facilitiesPaths.spaceDetail(record.roomId))}
                        >
                          {orDash(record.roomCode)}
                        </Button>
                      ),
                    },
                    { label: 'Site', value: record.siteCode },
                    { label: 'Expected attendees', value: String(record.expectedAttendees) },
                    { label: 'Requested by', value: record.requestedBy },
                    { label: 'On behalf of', value: orDash(record.requestedFor) },
                    { label: 'Requested', value: formatDateTime(record.requestedAt) },
                    { label: 'Started', value: record.startedAt ? formatDateTime(record.startedAt) : '-' },
                    {
                      label: 'Completed',
                      value: record.completedAt ? formatDateTime(record.completedAt) : '-',
                    },
                    { label: 'Lifecycle', value: humaniseCode(record.lifecycleStatus) },
                    { label: 'Version', value: String(record.metadata.version) },
                  ]}
                />
              </Card>
            </PageSection>

            <PageSection>
              <SectionHeader>
                <SectionTitle>Approval</SectionTitle>
                <SectionDescription>
                  {record.approvalRequired
                    ? 'Decisions taken on this request'
                    : 'This booking needed no approval, so there is nothing to show'}
                </SectionDescription>
              </SectionHeader>
              <Card bordered>
                <DataState
                  loading={approvals.loading}
                  error={approvals.error}
                  empty={(approvals.data ?? []).length === 0}
                  emptyTitle={record.approvalRequired ? 'Not yet decided' : 'No approval was needed'}
                  /*
                    The absence of an approval record is itself the statement that none was needed -
                    there is no separate flag that could fall out of step with it.
                  */
                  emptyHint={
                    record.approvalRequired
                      ? 'The space is held until somebody decides.'
                      : 'This site’s configuration confirms this purpose on request.'
                  }
                  minHeight={140}
                  onRetry={approvals.refetch}
                >
                  <ul className="divide-y divide-border">
                    {(approvals.data ?? []).map((approval) => (
                      <li key={approval.id} className="py-3 first:pt-0 last:pb-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <StatusBadge
                            value={approval.decision}
                            tone={approval.decision === 'APPROVED' ? 'ready' : 'blocked'}
                          />
                          <span className="text-sm text-foreground">
                            {approval.decidedBy} · {formatDateTime(approval.decidedAt)}
                          </span>
                        </div>
                        {approval.reason && (
                          <p className="mt-2 text-sm text-foreground">{approval.reason}</p>
                        )}
                      </li>
                    ))}
                  </ul>
                </DataState>
              </Card>
            </PageSection>

            <PageSection>
              <SectionHeader>
                <SectionTitle>Resources it holds</SectionTitle>
              </SectionHeader>
              <DataState loading={false} error={allocations.error} onRetry={allocations.refetch}>
                <Table paramPrefix="allocations" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={allocationColumns}
                      data={allocations.data ?? []}
                      rowKey={(row) => row.id}
                      loading={allocations.loading}
                      aria-label="Resources allocated to this booking"
                      emptyContent={
                        <EmptyState
                          title="No resources allocated"
                          description="This booking takes the room and nothing else."
                        />
                      }
                    />
                  </Card>
                </Table>
              </DataState>
            </PageSection>

            <PageSection>
              <SectionHeader>
                <SectionTitle>Room turnaround</SectionTitle>
                <SectionDescription>
                  Raised automatically for every resource that needs setting up
                </SectionDescription>
              </SectionHeader>
              <DataState loading={false} error={setupTasks.error} onRetry={setupTasks.refetch}>
                <Table paramPrefix="setup-tasks" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={setupColumns}
                      data={setupTasks.data ?? []}
                      rowKey={(row) => row.id}
                      loading={setupTasks.loading}
                      onRowClick={() => navigate(bookingPaths.setupTasks)}
                      aria-label="Setup tasks for this booking"
                      emptyContent={
                        <EmptyState
                          title="Nothing to set up"
                          description="No resource on this booking declares that it needs setting up."
                        />
                      }
                    />
                  </Card>
                </Table>
              </DataState>
            </PageSection>
          </>
        )}
      </DataState>

      {dialog === 'decide' && record && (
        <DecideBookingDialog
          booking={record}
          onClose={() => setDialog(null)}
          onSubmit={async (body) => {
            const decided = await bookingsApi.decide(bookingId, body);
            setDialog(null);
            notify.notifySuccess(
              decided.status === 'CONFIRMED'
                ? 'Approved and confirmed.'
                : 'Rejected. The space and its resources are released.',
            );
            refreshAll();
          }}
        />
      )}

      {dialog === 'reschedule' && record && (
        <RescheduleBookingDialog
          booking={record}
          onClose={() => setDialog(null)}
          onSubmit={async (body) => {
            await bookingsApi.reschedule(bookingId, body);
            setDialog(null);
            notify.notifySuccess('Moved. Its resources moved with it.');
            refreshAll();
          }}
        />
      )}

      {dialog === 'complete' && record && (
        <CompleteBookingDialog
          booking={record}
          onClose={() => setDialog(null)}
          onSubmit={async (body) => {
            await bookingsApi.complete(bookingId, body);
            setDialog(null);
            notify.notifySuccess('Completed. Everything it was holding is released.');
            refreshAll();
          }}
        />
      )}

      {dialog === 'cancel' && record && (
        <CancelBookingDialog
          booking={record}
          onClose={() => setDialog(null)}
          onSubmit={async (body) => {
            await bookingsApi.cancel(bookingId, body);
            setDialog(null);
            notify.notifySuccess('Cancelled. The space is free again.');
            refreshAll();
          }}
        />
      )}
    </>
  );
};

export default BookingDetailPage;

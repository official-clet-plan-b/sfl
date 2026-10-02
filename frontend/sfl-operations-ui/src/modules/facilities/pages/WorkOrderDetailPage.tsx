import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Banner, Button, MetricCard } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import DataTable, { FacilitiesColumn as Column } from '../components/FacilitiesDataTable';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import PageHeading from 'modules/emergency/components/PageHeading';
import TitledSection from '../components/TitledSection';
import StatusBadge from '../components/StatusBadge';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { MaintenanceEvidence, WorkOrderPart } from '../api/dto';
import {
  assignWorkOrder,
  attachEvidence,
  cancelWorkOrder,
  closeWorkOrder,
  completeWorkOrder,
  getWorkOrder,
  holdWorkOrder,
  listEvidence,
  listParts,
  recordPart,
  removePart,
  reopenWorkOrder,
  startWorkOrder,
} from '../api/facilitiesApi';
import {
  attachEvidenceAction,
  canCloseWorkOrders,
  canTransitionTo,
  cancelAction,
  closeAction,
  completeAction,
  holdAction,
  reopenAction,
  startAction,
} from '../api/workflow';
import AssignWorkOrderDialog from '../dialogs/AssignWorkOrderDialog';
import AttachEvidenceDialog from '../dialogs/AttachEvidenceDialog';
import CloseWorkOrderDialog from '../dialogs/CloseWorkOrderDialog';
import RecordPartDialog from '../dialogs/RecordPartDialog';
import TransitionNoteDialog from '../dialogs/TransitionNoteDialog';
import {
  evidenceGap,
  formatDateTime,
  heldFor,
  humaniseCode,
  orDash,
  overdueBy,
  relativeTime,
} from '../components/facilitiesFormat';

type Pending = 'assign' | 'hold' | 'reopen' | 'close' | 'cancel' | 'part' | 'evidence' | null;

/**
 * One work order, and everything needed to move it.
 *
 * ## The closure gap, stated as a number
 *
 * SRS-SFL-S153-02 refuses closure without the evidence the order required *when it was raised*. That
 * count is stored on the order, not recomputed, so an assignee is held to the rule that applied to
 * their job rather than one changed while they were working. The screen shows the shortfall as
 * "1 of 2 required" and disables close with the same sentence the service would answer with - two
 * different wordings for one rule is how a user learns to distrust both.
 *
 * ## Time on hold sits beside the deadline, never inside it
 *
 * `totalHeldSeconds` is reported next to the SLA rather than subtracted from it, because the service
 * does not subtract it either: a hall is no less unusable because the reason is a supplier. Showing
 * an adjusted deadline would put the screen and the escalation sweep into disagreement about what is
 * late.
 *
 * ## Only legal transitions are offered
 *
 * The buttons come from `WorkOrderStatus`'s own transition table by way of `workflow.ts`, not from a
 * remembered sequence. Reassignment is `ASSIGNED → ASSIGNED`, and closure is reachable from any
 * working state - neither is obvious, and hard-coding an order would get both wrong.
 */
const WorkOrderDetailPage = () => {
  const { workOrderId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [pending, setPending] = useState<Pending>(null);

  const order = useApiQuery((signal) => getWorkOrder(workOrderId, signal), [workOrderId]);
  const parts = useApiQuery((signal) => listParts(workOrderId, signal), [workOrderId]);
  const evidence = useApiQuery((signal) => listEvidence(workOrderId, signal), [workOrderId]);

  const refresh = () => {
    order.refetch();
    parts.refetch();
    evidence.refetch();
  };

  /** Only what counts towards closure - an invoice proves spend, not that the work was done. */
  const closureEvidence = (evidence.data ?? []).filter((item) => item.supportsClosure).length;

  const run = async (work: () => Promise<unknown>, success: string) => {
    try {
      await work();
      setPending(null);
      notify.notifySuccess(success);
      refresh();
    } catch (cause) {
      notify.notifyError(cause);
    }
  };

  const partColumns: Column<WorkOrderPart>[] = [
    {
      key: 'partCode',
      header: 'Part',
      width: 150,
      cell: (part) => <span className="font-medium text-gray-900">{part.partCode}</span>,
    },
    { key: 'description', header: 'Description', cell: (part) => part.description },
    { key: 'quantity', header: 'Qty', width: 80, cell: (part) => String(part.quantity) },
    {
      key: 'lineCost',
      header: 'Cost',
      width: 130,
      cell: (part) =>
        part.lineCost === null ? (
          <span className="text-theme-xs text-gray-500">Not recorded</span>
        ) : (
          `${part.currency} ${part.lineCost.toFixed(2)}`
        ),
    },
    {
      key: 'recordedBy',
      header: 'By',
      width: 140,
      hideBelowLg: true,
      cell: (part) => part.recordedBy,
    },
  ];

  const evidenceColumns: Column<MaintenanceEvidence>[] = [
    {
      key: 'evidenceType',
      header: 'Evidence',
      width: 170,
      cell: (item) => (
        <div className="flex flex-col gap-0.5">
          <span className="font-medium text-gray-900">{humaniseCode(item.evidenceType)}</span>
          {!item.supportsClosure && (
            <span className="text-theme-xs text-gray-500">Does not count towards closure</span>
          )}
        </div>
      ),
    },
    { key: 'fileName', header: 'File', cell: (item) => orDash(item.fileName) },
    {
      key: 'retentionClass',
      header: 'Retention',
      width: 160,
      cell: (item) => (
        <div className="flex flex-col gap-0.5">
          <StatusBadge value={humaniseCode(item.retentionClass)} tone="neutral" />
          {item.legalHold && (
            <span className="text-theme-xs font-medium text-warning-600">Legal hold</span>
          )}
        </div>
      ),
    },
    {
      key: 'uploadedBy',
      header: 'Attached',
      width: 190,
      cell: (item) => `${item.uploadedBy} · ${relativeTime(item.uploadedAt)}`,
    },
  ];

  return (
    <>
      <DataState loading={order.loading} error={order.error} onRetry={order.refetch} minHeight={280}>
        {order.data && (
          <>
            <PageHeading
              title={order.data.title}
              subtitle={`${order.data.workOrderNumber} · ${humaniseCode(order.data.workOrderType)} · ${orDash(order.data.locationCode)}`}
              crumbs={[
                { label: 'Facilities', to: facilitiesPaths.dashboard },
                { label: 'Work orders', to: facilitiesPaths.workOrders },
                { label: order.data.workOrderNumber },
              ]}
              actions={
                <div className="flex flex-wrap gap-2">
                  {(() => {
                    const action = startAction(order.data!);
                    return action.allowed ? (
                      <Button
                        variant="primary"
                        onClick={() => run(() => startWorkOrder(workOrderId, {}), 'Work started.')}
                      >
                        Start
                      </Button>
                    ) : null;
                  })()}
                  {holdAction(order.data).allowed && (
                    <Button variant="outline" onClick={() => setPending('hold')}>
                      Put on hold
                    </Button>
                  )}
                  {completeAction(order.data).allowed && (
                    <Button variant="outline" onClick={() => setPending('close')}>
                      Complete
                    </Button>
                  )}
                  {reopenAction(order.data).allowed && (
                    <Button variant="outline" onClick={() => setPending('reopen')}>
                      Reopen
                    </Button>
                  )}
                  {(() => {
                    // Two different reasons a close button might not be pressable, and they are
                    // shown differently on purpose. No permission - a technician never has it -
                    // renders nothing, because a dead button on every job reads as a broken screen.
                    // Evidence short renders it disabled with the count, because that is something
                    // the person looking at it can go and fix.
                    if (!canCloseWorkOrders() || !canTransitionTo(order.data!.status, 'CLOSED')) {
                      return null;
                    }
                    const action = closeAction(order.data!, closureEvidence);
                    return (
                      <Button
                        variant="primary"
                        disabled={!action.allowed}
                        title={action.reason}
                        onClick={() => setPending('close')}
                      >
                        Close
                      </Button>
                    );
                  })()}
                  {cancelAction(order.data).allowed && (
                    <Button variant="outline" onClick={() => setPending('cancel')}>
                      Cancel
                    </Button>
                  )}
                </div>
              }
            />

            <div className="space-y-5">
              {order.data.overdue && (
                <Banner variant="danger" heading={`Past its SLA - ${overdueBy(order.data.minutesOverdue)}`} subtext={<>The deadline was {formatDateTime(order.data.slaDueAt)}.
                  {order.data.escalationLevel > 0
                    ? ` Escalated to level ${order.data.escalationLevel}.`
                    : ' The next scheduled sweep will escalate it.'}
                  {order.data.totalHeldSeconds > 0 &&
                    ` ${heldFor(order.data.totalHeldSeconds)} of that was spent on hold - the clock does not stop for a hold.`}
                </>} />
              )}

              {order.data.status === 'ON_HOLD' && (
                <Banner variant="warning" heading="On hold" subtext={`${order.data.holdReason} · held ${relativeTime(order.data.heldAt)}. Assigning it to somebody releases the hold.`} />
              )}

              {order.data.open && order.data.evidenceRequired > 0 && (
                <Banner
                  variant={closureEvidence >= order.data.evidenceRequired ? 'success' : 'info'}
                  heading="Closure evidence"
                  subtext={
                    closureEvidence >= order.data.evidenceRequired
                      ? `${closureEvidence} attached. This work order can be closed.`
                      : `${evidenceGap(closureEvidence, order.data.evidenceRequired)}. Closure is refused until the shortfall is attached.`
                  }
                />
              )}

              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <MetricCard
                  label="Status"
                  value={humaniseCode(order.data.status)}
                  description={
                    order.data.assignedTo
                      ? `Assigned to ${order.data.assignedTo}`
                      : 'Nobody assigned'
                  }
                  variant="soft"
                />
                <MetricCard
                  label="SLA"
                  value={order.data.overdue ? 'Overdue' : order.data.slaDueAt ? 'On time' : '-'}
                  description={
                    order.data.slaDueAt ? `Due ${formatDateTime(order.data.slaDueAt)}` : 'No deadline'
                  }
                  variant="soft"
                />
                <MetricCard
                  label="Evidence"
                  value={`${closureEvidence}/${order.data.evidenceRequired}`}
                  description={
                    order.data.evidenceRequired === 0
                      ? 'None required at this priority'
                      : 'Counts towards closure'
                  }
                />
                <MetricCard
                  label="Parts"
                  value={parts.data?.length ?? 0}
                  description="Recorded against this job"
                  variant="soft"
                />
              </div>

              <TitledSection
                title="Work order record"
                actions={
                  <div className="flex gap-2">
                    {order.data.facilityFaultId && (
                      <Button
                        variant="ghost"
                        onClick={() =>
                          navigate(facilitiesPaths.faultDetail(order.data!.facilityFaultId!))
                        }
                      >
                        Open the fault
                      </Button>
                    )}
                    {order.data.roomId && (
                      <Button
                        variant="ghost"
                        onClick={() => navigate(facilitiesPaths.spaceDetail(order.data!.roomId!))}
                      >
                        Open the space
                      </Button>
                    )}
                    <Button variant="outline" onClick={() => setPending('assign')}>
                      {order.data.assignedTo ? 'Reassign' : 'Assign'}
                    </Button>
                  </div>
                }
              >
                <KeyValueGrid
                  items={[
                    { label: 'Number', value: order.data.workOrderNumber },
                    { label: 'Type', value: humaniseCode(order.data.workOrderType) },
                    { label: 'Priority', value: humaniseCode(order.data.priority) },
                    { label: 'Fault', value: orDash(order.data.faultNumber) },
                    { label: 'Assigned to', value: orDash(order.data.assignedTo) },
                    { label: 'Assigned', value: formatDateTime(order.data.assignedAt) },
                    { label: 'Started', value: formatDateTime(order.data.startedAt) },
                    {
                      label: 'On hold for',
                      value: order.data.totalHeldSeconds
                        ? heldFor(order.data.totalHeldSeconds)
                        : 'Never held',
                    },
                    { label: 'Completed', value: formatDateTime(order.data.completedAt) },
                    { label: 'Closed by', value: orDash(order.data.closedBy) },
                    { label: 'Raised by', value: order.data.metadata.createdBy },
                    { label: 'Version', value: String(order.data.metadata.version) },
                  ]}
                />
                {order.data.closureNotes && (
                  <p className="mt-3 text-theme-sm text-gray-800">
                    <span className="font-medium">Closure: </span>
                    {order.data.closureNotes}
                  </p>
                )}
                {order.data.cancellationReason && (
                  <p className="mt-3 text-theme-sm text-gray-800">
                    <span className="font-medium">Cancelled: </span>
                    {order.data.cancellationReason}
                  </p>
                )}
              </TitledSection>

              <TitledSection
                title="Parts"
                subtitle="What was fitted. Not a stores system - no stock is tracked."
                flush
                actions={
                  order.data.open && (
                    <Button variant="outline" onClick={() => setPending('part')}>
                      Record a part
                    </Button>
                  )
                }
              >
                <DataState
                  loading={parts.loading}
                  error={parts.error}
                  empty={parts.data?.length === 0}
                  emptyTitle="No parts recorded"
                  emptyHint="Anything fitted on this job can be recorded here."
                  onRetry={parts.refetch}
                >
                  <DataTable
                    rows={parts.data ?? []}
                    columns={partColumns}
                    getRowId={(part) => part.id}
                    dense
                    onRowClick={
                      order.data.open
                        ? (part) =>
                            run(
                              () => removePart(workOrderId, part.id),
                              `${part.partCode} removed.`,
                            )
                        : undefined
                    }
                  />
                </DataState>
              </TitledSection>

              <TitledSection
                title="Evidence"
                subtitle="By reference. The files live in document storage; this records where and what they hashed to."
                flush
                actions={
                  attachEvidenceAction(order.data).allowed && (
                    <Button variant="outline" onClick={() => setPending('evidence')}>
                      Attach evidence
                    </Button>
                  )
                }
              >
                <DataState
                  loading={evidence.loading}
                  error={evidence.error}
                  empty={evidence.data?.length === 0}
                  emptyTitle="No evidence attached"
                  emptyHint={
                    order.data.evidenceRequired > 0
                      ? `${order.data.evidenceRequired} item(s) are required before this work order can be closed.`
                      : 'None is required at this priority, but a photograph is rarely wasted.'
                  }
                  onRetry={evidence.refetch}
                >
                  <DataTable
                    rows={evidence.data ?? []}
                    columns={evidenceColumns}
                    getRowId={(item) => item.id}
                    dense
                    onRowClick={(item) => navigate(facilitiesPaths.evidenceDetail(item.id))}
                  />
                </DataState>
              </TitledSection>
            </div>
          </>
        )}
      </DataState>

      {pending === 'assign' && order.data && (
        <AssignWorkOrderDialog
          order={order.data}
          onClose={() => setPending(null)}
          onSubmit={async (request) =>
            run(() => assignWorkOrder(workOrderId, request), 'Work order assigned.')
          }
        />
      )}

      {pending === 'hold' && order.data && (
        <TransitionNoteDialog
          title="Put on hold"
          description={`${order.data.workOrderNumber} - ${order.data.title}`}
          label="Why it is blocked"
          placeholder="e.g. Waiting on a replacement ballast from the supplier."
          note="The SLA clock keeps running. Held time is recorded but never subtracted from the deadline."
          submitLabel="Put on hold"
          onClose={() => setPending(null)}
          onSubmit={async (notes) =>
            run(
              () =>
                holdWorkOrder(workOrderId, {
                  notes,
                  expectedVersion: order.data!.metadata.version,
                }),
              'Work order held.',
            )
          }
        />
      )}

      {pending === 'reopen' && order.data && (
        <TransitionNoteDialog
          title="Reopen work order"
          description={`${order.data.workOrderNumber} - ${order.data.title}`}
          label="Why it is going back"
          placeholder="e.g. The fire door still binds against the frame."
          note="This reverses somebody's judgement that the work was finished, so it takes the closing permission."
          submitLabel="Reopen"
          onClose={() => setPending(null)}
          onSubmit={async (notes) =>
            run(
              () =>
                reopenWorkOrder(workOrderId, {
                  notes,
                  expectedVersion: order.data!.metadata.version,
                }),
              'Work order reopened.',
            )
          }
        />
      )}

      {pending === 'cancel' && order.data && (
        <TransitionNoteDialog
          title="Cancel work order"
          description={`${order.data.workOrderNumber} - ${order.data.title}`}
          label="Why"
          placeholder="e.g. Duplicate of WO-CLET-HQ-000112."
          note="Cancellation is terminal. The fault behind it stays open."
          submitLabel="Cancel work order"
          destructive
          onClose={() => setPending(null)}
          onSubmit={async (reason) =>
            run(
              () =>
                cancelWorkOrder(workOrderId, {
                  reason,
                  expectedVersion: order.data!.metadata.version,
                }),
              'Work order cancelled.',
            )
          }
        />
      )}

      {pending === 'close' && order.data && (
        <CloseWorkOrderDialog
          order={order.data}
          attachedEvidence={closureEvidence}
          onClose={() => setPending(null)}
          onComplete={async (notes) =>
            run(
              () =>
                completeWorkOrder(workOrderId, {
                  notes,
                  expectedVersion: order.data!.metadata.version,
                }),
              'Marked complete. A supervisor accepts it from here.',
            )
          }
          onSubmit={async (request) =>
            run(() => closeWorkOrder(workOrderId, request), 'Work order closed.')
          }
        />
      )}

      {pending === 'part' && (
        <RecordPartDialog
          onClose={() => setPending(null)}
          onSubmit={async (request) =>
            run(() => recordPart(workOrderId, request), 'Part recorded.')
          }
        />
      )}

      {pending === 'evidence' && (
        <AttachEvidenceDialog
          onClose={() => setPending(null)}
          onSubmit={async (request) =>
            run(() => attachEvidence(workOrderId, request), 'Evidence attached.')
          }
        />
      )}
    </>
  );
};

export default WorkOrderDetailPage;

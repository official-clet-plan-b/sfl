import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Button, PageSection } from '@rfdtech/components';
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Inbox,
  Lock,
  RefreshCw,
  Route,
  Truck,
  X,
  type LucideIcon,
} from 'lucide-react';
import { ItemAction, courierItemsApi } from 'modules/dispatch/api/dispatchApi';
import {
  ITEM_RULES,
  itemActionAllowed,
  itemDistributable,
  itemLive,
  itemMisroutable,
} from 'modules/dispatch/api/workflow';
import {
  DistributeInboundDialog,
  MisrouteItemDialog,
} from 'modules/dispatch/dialogs/itemDialogs';
import { siteOf } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import { Callout } from 'modules/dispatch/components/formKit';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';

/** One sentence per move. "Transition applied" tells an operator nothing. */
const CONFIRMATIONS: Record<ItemAction, string> = {
  stage: 'Item staged, ready to be consigned.',
  dispatch: 'Item dispatched.',
  'in-transit': 'Item marked in transit.',
  deliver: 'Item marked delivered.',
  return: 'Item returned to origin.',
  close: 'Item closed.',
};

/** The order the moves appear in - the lifecycle forward, then the exits. */
const ACTION_ORDER: ItemAction[] = ['stage', 'dispatch', 'in-transit', 'deliver', 'return', 'close'];

/**
 * One courier item, with every move legal from where it stands.
 *
 * Which buttons appear is decided by `CourierItem`'s own `requireState` guards, transcribed in
 * `workflow.ts` - so the screen offers "Mark delivered" only from dispatched or in transit, and
 * "Close" only once the item has been delivered or returned.
 *
 * The item carries no history endpoint of its own; what it does carry is the acknowledgement,
 * misroute and exception reasons the service recorded against it, and those are shown in full.
 */
const CourierItemDetailPage = () => {
  const { itemId = '' } = useParams();
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [working, setWorking] = useState<ItemAction | null>(null);
  const [dialog, setDialog] = useState<'misroute' | 'distribute' | null>(null);

  const item = useApiQuery((signal) => courierItemsApi.findById(itemId, signal), [itemId]);

  const advance = async (action: ItemAction) => {
    setWorking(action);
    try {
      await courierItemsApi.advance(itemId, action);
      notifySuccess(CONFIRMATIONS[action]);
      item.refetch();
    } catch (error) {
      // A refused move is never silent - the service's own wording is shown.
      notifyError(error);
    } finally {
      setWorking(null);
    }
  };

  const record = item.data;

  return (
    <>
      <PageHeading
        title={record?.itemNumber ?? 'Courier item'}
        subtitle={record ? `${record.origin} → ${record.destination}` : undefined}
        crumbs={[
          { label: 'Dispatch', to: dispatchPaths.dashboard },
          { label: 'Courier items', to: dispatchPaths.items },
          { label: record?.itemNumber ?? '…' },
        ]}
        actions={
          <Button variant="outline" onClick={() => navigate(dispatchPaths.items)}>
            <ArrowLeft size={14} strokeWidth={1.5} aria-hidden="true" />
            Register
          </Button>
        }
        meta={
          record && (
            <>
              <StatusBadge value={record.status} />
              <StatusBadge value={record.direction} />
              <StatusBadge value={record.sensitivity} />
              <StatusBadge value={record.itemType} tone="neutral" />
              {record.chainOfCustodyRequired && (
                <StatusBadge value="SECRET" label="Chain of custody required" tone="blocked" />
              )}
            </>
          )
        }
      />

      <DataState
        loading={item.initialising}
        error={item.error}
        onRetry={item.refetch}
        minHeight={300}
      >
        {record && (
          <>
            {(record.undelivered ||
              record.status === 'EXCEPTION' ||
              record.misrouteReason ||
              record.acknowledgedBy) && (
              <PageSection className="space-y-3">
                {record.undelivered && (
                  <Callout tone="danger" title="This item is undelivered">
                    It was dispatched and never confirmed as delivered.{' '}
                    {record.exceptionReason ?? 'No reason was recorded.'}
                  </Callout>
                )}
                {record.status === 'EXCEPTION' && (
                  <Callout tone="danger" title="This item is in exception">
                    {record.exceptionReason ?? 'No reason was recorded.'}
                  </Callout>
                )}
                {record.misrouteReason && (
                  <Callout tone="warning" title="A misroute was recorded against this item">
                    {record.misrouteReason}
                  </Callout>
                )}
                {record.acknowledgedBy && (
                  <Callout tone="success" title="Distribution acknowledged">
                    Taken by {record.acknowledgedBy} on {formatDateTime(record.acknowledgedAt)}
                    {record.distributionReference ? ` · ${record.distributionReference}` : ''}.
                  </Callout>
                )}
              </PageSection>
            )}

            <Panel title="Actions">
              <div className="flex flex-wrap items-center gap-2">
                {ACTION_ORDER.filter((action) => itemActionAllowed(record, action)).map((action) => {
                  const ActionIcon = buttonIcon(action);
                  return (
                    <Button
                      key={action}
                      variant={action === 'close' ? 'secondary' : 'primary'}
                      loading={working === action}
                      disabled={working !== null}
                      onClick={() => advance(action)}
                    >
                      <ActionIcon size={14} strokeWidth={1.5} aria-hidden="true" />
                      {ITEM_RULES[action].label}
                    </Button>
                  );
                })}
                {itemDistributable(record) && (
                  <Button variant="secondary" onClick={() => setDialog('distribute')}>
                    <CheckCircle2 size={14} strokeWidth={1.5} aria-hidden="true" />
                    Record distribution
                  </Button>
                )}
                {itemMisroutable(record) && (
                  <Button variant="outline" onClick={() => setDialog('misroute')}>
                    <AlertTriangle size={14} strokeWidth={1.5} aria-hidden="true" />
                    Record misroute
                  </Button>
                )}
              </div>

              {!itemLive(record) && (
                <p className="mt-3 flex items-center gap-1.5 text-sm text-muted-foreground">
                  <Lock size={14} className="shrink-0" aria-hidden="true" />
                  This item is {humanise(record.status).toLowerCase()}. No further move is offered.
                </p>
              )}
            </Panel>

            <div className="mt-6 grid gap-6 xl:grid-cols-[1.4fr_1fr]">
              <Panel title="Item">
                <KeyValueGrid
                  items={[
                    { label: 'Item number', value: record.itemNumber },
                    { label: 'Site', value: siteOf(record.siteCode) },
                    { label: 'Direction', value: humanise(record.direction) },
                    { label: 'Type', value: humanise(record.itemType) },
                    { label: 'Sensitivity', value: humanise(record.sensitivity) },
                    {
                      label: 'Chain of custody',
                      value: record.chainOfCustodyRequired ? 'Required' : 'Not required',
                    },
                    { label: 'Origin', value: record.origin },
                    { label: 'Destination', value: record.destination },
                    { label: 'Sender', value: record.sender ?? '-' },
                    { label: 'Recipient', value: record.recipient ?? '-' },
                    { label: 'Handler', value: record.assignedHandler ?? 'Unassigned' },
                    {
                      label: 'Distribution reference',
                      value: record.distributionReference ?? '-',
                    },
                    {
                      label: 'Acknowledgement evidence',
                      value: record.acknowledgementEvidenceId ?? '-',
                      span: 2,
                    },
                    { label: 'Misroute reason', value: record.misrouteReason ?? '-', span: 2 },
                    { label: 'Exception reason', value: record.exceptionReason ?? '-', span: 2 },
                  ]}
                />
              </Panel>

              <div className="space-y-6">
                <Panel title="Provenance">
                  <KeyValueGrid
                    columns={2}
                    items={[
                      { label: 'Registered by', value: record.metadata.createdBy ?? '-' },
                      { label: 'Registered at', value: formatDateTime(record.metadata.createdAt) },
                      { label: 'Last change by', value: record.metadata.lastModifiedBy ?? '-' },
                      {
                        label: 'Last change at',
                        value: formatDateTime(record.metadata.lastModifiedAt),
                      },
                      { label: 'Source channel', value: humanise(record.metadata.sourceChannel) },
                      { label: 'Record version', value: record.metadata.version },
                      {
                        label: 'Correlation ID',
                        value: record.metadata.auditCorrelationId ?? '-',
                        span: 2,
                      },
                    ]}
                  />
                  <p className="mt-3 text-xs text-muted-foreground">
                    The dispatch module exposes no per-record transition history, so this is the
                    record’s own provenance rather than its audit trail.
                  </p>
                </Panel>

                <Panel title="Where this can go next">
                  <ul className="space-y-2.5">
                    {ACTION_ORDER.map((action) => {
                      const allowed = itemActionAllowed(record, action);
                      const rule = ITEM_RULES[action];
                      const MarkIcon = allowed ? CheckCircle2 : X;
                      return (
                        <li key={action} className="flex items-start gap-2.5">
                          <MarkIcon
                            size={15}
                            className={
                              allowed
                                ? 'mt-0.5 shrink-0 text-success'
                                : 'mt-0.5 shrink-0 text-muted-foreground'
                            }
                            aria-hidden="true"
                          />
                          <div className="min-w-0">
                            <p
                              className={
                                allowed
                                  ? 'text-sm font-medium text-foreground'
                                  : 'text-sm text-muted-foreground'
                              }
                            >
                              {rule.label}
                            </p>
                            <p className="text-xs text-muted-foreground">
                              {allowed
                                ? `Needs ${rule.permission}.`
                                : `From ${rule.from.map((s) => humanise(s).toLowerCase()).join(', ')}.`}
                            </p>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </Panel>
              </div>
            </div>

            {dialog === 'misroute' && (
              <MisrouteItemDialog
                open
                item={record}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Misroute recorded. The item is back in the received state.');
                  item.refetch();
                }}
              />
            )}
            {dialog === 'distribute' && (
              <DistributeInboundDialog
                open
                item={record}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess('Distribution recorded with its acknowledgement.');
                  item.refetch();
                }}
              />
            )}
          </>
        )}
      </DataState>
    </>
  );
};

const buttonIcon = (action: ItemAction): LucideIcon => {
  switch (action) {
    case 'stage':
      return Inbox;
    case 'dispatch':
      return Truck;
    case 'in-transit':
      return Route;
    case 'deliver':
      return CheckCircle2;
    case 'return':
      return RefreshCw;
    default:
      return Lock;
  }
};

export default CourierItemDetailPage;

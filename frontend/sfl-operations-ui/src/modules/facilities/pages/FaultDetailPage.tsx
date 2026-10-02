import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import {
  createWorkOrderFromFault,
  dismissFault,
  getFault,
  triageFault,
} from '../api/facilitiesApi';
import { dismissAction, raiseWorkOrderAction, triageAction } from '../api/workflow';
import DismissFaultDialog from '../dialogs/DismissFaultDialog';
import TriageFaultDialog from '../dialogs/TriageFaultDialog';
import {
  formatDateTime,
  humaniseCode,
  orDash,
  relativeTime,
} from '../components/facilitiesFormat';
import { Banner, Button, MetricCard } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import TitledSection from '../components/TitledSection';
import StatusBadge from '../components/StatusBadge';

/**
 * One fault, and everything that decides what happens to it.
 *
 * Four things on one screen, because somebody looking at a fault is deciding what to do about it:
 * where it stands, what it is doing to the space, what work is booked, and who has touched it.
 *
 * The blocker notice is the point of the whole module. A fault that has taken a hall out of service
 * says so here, in the place somebody is already looking, rather than only on the space - which is
 * a screen they would have to know to open.
 */
const FaultDetailPage = () => {
  const { faultId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [triaging, setTriaging] = useState(false);
  const [dismissing, setDismissing] = useState(false);

  const fault = useApiQuery((signal) => getFault(faultId, signal), [faultId]);

  const refresh = () => fault.refetch();

  const raiseWorkOrder = async () => {
    try {
      const order = await createWorkOrderFromFault({ facilityFaultId: faultId });
      notify.notifySuccess(`Work order ${order.workOrderNumber} raised.`);
      navigate(facilitiesPaths.workOrderDetail(order.id));
    } catch (cause) {
      notify.notifyError(cause);
    }
  };

  return (
    <>
      <DataState
        loading={fault.loading}
        error={fault.error}
        onRetry={fault.refetch}
        minHeight={280}
      >
        {fault.data && (
          <>
            <PageHeading
              title={fault.data.title}
              subtitle={`${fault.data.faultNumber} · ${orDash(fault.data.locationCode)} · ${fault.data.siteCode}`}
              crumbs={[
                { label: 'Facilities', to: facilitiesPaths.dashboard },
                { label: 'Faults', to: facilitiesPaths.faults },
                { label: fault.data.faultNumber },
              ]}
              actions={
                <div className="flex flex-wrap gap-2">
                  {(() => {
                    const action = triageAction(fault.data!);
                    return action.allowed ? (
                      <Button variant="primary" onClick={() => setTriaging(true)}>
                        Triage
                      </Button>
                    ) : null;
                  })()}
                  {(() => {
                    const action = raiseWorkOrderAction(fault.data!);
                    if (!action.allowed && fault.data!.workOrderId) {
                      return (
                        <Button
                          variant="outline"
                          onClick={() =>
                            navigate(facilitiesPaths.workOrderDetail(fault.data!.workOrderId!))
                          }
                        >
                          Open work order
                        </Button>
                      );
                    }
                    return (
                      <Button
                        variant="outline"
                        disabled={!action.allowed}
                        title={action.reason}
                        onClick={raiseWorkOrder}
                      >
                        Raise work order
                      </Button>
                    );
                  })()}
                  {(() => {
                    const action = dismissAction(fault.data!);
                    return action.allowed ? (
                      <Button variant="outline" onClick={() => setDismissing(true)}>
                        Dismiss
                      </Button>
                    ) : null;
                  })()}
                </div>
              }
            />

            <div className="space-y-5">
              {fault.data.blockerRaised && (
                <Banner variant="danger" heading="This fault is blocking its space" subtext="It holds a readiness blocker open on the space it was reported in, so the space cannot be booked or used for an examination. Resolving the fault clears it." />
              )}

              {fault.data.overdue && (
                <Banner variant="warning" heading="Past its SLA" subtext={<>The deadline was {formatDateTime(fault.data.slaDueAt)}.{fault.data.escalationLevel > 0 ? ` It has been escalated to level ${fault.data.escalationLevel}.` : ' The next scheduled sweep will escalate it.'}</>} />
              )}

              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                <MetricCard
                  label="Status"
                  value={humaniseCode(fault.data.status)}
                  description={`Reported ${relativeTime(fault.data.reportedAt)} by ${fault.data.reportedBy}`}
                  variant="soft"
                />
                <MetricCard
                  label="Priority"
                  value={humaniseCode(fault.data.priority)}
                  description={
                    fault.data.triagedAt
                      ? `Confirmed at triage by ${orDash(fault.data.triagedBy)}`
                      : 'Not yet confirmed - triage sets the SLA'
                  }
                  variant="soft"
                />
                <MetricCard
                  label="SLA"
                  value={fault.data.slaDueAt ? (fault.data.overdue ? 'Overdue' : 'On time') : '-'}
                  description={
                    fault.data.slaDueAt
                      ? `Due ${formatDateTime(fault.data.slaDueAt)}`
                      : 'No deadline until the fault is triaged'
                  }
                  variant="soft"
                />
                <MetricCard
                  label="Escalation"
                  value={fault.data.escalationLevel > 0 ? `Level ${fault.data.escalationLevel}` : 'None'}
                  description={
                    fault.data.escalatedAt
                      ? `Last raised ${relativeTime(fault.data.escalatedAt)}`
                      : 'Raised automatically once the SLA passes'
                  }
                  variant="soft"
                />
              </div>

              <TitledSection title="What was reported">
                <p className="whitespace-pre-line text-theme-sm text-gray-800">
                  {fault.data.description}
                </p>
              </TitledSection>

              {fault.data.triageNotes && (
                <TitledSection
                  title="Triage"
                  description={`${orDash(fault.data.triagedBy)} · ${formatDateTime(fault.data.triagedAt)}`}
                >
                  <p className="whitespace-pre-line text-theme-sm text-gray-800">
                    {fault.data.triageNotes}
                  </p>
                </TitledSection>
              )}

              {fault.data.resolutionNotes && (
                <TitledSection
                  title={fault.data.status === 'RESOLVED' ? 'Resolution' : 'Why it was dismissed'}
                  description={formatDateTime(fault.data.resolvedAt)}
                >
                  <p className="whitespace-pre-line text-theme-sm text-gray-800">
                    {fault.data.resolutionNotes}
                  </p>
                  {fault.data.duplicateOfFaultId && (
                    <Button
                      variant="ghost"
                      className="mt-2"
                      onClick={() =>
                        navigate(facilitiesPaths.faultDetail(fault.data!.duplicateOfFaultId!))
                      }
                    >
                      Open the fault it duplicates
                    </Button>
                  )}
                </TitledSection>
              )}

              <TitledSection title="Fault record">
                <KeyValueGrid
                  items={[
                    { label: 'Number', value: fault.data.faultNumber },
                    { label: 'Category', value: orDash(fault.data.category) },
                    { label: 'Site', value: fault.data.siteCode },
                    { label: 'Location', value: orDash(fault.data.locationCode) },
                    {
                      label: 'Space',
                      value: fault.data.roomId ? (
                        <Button
                          variant="ghost"
                          onClick={() => navigate(facilitiesPaths.spaceDetail(fault.data!.roomId!))}
                        >
                          Open the space
                        </Button>
                      ) : (
                        'Not a listed space'
                      ),
                    },
                    {
                      label: 'Work order',
                      value: fault.data.workOrderId ? (
                        <Button
                          variant="ghost"
                          onClick={() =>
                            navigate(facilitiesPaths.workOrderDetail(fault.data!.workOrderId!))
                          }
                        >
                          Open the work order
                        </Button>
                      ) : (
                        'None raised'
                      ),
                    },
                    { label: 'Reported by', value: fault.data.reportedBy },
                    { label: 'Reported', value: formatDateTime(fault.data.reportedAt) },
                    {
                      label: 'Lifecycle',
                      value: (
                        <StatusBadge
                          value={humaniseCode(fault.data.lifecycleStatus)}
                          tone="neutral"
                        />
                      ),
                    },
                    { label: 'Version', value: String(fault.data.metadata.version) },
                  ]}
                />
              </TitledSection>
            </div>
          </>
        )}
      </DataState>

      {triaging && fault.data && (
        <TriageFaultDialog
          fault={fault.data}
          onClose={() => setTriaging(false)}
          onSubmit={async (request) => {
            await triageFault(faultId, request);
            setTriaging(false);
            notify.notifySuccess('Triaged. The SLA clock has started.');
            refresh();
          }}
        />
      )}

      {dismissing && fault.data && (
        <DismissFaultDialog
          fault={fault.data}
          onClose={() => setDismissing(false)}
          onSubmit={async (request) => {
            await dismissFault(faultId, request);
            setDismissing(false);
            notify.notifySuccess('Fault dismissed.');
            refresh();
          }}
        />
      )}
    </>
  );
};

export default FaultDetailPage;

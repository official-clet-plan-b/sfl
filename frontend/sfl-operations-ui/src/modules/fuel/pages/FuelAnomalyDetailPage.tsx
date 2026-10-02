import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  NON_RECONCILIATION_RULES,
  RULE_DESCRIPTIONS,
  ReconciliationRule,
} from 'modules/fuel/api/enums';
import { AnomalyAction, fuelAnomaliesApi } from 'modules/fuel/api/fuelApi';
import {
  ANOMALY_RULES,
  anomalyActionAllowed,
  anomalyClosureBlockers,
  anomalyOpen,
  anomalySlaBreached,
} from 'modules/fuel/api/workflow';
import { AnomalyActionDialog } from 'modules/fuel/dialogs/anomalyDialogs';
import HistoryTimeline from 'modules/fuel/components/HistoryTimeline';
import { formatDueIn, siteOf } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths, fuelPaths } from 'shared/layout/navigation';
import { Banner, Button } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import { FuelBadge, Panel } from 'modules/fuel/components/fuelUi';

/** One sentence per action, naming what landed. */
const CONFIRMATIONS: Record<AnomalyAction, string> = {
  assign: 'Case assigned. The assignee has been notified.',
  reassign: 'Case reassigned. The new assignee has been notified.',
  review: 'Review started.',
  'request-explanation': 'Explanation requested. The case is now awaiting a response.',
  explain: 'Explanation recorded.',
  approve: 'Approved. The case still has to be closed.',
  reject: 'Rejected. The case still has to be closed.',
  escalate: 'Case escalated and the fleet manager notified.',
  hold: 'Case placed on hold.',
  resume: 'Case resumed.',
  cancel: 'Case cancelled.',
  close: 'Case closed against its evidence.',
  reopen: 'Case reopened.',
};

/** The order actions are offered in: progress the case, then decide, then the privileged exits. */
const ACTION_ORDER: AnomalyAction[] = [
  'assign',
  'reassign',
  'review',
  'request-explanation',
  'explain',
  'approve',
  'reject',
  'close',
  'hold',
  'resume',
  'escalate',
  'reopen',
  'cancel',
];

/** Actions the service takes no input for, so they run from the button rather than a dialog. */
const NO_INPUT_ACTIONS: AnomalyAction[] = ['review', 'request-explanation', 'resume'];

/**
 * A fuel anomaly case: why it exists, who owns it, and every action legal from where it stands.
 *
 * The closure gate is the heart of this screen. `FuelAnomalyCase.close` demands an explanation, a
 * decision and evidence, and refuses with a single message naming all three - so the panel tracks
 * each of them separately and shows which are actually satisfied. An operator can see the whole
 * path to closure at a glance instead of discovering it one refusal at a time.
 */
const FuelAnomalyDetailPage = () => {
  const { anomalyId = '' } = useParams();
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [dialog, setDialog] = useState<AnomalyAction | null>(null);
  const [working, setWorking] = useState<AnomalyAction | null>(null);

  const anomaly = useApiQuery(
    (signal) => fuelAnomaliesApi.findById(anomalyId, signal),
    [anomalyId],
  );

  /** Assignment, explanation, decision, escalation and closure, as recorded. */
  const history = useApiQuery(
    (signal) => fuelAnomaliesApi.history(anomalyId, signal),
    [anomalyId],
  );

  const refreshAll = () => {
    anomaly.refetch();
    history.refetch();
  };

  const record = anomaly.data;

  const runDirect = async (action: AnomalyAction) => {
    setWorking(action);
    try {
      await fuelAnomaliesApi.transition(anomalyId, action, { value: null, evidenceId: null });
      notifySuccess(CONFIRMATIONS[action]);
      refreshAll();
    } catch (error) {
      notifyError(error);
    } finally {
      setWorking(null);
    }
  };

  const closureBlockers = record ? anomalyClosureBlockers(record) : [];
  const breached = record ? anomalySlaBreached(record) : false;

  return (
    <div>
      <PageHeading
        title={record?.anomalyNumber ?? 'Anomaly case'}
        subtitle={record ? humanise(record.type) : undefined}
        crumbs={[
          { label: 'Fuel', to: fuelPaths.dashboard },
          { label: 'Anomaly cases', to: fuelPaths.anomalies },
          { label: record?.anomalyNumber ?? '…' },
        ]}
        actions={
          <Button
            variant="outline"
            onClick={() => navigate(fuelPaths.anomalies)}
          >
            Queue
          </Button>
        }
        meta={
          record && (
            <div className="flex flex-wrap items-center gap-2">
              <FuelBadge value={record.status} />
              <FuelBadge value={record.severity} />
              {record.material && <FuelBadge value="HIGH" label="Material" tone="caution" />}
              {record.escalationLevel > 0 && (
                <FuelBadge
                  value="ESCALATED"
                  label={`Escalation level ${record.escalationLevel}`}
                  tone="blocked"
                />
              )}
            </div>
          )
        }
      />

      <DataState
        loading={anomaly.initialising}
        error={anomaly.error}
        onRetry={anomaly.refetch}
        minHeight={300}
      >
        {record && (
          <div className="space-y-5">
            {breached && (
              <Banner variant="danger" heading="This case has breached its SLA" subtext={<>It was due {formatDateTime(record.slaDueAt)} - {formatDueIn(record.slaDueAt)}. The
                scheduled sweep escalates cases past their target automatically.</>} />
            )}
            {record.material && anomalyOpen(record) && (
              <Banner variant="warning" heading="This case is material" subtext="The transaction met or exceeded the policy’s materiality amount, so escalating it surfaces it to finance and audit as well as to the fleet manager." />
            )}
            {record.status === 'CLOSED' && (
              <Banner variant="success" heading="This case is closed" subtext={record.closureReason ?? 'No closure reason was recorded.'} />
            )}
            {record.status === 'CANCELLED' && (
              <Banner variant="info" heading="This case is cancelled" subtext={record.closureReason ?? 'No reason was recorded.'} />
            )}

            <Panel title="Actions">
              <div className="flex flex-wrap items-center gap-2">
                {ACTION_ORDER.filter((action) => anomalyActionAllowed(record, action)).map(
                  (action) =>
                    NO_INPUT_ACTIONS.includes(action) ? (
                      <Button
                        key={action}
                        variant="outline"
                        loading={working === action}
                        onClick={() => runDirect(action)}
                      >
                        {ANOMALY_RULES[action].label}
                      </Button>
                    ) : (
                      <Button
                        key={action}
                        variant={buttonVariant(action)}
                        onClick={() => setDialog(action)}
                      >
                        {ANOMALY_RULES[action].label}
                      </Button>
                    ),
                )}
              </div>

              <div className="mt-4 flex flex-wrap items-center gap-2">
                {record.transactionId && (
                  <Button
                    variant="ghost"
                    onClick={() =>
                      navigate(fuelPaths.transactionDetail(record.transactionId as string))
                    }
                  >
                    Transaction
                  </Button>
                )}
                {record.logbookId && (
                  <Button
                    variant="ghost"
                    onClick={() => navigate(fuelPaths.logbookDetail(record.logbookId as string))}
                  >
                    Logbook
                  </Button>
                )}
                {record.vehicleId && (
                  <Button
                    variant="ghost"
                    onClick={() => navigate(fleetPaths.vehicleDetail(record.vehicleId as string))}
                  >
                    Vehicle
                  </Button>
                )}
                {record.driverId && (
                  <Button
                    variant="ghost"
                    onClick={() => navigate(fleetPaths.driverDetail(record.driverId as string))}
                  >
                    Driver
                  </Button>
                )}
                {record.tripId && (
                  <Button
                    variant="ghost"
                    onClick={() => navigate(fleetPaths.tripDetail(record.tripId as string))}
                  >
                    Trip
                  </Button>
                )}
              </div>
            </Panel>

            <div className="grid gap-5 xl:grid-cols-[1.4fr_1fr]">
              <div className="space-y-5">
                <Panel title="Case">
                  <KeyValueGrid
                    items={[
                      { label: 'Case number', value: record.anomalyNumber },
                      { label: 'Site', value: siteOf(record.siteCode) },
                      { label: 'Type', value: humanise(record.type) },
                      { label: 'Severity', value: humanise(record.severity) },
                      { label: 'Material', value: record.material ? 'Yes' : 'No' },
                      { label: 'Assignee', value: record.assignee ?? 'Unassigned' },
                      { label: 'SLA due', value: formatDateTime(record.slaDueAt) },
                      { label: 'SLA standing', value: formatDueIn(record.slaDueAt) },
                      { label: 'Escalation level', value: record.escalationLevel },
                      { label: 'Explanation', value: record.explanation ?? '-', span: 2 },
                      {
                        label: 'Decision',
                        value: record.decision ? humanise(record.decision) : '-',
                      },
                      { label: 'Evidence reference', value: record.evidenceId ?? '-' },
                      { label: 'Closure reason', value: record.closureReason ?? '-', span: 2 },
                    ]}
                  />
                </Panel>

                <Panel
                  title="Why this case exists"
                  description="The rules the service recorded when it raised the case"
                >
                  {record.detectedRules.length === 0 ? (
                    <p className="text-theme-sm text-gray-600">No rule was recorded.</p>
                  ) : (
                    <ul className="space-y-2.5">
                      {record.detectedRules.map((rule) => (
                        <li key={rule} className="rounded-md border border-gray-200 px-3.5 py-2.5">
                          <p className="text-theme-sm font-semibold text-gray-900">
                            {humanise(rule)}
                          </p>
                          <p className="mt-0.5 text-theme-sm text-gray-700">
                            {RULE_DESCRIPTIONS[rule as ReconciliationRule] ??
                              NON_RECONCILIATION_RULES[rule] ??
                              'This rule is recorded by the service but is not described here.'}
                          </p>
                        </li>
                      ))}
                    </ul>
                  )}
                </Panel>
              </div>

              <div className="space-y-5">
                <Panel
                  title="Path to closure"
                  description="All three are required before the case can be closed"
                >
                  <ul className="space-y-3">
                    <ClosureStep
                      label="Explanation recorded"
                      satisfied={Boolean(record.explanation)}
                      hint="Request one, then record the response."
                    />
                    <ClosureStep
                      label="Decision recorded"
                      satisfied={Boolean(record.decision)}
                      hint="Approve or reject the case from under review."
                    />
                    <ClosureStep
                      label="Closure evidence"
                      satisfied={record.status === 'CLOSED'}
                      hint="Supplied with the closure itself."
                    />
                  </ul>
                  {anomalyOpen(record) && closureBlockers.length === 0 && (
                    <Banner variant="success" heading="Ready to close" subtext="Everything the service needs is recorded. Closure needs an evidence reference." className="mt-4" />
                  )}
                </Panel>

                <Panel
                  title="History"
                  description="Recorded transitions, from the audit log"
                  actions={
                    <Button variant="ghost" size="sm" onClick={history.refetch}>
                      Refresh
                    </Button>
                  }
                >
                  <DataState
                    loading={history.initialising}
                    error={history.error}
                    onRetry={history.refetch}
                    minHeight={160}
                  >
                    <HistoryTimeline events={history.data} recordNoun="case" />
                  </DataState>
                </Panel>

                <Panel title="Where this can go next">
                  <ul className="space-y-2.5">
                    {ACTION_ORDER.map((action) => {
                      const allowed = anomalyActionAllowed(record, action);
                      const rule = ANOMALY_RULES[action];
                      return (
                        <li key={action} className="flex items-start gap-2.5">
                          <Icon
                            name={allowed ? 'check-circle' : 'close'}
                            size={15}
                            className={
                              allowed
                                ? 'mt-0.5 shrink-0 text-success-700'
                                : 'mt-0.5 shrink-0 text-gray-400'
                            }
                          />
                          <div className="min-w-0">
                            <p
                              className={
                                allowed
                                  ? 'text-theme-sm font-medium text-gray-900'
                                  : 'text-theme-sm text-gray-500'
                              }
                            >
                              {rule.label}
                              {rule.privileged && (
                                <span className="ml-1.5 text-theme-xs font-semibold text-gold-900">
                                  privileged
                                </span>
                              )}
                            </p>
                            <p className="text-theme-xs text-gray-600">
                              {allowed
                                ? `Needs ${rule.permission}.`
                                : rule.from.length === 0
                                  ? 'Available from any state.'
                                  : `From ${rule.from
                                      .map((state) => humanise(state).toLowerCase())
                                      .join(', ')}.`}
                            </p>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </Panel>
              </div>
            </div>

            {dialog && (
              <AnomalyActionDialog
                open
                anomaly={record}
                action={dialog}
                onClose={() => setDialog(null)}
                onSaved={() => {
                  notifySuccess(CONFIRMATIONS[dialog]);
                  refreshAll();
                }}
              />
            )}
          </div>
        )}
      </DataState>
    </div>
  );
};

const ClosureStep = ({
  label,
  satisfied,
  hint,
}: {
  label: string;
  satisfied: boolean;
  hint: string;
}) => (
  <li className="flex items-start gap-2.5">
    <Icon
      name={satisfied ? 'check-circle' : 'alert-circle'}
      size={16}
      className={satisfied ? 'mt-0.5 shrink-0 text-success-700' : 'mt-0.5 shrink-0 text-warning-700'}
    />
    <div className="min-w-0">
      <p className="text-theme-sm font-medium text-gray-900">{label}</p>
      {!satisfied && <p className="text-theme-xs text-gray-600">{hint}</p>}
    </div>
  </li>
);

const buttonVariant = (action: AnomalyAction) => {
  switch (action) {
    case 'assign':
      return 'primary' as const;
    case 'close':
      return 'primary' as const;
    case 'cancel':
    case 'reject':
      return 'primary-destructive' as const;
    default:
      return 'outline' as const;
  }
};

export default FuelAnomalyDetailPage;

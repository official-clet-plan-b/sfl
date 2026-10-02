import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { FuelAnomalyCase, rulePassed } from 'modules/fuel/api/dto';
import {
  NON_RECONCILIATION_RULES,
  RULE_DESCRIPTIONS,
  ReconciliationRule,
  UNREACHABLE_TRANSACTION_STATUSES,
} from 'modules/fuel/api/enums';
import { fuelAnomaliesApi, fuelTransactionsApi } from 'modules/fuel/api/fuelApi';
import {
  transactionReconcilable,
  transactionReconciled,
  transactionVoidable,
} from 'modules/fuel/api/workflow';
import { VoidTransactionDialog } from 'modules/fuel/dialogs/transactionDialogs';
import HistoryTimeline from 'modules/fuel/components/HistoryTimeline';
import {
  formatMoney,
  formatQuantity,
  formatUnitPrice,
  siteOf,
} from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import { Alert, Button, DataTable, PageHeader, SectionCard, StatusChip, CellStack } from 'modules/fuel/components/fuelUi';
import type { FuelColumn as Column } from 'modules/fuel/components/fuelUi';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { EvidenceFileActions } from 'shared/components/EvidenceFileField';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths, fuelPaths } from 'shared/layout/navigation';
import { canRunReconciliation, canVoidFuel } from 'modules/fleet/api/access';

/**
 * A fuel transaction, its reconciliation outcome and the cases that outcome raised.
 *
 * The reconciliation panel reads the stored run: every rule the policy applied, passed and failed
 * alike, with the policy version it was judged against. That is the whole decision, reproducible.
 * It used to be half a decision - the rule outcomes were written on every run and readable from
 * none of it, so the panel could only infer the *failures* from the cases they raised and had to
 * say that the rules which passed were unavailable.
 */
const FuelTransactionDetailPage = () => {
  const { transactionId = '' } = useParams();
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [voiding, setVoiding] = useState(false);
  const [reconciling, setReconciling] = useState(false);

  const transaction = useApiQuery(
    (signal) => fuelTransactionsApi.findById(transactionId, signal),
    [transactionId],
  );

  const siteCode = transaction.data ? siteOf(transaction.data.siteCode) : '';

  /** The cases raised against this transaction, filtered by the service. */
  const anomalies = useApiQuery(
    (signal) =>
      siteCode
        ? fuelAnomaliesApi.search({ siteCode, transactionId }, signal)
        : Promise.resolve(undefined),
    [siteCode, transactionId],
  );

  /**
   * Every reconciliation run against this transaction, newest first.
   *
   * This is what the screen was built around and could not have: the stored per-rule outcomes. The
   * panel below shows the rules that **passed** as well as the ones that failed, and the policy
   * version each run applied.
   */
  const runs = useApiQuery(
    (signal) => fuelTransactionsApi.reconciliations(transactionId, signal),
    [transactionId],
  );

  const history = useApiQuery(
    (signal) => fuelTransactionsApi.history(transactionId, signal),
    [transactionId],
  );

  const relatedCases = useMemo(() => anomalies.data?.content ?? [], [anomalies.data]);

  /** The most recent run is the one that decided the record's current status. */
  const latestRun = runs.data?.[0];

  const failedRules = useMemo(
    () =>
      Object.entries(latestRun?.ruleResults ?? {})
        .filter(([, outcome]) => !rulePassed(outcome))
        .map(([rule]) => rule),
    [latestRun],
  );

  const passedRules = useMemo(
    () =>
      Object.entries(latestRun?.ruleResults ?? {})
        .filter(([, outcome]) => rulePassed(outcome))
        .map(([rule]) => rule),
    [latestRun],
  );

  const refreshAll = () => {
    transaction.refetch();
    anomalies.refetch();
    runs.refetch();
    history.refetch();
  };

  const reconcile = async () => {
    setReconciling(true);
    try {
      const result = await fuelTransactionsApi.reconcile(transactionId);
      if (result.status === 'RECONCILED') {
        notifySuccess('Reconciled. Every policy rule passed.');
      } else {
        notifySuccess(
          'Reconciliation completed with exceptions.',
          'One or more rules failed; the cases they raised are listed below.',
        );
      }
      refreshAll();
    } catch (error) {
      // A missing policy, a voided record or a refused permission all land here with the service's
      // own wording - never swallowed, never rewritten.
      notifyError(error);
    } finally {
      setReconciling(false);
    }
  };

  const caseColumns = useMemo<Column<FuelAnomalyCase>[]>(
    () => [
      {
        key: 'case',
        header: 'Case',
        width: 240,
        cell: (row) => (
          <CellStack
            primary={`${row.anomalyNumber} · ${humanise(row.type)}`}
            secondary={row.detectedRules.join(', ') || 'no rule recorded'}
          />
        ),
      },
      {
        key: 'severity',
        header: 'Severity',
        width: 110,
        cell: (row) => <StatusChip value={row.severity} />,
      },
      {
        key: 'material',
        header: 'Material',
        width: 100,
        align: 'center',
        hideBelowLg: true,
        cell: (row) =>
          row.material ? <StatusChip value="HIGH" label="Material" tone="caution" /> : '-',
      },
      {
        key: 'status',
        header: 'Status',
        width: 140,
        align: 'right',
        cell: (row) => <StatusChip value={row.status} />,
      },
    ],
    [],
  );

  const record = transaction.data;

  return (
    <div>
      <PageHeader
        title={record ? `${record.vendorReference} · ${record.fuelProduct}` : 'Fuel transaction'}
        subtitle={record ? `Occurred ${formatDateTime(record.occurredAt)}` : undefined}
        crumbs={[
          { label: 'Fuel', to: fuelPaths.dashboard },
          { label: 'Transactions', to: fuelPaths.transactions },
          { label: record ? record.id.slice(0, 8) : '…' },
        ]}
        actions={
          <Button
            variant="outline"
            startIcon="arrow-left"
            onClick={() => navigate(fuelPaths.transactions)}
          >
            Register
          </Button>
        }
        meta={
          record && (
            <div className="flex flex-wrap items-center gap-2">
              <StatusChip value={record.status} />
              <StatusChip value={record.lifecycle} label={`Lifecycle ${humanise(record.lifecycle).toLowerCase()}`} />
              <StatusChip value={record.sourceSystem} tone="neutral" label={record.sourceSystem} />
            </div>
          )
        }
      />

      <DataState
        loading={transaction.initialising}
        error={transaction.error}
        onRetry={transaction.refetch}
        minHeight={300}
      >
        {record && (
          <div className="space-y-5">
            {record.status === 'VOIDED' && (
              <Alert variant="warning" title="This transaction is voided">
                It is excluded from reconciliation permanently and cannot be changed. The reason
                recorded at the time is held in its comments.
              </Alert>
            )}

            <SectionCard title="Actions">
              <div className="flex flex-wrap items-center gap-2">
                {canRunReconciliation() && (
                  <Button
                    variant="primary"
                    startIcon="scale"
                    loading={reconciling}
                    disabled={!transactionReconcilable(record)}
                    onClick={reconcile}
                  >
                    {transactionReconciled(record) ? 'Reconcile again' : 'Reconcile'}
                  </Button>
                )}
                {/* Void is irreversible and separately granted - a reader must not be offered it. */}
                {canVoidFuel() && (
                  <Button
                    variant="danger"
                    startIcon="close"
                    disabled={!transactionVoidable(record)}
                    onClick={() => setVoiding(true)}
                  >
                    Void
                  </Button>
                )}
                <Button
                  variant="ghost"
                  startIcon="truck"
                  endIcon="chevron-right"
                  onClick={() => navigate(fleetPaths.vehicleDetail(record.vehicleId))}
                >
                  Vehicle
                </Button>
                <Button
                  variant="ghost"
                  startIcon="driver"
                  endIcon="chevron-right"
                  onClick={() => navigate(fleetPaths.driverDetail(record.driverId))}
                >
                  Driver
                </Button>
                {record.tripId && (
                  <Button
                    variant="ghost"
                    startIcon="route"
                    endIcon="chevron-right"
                    onClick={() => navigate(fleetPaths.tripDetail(record.tripId as string))}
                  >
                    Trip
                  </Button>
                )}
              </div>
              {!transactionReconcilable(record) && record.status !== 'VOIDED' && (
                <p className="mt-3 text-theme-sm text-gray-600">
                  This record’s lifecycle is {humanise(record.lifecycle).toLowerCase()}, so the
                  service will not change its status.
                </p>
              )}
            </SectionCard>

            <div className="grid gap-5 xl:grid-cols-[1.4fr_1fr]">
              <div className="space-y-5">
                <SectionCard title="Transaction">
                  <KeyValueGrid
                    items={[
                      { label: 'Site', value: siteOf(record.siteCode) },
                      { label: 'Vendor', value: record.vendorReference },
                      { label: 'Station', value: record.stationReference ?? '-' },
                      { label: 'Product', value: record.fuelProduct },
                      {
                        label: 'Quantity',
                        value: formatQuantity(record.quantity, record.quantityUnit),
                      },
                      {
                        label: 'Unit price',
                        value: formatUnitPrice(record.unitPrice, record.currency),
                      },
                      {
                        label: 'Total cost',
                        value: formatMoney(record.totalCost, record.currency),
                      },
                      {
                        label: 'Odometer reading',
                        value: `${formatNumber(record.odometerReading)} km`,
                      },
                      { label: 'Fuel purchased at', value: formatDateTime(record.occurredAt) },
                      {
                        label: 'Card reference',
                        value: record.maskedCardReference ?? '-',
                        masked: Boolean(record.maskedCardReference),
                      },
                      { label: 'Comments', value: record.comments ?? '-', span: 2 },
                    ]}
                  />
                </SectionCard>

                {/*
                  The receipt and the pump reading, side by side and openable.

                  This is the point of storing both. A reviewer asking "did this driver really buy
                  GHS 240 of fuel" cannot answer it from the numbers - the numbers are what is in
                  question - and they cannot answer it from an evidence identifier either. They
                  answer it by looking at what the attendant wrote and what the pump displayed, and
                  seeing whether the two agree with each other and with the claim.
                */}
                <SectionCard
                  title="Evidence"
                  subtitle="What the vendor wrote, and what the pump showed"
                >
                  <div className="grid gap-5 sm:grid-cols-2">
                    <div>
                      <p className="mb-1.5 text-theme-sm font-semibold text-gray-800">Receipt</p>
                      {record.receiptEvidenceId ? (
                        <EvidenceFileActions
                          evidenceId={record.receiptEvidenceId}
                          fileName={`receipt-${record.id.slice(0, 8)}`}
                          onError={notifyError}
                        />
                      ) : (
                        <p className="text-theme-xs text-gray-500">
                          None held.{' '}
                          {record.sourceSystem === 'MANUAL'
                            ? 'A manual capture without a receipt fails the RECEIPT rule once the grace window closes.'
                            : 'Provider-fed records rarely carry one.'}
                        </p>
                      )}
                    </div>
                    <div>
                      <p className="mb-1.5 text-theme-sm font-semibold text-gray-800">
                        Pump meter reading
                      </p>
                      {record.pumpEvidenceId ? (
                        <EvidenceFileActions
                          evidenceId={record.pumpEvidenceId}
                          fileName={`pump-${record.id.slice(0, 8)}`}
                          onError={notifyError}
                        />
                      ) : (
                        <p className="text-theme-xs text-gray-500">
                          None held.{' '}
                          {record.sourceSystem === 'MANUAL'
                            ? 'Required on manual captures - see the PUMP_IMAGE rule below.'
                            : 'Only manual captures carry one.'}
                        </p>
                      )}
                    </div>
                  </div>
                </SectionCard>

                <SectionCard
                  title="Reconciliation"
                  subtitle={
                    latestRun
                      ? `Policy version ${latestRun.policyVersion ?? '-'} · evaluated ${formatDateTime(latestRun.evaluatedAt)}`
                      : 'What the policy rules made of this transaction'
                  }
                  actions={
                    runs.data && runs.data.length > 1 ? (
                      <span className="text-theme-xs text-gray-600">
                        {runs.data.length} runs recorded
                      </span>
                    ) : undefined
                  }
                >
                  <DataState
                    loading={runs.initialising}
                    error={runs.error}
                    onRetry={runs.refetch}
                    minHeight={140}
                  >
                    {!latestRun ? (
                      <Alert variant="info" title="Reconciliation has not run">
                        This transaction is {humanise(record.status).toLowerCase()}. It contributes
                        to the site’s totals but has not been judged against a policy, so no rule
                        outcome exists yet.
                      </Alert>
                    ) : (
                      <div className="space-y-3">
                        {failedRules.length === 0 ? (
                          <Alert variant="success" title="Every rule passed">
                            All {passedRules.length} rules the policy applied were satisfied.
                          </Alert>
                        ) : (
                          <Alert
                            variant="error"
                            title={`${failedRules.length} of ${failedRules.length + passedRules.length} rules failed`}
                          >
                            Each failure raised the case listed below. A case stays open until it is
                            explained, decided and closed.
                          </Alert>
                        )}

                        {latestRun.calculatedConsumption !== null && (
                          <p className="text-theme-sm text-gray-700">
                            Calculated consumption: {latestRun.calculatedConsumption}{' '}
                            {record.quantityUnit.toLowerCase()} per kilometre since the previous
                            transaction for this vehicle.
                          </p>
                        )}

                        {/* Every rule the run evaluated, failures first - the outcome map the
                            service stores, read in full rather than inferred from the cases. */}
                        <ul className="space-y-2">
                          {[...failedRules, ...passedRules].map((rule) => {
                            const failed = failedRules.includes(rule);
                            return (
                              <li
                                key={rule}
                                className="flex items-start gap-2.5 rounded-md border border-gray-200 px-3.5 py-2.5"
                              >
                                <Icon
                                  name={failed ? 'alert-circle' : 'check-circle'}
                                  size={15}
                                  className={
                                    failed
                                      ? 'mt-0.5 shrink-0 text-error-800'
                                      : 'mt-0.5 shrink-0 text-success-700'
                                  }
                                />
                                <div className="min-w-0">
                                  <p className="text-theme-sm font-semibold text-gray-900">
                                    {humanise(rule)}
                                  </p>
                                  <p className="mt-0.5 text-theme-sm text-gray-700">
                                    {RULE_DESCRIPTIONS[rule as ReconciliationRule] ??
                                      NON_RECONCILIATION_RULES[rule] ??
                                      'This rule is recorded by the service but is not described here.'}
                                  </p>
                                </div>
                              </li>
                            );
                          })}
                        </ul>
                      </div>
                    )}
                  </DataState>
                </SectionCard>

                <SectionCard
                  title="Anomaly cases"
                  subtitle="Raised against this transaction"
                  flush
                >
                  <DataState
                    loading={anomalies.initialising}
                    error={anomalies.error}
                    empty={relatedCases.length === 0}
                    emptyTitle="No anomaly cases"
                    emptyHint="Nothing has been raised against this transaction."
                    onRetry={anomalies.refetch}
                    minHeight={140}
                  >
                    <DataTable
                      rows={relatedCases}
                      columns={caseColumns}
                      getRowId={(row) => row.id}
                      loading={anomalies.loading}
                      onRowClick={(row) => navigate(fuelPaths.anomalyDetail(row.id))}
                      caption="Fuel anomaly cases raised against this transaction, with the rule that raised each, its severity, materiality and status."
                      dense
                    />
                  </DataState>
                </SectionCard>
              </div>

              <div className="space-y-5">
                <SectionCard title="Provenance" subtitle="Where this record came from">
                  <KeyValueGrid
                    columns={2}
                    items={[
                      { label: 'Source system', value: record.sourceSystem },
                      {
                        label: 'Provider reference',
                        value: record.providerTransactionId ?? '-',
                      },
                      {
                        label: 'Ingested at',
                        value: formatDateTime(record.ingestionTimestamp),
                      },
                      { label: 'Idempotency key', value: record.idempotencyKey ?? '-', span: 2 },
                      {
                        label: 'Correlation ID',
                        value: record.metadata.auditCorrelationId ?? '-',
                        span: 2,
                      },
                    ]}
                  />
                </SectionCard>

                <SectionCard
                  title="History"
                  subtitle="Recorded transitions, from the audit log"
                  actions={
                    <Button variant="ghost" size="sm" startIcon="refresh" onClick={history.refetch}>
                      Refresh
                    </Button>
                  }
                >
                  <DataState
                    loading={history.initialising}
                    error={history.error}
                    onRetry={history.refetch}
                    minHeight={140}
                  >
                    <HistoryTimeline events={history.data} recordNoun="transaction" />
                  </DataState>
                </SectionCard>

                <SectionCard title="Lifecycle" subtitle="Where this record can go next">
                  <ol className="space-y-2 text-theme-sm text-gray-700">
                    {['RECEIVED', 'RECONCILED', 'EXCEPTION', 'VOIDED'].map((state) => (
                      <li key={state} className="flex items-center gap-2">
                        <StatusChip value={state} />
                        {state === record.status && (
                          <span className="text-theme-xs font-semibold text-teal-800">
                            current
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>
                  <p className="mt-3 text-theme-xs text-gray-600">
                    The status enum also declares{' '}
                    {UNREACHABLE_TRANSACTION_STATUSES.map((state) =>
                      humanise(state).toLowerCase(),
                    ).join(', ')}
                    . No service code path writes them, so a record will not reach them.
                  </p>
                </SectionCard>
              </div>
            </div>

            {voiding && (
              <VoidTransactionDialog
                open
                transaction={record}
                onClose={() => setVoiding(false)}
                onSaved={() => {
                  notifySuccess('Transaction voided.');
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

export default FuelTransactionDetailPage;

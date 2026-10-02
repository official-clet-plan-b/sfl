import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Banner,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  Dropdown,
  EmptyState,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  useTableState,
  type TableColumn,
} from '@rfdtech/components';
import { Scale } from 'lucide-react';
import { FuelPolicy, FuelTransaction, rulePassed } from 'modules/fuel/api/dto';
import {
  RECONCILIATION_RULES,
  RULE_DESCRIPTIONS,
  type ReconciliationRule,
} from 'modules/fuel/api/enums';
import { fuelPoliciesApi, fuelTransactionsApi } from 'modules/fuel/api/fuelApi';
import { transactionReconcilable } from 'modules/fuel/api/workflow';
import { useClampPage, useRegisterPaging } from 'modules/fuel/components/useRegisterPaging';
import { formatMoney, formatQuantity } from 'modules/fuel/components/fuelFormat';
import { CellStack, ErrorBanner, FuelBadge, Panel, RegisterTable } from 'modules/fuel/components/fuelUi';
import { metricLink } from 'modules/fuel/components/metricLink';
import { humanise } from 'modules/fleet/api/enums';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate, formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { canRunReconciliation } from 'modules/fleet/api/access';

/**
 * The six groups the design sorts the rules into, with the line it gives each.
 *
 * The grouping is presentation only: the service evaluates the rules in `RECONCILIATION_RULES`'
 * order and reports each by name, so a rule missing from here would still run - it would just not be
 * listed. The count under the heading is read from the groups so the two cannot disagree.
 */
const RULE_GROUPS: { title: string; summary: string; rules: ReconciliationRule[] }[] = [
  {
    title: 'Quantity and product',
    summary:
      'Maximum per transaction, tank capacity, permitted fuel product and approved vendor.',
    rules: ['MAX_PER_TRANSACTION', 'TANK_CAPACITY', 'FUEL_PRODUCT', 'APPROVED_VENDOR'],
  },
  {
    title: 'Spend limits',
    summary: 'Daily and monthly spend limits for each vehicle and each driver.',
    rules: [
      'POLICY_DAILY_VEHICLE_LIMIT',
      'POLICY_DAILY_DRIVER_LIMIT',
      'POLICY_MONTHLY_VEHICLE_LIMIT',
      'POLICY_MONTHLY_DRIVER_LIMIT',
    ],
  },
  {
    title: 'Fuel card',
    summary:
      'Card is known and assigned to the vehicle, and stays within its transaction, daily and monthly limits.',
    rules: [
      'CARD_KNOWN',
      'CARD_VEHICLE_MATCH',
      'CARD_TRANSACTION_LIMIT',
      'CARD_DAILY_LIMIT',
      'CARD_MONTHLY_LIMIT',
    ],
  },
  {
    title: 'Driver and vehicle',
    summary: 'Driver was eligible at the time. Vehicle was active and available.',
    rules: ['DRIVER_ELIGIBLE', 'VEHICLE_OPERATIONAL'],
  },
  {
    title: 'Trip and odometer',
    summary:
      'Falls inside a booked trip. Odometer never goes backwards, jumps within tolerance and agrees with the logbook.',
    rules: ['TRIP_MATCH', 'ODOMETER_NON_REGRESSION', 'ODOMETER_JUMP', 'LOGBOOK_MATCH'],
  },
  {
    title: 'Receipt, price and pattern',
    summary:
      'Receipt attached within the grace period, consumption and unit price in range, no repeated pattern.',
    rules: ['RECEIPT', 'CONSUMPTION_RANGE', 'COST_VARIANCE', 'REPEATED_PATTERN'],
  },
];

/** What a reconciliation run can be asked to cover. */
const SCOPES = ['RECEIVED', 'EXCEPTION'] as const;
type Scope = (typeof SCOPES)[number];

const SCOPE_LABELS: Record<Scope, string> = {
  RECEIVED: 'Not yet reconciled',
  EXCEPTION: 'Previously in exception',
};

/** The outcome of one transaction in a run, with the rules the service actually recorded. */
interface RunOutcome {
  transaction: FuelTransaction;
  status: 'RECONCILED' | 'EXCEPTION' | 'REFUSED';
  failedRules: string[];
  passedCount: number;
  policyVersion: number | null;
  message?: string;
}

/**
 * Run reconciliation and read what it decided.
 *
 * There is no `POST /reconciliations/run` - the inventory document lists one, but the only entry
 * point the service has is `POST /transactions/{id}/reconcile`, one transaction at a time (gap 1).
 * So a "run" here is exactly that: the selected transactions, reconciled in sequence, with each
 * outcome reported as it lands. That is honest about what is happening and it means a failure on one
 * record does not abandon the rest.
 *
 * The per-rule results the service stores are not readable, so the outcomes below give the verdict
 * and link to the cases the run raised - where the failing rule *is* recorded, in `detectedRules`.
 */
const FuelReconciliationPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  // Seeded from the URL, where the table keeps an applied filter, so a reload restores the scope.
  const { filters } = useTableState({ paramPrefix: 'fuel-reconciliation' });
  const [scope, setScope] = useState<Scope>(
    SCOPES.find((value) => value === filters.scope) ?? 'RECEIVED',
  );
  const [running, setRunning] = useState(false);
  const [outcomes, setOutcomes] = useState<RunOutcome[]>([]);
  const [guidanceTab, setGuidanceTab] = useState<'rules' | 'policies'>('rules');
  const [confirming, setConfirming] = useState(false);

  const filterKey = siteCode + '|' + scope;
  const paging = useRegisterPaging('fuel-reconciliation', filterKey);

  const candidates = useApiQuery(
    (signal) =>
      fuelTransactionsApi.search(
        { siteCode, status: scope, page: paging.page, size: paging.size },
        signal,
      ),
    [filterKey, paging.page, paging.size],
  );

  useClampPage(paging.page, candidates.data?.totalPages, paging.setPage);

  /** The policies a run would actually resolve against, asked of the service. */
  const policies = useApiQuery(
    (signal) => fuelPoliciesApi.search({ siteCode, inForceOnly: true, size: 50 }, signal),
    [siteCode],
  );

  const activePolicies = useMemo(() => policies.data?.content ?? [], [policies.data]);

  /**
   * Reads back the run the service just recorded, so the outcome names real rules.
   *
   * The reconciliation record carries the full per-rule map and the policy version it applied.
   * Before that read existed, the failing rules had to be inferred from the anomaly cases the run
   * raised - which could only ever show failures, never what passed.
   */
  const outcomeFor = async (transaction: FuelTransaction): Promise<RunOutcome> => {
    const result = await fuelTransactionsApi.reconcile(transaction.id);
    const runs = await fuelTransactionsApi.reconciliations(transaction.id);
    const latest = runs[0];
    const rules = Object.entries(latest?.ruleResults ?? {});
    return {
      transaction: result,
      status: result.status === 'RECONCILED' ? 'RECONCILED' : 'EXCEPTION',
      failedRules: rules.filter(([, outcome]) => !rulePassed(outcome)).map(([rule]) => rule),
      passedCount: rules.filter(([, outcome]) => rulePassed(outcome)).length,
      policyVersion: latest?.policyVersion ?? null,
    };
  };

  /**
   * Reconciles every candidate in sequence.
   *
   * Sequential rather than parallel, deliberately: each run advances the vehicle's accepted odometer
   * through `FleetOdometerPort` and reads the previous transaction for the consumption and
   * cost-variance rules, so running them concurrently would have them race over the same vehicle
   * state and produce outcomes that depend on scheduling.
   */
  const runAll = async () => {
    setConfirming(false);
    const targets = (candidates.data?.content ?? []).filter(transactionReconcilable);
    if (targets.length === 0) {
      return;
    }
    setRunning(true);
    setOutcomes([]);
    const results: RunOutcome[] = [];

    for (const transaction of targets) {
      try {
        results.push(await outcomeFor(transaction));
      } catch (error) {
        results.push({
          transaction,
          status: 'REFUSED',
          failedRules: [],
          passedCount: 0,
          policyVersion: null,
          message: error instanceof Error ? error.message : 'The service refused this transaction.',
        });
      }
      setOutcomes([...results]);
    }

    setRunning(false);
    const reconciled = results.filter((outcome) => outcome.status === 'RECONCILED').length;
    const exceptions = results.filter((outcome) => outcome.status === 'EXCEPTION').length;
    const refused = results.filter((outcome) => outcome.status === 'REFUSED').length;

    if (refused === results.length) {
      notifyError(
        undefined,
        `The service refused all ${refused} transactions. See the outcomes below.`,
      );
    } else {
      notifySuccess(
        `Reconciled ${reconciled}, raised ${exceptions} exception${exceptions === 1 ? '' : 's'}${
          refused > 0 ? `, ${refused} refused` : ''
        }.`,
      );
    }
    candidates.refetch();
  };

  const runOne = async (transaction: FuelTransaction) => {
    try {
      const outcome = await outcomeFor(transaction);
      setOutcomes((current) => [
        outcome,
        ...current.filter((entry) => entry.transaction.id !== transaction.id),
      ]);
      notifySuccess(
        outcome.status === 'RECONCILED'
          ? `Reconciled. All ${outcome.passedCount} policy rules passed.`
          : `Reconciliation completed with ${outcome.failedRules.length} failed rule${outcome.failedRules.length === 1 ? '' : 's'}.`,
      );
      candidates.refetch();
    } catch (error) {
      notifyError(error);
    }
  };

  const candidateColumns = useMemo<TableColumn<FuelTransaction>[]>(
    () => [
      {
        id: 'transaction',
        header: 'Transaction',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.vendorReference} · ${row.fuelProduct}`}
            secondary={`${formatDateTime(row.occurredAt)} · ${row.sourceSystem}`}
          />
        ),
      },
      {
        id: 'quantity',
        header: 'Quantity',
        width: 120,
        align: 'right',
        cell: ({ row }) => formatQuantity(row.quantity, row.quantityUnit),
      },
      {
        id: 'cost',
        header: 'Cost',
        width: 130,
        align: 'right',
        cell: ({ row }) => formatMoney(row.totalCost, row.currency),
      },
      {
        id: 'status',
        header: 'Status',
        width: 120,
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
      {
        id: 'action',
        header: 'Run',
        width: 140,
        align: 'right',
        cell: ({ row }) => (
          <Button
            size="sm"
            variant="outline"
            disabled={running || !transactionReconcilable(row)}
            onClick={() => runOne(row)}
          >
            <Scale size={14} strokeWidth={1.5} aria-hidden />
            Reconcile
          </Button>
        ),
      },
    ],
    // `running` and `runOne` both change what the button does, so the column set is rebuilt with
    // them rather than capturing a stale closure.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [running],
  );

  const outcomeColumns = useMemo<TableColumn<RunOutcome>[]>(
    () => [
      {
        id: 'transaction',
        header: 'Transaction',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.transaction.vendorReference} · ${row.transaction.fuelProduct}`}
            secondary={formatDateTime(row.transaction.occurredAt)}
          />
        ),
      },
      {
        id: 'outcome',
        header: 'Outcome',
        width: 150,
        cell: ({ row }) =>
          row.status === 'REFUSED' ? (
            <FuelBadge value="REJECTED" label="Refused" tone="blocked" />
          ) : (
            <FuelBadge value={row.status} />
          ),
      },
      {
        id: 'rules',
        header: 'Rules',
        minWidth: 280,
        cell: ({ row }) => {
          if (row.status === 'REFUSED') {
            return <span className="text-(--clet-error-text)">{row.message}</span>;
          }
          if (row.failedRules.length === 0) {
            return (
              <span className="text-(--clet-text-secondary)">
                All {row.passedCount} rules passed
                {row.policyVersion !== null ? ` · policy version ${row.policyVersion}` : ''}
              </span>
            );
          }
          return (
            <span>
              <span className="font-medium text-(--clet-error-text)">
                {row.failedRules.map((rule) => humanise(rule)).join(', ')}
              </span>
              <span className="text-(--clet-text-secondary)">
                {' '}
                · {row.passedCount} passed
                {row.policyVersion !== null ? ` · policy version ${row.policyVersion}` : ''}
              </span>
            </span>
          );
        },
      },
      {
        id: 'link',
        header: '',
        width: 110,
        align: 'right',
        cell: ({ row }) => (
          <Button
            size="sm"
            variant="ghost"
            onClick={() => navigate(fuelPaths.transactionDetail(row.transaction.id))}
          >
            Open
          </Button>
        ),
      },
    ],
    [navigate],
  );

  const runnableRows = (candidates.data?.content ?? []).filter(transactionReconcilable);
  const runnable = runnableRows.length;
  const occurred = runnableRows.map((row) => row.occurredAt).sort();
  const policyLine =
    activePolicies.length === 1
      ? `Version ${activePolicies[0].policyVersion}, in force when each occurred`
      : `${activePolicies.length} policies in force when each occurred`;
  const ruleCount = RULE_GROUPS.reduce((total, group) => total + group.rules.length, 0);
  const reconciledCount = outcomes.filter((outcome) => outcome.status === 'RECONCILED').length;
  const exceptionCount = outcomes.filter((outcome) => outcome.status === 'EXCEPTION').length;

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Reconciliation</SectionTitle>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {/*
              Hidden for a reader, disabled for a shortfall - the two are different answers and must
              look different. Someone without FUEL_RECONCILIATION_RUN never sees the control; someone
              who holds it sees it greyed with the reason when there is nothing to run or no policy
              is in force.
            */}
            {canRunReconciliation() && (
              <Button
                variant="primary"
                loading={running}
                disabled={runnable === 0 || activePolicies.length === 0}
                onClick={() => setConfirming(true)}
              >
                <Scale size={14} strokeWidth={1.5} aria-hidden />
                {runnable === 0 ? 'Nothing to run' : `Run reconciliation`}
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      {policies.data && activePolicies.length === 0 && (
        <PageSection>
          <Banner
            variant="danger"
            heading="No active fuel policy at this site"
            subtext="Reconciliation reads the policy in force when a transaction occurred, and refuses the run outright when it cannot find one. Create a policy covering the period first."
            action={
              <Button size="sm" variant="outline" onClick={() => navigate(fuelPaths.policies)}>
                Fuel policies
              </Button>
            }
          />
        </PageSection>
      )}

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={candidates.initialising}
            label="Awaiting a run"
            value={formatNumber(candidates.data?.totalElements ?? 0)}
            description={SCOPE_LABELS[scope]}
          />
          <MetricCard
            variant="soft"
            loading={policies.initialising}
            label="Policy in force"
            value={activePolicies.length === 1 ? `Version ${activePolicies[0].policyVersion}` : formatNumber(activePolicies.length)}
            description={
              activePolicies.length === 1
                ? `In force since ${formatDate(activePolicies[0].effectiveFrom)}`
                : 'Available to judge against'
            }
            {...metricLink(() => navigate(fuelPaths.policies))}
          />
          <MetricCard
            variant="soft"
            label="Reconciled in this run"
            value={formatNumber(reconciledCount)}
            description="Passed every rule"
          />
          <MetricCard
            variant="soft"
            label="Exceptions in this run"
            value={formatNumber(exceptionCount)}
            description="Raised as anomaly cases"
            {...metricLink(() => navigate(fuelPaths.anomalies))}
          />
        </MetricCards>
      </PageSection>

      {outcomes.length > 0 && (
        <PageSection>
          <Panel
            title="Run outcomes"
            description={`${outcomes.length} transaction${outcomes.length === 1 ? '' : 's'} processed`}
            actions={
              <Button size="sm" variant="outline" onClick={() => setOutcomes([])}>
                Clear
              </Button>
            }
          >
            <Table paramPrefix="fuel-recon-outcomes" variant="soft">
              <TableContent
                variant="soft"
                columns={outcomeColumns}
                data={outcomes}
                rowKey={(row) => row.transaction.id}
              />
            </Table>
          </Panel>
        </PageSection>
      )}

      <PageSection>
        <Panel title="Transactions in scope" description="Reconcile them together, or one at a time">
          {candidates.error && (
            <ErrorBanner error={candidates.error} onRetry={candidates.refetch} className="mb-4" />
          )}
          <RegisterTable
            paramPrefix="fuel-reconciliation"
            columns={candidateColumns}
            rows={candidates.data?.content ?? []}
            rowKey={(row) => row.id}
            loading={candidates.loading}
            empty={{
              title: 'Nothing in scope',
              description: `No transaction at ${siteCode} is ${SCOPE_LABELS[scope].toLowerCase()}.`,
              filteredTitle: 'Nothing in scope',
            }}
            filtersApplied={false}
            totalPages={candidates.data?.totalPages ?? 0}
            totalItems={candidates.data?.totalElements ?? 0}
            pageSize={paging.size}
            spreadFilters
            filters={
              <Dropdown
                name="scope"
                aria-label="Scope: which transactions this run will cover"
                value={scope}
                onValueChange={(next) => setScope((next ?? 'RECEIVED') as Scope)}
                options={SCOPES.map((value) => ({ value, label: SCOPE_LABELS[value] }))}
                placeholder="Scope"
              />
            }
          />
        </Panel>
      </PageSection>

      <PageSection>
        <Panel title="The rules a run applies" description={`${ruleCount} rules in ${RULE_GROUPS.length} groups, read from the policy in force when each transaction occurred`}>
          <Tabs
            variant="pill"
            value={guidanceTab}
            onValueChange={(value) => setGuidanceTab(value as 'rules' | 'policies')}
          >
            <TabsList>
              <TabsTrigger value="rules">Rules ({RECONCILIATION_RULES.length})</TabsTrigger>
              <TabsTrigger value="policies">Policies in force ({activePolicies.length})</TabsTrigger>
            </TabsList>

            <TabsContent value="rules">
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                {RULE_GROUPS.map((group) => (
                  <section
                    key={group.title}
                    className="rounded-lg bg-(--clet-surface-subtle) px-4 py-3"
                  >
                    <div className="flex items-baseline justify-between gap-3">
                      <h4 className="text-sm font-semibold">{group.title}</h4>
                      <span className="text-xs text-(--clet-text-secondary)">
                        {group.rules.length} rules
                      </span>
                    </div>
                    <p className="mt-1 text-sm">{group.summary}</p>
                    <ul className="mt-2 space-y-0.5 text-xs text-(--clet-text-secondary)">
                      {group.rules.map((rule) => (
                        <li key={rule}>
                          <span className="font-medium">{humanise(rule)}</span>
                          {' - '}
                          {RULE_DESCRIPTIONS[rule]}
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}
              </div>
              <p className="mt-3 text-xs text-(--clet-text-secondary)">
                Transcribed from the service’s reconciliation routine. Three of them only run when
                the policy supplies the relevant limit, and two only when a previous transaction
                exists for the vehicle. Which ones actually ran is recorded against each transaction
                and shown on its detail screen.
              </p>
            </TabsContent>

            <TabsContent value="policies">
              {policies.error && <ErrorBanner error={policies.error} onRetry={policies.refetch} />}
              {activePolicies.length === 0 && !policies.initialising ? (
                <EmptyState
                  title="No policy in force"
                  description="Reconciliation cannot run without one."
                />
              ) : (
                <ul className="mt-4 space-y-3">
                  {activePolicies.map((policy: FuelPolicy) => (
                    <li
                      key={policy.id}
                      className="rounded-lg border border-(--clet-border-subtle) px-4 py-3"
                    >
                      <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <p className="text-sm font-semibold">
                          {policy.name}
                          <span className="ml-1.5 font-normal text-(--clet-text-secondary)">
                            version {policy.policyVersion}
                          </span>
                        </p>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => navigate(fuelPaths.policyDetail(policy.id))}
                        >
                          Open
                        </Button>
                      </div>
                      <p className="mt-0.5 text-xs text-(--clet-text-secondary)">
                        From {formatDate(policy.effectiveFrom)}
                        {policy.effectiveTo
                          ? ` to ${formatDate(policy.effectiveTo)}`
                          : ', with no end date'}{' '}
                        · max {policy.maxPerTransaction} per transaction · SLA{' '}
                        {policy.anomalySlaHours} hours
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </TabsContent>
          </Tabs>
        </Panel>
      </PageSection>

      <Dialog open={confirming} onOpenChange={setConfirming}>
        <DialogPortal>
          <DialogOverlay />
          <DialogContent showCloseButton>
            <DialogTitle>Run reconciliation now?</DialogTitle>
            <DialogDescription>
              {formatNumber(candidates.data?.totalElements ?? 0)} transactions{' '}
              {SCOPE_LABELS[scope].toLowerCase()}
            </DialogDescription>
            <dl className="my-4 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5 rounded-lg bg-(--clet-surface-subtle) px-4 py-3 text-sm">
              <dt className="font-semibold">This run</dt>
              <dd />
              <dt className="text-(--clet-info-text)">Transactions</dt>
              <dd className="text-right">
                {runnable} on this page
                {occurred.length > 0
                  ? `, ${formatDate(occurred[0])} to ${formatDate(occurred[occurred.length - 1])}`
                  : ''}
              </dd>
              <dt className="text-(--clet-info-text)">Policy applied</dt>
              <dd className="text-right">{policyLine}</dd>
              <dt className="text-(--clet-info-text)">Rules</dt>
              <dd className="text-right">
                {ruleCount} in {RULE_GROUPS.length} groups
              </dd>
            </dl>
            <p className="mb-4 text-sm">
              Anything that fails a rule opens an anomaly case. Nothing already reconciled is run
              again.
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setConfirming(false)}>
                Cancel
              </Button>
              <Button variant="primary" onClick={runAll}>
                Run {runnable} transactions
              </Button>
            </div>
          </DialogContent>
        </DialogPortal>
      </Dialog>
    </>
  );
};

export default FuelReconciliationPage;

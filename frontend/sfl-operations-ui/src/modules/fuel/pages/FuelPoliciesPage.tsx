import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { FuelPolicy } from 'modules/fuel/api/dto';
import { fuelPoliciesApi } from 'modules/fuel/api/fuelApi';
import { CreatePolicyDialog } from 'modules/fuel/dialogs/policyDialogs';
import { useClampPage, useServerPage } from 'shared/hooks/useServerPage';
import PostedPricePanel from 'modules/fuel/components/PostedPricePanel';
import { siteOf } from 'modules/fuel/components/fuelFormat';
import Alert from 'shared/components/Alert';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import DataTable, { CellStack, Column } from 'shared/components/DataTable';
import FilterBar from 'shared/components/FilterBar';
import { useNotifier } from 'shared/components/Notifier';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { FieldLabelSpacer } from 'shared/components/fields';
import StatusChip from 'shared/components/StatusChip';
import { formatDate, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { canManageFuelPolicies } from 'modules/fleet/api/access';

/** A policy covers `now` when it is ACTIVE and now falls inside its effective period. */
const inForce = (policy: FuelPolicy, at = Date.now()): boolean =>
  policy.status === 'ACTIVE' &&
  new Date(policy.effectiveFrom).getTime() <= at &&
  (!policy.effectiveTo || new Date(policy.effectiveTo).getTime() > at);

/**
 * The fuel policy register.
 *
 * `GET /policies?siteCode=` returns every policy for a site, unpaged and unfiltered - it is the one
 * fuel collection that takes no `size` at all - so this register holds the whole set and the effective
 * period is the thing worth reading. A policy is what reconciliation resolves against a transaction's
 * own timestamp, so "which one is in force right now" is called out rather than left to be worked
 * out from two dates.
 */
const FuelPoliciesPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [activeOnly, setActiveOnly] = useState(false);
  const [creating, setCreating] = useState(false);

  const filterKey = `${siteCode}|${activeOnly}`;
  const paging = useServerPage(filterKey);

  const query = useApiQuery(
    (signal) =>
      fuelPoliciesApi.search(
        { siteCode, status: activeOnly ? 'ACTIVE' : undefined, page: paging.page, size: paging.size },
        signal,
      ),
    [filterKey, paging.page, paging.size],
  );

  useClampPage(paging.page, query.data?.totalPages, paging.setPage);

  const policies = useMemo(() => query.data?.content ?? [], [query.data]);

  /**
   * The policies in force right now, asked of the service.
   *
   * `inForceOnly` is an interval test the dashboard cannot do correctly over a page: a policy that
   * covers today may sit on any page of the register.
   */
  const inForceNow = useApiQuery(
    (signal) => fuelPoliciesApi.search({ siteCode, inForceOnly: true, size: 50 }, signal),
    [siteCode],
  );

  const currentlyInForce = useMemo(() => inForceNow.data?.content ?? [], [inForceNow.data]);

  const columns = useMemo<Column<FuelPolicy>[]>(
    () => [
      {
        key: 'policy',
        header: 'Policy',
        width: 260,
        cell: (row) => (
          <CellStack
            primary={`${row.name} · version ${row.policyVersion}`}
            secondary={
              inForce(row)
                ? 'In force now'
                : row.status === 'ACTIVE'
                  ? 'Active, outside its period'
                  : `Status ${row.status.toLowerCase()}`
            }
          />
        ),
      },
      {
        key: 'period',
        header: 'Effective period',
        width: 220,
        cell: (row) => (
          <CellStack
            primary={formatDate(row.effectiveFrom)}
            secondary={row.effectiveTo ? `to ${formatDate(row.effectiveTo)}` : 'no end date'}
          />
        ),
      },
      {
        key: 'max',
        header: 'Max per transaction',
        width: 150,
        align: 'right',
        cell: (row) => formatNumber(row.maxPerTransaction),
      },
      {
        key: 'rolling',
        header: 'Rolling limits',
        width: 180,
        hideBelowLg: true,
        cell: (row) => (
          <CellStack
            primary={`Daily ${row.dailyLimit === null ? 'not set' : formatNumber(row.dailyLimit)}`}
            secondary={`Monthly ${row.monthlyLimit === null ? 'not set' : formatNumber(row.monthlyLimit)}`}
          />
        ),
      },
      {
        key: 'pattern',
        header: 'Pattern threshold',
        width: 170,
        hideBelowLg: true,
        cell: (row) => (
          <CellStack
            primary={`${row.repeatedPatternThreshold} cases`}
            secondary={`${row.repeatedPatternWindowHours}h window`}
          />
        ),
      },
      {
        key: 'sla',
        header: 'Anomaly SLA',
        width: 120,
        align: 'right',
        hideBelowLg: true,
        cell: (row) => `${row.anomalySlaHours} hrs`,
      },
      {
        key: 'receipt',
        header: 'Receipt',
        width: 140,
        hideBelowLg: true,
        cell: (row) =>
          row.receiptRequired ? (
            <StatusChip
              value="ACTIVE"
              label={`Required · ${row.receiptGraceHours}h grace`}
              tone="active"
            />
          ) : (
            <StatusChip value="INACTIVE" label="Not required" tone="neutral" />
          ),
      },
      {
        key: 'status',
        header: 'Status',
        width: 150,
        align: 'right',
        cell: (row) => <StatusChip value={row.status} />,
      },
    ],
    [],
  );

  return (
    <div>
      <PageHeader
        title="Fuel policies"
        subtitle="The effective-dated limits every reconciliation is read from."
        crumbs={[{ label: 'Fuel', to: fuelPaths.dashboard }, { label: 'Fuel policies' }]}
        actions={
          // A policy is the rule set every reconciliation is judged against; writing one is a fleet
          // manager's act, not a reader's.
          canManageFuelPolicies() ? (
            <Button variant="primary" startIcon="plus" onClick={() => setCreating(true)}>
              Create policy
            </Button>
          ) : undefined
        }
      />

      <SectionCard flush>
        <FilterBar onReset={() => setActiveOnly(false)} resetDisabled={!activeOnly}>
          <SiteSelect value={siteCode} onChange={setSiteCode} required />
          {/*
            `items-end pb-1` here was compensating for the bar aligning at the bottom - it nudged a
            label-less button up onto the control line by hand. The bar aligns at the top now, so
            the reserved label line does the same job without a tuned padding that only held for
            one field height.
          */}
          <div>
            <FieldLabelSpacer />
            <Button
              variant={activeOnly ? 'primary' : 'outline'}
              startIcon="filter"
              onClick={() => setActiveOnly((current) => !current)}
            >
              Active only
            </Button>
          </div>
        </FilterBar>
      </SectionCard>

      <div className="mt-5 space-y-5">
        {query.data && currentlyInForce.length === 0 && (
          <Alert variant="warning" title="No policy is in force at this site right now">
            Reconciliation resolves the policy covering a transaction’s own timestamp and refuses the
            run when it finds none. Transactions can still be captured; they cannot be reconciled.
          </Alert>
        )}

        <SectionCard flush>
          <DataState
            loading={query.initialising}
            error={query.error}
            empty={policies.length === 0}
            emptyTitle="No fuel policy at this site"
            emptyHint="Create one before capturing transactions, or reconciliation will have nothing to judge them against."
            onRetry={query.refetch}
            minHeight={280}
          >
            <DataTable
              rows={policies}
              columns={columns}
              getRowId={(row) => row.id}
              loading={query.loading}
              onRowClick={(row) => navigate(fuelPaths.policyDetail(row.id))}
              caption="Fuel policies at this site, with their effective period, per-transaction limit, anomaly SLA, receipt rule and status."
              page={query.data?.page ?? paging.page}
              pageSize={query.data?.size ?? paging.size}
              totalElements={query.data?.totalElements ?? 0}
              onPageChange={paging.setPage}
              onPageSizeChange={paging.setSize}
            />
          </DataState>
        </SectionCard>

        {/*
          Beside the policies rather than on a screen of its own: a posted price is a rule set by the
          same person under the same permission, and the two are read together when a price deviation
          case is being judged.
        */}
        <PostedPricePanel siteCode={siteCode} />

        {currentlyInForce.length > 0 && (
          <SectionCard
            title="In force right now"
            subtitle="What a reconciliation run today would read"
          >
            <ul className="space-y-2.5">
              {currentlyInForce.map((policy) => (
                <li key={policy.id} className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="text-theme-sm text-gray-900">
                    <span className="font-semibold">{policy.name}</span>
                    <span className="text-gray-600">
                      {' '}
                      · version {policy.policyVersion} · {siteOf(policy.siteCode)}
                    </span>
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    endIcon="chevron-right"
                    onClick={() => navigate(fuelPaths.policyDetail(policy.id))}
                  >
                    Open
                  </Button>
                </li>
              ))}
            </ul>
          </SectionCard>
        )}
      </div>

      {creating && (
        <CreatePolicyDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreating(false)}
          onSaved={(policy) => {
            notifySuccess(
              `${policy.name} created as an active policy.`,
              `Version ${policy.policyVersion}, effective from ${formatDate(policy.effectiveFrom)}.`,
            );
            query.refetch();
            inForceNow.refetch();
          }}
        />
      )}
    </div>
  );
};

export default FuelPoliciesPage;

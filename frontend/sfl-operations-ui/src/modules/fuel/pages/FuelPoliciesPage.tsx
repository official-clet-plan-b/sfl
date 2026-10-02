import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { FuelPolicy } from 'modules/fuel/api/dto';
import { fuelPoliciesApi } from 'modules/fuel/api/fuelApi';
import { CreatePolicyDialog } from 'modules/fuel/dialogs/policyDialogs';
import { useClampPage, useServerPage } from 'shared/hooks/useServerPage';
import PostedPricePanel from 'modules/fuel/components/PostedPricePanel';
import { siteOf } from 'modules/fuel/components/fuelFormat';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { canManageFuelPolicies } from 'modules/fleet/api/access';
import { Banner, Button, type TableColumn } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import { FuelBadge, Panel, RegisterTable, CellStack } from 'modules/fuel/components/fuelUi';

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

  const columns = useMemo<TableColumn<FuelPolicy>[]>(
    () => [
      {
        id: 'policy',
        header: 'Policy',
        width: 260,
        cell: ({ row }) => (
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
        id: 'period',
        header: 'Effective period',
        width: 220,
        cell: ({ row }) => (
          <CellStack
            primary={formatDate(row.effectiveFrom)}
            secondary={row.effectiveTo ? `to ${formatDate(row.effectiveTo)}` : 'no end date'}
          />
        ),
      },
      {
        id: 'max',
        header: 'Max per transaction',
        width: 150,
        align: 'right',
        cell: ({ row }) => formatNumber(row.maxPerTransaction),
      },
      {
        id: 'rolling',
        header: 'Rolling limits',
        width: 180,
        cell: ({ row }) => (
          <CellStack
            primary={`Daily ${row.dailyLimit === null ? 'not set' : formatNumber(row.dailyLimit)}`}
            secondary={`Monthly ${row.monthlyLimit === null ? 'not set' : formatNumber(row.monthlyLimit)}`}
          />
        ),
      },
      {
        id: 'pattern',
        header: 'Pattern threshold',
        width: 170,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.repeatedPatternThreshold} cases`}
            secondary={`${row.repeatedPatternWindowHours}h window`}
          />
        ),
      },
      {
        id: 'sla',
        header: 'Anomaly SLA',
        width: 120,
        align: 'right',
        cell: ({ row }) => `${row.anomalySlaHours} hrs`,
      },
      {
        id: 'receipt',
        header: 'Receipt',
        width: 140,
        cell: ({ row }) =>
          row.receiptRequired ? (
            <FuelBadge
              value="ACTIVE"
              label={`Required · ${row.receiptGraceHours}h grace`}
              tone="active"
            />
          ) : (
            <FuelBadge value="INACTIVE" label="Not required" tone="neutral" />
          ),
      },
      {
        id: 'status',
        header: 'Status',
        width: 150,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
    ],
    [],
  );

  return (
    <div>
      <PageHeading
        title="Fuel policies"
        subtitle="The effective-dated limits every reconciliation is read from."
        crumbs={[{ label: 'Fuel', to: fuelPaths.dashboard }, { label: 'Fuel policies' }]}
        actions={
          // A policy is the rule set every reconciliation is judged against; writing one is a fleet
          // manager's act, not a reader's.
          canManageFuelPolicies() ? (
            <Button variant="primary" onClick={() => setCreating(true)}>
              Create policy
            </Button>
          ) : undefined
        }
      />

      <Panel title="Policy register filters">
        <div className="flex flex-wrap items-end gap-3">
          <SiteSelect value={siteCode} onChange={setSiteCode} required />
          {/*
            `items-end pb-1` here was compensating for the bar aligning at the bottom - it nudged a
            label-less button up onto the control line by hand. The bar aligns at the top now, so
            the reserved label line does the same job without a tuned padding that only held for
            one field height.
          */}
            <Button
              variant={activeOnly ? 'primary' : 'outline'}
              onClick={() => setActiveOnly((current) => !current)}
            >
              Active only
            </Button>
        </div>
      </Panel>

      <div className="mt-5 space-y-5">
        {query.data && currentlyInForce.length === 0 && (
          <Banner variant="warning" heading="No policy is in force at this site right now" subtext="Reconciliation resolves the policy covering a transaction’s own timestamp and refuses the run when it finds none. Transactions can still be captured; they cannot be reconciled." />
        )}

        <Panel title="Policy register">
          <DataState
            loading={query.initialising}
            error={query.error}
            empty={policies.length === 0}
            emptyTitle="No fuel policy at this site"
            emptyHint="Create one before capturing transactions, or reconciliation will have nothing to judge them against."
            onRetry={query.refetch}
            minHeight={280}
          >
            <RegisterTable
              paramPrefix="fuel-policies"
              rows={policies}
              columns={columns}
              rowKey={(row) => row.id}
              loading={query.loading}
              onRowClick={(row) => navigate(fuelPaths.policyDetail(row.id))}
              empty={{ title: 'No fuel policy at this site', description: 'Create one before capturing transactions.', filteredTitle: 'No fuel policies match the filter' }}
              filtersApplied={activeOnly}
              totalPages={query.data?.totalPages ?? 0}
              totalItems={query.data?.totalElements ?? 0}
              pageSize={query.data?.size ?? paging.size}
            />
          </DataState>
        </Panel>

        {/*
          Beside the policies rather than on a screen of its own: a posted price is a rule set by the
          same person under the same permission, and the two are read together when a price deviation
          case is being judged.
        */}
        <PostedPricePanel siteCode={siteCode} />

        {currentlyInForce.length > 0 && (
          <Panel
            title="In force right now"
            description="What a reconciliation run today would read"
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
                    onClick={() => navigate(fuelPaths.policyDetail(policy.id))}
                  >
                    Open
                  </Button>
                </li>
              ))}
            </ul>
          </Panel>
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

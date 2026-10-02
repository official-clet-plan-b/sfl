import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { FuelPolicy } from 'modules/fuel/api/dto';
import { fuelPoliciesApi } from 'modules/fuel/api/fuelApi';
import { EditPolicyDialog, WithdrawPolicyDialog } from 'modules/fuel/dialogs/policyDialogs';
import HistoryTimeline from 'modules/fuel/components/HistoryTimeline';
import { canManageFuelPolicies } from 'modules/fleet/api/access';
import { siteOf } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { Banner, Button } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import { FuelBadge, Panel } from 'modules/fuel/components/fuelUi';

const inForce = (policy: FuelPolicy, at = Date.now()): boolean =>
  policy.status === 'ACTIVE' &&
  new Date(policy.effectiveFrom).getTime() <= at &&
  (!policy.effectiveTo || new Date(policy.effectiveTo).getTime() > at);

/**
 * A fuel policy in full.
 *
 * Read by id. That was not possible when this screen was first built - there was no
 * `GET /policies/{id}`, so the page walked the actor's sites listing policies until it found one,
 * and a deep link into a policy at a site the picker had not selected was a dead end. A policy
 * outside the actor's scope now answers with the service's own not-found or authorisation error.
 */
const FuelPolicyDetailPage = () => {
  const { policyId = '' } = useParams();
  const navigate = useNavigate();
  const [editing, setEditing] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);

  const lookup = useApiQuery(
    (signal) => fuelPoliciesApi.findById(policyId, signal),
    [policyId],
  );

  const history = useApiQuery(
    (signal) => fuelPoliciesApi.history(policyId, signal),
    [policyId],
  );

  const policy = lookup.data;

  return (
    <div>
      <PageHeading
        title={policy ? policy.name : 'Fuel policy'}
        subtitle={policy ? `Version ${policy.policyVersion} · ${siteOf(policy.siteCode)}` : undefined}
        crumbs={[
          { label: 'Fuel', to: fuelPaths.dashboard },
          { label: 'Fuel policies', to: fuelPaths.policies },
          { label: policy?.name ?? '…' },
        ]}
        actions={
          <>
            {/*
              Both gated on the same grant the service checks. Withdraw is hidden rather than
              disabled once a policy is archived, because it would do nothing - the service returns
              the record unchanged - and a control that is a no-op reads as one that is broken.
            */}
            {policy && canManageFuelPolicies() && (
              <>
                <Button variant="outline" onClick={() => setEditing(true)}>
                  Edit
                </Button>
                {policy.status !== 'ARCHIVED' && (
                  <Button variant="outline" onClick={() => setWithdrawing(true)}>
                    Withdraw
                  </Button>
                )}
              </>
            )}
            <Button
              variant="outline"
              onClick={() => navigate(fuelPaths.policies)}
            >
              Register
            </Button>
          </>
        }
        meta={
          policy && (
            <div className="flex flex-wrap items-center gap-2">
              <FuelBadge value={policy.status} />
              {inForce(policy) ? (
                <FuelBadge value="ACTIVE" label="In force now" tone="ready" />
              ) : (
                <FuelBadge value="INACTIVE" label="Outside its period" tone="neutral" />
              )}
            </div>
          )
        }
      />

      <DataState
        loading={lookup.initialising}
        error={lookup.error}
        onRetry={lookup.refetch}
        minHeight={300}
      >
        {policy && (
          <div className="space-y-5">
            <div className="grid gap-5 xl:grid-cols-[1.4fr_1fr]">
              <div className="space-y-5">
                <Panel title="Effective period" description="What reconciliation resolves against">
                  <KeyValueGrid
                    columns={2}
                    items={[
                      { label: 'Site', value: siteOf(policy.siteCode) },
                      { label: 'Policy version', value: policy.policyVersion },
                      { label: 'Effective from', value: formatDateTime(policy.effectiveFrom) },
                      {
                        label: 'Effective to',
                        value: policy.effectiveTo
                          ? formatDateTime(policy.effectiveTo)
                          : 'Open ended',
                      },
                      { label: 'Status', value: humanise(policy.status) },
                      {
                        label: 'In force now',
                        value: inForce(policy) ? 'Yes' : 'No',
                      },
                    ]}
                  />
                </Panel>

                <Panel title="Limits" description="What the reconciliation rules read">
                  <KeyValueGrid
                    items={[
                      {
                        label: 'Maximum per transaction',
                        value: formatNumber(policy.maxPerTransaction),
                      },
                      {
                        label: 'Tank capacity',
                        value:
                          policy.tankCapacity === null
                            ? 'Not set - rule skipped'
                            : formatNumber(policy.tankCapacity),
                      },
                      {
                        label: 'Odometer jump tolerance',
                        value: `${formatNumber(policy.odometerJumpTolerance)} km`,
                      },
                      {
                        label: 'Minimum consumption',
                        value:
                          policy.minConsumption === null
                            ? 'Not set'
                            : formatNumber(policy.minConsumption),
                      },
                      {
                        label: 'Maximum consumption',
                        value:
                          policy.maxConsumption === null
                            ? 'Not set'
                            : formatNumber(policy.maxConsumption),
                      },
                      {
                        label: 'Daily limit',
                        value: policy.dailyLimit === null ? 'Not set' : formatNumber(policy.dailyLimit),
                      },
                      {
                        label: 'Monthly limit',
                        value:
                          policy.monthlyLimit === null
                            ? 'Not set'
                            : formatNumber(policy.monthlyLimit),
                      },
                      {
                        label: 'Cost variance tolerance',
                        value: `${formatNumber(policy.costVarianceTolerance * 100)}%`,
                      },
                      {
                        label: 'Repeated-pattern window',
                        value: `${formatNumber(policy.repeatedPatternWindowHours)} hours`,
                      },
                      {
                        label: 'Repeated-pattern threshold',
                        value: `${formatNumber(policy.repeatedPatternThreshold)} anomalies`,
                      },
                      {
                        label: 'Materiality amount',
                        value: formatNumber(policy.materialityAmount),
                      },
                      { label: 'Anomaly SLA', value: `${policy.anomalySlaHours} hours` },
                    ]}
                  />
                  <p className="mt-3 text-theme-xs text-gray-600">
                    The consumption rule only runs when both bounds are set and a previous
                    transaction exists for the vehicle. Daily and monthly limits are evaluated for
                    the vehicle, driver and fuel card, and the cost-variance and repeated-pattern
                    checks are versioned policy values recorded with each reconciliation.
                  </p>
                </Panel>

                <Panel title="Allowed products and vendors">
                  <div className="grid gap-5 sm:grid-cols-2">
                    <div>
                      <p className="text-theme-xs font-semibold text-gray-600">Fuel products</p>
                      {policy.allowedFuelProducts.length === 0 ? (
                        <p className="mt-1 text-theme-sm text-gray-700">
                          None listed - any product is allowed.
                        </p>
                      ) : (
                        <ul className="mt-1.5 flex flex-wrap gap-1.5">
                          {policy.allowedFuelProducts.map((product) => (
                            <li key={product}>
                              <FuelBadge value={product} label={product} tone="neutral" />
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                    <div>
                      <p className="text-theme-xs font-semibold text-gray-600">Approved vendors</p>
                      {policy.approvedVendors.length === 0 ? (
                        <p className="mt-1 text-theme-sm text-gray-700">
                          None listed - any vendor is allowed.
                        </p>
                      ) : (
                        <ul className="mt-1.5 flex flex-wrap gap-1.5">
                          {policy.approvedVendors.map((vendor) => (
                            <li key={vendor}>
                              <FuelBadge value={vendor} label={vendor} tone="neutral" />
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  </div>
                </Panel>
              </div>

              <div className="space-y-5">
                <Panel title="Receipts">
                  <KeyValueGrid
                    columns={2}
                    items={[
                      {
                        label: 'Receipt required',
                        value: policy.receiptRequired ? 'Yes' : 'No',
                      },
                      {
                        label: 'Grace period',
                        value: `${policy.receiptGraceHours} hours`,
                      },
                    ]}
                  />
                  <p className="mt-3 text-theme-sm text-gray-700">
                    {policy.receiptRequired
                      ? `A transaction with no receipt passes reconciliation while it is within ${policy.receiptGraceHours} hours of occurring. After that, the scheduled sweep reconciles it again and raises a missing-receipt case.`
                      : 'Reconciliation does not check for a receipt under this policy.'}
                  </p>
                </Panel>

                <Panel title="History" description="Recorded changes, from the audit log">
                  <DataState
                    loading={history.initialising}
                    error={history.error}
                    onRetry={history.refetch}
                    minHeight={140}
                  >
                    <HistoryTimeline events={history.data} recordNoun="policy" />
                  </DataState>
                </Panel>

                <Panel title="Changing this policy">
                  <Banner
                    variant="info"
                    heading="Revisions keep past judgements intact"
                    subtext={<>Every reconciliation run records the policy version it applied, so editing the
                    limits here does not change how anything was judged before. Withdrawing moves
                    the policy to archived rather than deleting it - the runs that cited it still
                    point at it - and releases its period so a replacement can cover the same dates.</>}
                  />
                </Panel>
              </div>
            </div>
          </div>
        )}
      </DataState>

      {/* Mounted only while open, so a cancelled edit cannot reappear prefilled in the next one. */}
      {policy && editing && (
        <EditPolicyDialog
          open
          policy={policy}
          onClose={() => setEditing(false)}
          onSaved={() => {
            lookup.refetch();
            history.refetch();
          }}
        />
      )}

      {policy && withdrawing && (
        <WithdrawPolicyDialog
          open
          policy={policy}
          onClose={() => setWithdrawing(false)}
          onSaved={() => {
            lookup.refetch();
            history.refetch();
          }}
        />
      )}
    </div>
  );
};

export default FuelPolicyDetailPage;

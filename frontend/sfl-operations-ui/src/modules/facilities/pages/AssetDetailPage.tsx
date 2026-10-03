import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { AlertTriangle, Clock, ShieldCheck, Wrench } from 'lucide-react';
import {
  Banner,
  Button,
  Card,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  MetricCard,
  MetricCards,
  useBreadcrumbs,
} from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import { changeAssetStatus, getAsset, getSpace, relocateAsset, updateAsset } from '../api/facilitiesApi';
import {
  canManageAssets,
  changeAssetStatusAction,
  editAssetControl,
  relocateAssetControl,
} from '../api/workflow';
import { EditRowAction, MoveRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import TitledSection from '../components/TitledSection';
import AssetStatusDialog from '../dialogs/AssetStatusDialog';
import { EditAssetDialog, RelocateAssetDialog } from '../dialogs/assetDialogs';
import {
  figureToneClass,
  formatDate,
  formatDateTime,
  humaniseCode,
  orDash,
  readinessTone,
  relativeTime,
} from '../components/facilitiesFormat';

/**
 * One facility asset.
 *
 * The screen leads with the space it serves, because that is the consequence an operator is actually
 * managing: an asset's condition matters here only insofar as it decides whether a hall can be used.
 * An asset attached to no space says so plainly rather than leaving a blank.
 */
const AssetDetailPage = () => {
  const { assetId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [changingStatus, setChangingStatus] = useState(false);
  const [editing, setEditing] = useState(false);
  const [moving, setMoving] = useState(false);

  const asset = useApiQuery((signal) => getAsset(assetId, signal), [assetId]);
  const space = useApiQuery(
    (signal) => (asset.data?.roomId ? getSpace(asset.data.roomId, signal) : Promise.resolve(null)),
    [asset.data?.roomId],
  );

  const statusAction = asset.data ? changeAssetStatusAction(asset.data) : { allowed: false };
  const today = new Date();
  const overdue =
    asset.data?.serviceDueOn !== null &&
    asset.data?.serviceDueOn !== undefined &&
    new Date(asset.data.serviceDueOn) < today;

  useBreadcrumbs([
    { label: 'Facilities', href: facilitiesPaths.dashboard },
    { label: 'Assets', href: facilitiesPaths.assets },
    { label: asset.data?.assetCode ?? 'Asset' },
  ]);

  return (
    <>
      <DataState
        loading={asset.loading}
        error={asset.error}
        empty={!asset.data}
        onRetry={asset.refetch}
        minHeight={280}
      >
        {asset.data && (
          <>
            <PageSection>
              <SectionHeader>
                <SectionTitle>{asset.data.name}</SectionTitle>
                <SectionDescription>
                  {`${asset.data.assetCode} · ${humaniseCode(asset.data.category)} · ${asset.data.siteCode}`}
                </SectionDescription>
                <SectionActions className="items-end [&_button]:whitespace-nowrap">
                  {/*
                    Hidden for a permission, disabled for a state - the rule S153 paid for, which this
                    control was collapsing into one. `changeAssetStatusAction` answers false for both
                    reasons, so `disabled` alone left somebody who may never change an asset's
                    condition staring at a greyed button with a reason they cannot act on. The grant
                    decides whether the control exists; the action still decides whether it is live.
                  */}
                  <EditRowAction
                    size="md"
                    state={editAssetControl(asset.data)}
                    onClick={() => setEditing(true)}
                    label={`Edit ${asset.data.assetCode}`}
                  />
                  <MoveRowAction
                    size="md"
                    state={relocateAssetControl(asset.data)}
                    onClick={() => setMoving(true)}
                    label={`Move ${asset.data.assetCode}`}
                  />
                  {canManageAssets() && (
                    <Button
                      variant="primary"
                      disabled={!statusAction.allowed}
                      title={statusAction.reason}
                      onClick={() => setChangingStatus(true)}
                    >
                      <Wrench size={14} strokeWidth={1.5} aria-hidden="true" />
                      Change condition
                    </Button>
                  )}
                </SectionActions>
              </SectionHeader>
            </PageSection>

            {asset.data.impairsReadiness && (
              <PageSection>
                <Banner
                  variant="danger"
                  heading="This asset is impairing a space"
                  subtext={`${asset.data.assetCode} is ${humaniseCode(asset.data.operationalStatus).toLowerCase()} and is raising a readiness blocker${space.data ? ` on ${space.data.roomCode} - ${space.data.name}` : ''}. Returning it to service resolves that blocker.`}
                />
              </PageSection>
            )}

            <PageSection>
              <MetricCards>
                <MetricCard
                  variant="soft"
                  label="Condition"
                  value={humaniseCode(asset.data.operationalStatus)}
                  description={
                    asset.data.statusChangedAt
                      ? `Changed ${relativeTime(asset.data.statusChangedAt)}`
                      : 'Never changed'
                  }
                  descriptionAdornment={
                    <Wrench
                      size={16}
                      strokeWidth={2}
                      className={
                        figureToneClass[
                          asset.data.operationalStatus === 'OPERATIONAL'
                            ? 'good'
                            : asset.data.operationalStatus === 'OUT_OF_SERVICE'
                              ? 'critical'
                              : asset.data.operationalStatus === 'DECOMMISSIONED'
                                ? 'neutral'
                                : 'caution'
                        ]
                      }
                      aria-hidden
                    />
                  }
                />
                <MetricCard
                  variant="soft"
                  label="Criticality"
                  value={humaniseCode(asset.data.criticality)}
                  description="Sets the severity of any blocker it raises"
                  descriptionAdornment={
                    <AlertTriangle
                      size={16}
                      strokeWidth={2}
                      className={
                        figureToneClass[
                          asset.data.criticality === 'CRITICAL'
                            ? 'critical'
                            : asset.data.criticality === 'HIGH'
                              ? 'caution'
                              : 'neutral'
                        ]
                      }
                      aria-hidden
                    />
                  }
                />
                <MetricCard
                  variant="soft"
                  label="Service due"
                  value={formatDate(asset.data.serviceDueOn)}
                  description={
                    asset.data.serviceIntervalDays
                      ? `Every ${asset.data.serviceIntervalDays} days`
                      : 'Not on a service schedule'
                  }
                  descriptionAdornment={
                    <Clock
                      size={16}
                      strokeWidth={2}
                      className={figureToneClass[overdue ? 'critical' : 'neutral']}
                      aria-hidden
                    />
                  }
                />
                <MetricCard
                  variant="soft"
                  label="Warranty"
                  value={formatDate(asset.data.warrantyExpiresOn)}
                  description={asset.data.manufacturer ?? 'No manufacturer recorded'}
                  descriptionAdornment={
                    <ShieldCheck
                      size={16}
                      strokeWidth={2}
                      className={figureToneClass.neutral}
                      aria-hidden
                    />
                  }
                />
              </MetricCards>
            </PageSection>

            <TitledSection
              title="Location"
              description="The space whose readiness this asset's condition feeds"
              actions={
                space.data ? (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => navigate(facilitiesPaths.spaceDetail(space.data!.id))}
                  >
                    Open space
                  </Button>
                ) : undefined
              }
            >
              {space.data ? (
                <Card bordered>
                  <KeyValueGrid
                    items={[
                      { label: 'Space', value: `${space.data.roomCode} - ${space.data.name}` },
                      {
                        label: 'Space readiness',
                        value: (
                          <StatusBadge
                            value={space.data.readinessStatus}
                            tone={readinessTone(space.data.readinessStatus)}
                          />
                        ),
                      },
                      { label: 'Location code', value: orDash(asset.data.locationCode) },
                    ]}
                  />
                </Card>
              ) : (
                <Banner
                  variant="info"
                  heading="This asset is not attached to a space, so its condition raises no readiness blocker."
                  subtext="Relocate it to a space if it should."
                />
              )}
            </TitledSection>

            <TitledSection title="Asset record">
              <Card bordered>
                <KeyValueGrid
                  items={[
                    { label: 'Code', value: asset.data.assetCode },
                    { label: 'Category', value: humaniseCode(asset.data.category) },
                    { label: 'Manufacturer', value: orDash(asset.data.manufacturer) },
                    { label: 'Model', value: orDash(asset.data.modelNumber) },
                    { label: 'Serial', value: orDash(asset.data.serialNumber) },
                    { label: 'Custodian', value: orDash(asset.data.custodian) },
                    { label: 'Installed', value: formatDate(asset.data.installedOn) },
                    { label: 'Last serviced', value: formatDate(asset.data.lastServicedOn) },
                    { label: 'Lifecycle', value: humaniseCode(asset.data.lifecycleStatus) },
                    { label: 'Status notes', value: orDash(asset.data.statusNotes), span: 2 },
                    {
                      label: 'Asset visibility reference',
                      value: orDash(asset.data.assetReferenceId),
                    },
                    {
                      label: 'Last changed',
                      value: `${formatDateTime(asset.data.metadata.lastModifiedAt)} by ${asset.data.metadata.lastModifiedBy}`,
                    },
                  ]}
                />
              </Card>
            </TitledSection>
          </>
        )}
      </DataState>

      {editing && asset.data && (
        <EditAssetDialog
          asset={asset.data}
          onClose={() => setEditing(false)}
          onSubmit={async (request) => {
            const saved = await updateAsset(asset.data!.id, request);
            setEditing(false);
            notify.notifySuccess(`${saved.assetCode} updated.`);
            asset.refetch();
          }}
        />
      )}

      {moving && asset.data && (
        <RelocateAssetDialog
          asset={asset.data}
          onClose={() => setMoving(false)}
          onSubmit={async (request) => {
            const saved = await relocateAsset(asset.data!.id, request);
            setMoving(false);
            notify.notifySuccess(
              `${saved.assetCode} moved. Readiness has been re-derived for both spaces.`,
            );
            asset.refetch();
            space.refetch();
          }}
        />
      )}

      {changingStatus && asset.data && (
        <AssetStatusDialog
          asset={asset.data}
          onClose={() => setChangingStatus(false)}
          onChanged={async (status, notes) => {
            await changeAssetStatus(asset.data!.id, { operationalStatus: status, notes });
            setChangingStatus(false);
            notify.notifySuccess(
              'Condition changed. The readiness of the space it serves has been re-derived.',
            );
            asset.refetch();
            space.refetch();
          }}
        />
      )}
    </>
  );
};

export default AssetDetailPage;

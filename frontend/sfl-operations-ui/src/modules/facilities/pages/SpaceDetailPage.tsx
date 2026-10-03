import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Calendar, ClipboardCheck, Gauge, ShieldCheck } from 'lucide-react';
import {
  Banner,
  Button,
  Card,
  EmptyState,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { FacilityAsset, ReadinessAssessment, ReadinessBlocker } from '../api/dto';
import {
  changeSpaceLifecycle,
  getSpace,
  getSpaceReadiness,
  listAssessments,
  lockSpaceReadiness,
  resolveBlocker,
  searchAssets,
  unlockSpaceReadiness,
  updateSpace,
  updateSpaceReadiness,
} from '../api/facilitiesApi';
import {
  canAssessReadiness,
  changeSpaceLifecycleControl,
  editSpaceControl,
  lockAction,
  unlockAction,
} from '../api/workflow';
import { LifecycleDialog } from '../dialogs/common';
import { EditSpaceDialog } from '../dialogs/spaceDialogs';
import ReadinessBlockerList from '../components/ReadinessBlockerList';
import { EditRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import TitledSection from '../components/TitledSection';
import ResolveBlockerDialog from '../dialogs/ResolveBlockerDialog';
import SetReadinessDialog from '../dialogs/SetReadinessDialog';
import {
  assetStatusTone,
  figureToneClass,
  figureTone,
  formatDateTime,
  humaniseCode,
  orDash,
  readinessTone,
  relativeTime,
  scoreTone,
} from '../components/facilitiesFormat';

/**
 * One space, and everything that decides whether it can be used.
 *
 * Four things are on this screen because an operator standing in front of a blocked hall needs all
 * four to act: the space's own attributes, its readiness with the blockers behind it, the assets in
 * it that might be causing them, and its assessment history. Splitting them across tabs would make
 * the common question - "why is this not ready and what do I do?" - a three-click answer.
 */
const SpaceDetailPage = () => {
  const { roomId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [resolving, setResolving] = useState<ReadinessBlocker | null>(null);
  const [settingReadiness, setSettingReadiness] = useState(false);
  const [editing, setEditing] = useState(false);
  const [retiring, setRetiring] = useState(false);

  const space = useApiQuery((signal) => getSpace(roomId, signal), [roomId]);
  const readiness = useApiQuery((signal) => getSpaceReadiness(roomId, signal), [roomId]);
  const assets = useApiQuery(
    (signal) => searchAssets({ roomId, size: 50 }, signal),
    [roomId],
  );
  const assessments = useApiQuery(
    (signal) => listAssessments({ roomId, limit: 10 }, signal),
    [roomId],
  );

  const refreshReadiness = () => {
    space.refetch();
    readiness.refetch();
    assets.refetch();
    assessments.refetch();
  };

  const toggleLock = async (lock: boolean) => {
    try {
      await (lock ? lockSpaceReadiness(roomId) : unlockSpaceReadiness(roomId));
      notify.notifySuccess(lock ? 'Space locked for examination use.' : 'Readiness lock released.');
      refreshReadiness();
    } catch (cause) {
      notify.notifyError(cause);
    }
  };

  useBreadcrumbs([
    { label: 'Facilities', href: facilitiesPaths.dashboard },
    { label: 'Spaces', href: facilitiesPaths.spaces },
    { label: space.data?.roomCode ?? 'Space' },
  ]);

  const assetColumns: TableColumn<FacilityAsset>[] = [
    {
      id: 'assetCode',
      header: 'Code',
      width: 130,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.assetCode}</span>,
    },
    { id: 'name', header: 'Asset', accessorKey: 'name' },
    {
      id: 'category',
      header: 'Category',
      cell: ({ row }) => humaniseCode(row.category),
    },
    {
      id: 'criticality',
      header: 'Criticality',
      width: 120,
      cell: ({ row }) => <StatusBadge value={row.criticality} />,
    },
    {
      id: 'status',
      header: 'Condition',
      width: 160,
      cell: ({ row }) => (
        <StatusBadge value={row.operationalStatus} tone={assetStatusTone(row.operationalStatus)} />
      ),
    },
  ];

  const assessmentColumns: TableColumn<ReadinessAssessment>[] = [
    {
      id: 'assessedAt',
      header: 'Assessed',
      width: 190,
      cell: ({ row }) => formatDateTime(row.assessedAt),
    },
    { id: 'by', header: 'By', accessorKey: 'assessedBy' },
    {
      id: 'checklist',
      header: 'Checklist',
      cell: ({ row }) => (row.checklistCode ? `${row.checklistCode} v${row.checklistVersion}` : '-'),
    },
    {
      id: 'score',
      header: 'Score',
      align: 'right',
      width: 90,
      cell: ({ row }) => `${row.score}%`,
    },
    {
      id: 'outcome',
      header: 'Outcome',
      width: 130,
      align: 'right',
      cell: ({ row }) => <StatusBadge value={row.outcome} tone={readinessTone(row.outcome)} />,
    },
  ];

  return (
    <>
      <DataState
        loading={space.loading}
        error={space.error}
        empty={!space.data}
        onRetry={space.refetch}
        minHeight={280}
      >
        {space.data && (
          <>
            <PageSection>
              <SectionHeader>
                <SectionTitle>{space.data.name}</SectionTitle>
                <SectionDescription>
                  {`${space.data.roomCode} · ${humaniseCode(space.data.spaceType)} · ${space.data.siteCode}`}
                </SectionDescription>
                <SectionActions className="items-end [&_button]:whitespace-nowrap">
                  <EditRowAction
                    size="md"
                    state={editSpaceControl(space.data)}
                    onClick={() => setEditing(true)}
                    label={`Edit ${space.data.roomCode}`}
                  />
                  <RetireRowAction
                    size="md"
                    state={changeSpaceLifecycleControl(space.data)}
                    onClick={() => setRetiring(true)}
                    label={`Retire ${space.data.roomCode}`}
                  />
                  {canAssessReadiness() && (
                    <Button
                      variant="primary"
                      onClick={() =>
                        navigate(`${facilitiesPaths.assessments}?roomId=${space.data!.id}`)
                      }
                    >
                      <ClipboardCheck size={14} strokeWidth={1.5} aria-hidden="true" />
                      Assess readiness
                    </Button>
                  )}
                  {/*
                    The manual override. An assessment is the ordinary route and it computes the
                    outcome; this is for the times there is no checklist to answer - a burst pipe,
                    or the space coming back after one. Gated on the same permission, because it
                    reaches the same state by a shorter path.
                  */}
                  {canAssessReadiness() && (
                    <Button variant="outline" onClick={() => setSettingReadiness(true)}>
                      Set readiness
                    </Button>
                  )}
                  {(() => {
                    // Only the applicable half of the lock pair is rendered, and it is disabled
                    // with the reason when the actor or the record forbids it - an offered button
                    // that answers 403 has misled the operator before they clicked it.
                    const action = space.data!.readinessLocked
                      ? unlockAction(space.data!)
                      : lockAction(space.data!);
                    return (
                      <Button
                        variant="outline"
                        disabled={!action.allowed}
                        title={action.reason}
                        onClick={() => toggleLock(!space.data!.readinessLocked)}
                      >
                        {space.data!.readinessLocked ? 'Release lock' : 'Lock for examination'}
                      </Button>
                    );
                  })()}
                </SectionActions>
              </SectionHeader>
            </PageSection>

            {space.data.readinessLocked && (
              <PageSection>
                <Banner
                  variant="info"
                  heading="Locked for examination use"
                  subtext={`Locked by ${orDash(space.data.readinessLockedBy)} on ${formatDateTime(space.data.readinessLockedAt)}. Attribute and lifecycle changes are refused until the lock is released; readiness can still be reassessed.`}
                />
              </PageSection>
            )}

            <PageSection>
              <MetricCards>
                <MetricCard
                  variant="soft"
                  label="Readiness"
                  value={humaniseCode(space.data.readinessStatus)}
                  description={`Assessed ${relativeTime(space.data.readinessUpdatedAt)}`}
                  descriptionAdornment={
                    <ShieldCheck
                      size={16}
                      strokeWidth={2}
                      className={
                        figureToneClass[
                          space.data.readinessStatus === 'READY'
                            ? 'good'
                            : space.data.readinessStatus === 'BLOCKED'
                              ? 'critical'
                              : space.data.readinessStatus === 'DEGRADED'
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
                  loading={readiness.loading}
                  label="Score"
                  value={readiness.data ? `${readiness.data.score}%` : '-'}
                  description="Weighted checklist result"
                  descriptionAdornment={
                    <Gauge
                      size={16}
                      strokeWidth={2}
                      className={
                        figureToneClass[
                          readiness.data ? figureTone(scoreTone(readiness.data.score)) : 'neutral'
                        ]
                      }
                      aria-hidden
                    />
                  }
                />
                <MetricCard
                  variant="soft"
                  label="Bookable"
                  value={space.data.availableForBooking ? 'Yes' : 'No'}
                  description={space.data.bookable ? 'Flagged bookable' : 'Not a bookable space'}
                  descriptionAdornment={
                    <Calendar
                      size={16}
                      strokeWidth={2}
                      className={figureToneClass[space.data.availableForBooking ? 'good' : 'caution']}
                      aria-hidden
                    />
                  }
                />
                <MetricCard
                  variant="soft"
                  label="Examination"
                  value={space.data.availableForExamination ? 'Ready' : 'Not ready'}
                  description={
                    space.data.examinationCapable
                      ? 'Capable of hosting an examination'
                      : 'Not examination-capable'
                  }
                  descriptionAdornment={
                    <ClipboardCheck
                      size={16}
                      strokeWidth={2}
                      className={
                        figureToneClass[space.data.availableForExamination ? 'good' : 'critical']
                      }
                      aria-hidden
                    />
                  }
                />
              </MetricCards>
            </PageSection>

            <TitledSection
              title="Open blockers"
              description={readiness.data?.summary}
              actions={
                <Button variant="ghost" size="sm" onClick={refreshReadiness}>
                  Refresh
                </Button>
              }
            >
              <DataState
                loading={readiness.loading}
                error={readiness.error}
                onRetry={readiness.refetch}
                minHeight={80}
              >
                <ReadinessBlockerList
                  blockers={readiness.data?.openBlockers ?? []}
                  clearMessage="No open blockers. This space is clear."
                  onResolve={canAssessReadiness() ? setResolving : undefined}
                />
              </DataState>
            </TitledSection>

            <TitledSection title="Space record">
              <Card bordered>
                <KeyValueGrid
                  items={[
                    { label: 'Code', value: space.data.roomCode },
                    { label: 'Type', value: humaniseCode(space.data.spaceType) },
                    { label: 'Capacity', value: orDash(space.data.capacity) },
                    {
                      label: 'Area',
                      value: space.data.areaSqm ? `${space.data.areaSqm} m²` : '-',
                    },
                    { label: 'Cost centre', value: orDash(space.data.costCentre) },
                    { label: 'Lifecycle', value: humaniseCode(space.data.lifecycleStatus) },
                    { label: 'Created by', value: space.data.metadata.createdBy },
                    {
                      label: 'Last changed',
                      value: `${formatDateTime(space.data.metadata.lastModifiedAt)} by ${space.data.metadata.lastModifiedBy}`,
                    },
                    { label: 'Version', value: String(space.data.metadata.version) },
                  ]}
                />
              </Card>
            </TitledSection>

            <TitledSection
              title="Assets in this space"
              description="Fixed plant whose condition feeds this space's readiness"
            >
              <DataState loading={false} error={assets.error} onRetry={assets.refetch} minHeight={80}>
                <Table paramPrefix="space-assets" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={assetColumns}
                      data={assets.data?.items ?? []}
                      rowKey={(asset) => asset.id}
                      loading={assets.loading}
                      onRowClick={(asset) => navigate(facilitiesPaths.assetDetail(asset.id))}
                      aria-label="Assets in this space"
                      emptyContent={<EmptyState title="No assets are registered in this space." />}
                    />
                  </Card>
                </Table>
              </DataState>
            </TitledSection>

            <TitledSection title="Assessment history" description="Most recent first">
              <DataState
                loading={false}
                error={assessments.error}
                onRetry={assessments.refetch}
                minHeight={80}
              >
                <Table paramPrefix="space-assessments" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={assessmentColumns}
                      data={assessments.data?.items ?? []}
                      rowKey={(row) => row.id}
                      loading={assessments.loading}
                      onRowClick={(row) => navigate(facilitiesPaths.assessmentDetail(row.id))}
                      aria-label="Assessment history"
                      emptyContent={<EmptyState title="This space has never been assessed." />}
                    />
                  </Card>
                </Table>
              </DataState>
            </TitledSection>
          </>
        )}
      </DataState>

      {editing && space.data && (
        <EditSpaceDialog
          space={space.data}
          onClose={() => setEditing(false)}
          onSubmit={async (request) => {
            const saved = await updateSpace(space.data!.id, request);
            setEditing(false);
            notify.notifySuccess(`${saved.roomCode} updated.`);
            space.refetch();
          }}
        />
      )}

      {retiring && space.data && (
        <LifecycleDialog
          noun="space"
          label={space.data.roomCode}
          current={space.data.lifecycleStatus}
          expectedVersion={space.data.metadata.version}
          onClose={() => setRetiring(false)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeSpaceLifecycle(space.data!.id, { status, expectedVersion });
            setRetiring(false);
            notify.notifySuccess(`${saved.roomCode} is now ${status.toLowerCase()}.`);
            space.refetch();
          }}
        />
      )}

      {resolving && (
        <ResolveBlockerDialog
          blocker={resolving}
          onClose={() => setResolving(null)}
          onResolved={async (notes) => {
            await resolveBlocker(resolving.id, { resolutionNotes: notes });
            setResolving(null);
            notify.notifySuccess('Blocker resolved. Readiness has been re-derived.');
            refreshReadiness();
          }}
        />
      )}

      {settingReadiness && space.data && (
        <SetReadinessDialog
          space={space.data}
          // The blockers the dialog reasons about are the ones already on screen, so the count in a
          // refusal is the count the operator can see above it.
          openBlockers={readiness.data?.openBlockers ?? []}
          onClose={() => setSettingReadiness(false)}
          onSubmit={async (status, notes) => {
            await updateSpaceReadiness(roomId, { status, notes });
            setSettingReadiness(false);
            notify.notifySuccess(`Readiness set to ${humaniseCode(status).toLowerCase()}.`);
            refreshReadiness();
          }}
        />
      )}
    </>
  );
};

export default SpaceDetailPage;

import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus } from 'lucide-react';
import {
  Banner,
  Button,
  Card,
  EmptyState,
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
import type { Building, CreateBuildingRequest } from '../api/dto';
import {
  changeOperatingMode,
  changeSiteLifecycle,
  createBuilding,
  getSite,
  listBuildings,
  updateSite,
} from '../api/facilitiesApi';
import {
  canManageSpaces,
  changeOperatingModeAction,
  changeSiteLifecycleControl,
  editSiteControl,
} from '../api/workflow';
import { EditRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import TitledSection from '../components/TitledSection';
import CreateBuildingDialog from '../dialogs/CreateBuildingDialog';
import OperatingModeDialog from '../dialogs/OperatingModeDialog';
import { LifecycleDialog } from '../dialogs/common';
import { EditSiteDialog } from '../dialogs/siteDialogs';
import { formatDateTime, humaniseCode, orDash } from '../components/facilitiesFormat';

/**
 * One site: its record, its operating mode, and the buildings beneath it.
 *
 * The operating-mode control is the only consequential action on the screen, so it is the only one
 * given prominence - and it is hidden outright, not merely disabled, for an actor without
 * `FACILITIES_OPERATING_MODE_CHANGE`. A greyed-out button invites the question "how do I get this?"
 * from someone whose role is deliberately not meant to have it.
 */
const SiteDetailPage = () => {
  const { siteId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [changingMode, setChangingMode] = useState(false);
  const [addingBuilding, setAddingBuilding] = useState(false);
  const [editing, setEditing] = useState(false);
  const [retiring, setRetiring] = useState(false);

  const site = useApiQuery((signal) => getSite(siteId, signal), [siteId]);
  const buildings = useApiQuery(
    (signal) => (site.data ? listBuildings(site.data.siteCode, signal) : Promise.resolve([])),
    [site.data?.siteCode],
  );

  useBreadcrumbs([
    { label: 'Facilities', href: facilitiesPaths.dashboard },
    { label: 'Sites', href: facilitiesPaths.sites },
    { label: site.data?.siteCode ?? 'Site' },
  ]);

  const columns: TableColumn<Building>[] = [
    {
      id: 'buildingCode',
      header: 'Code',
      width: 140,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.buildingCode}</span>,
    },
    { id: 'name', header: 'Building', accessorKey: 'name' },
    {
      id: 'description',
      header: 'Description',
      cell: ({ row }) => <span className="text-muted-foreground">{orDash(row.description)}</span>,
    },
    {
      id: 'lifecycle',
      header: 'Lifecycle',
      width: 120,
      align: 'right',
      cell: ({ row }) => <StatusBadge value={row.lifecycleStatus} />,
    },
  ];

  const modeAction = site.data ? changeOperatingModeAction(site.data) : { allowed: false };

  return (
    <>
      <DataState
        loading={site.loading}
        error={site.error}
        empty={!site.data}
        onRetry={site.refetch}
        minHeight={280}
      >
        {site.data && (
          <>
            <PageSection>
              <SectionHeader>
                <SectionTitle>{site.data.name}</SectionTitle>
                <SectionDescription>{site.data.siteCode}</SectionDescription>
                <SectionActions className="items-end [&_button]:whitespace-nowrap">
                  <EditRowAction
                    size="md"
                    state={editSiteControl(site.data)}
                    onClick={() => setEditing(true)}
                    label={`Edit ${site.data.siteCode}`}
                  />
                  <RetireRowAction
                    size="md"
                    state={changeSiteLifecycleControl(site.data)}
                    onClick={() => setRetiring(true)}
                    label={`Retire ${site.data.siteCode}`}
                  />
                  {modeAction.allowed && (
                    <Button
                      variant={site.data.operatingMode === 'EXAMINATION' ? 'outline' : 'primary'}
                      onClick={() => setChangingMode(true)}
                    >
                      {site.data.operatingMode === 'EXAMINATION'
                        ? 'Stand down examination mode'
                        : 'Declare examination mode'}
                    </Button>
                  )}
                </SectionActions>
              </SectionHeader>
            </PageSection>

            {site.data.operatingMode === 'EXAMINATION' && (
              <PageSection>
                <Banner
                  variant="warning"
                  heading="This centre is in examination mode"
                  subtext={`Declared by ${orDash(site.data.operatingModeChangedBy)} on ${formatDateTime(site.data.operatingModeChangedAt)}. Readiness is assessed against the examination standard and the staleness threshold is tighter.`}
                />
              </PageSection>
            )}

            <TitledSection title="Site record">
              <Card bordered>
                <KeyValueGrid
                  items={[
                    { label: 'Code', value: site.data.siteCode },
                    { label: 'Name', value: site.data.name },
                    { label: 'Description', value: orDash(site.data.description), span: 2 },
                    {
                      label: 'Operating mode',
                      value: (
                        <StatusBadge
                          value={site.data.operatingMode}
                          tone={site.data.operatingMode === 'EXAMINATION' ? 'accent' : 'neutral'}
                        />
                      ),
                    },
                    {
                      label: 'Lifecycle',
                      value: <StatusBadge value={site.data.lifecycleStatus} />,
                    },
                    { label: 'Created by', value: site.data.metadata.createdBy },
                    {
                      label: 'Last changed',
                      value: `${formatDateTime(site.data.metadata.lastModifiedAt)} by ${site.data.metadata.lastModifiedBy}`,
                    },
                    { label: 'Version', value: String(site.data.metadata.version) },
                    {
                      label: 'Source channel',
                      value: humaniseCode(site.data.metadata.sourceChannel),
                    },
                  ]}
                />
              </Card>
            </TitledSection>

            <TitledSection
              title="Buildings"
              description="What stands on this site. Open one for its floors and what is on them."
              actions={
                canManageSpaces() ? (
                  <Button size="sm" variant="outline" onClick={() => setAddingBuilding(true)}>
                    <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                    Register a building
                  </Button>
                ) : undefined
              }
            >
              <DataState
                loading={false}
                error={buildings.error}
                onRetry={buildings.refetch}
                minHeight={80}
              >
                <Table paramPrefix="buildings" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={columns}
                      data={buildings.data ?? []}
                      rowKey={(building) => building.id}
                      loading={buildings.loading}
                      onRowClick={(building) => navigate(facilitiesPaths.buildingDetail(building.id))}
                      aria-label="Buildings"
                      emptyContent={<EmptyState title="No buildings are registered on this site." />}
                    />
                  </Card>
                </Table>
              </DataState>
            </TitledSection>

            <TitledSection
              title="Spaces"
              description="Open the space register filtered to this site"
              actions={
                <Button variant="outline" size="sm" onClick={() => navigate(facilitiesPaths.spaces)}>
                  Open space register
                </Button>
              }
            >
              <Card bordered>
                <p className="text-sm text-muted-foreground">
                  Spaces are searched rather than listed here - an estate of any size is not
                  browsable, and readiness is the thing worth filtering on.
                </p>
              </Card>
            </TitledSection>
          </>
        )}
      </DataState>

      {editing && site.data && (
        <EditSiteDialog
          site={site.data}
          onClose={() => setEditing(false)}
          onSubmit={async (request) => {
            const saved = await updateSite(site.data!.id, request);
            setEditing(false);
            notify.notifySuccess(`${saved.siteCode} updated.`);
            site.refetch();
          }}
        />
      )}

      {retiring && site.data && (
        <LifecycleDialog
          noun="site"
          label={site.data.siteCode}
          current={site.data.lifecycleStatus}
          expectedVersion={site.data.metadata.version}
          onClose={() => setRetiring(false)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeSiteLifecycle(site.data!.id, { status, expectedVersion });
            setRetiring(false);
            notify.notifySuccess(`${saved.siteCode} is now ${status.toLowerCase()}.`);
            site.refetch();
          }}
        />
      )}

      {changingMode && site.data && (
        <OperatingModeDialog
          site={site.data}
          onClose={() => setChangingMode(false)}
          onChanged={async (mode, reason) => {
            await changeOperatingMode(site.data!.id, { operatingMode: mode, reason });
            setChangingMode(false);
            notify.notifySuccess(
              mode === 'EXAMINATION'
                ? 'Examination mode declared for this centre.'
                : 'Centre returned to routine operations.',
            );
            site.refetch();
          }}
        />
      )}

      {addingBuilding && site.data && (
        <CreateBuildingDialog
          site={site.data}
          existingBuildings={buildings.data ?? []}
          onClose={() => setAddingBuilding(false)}
          onSubmit={async (request: CreateBuildingRequest) => {
            const created = await createBuilding(request);
            setAddingBuilding(false);
            notify.notifySuccess(`${created.buildingCode} registered.`);
            // Straight to it: a building with no floors holds nothing, and adding one is the
            // next thing whoever just created it came to do.
            navigate(facilitiesPaths.buildingDetail(created.id));
          }}
        />
      )}
    </>
  );
};

export default SiteDetailPage;

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
import type { CreateFloorRequest, Floor, Space } from '../api/dto';
import { createFloor, getBuilding, listFloors, searchSpaces } from '../api/facilitiesApi';
import { canManageSpaces } from '../api/workflow';
import CellStack from '../components/CellStack';
import StatusBadge from '../components/StatusBadge';
import TitledSection from '../components/TitledSection';
import CreateFloorDialog from '../dialogs/CreateFloorDialog';
import {
  floorLabel,
  formatDateTime,
  humaniseCode,
  orDash,
  readinessTone,
} from '../components/facilitiesFormat';

/**
 * One building, its floors, and what is on the floor being looked at.
 *
 * ## The screen that was missing from the middle of the hierarchy
 *
 * S152's estate is Site → Building → Floor → Space, and until now the dashboard had screens for the
 * first, second and fourth. `listFloors`, `getFloor` and `createFloor` were written, exported and
 * called by nothing, and a building row on the site page led nowhere - so the only way to place a
 * space was to already know a floor id.
 *
 * ## Why floors are a list beside the spaces rather than a page of their own
 *
 * A floor has four fields and no behaviour: a code, a name, a level number and a lifecycle status.
 * Nothing is ever done *to* a floor. What somebody wants from "the second floor" is **what is on
 * it**, so selecting one filters the spaces beside it rather than navigating away - and the building
 * stays on screen, which is the context that makes a floor mean anything.
 *
 * ## Level number is nullable and signed, and the sort has to survive both
 *
 * Basements are negative and mezzanines have no level at all. The service returns them lowest level
 * first; this screen preserves that order rather than re-sorting, because a client that sorted
 * `null` as zero would file every mezzanine at ground level.
 */
const BuildingDetailPage = () => {
  const { buildingId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const [selectedFloor, setSelectedFloor] = useState<string | null>(null);
  const [addingFloor, setAddingFloor] = useState(false);

  const building = useApiQuery((signal) => getBuilding(buildingId, signal), [buildingId]);
  const floors = useApiQuery((signal) => listFloors(buildingId, signal), [buildingId]);

  /*
    Scoped to the building when no floor is chosen, so the page opens on everything in it rather than
    on nothing. `floorId` narrows within that - both are server-side filters, because a client
    filtering a page of fifty rooms would be filtering a page rather than the register.
  */
  const spaces = useApiQuery(
    (signal) =>
      searchSpaces(
        { buildingId, floorId: selectedFloor ?? undefined, size: 100 },
        signal,
      ),
    [buildingId, selectedFloor],
  );

  const floorRows = floors.data ?? [];
  const chosen = floorRows.find((floor) => floor.id === selectedFloor) ?? null;
  const mayManage = canManageSpaces();

  useBreadcrumbs([
    { label: 'Facilities', href: facilitiesPaths.dashboard },
    { label: 'Sites', href: facilitiesPaths.sites },
    ...(building.data
      ? [
          { label: building.data.siteCode, href: facilitiesPaths.siteDetail(building.data.siteId) },
          { label: building.data.buildingCode },
        ]
      : [{ label: 'Building' }]),
  ]);

  const floorColumns: TableColumn<Floor>[] = [
    {
      id: 'floorCode',
      header: 'Floor',
      width: 200,
      cell: ({ row }) => (
        <CellStack primary={floorLabel(row.levelNumber, row.floorCode)} secondary={row.name} />
      ),
    },
    {
      id: 'lifecycle',
      header: 'Lifecycle',
      width: 120,
      cell: ({ row }) => <StatusBadge value={row.lifecycleStatus} />,
    },
    {
      id: 'view',
      header: 'Spaces',
      align: 'right',
      width: 150,
      cell: ({ row: floor }) => (
        <Button
          size="sm"
          variant={floor.id === selectedFloor ? 'primary' : 'outline'}
          onClick={(event) => {
            // The row itself is not a control here; only this button chooses the floor.
            event.stopPropagation();
            setSelectedFloor(floor.id === selectedFloor ? null : floor.id);
          }}
        >
          {floor.id === selectedFloor ? 'Showing' : 'Show'}
        </Button>
      ),
    },
  ];

  const spaceColumns: TableColumn<Space>[] = [
    {
      id: 'roomCode',
      header: 'Code',
      width: 150,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.roomCode}</span>,
    },
    { id: 'name', header: 'Space', accessorKey: 'name' },
    {
      id: 'spaceType',
      header: 'Type',
      cell: ({ row }) => humaniseCode(row.spaceType),
    },
    {
      id: 'capacity',
      header: 'Seats',
      align: 'right',
      width: 90,
      cell: ({ row }) => orDash(row.capacity),
    },
    {
      id: 'readiness',
      header: 'Readiness',
      width: 130,
      align: 'right',
      cell: ({ row }) => (
        <StatusBadge value={row.readinessStatus} tone={readinessTone(row.readinessStatus)} />
      ),
    },
  ];

  const submitFloor = async (request: CreateFloorRequest) => {
    const created = await createFloor(request);
    setAddingFloor(false);
    notify.notifySuccess(`${created.floorCode} added to this building.`);
    floors.refetch();
    // Select it: somebody who has just created a floor is about to put spaces on it.
    setSelectedFloor(created.id);
  };

  return (
    <>
      <DataState
        loading={building.loading}
        error={building.error}
        empty={!building.data}
        emptyTitle="Building not found"
        onRetry={building.refetch}
        minHeight={280}
      >
        {building.data && (
          <>
            <PageSection>
              <SectionHeader>
                <SectionTitle>{building.data.name}</SectionTitle>
                <SectionDescription>
                  {`${building.data.buildingCode} · ${building.data.siteCode}`}
                </SectionDescription>
                <SectionActions>
                  <StatusBadge value={building.data.lifecycleStatus} size="md" />
                  {mayManage && (
                    <Button variant="primary" onClick={() => setAddingFloor(true)}>
                      <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                      Add a floor
                    </Button>
                  )}
                </SectionActions>
              </SectionHeader>
            </PageSection>

            <TitledSection title="Building record">
              <Card bordered>
                <KeyValueGrid
                  items={[
                    { label: 'Code', value: building.data.buildingCode },
                    { label: 'Site', value: building.data.siteCode },
                    { label: 'Description', value: orDash(building.data.description) },
                    { label: 'Registered', value: formatDateTime(building.data.createdAt) },
                    { label: 'Registered by', value: building.data.metadata.createdBy },
                    { label: 'Version', value: String(building.data.metadata.version) },
                  ]}
                />
              </Card>
            </TitledSection>

            <TitledSection
              title="Floors"
              description="Lowest level first. Choose one to see what is on it."
            >
              <DataState loading={false} error={floors.error} minHeight={180} onRetry={floors.refetch}>
                <Table paramPrefix="floors" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={floorColumns}
                      data={floorRows}
                      rowKey={(floor) => floor.id}
                      loading={floors.loading}
                      aria-label="Floors in this building"
                      emptyContent={
                        <EmptyState
                          title="No floors registered"
                          description={
                            mayManage
                              ? 'A space is placed on a floor, so this building needs one before it can hold anything.'
                              : 'A space is placed on a floor, so nothing can be placed in this building yet.'
                          }
                        />
                      }
                    />
                  </Card>
                </Table>
              </DataState>
            </TitledSection>

            <TitledSection
              title={chosen ? `Spaces on ${floorLabel(chosen.levelNumber, chosen.floorCode)}` : 'Spaces'}
              description={
                chosen ? chosen.name : 'Everything in this building. Choose a floor to narrow it.'
              }
              actions={
                chosen ? (
                  <Button size="sm" variant="ghost" onClick={() => setSelectedFloor(null)}>
                    Show the whole building
                  </Button>
                ) : undefined
              }
            >
              <DataState loading={false} error={spaces.error} minHeight={180} onRetry={spaces.refetch}>
                <Table paramPrefix="building-spaces" variant="soft">
                  <Card bordered>
                    <TableContent
                      variant="soft"
                      columns={spaceColumns}
                      data={spaces.data?.items ?? []}
                      rowKey={(space) => space.id}
                      loading={spaces.loading}
                      onRowClick={(space) => navigate(facilitiesPaths.spaceDetail(space.id))}
                      aria-label="Spaces"
                      emptyContent={
                        <EmptyState
                          title={chosen ? 'Nothing on this floor' : 'No spaces in this building'}
                          description="Spaces are registered against a floor from the space register."
                        />
                      }
                    />
                  </Card>
                </Table>
              </DataState>
            </TitledSection>

            {floorRows.length > 0 && spaces.data && spaces.data.totalElements > 100 && (
              <PageSection>
                <Banner
                  variant="info"
                  heading="Showing the first 100 spaces"
                  subtext={`This building has ${spaces.data.totalElements.toLocaleString()}. Choose a floor, or use the space register, which pages properly.`}
                />
              </PageSection>
            )}
          </>
        )}
      </DataState>

      {addingFloor && building.data && (
        <CreateFloorDialog
          building={building.data}
          existingFloors={floorRows}
          onClose={() => setAddingFloor(false)}
          onSubmit={submitFloor}
        />
      )}
    </>
  );
};

export default BuildingDetailPage;

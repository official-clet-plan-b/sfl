import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import {
  Card,
  Dropdown,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  useBreadcrumbs,
  useTableState,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { Space } from '../api/dto';
import { readinessStatuses, spaceTypes } from '../api/enums';
import type { LocationReadinessStatus, SpaceType } from '../api/enums';
import { changeSpaceLifecycle, createSpace, searchSpaces, updateSpace } from '../api/facilitiesApi';
import {
  changeSpaceLifecycleControl,
  createSpaceControl,
  editSpaceControl,
} from '../api/workflow';
import ControlButton from '../components/ControlButton';
import RowActions, { EditRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import {
  humaniseCode,
  orDash,
  readinessTone,
  relativeTime,
} from '../components/facilitiesFormat';
import { LifecycleDialog } from '../dialogs/common';
import { CreateSpaceDialog, EditSpaceDialog } from '../dialogs/spaceDialogs';

/**
 * The space register.
 *
 * The most-visited screen in the module, because a space is what every other IFIMP system points at:
 * S153 raises faults against one, S159 will book one, S162a zones contain one. It is therefore a
 * search rather than a list - an estate of any size is not browsable - and it leads with readiness,
 * which is the column an operator is actually scanning for.
 */
const pageSizeOptions = [10, 25, 50, 100];

const SpaceRegisterPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Spaces' }]);

  /*
    Paging and the filters live in the URL, as the table keeps them. The search endpoint takes one
    value per axis, so each filter is a single choice: choosing replaces, and clearing the field
    removes the constraint. Changing a filter returns the table to its first page - page 4 of a new
    filter is meaningless.
  */
  const { filters, page, pageSize } = useTableState({
    paramPrefix: 'spaces',
    defaultPageSize: 25,
    pageSizeOptions,
  });
  const spaceType = filters.type ?? '';
  const readiness = filters.readiness ?? '';

  const [spaceTypeValue, setSpaceTypeValue] = useState(spaceType);
  const [readinessValue, setReadinessValue] = useState(readiness);

  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Space | null>(null);
  const [retiring, setRetiring] = useState<Space | null>(null);

  const { data, loading, error, refetch } = useApiQuery(
    (signal) =>
      searchSpaces(
        {
          siteCode: siteCode || undefined,
          spaceType: (spaceType as SpaceType) || undefined,
          readinessStatus: (readiness as LocationReadinessStatus) || undefined,
          // The table counts pages from one, the service from zero.
          page: page - 1,
          size: pageSize,
        },
        signal,
      ),
    [siteCode, spaceType, readiness, page, pageSize],
  );

  const columns: TableColumn<Space>[] = [
    {
      id: 'roomCode',
      header: 'Code',
      width: 140,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.roomCode}</span>,
    },
    { id: 'name', header: 'Name', accessorKey: 'name' },
    {
      id: 'spaceType',
      header: 'Type',
      cell: ({ row }) => humaniseCode(row.spaceType),
    },
    {
      id: 'capacity',
      header: 'Capacity',
      align: 'right',
      width: 100,
      cell: ({ row }) => orDash(row.capacity),
    },
    {
      id: 'readiness',
      header: 'Readiness',
      width: 130,
      cell: ({ row }) => (
        <StatusBadge value={row.readinessStatus} tone={readinessTone(row.readinessStatus)} />
      ),
    },
    {
      id: 'assessed',
      header: 'Assessed',
      width: 140,
      cell: ({ row }) => (
        <span className="text-muted-foreground">{relativeTime(row.readinessUpdatedAt)}</span>
      ),
    },
    {
      id: 'availability',
      header: 'Available for',
      align: 'right',
      width: 190,
      cell: ({ row: space }) => (
        <div className="flex flex-wrap justify-end gap-1">
          {/*
            Both flags are derived by the service. Showing "capable but not available" as two
            different things is the point: a hall can be examination-capable and still unusable,
            and an operator planning an examination needs to see which.
          */}
          {space.bookable && (
            <StatusBadge
              value="BOOKING"
              label="Booking"
              tone={space.availableForBooking ? 'ready' : 'blocked'}
            />
          )}
          {space.examinationCapable && (
            <StatusBadge
              value="EXAM"
              label="Exam"
              tone={space.availableForExamination ? 'ready' : 'blocked'}
            />
          )}
          {space.readinessLocked && <StatusBadge value="LOCKED" tone="accent" label="Locked" />}
        </div>
      ),
    },
    {
      id: 'actions',
      header: '',
      width: 150,
      align: 'right',
      cell: ({ row: space }) => (
        <RowActions>
          <EditRowAction
            state={editSpaceControl(space)}
            onClick={() => setEditing(space)}
            label={`Edit ${space.roomCode}`}
          />
          <RetireRowAction
            state={changeSpaceLifecycleControl(space)}
            onClick={() => setRetiring(space)}
            label={`Retire ${space.roomCode}`}
          />
        </RowActions>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Spaces</SectionTitle>
          <SectionDescription>Rooms, halls and courtrooms, with the readiness of each</SectionDescription>
          <SectionActions>
            <SiteSelect
              value={siteCode}
              onChange={setSiteCode}
              allowEmpty
              emptyLabel="All sites"
            />
            <ControlButton state={createSpaceControl()} variant="primary" onClick={() => setAdding(true)}>
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Add a space
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="spaces" variant="soft">
            <Card bordered>
              <TableHeader>
                <TableFilter variant="spread">
                  <Dropdown
                    name="type"
                    aria-label="Space type"
                    placeholder="All space types"
                    clearable
                    value={spaceTypeValue || null}
                    onValueChange={(next) => setSpaceTypeValue(next ?? '')}
                    options={spaceTypes.map((type) => ({ value: type, label: humaniseCode(type) }))}
                  />
                  <Dropdown
                    name="readiness"
                    aria-label="Readiness"
                    placeholder="All readiness"
                    clearable
                    value={readinessValue || null}
                    onValueChange={(next) => setReadinessValue(next ?? '')}
                    options={readinessStatuses.map((status) => ({
                      value: status,
                      label: humaniseCode(status),
                    }))}
                  />
                </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={data?.items ?? []}
                rowKey={(space) => space.id}
                loading={loading}
                onRowClick={(space) => navigate(facilitiesPaths.spaceDetail(space.id))}
                aria-label="Spaces"
                emptyContent={
                  <EmptyState
                    title="No spaces match these filters"
                    description="Widen the site or clear the type and readiness filters."
                  />
                }
              />
            </Card>
            <TableFooter noBorder>
              <TablePagination
                totalPages={Math.max(1, data?.totalPages ?? 1)}
                totalItems={data?.totalElements ?? 0}
                pageSizeOptions={pageSizeOptions}
                defaultPageSize={25}
              />
            </TableFooter>
          </Table>
        </DataState>
      </PageSection>

      {adding && (
        <CreateSpaceDialog
          siteCode={siteCode || defaultSite}
          onClose={() => setAdding(false)}
          onSubmit={async (request) => {
            const created = await createSpace(request);
            setAdding(false);
            notify.notifySuccess(`${created.roomCode} added to ${created.siteCode}.`);
            refetch();
          }}
        />
      )}

      {editing && (
        <EditSpaceDialog
          space={editing}
          onClose={() => setEditing(null)}
          onSubmit={async (request) => {
            const saved = await updateSpace(editing.id, request);
            setEditing(null);
            notify.notifySuccess(`${saved.roomCode} updated.`);
            refetch();
          }}
        />
      )}

      {retiring && (
        <LifecycleDialog
          noun="space"
          label={retiring.roomCode}
          current={retiring.lifecycleStatus}
          expectedVersion={retiring.metadata.version}
          onClose={() => setRetiring(null)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeSpaceLifecycle(retiring.id, { status, expectedVersion });
            setRetiring(null);
            notify.notifySuccess(`${saved.roomCode} is now ${status.toLowerCase()}.`);
            refetch();
          }}
        />
      )}
    </>
  );
};

export default SpaceRegisterPage;

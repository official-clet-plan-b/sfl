import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import {
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
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { ReadinessChecklist } from '../api/dto';
import { createChecklist, listChecklists, updateChecklist } from '../api/facilitiesApi';
import { createChecklistControl, editChecklistControl } from '../api/workflow';
import ControlButton from '../components/ControlButton';
import RowActions, { EditRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import { humaniseCode } from '../components/facilitiesFormat';
import { CreateChecklistDialog, EditChecklistDialog } from '../dialogs/checklistDialogs';

/**
 * The readiness checklists configured for a site.
 *
 * Applicability is the column worth reading: a checklist naming both a space type and an operating
 * mode applies narrowly, one naming neither applies to everything, and the most specific match wins
 * when an assessment is taken. Showing "Any" rather than an empty cell makes that rule visible.
 */
const ReadinessChecklistsPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Checklists' }]);
  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<ReadinessChecklist | null>(null);

  const { data, loading, error, refetch } = useApiQuery(
    (signal) => listChecklists(siteCode || undefined, signal),
    [siteCode],
  );

  const columns: TableColumn<ReadinessChecklist>[] = [
    {
      id: 'checklistCode',
      header: 'Code',
      width: 160,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.checklistCode}</span>,
    },
    { id: 'name', header: 'Checklist', accessorKey: 'name' },
    {
      id: 'spaceType',
      header: 'Applies to',
      cell: ({ row }) => (
        <span className="text-muted-foreground">
          {row.spaceType ? humaniseCode(row.spaceType) : 'Any space type'}
        </span>
      ),
    },
    {
      id: 'operatingMode',
      header: 'In mode',
      width: 140,
      cell: ({ row }) =>
        row.operatingMode ? (
          <StatusBadge
            value={row.operatingMode}
            tone={row.operatingMode === 'EXAMINATION' ? 'accent' : 'neutral'}
          />
        ) : (
          <span className="text-muted-foreground">Any mode</span>
        ),
    },
    {
      id: 'items',
      header: 'Items',
      align: 'right',
      width: 90,
      cell: ({ row }) => row.items.length,
    },
    {
      id: 'version',
      header: 'Version',
      align: 'right',
      width: 100,
      cell: ({ row }) => <span className="text-muted-foreground">v{row.version}</span>,
    },
    {
      id: 'actions',
      header: '',
      width: 100,
      align: 'right',
      cell: ({ row: checklist }) => (
        <RowActions>
          <EditRowAction
            state={editChecklistControl(checklist)}
            onClick={() => setEditing(checklist)}
            label={`Edit ${checklist.checklistCode}`}
          />
        </RowActions>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Readiness checklists</SectionTitle>
          <SectionDescription>What an assessment asks, and what a failure costs</SectionDescription>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
            <ControlButton state={createChecklistControl()} variant="primary" onClick={() => setAdding(true)}>
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Add a checklist
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="checklists" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={data ?? []}
                rowKey={(checklist) => checklist.id}
                loading={loading}
                onRowClick={(checklist) => navigate(facilitiesPaths.checklistDetail(checklist.id))}
                aria-label="Readiness checklists"
                emptyContent={
                  <EmptyState
                    title="No checklists configured"
                    description="Without one, an assessment records no answers and a space stays UNKNOWN."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {adding && (
        <CreateChecklistDialog
          siteCode={siteCode || defaultSite}
          onClose={() => setAdding(false)}
          onSubmit={async (request) => {
            const created = await createChecklist(request);
            setAdding(false);
            notify.notifySuccess(`${created.checklistCode} added to ${created.siteCode}.`);
            refetch();
          }}
        />
      )}

      {editing && (
        <EditChecklistDialog
          checklist={editing}
          onClose={() => setEditing(null)}
          onSubmit={async (request) => {
            const saved = await updateChecklist(editing.id, request);
            setEditing(null);
            notify.notifySuccess(`${saved.checklistCode} saved at version ${saved.version}.`);
            refetch();
          }}
        />
      )}
    </>
  );
};

export default ReadinessChecklistsPage;

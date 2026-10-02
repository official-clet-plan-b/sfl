import { useState } from 'react';
import { useParams } from 'react-router';
import DataState from 'shared/components/DataState';
import DataTable, { FacilitiesColumn as Column } from '../components/FacilitiesDataTable';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import PageHeading from 'modules/emergency/components/PageHeading';
import TitledSection from '../components/TitledSection';
import StatusBadge from '../components/StatusBadge';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { ChecklistItem } from '../api/dto';
import { getChecklist, updateChecklist } from '../api/facilitiesApi';
import { editChecklistControl } from '../api/workflow';
import { EditRowAction } from '../components/RowActions';
import { EditChecklistDialog } from '../dialogs/checklistDialogs';
import { formatDateTime, humaniseCode, orDash, severityTone } from '../components/facilitiesFormat';

/**
 * One checklist and its questions.
 *
 * The severity column is the point of this screen. An assessor records pass or fail; how much a
 * failure counts was decided when the checklist was approved, and it is what keeps two officers
 * assessing the same hall to the same standard. Showing it here is how somebody maintaining the
 * checklist sees the consequence of what they are writing.
 */
const ReadinessChecklistDetailPage = () => {
  const { checklistId = '' } = useParams();
  const notify = useNotifier();
  const [editing, setEditing] = useState(false);
  const { data, loading, error, refetch } = useApiQuery(
    (signal) => getChecklist(checklistId, signal),
    [checklistId],
  );

  const columns: Column<ChecklistItem>[] = [
    {
      key: 'itemCode',
      header: 'Code',
      width: 160,
      cell: (item) => <span className="font-medium text-gray-900">{item.itemCode}</span>,
    },
    { key: 'description', header: 'Question', cell: (item) => item.description },
    {
      key: 'severity',
      header: 'If failed',
      width: 140,
      cell: (item) => (
        <StatusBadge value={item.severityIfFailed} tone={severityTone(item.severityIfFailed)} />
      ),
    },
    {
      key: 'mandatory',
      header: 'Mandatory',
      width: 110,
      cell: (item) =>
        item.mandatory ? <StatusBadge value="YES" label="Yes" tone="active" /> : <span className="text-gray-500">No</span>,
    },
    {
      key: 'weight',
      header: 'Weight',
      align: 'right',
      width: 90,
      cell: (item) => item.weight,
    },
  ];

  return (
    <DataState
      loading={loading}
      error={error}
      empty={!data}
      onRetry={refetch}
      minHeight={280}
    >
      {data && (
        <>
          <PageHeading
            title={data.name}
            subtitle={`${data.checklistCode} · version ${data.version} · ${data.siteCode}`}
            crumbs={[
              { label: 'Facilities', to: facilitiesPaths.dashboard },
              { label: 'Checklists', to: facilitiesPaths.checklists },
              { label: data.checklistCode },
            ]}
            actions={
              <EditRowAction
                size="md"
                state={editChecklistControl(data)}
                onClick={() => setEditing(true)}
                label={`Edit ${data.checklistCode}`}
              />
            }
          />

          <div className="space-y-5">
            <TitledSection title="Applicability">
              <KeyValueGrid
                items={[
                  {
                    label: 'Space type',
                    value: data.spaceType ? humaniseCode(data.spaceType) : 'Any space type',
                  },
                  {
                    label: 'Operating mode',
                    value: data.operatingMode ? (
                      <StatusBadge
                        value={data.operatingMode}
                        tone={data.operatingMode === 'EXAMINATION' ? 'accent' : 'neutral'}
                      />
                    ) : (
                      'Any mode'
                    ),
                  },
                  { label: 'Version', value: `v${data.version}` },
                  { label: 'Total weight', value: String(data.totalWeight) },
                  { label: 'Description', value: orDash(data.description), span: 2 },
                  {
                    label: 'Last changed',
                    value: `${formatDateTime(data.metadata.lastModifiedAt)} by ${data.metadata.lastModifiedBy}`,
                    span: 2,
                  },
                ]}
              />
            </TitledSection>

            <TitledSection
              title="Questions"
              subtitle="A failure raises a blocker at the severity declared here"
            >
              <DataTable
                rows={data.items}
                columns={columns}
                getRowId={(item) => item.id}
                emptyMessage="This checklist has no questions."
                dense
              />
            </TitledSection>
          </div>

          {editing && (
            <EditChecklistDialog
              checklist={data}
              onClose={() => setEditing(false)}
              onSubmit={async (request) => {
                const saved = await updateChecklist(data.id, request);
                setEditing(false);
                notify.notifySuccess(`${saved.checklistCode} saved at version ${saved.version}.`);
                refetch();
              }}
            />
          )}
        </>
      )}
    </DataState>
  );
};

export default ReadinessChecklistDetailPage;

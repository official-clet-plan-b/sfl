import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { Plus } from 'lucide-react';
import {
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
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { ReadinessAssessment } from '../api/dto';
import {
  getSpace,
  listAssessments,
  listChecklists,
  submitAssessment,
} from '../api/facilitiesApi';
import { canAssessReadiness } from '../api/workflow';
import StatusBadge from '../components/StatusBadge';
import SubmitAssessmentDialog from '../dialogs/SubmitAssessmentDialog';
import { formatDateTime, readinessTone, scoreTone } from '../components/facilitiesFormat';

/**
 * The assessment register, and the entry point for taking a new one.
 *
 * Reached with `?roomId=` from a space, which is how the field workflow starts: an assessor opens the
 * space they are standing in and taps through to assess it. Without the parameter it is a register of
 * what has been assessed recently across the site.
 */
const ReadinessAssessmentsPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  const [params, setParams] = useSearchParams();
  const roomId = params.get('roomId') ?? '';

  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [assessing, setAssessing] = useState(false);

  const assessments = useApiQuery(
    (signal) =>
      listAssessments(
        { siteCode: roomId ? undefined : siteCode || undefined, roomId: roomId || undefined, limit: 50 },
        signal,
      ),
    [siteCode, roomId],
  );

  /** Only loaded when a space is in context - the dialog needs both to offer a checklist. */
  const space = useApiQuery(
    (signal) => (roomId ? getSpace(roomId, signal) : Promise.resolve(null)),
    [roomId],
  );
  const checklists = useApiQuery(
    (signal) => (space.data ? listChecklists(space.data.siteCode, signal) : Promise.resolve([])),
    [space.data?.siteCode],
  );

  useBreadcrumbs(
    space.data
      ? [
          { label: 'Facilities', href: facilitiesPaths.dashboard },
          { label: 'Spaces', href: facilitiesPaths.spaces },
          { label: space.data.roomCode, href: facilitiesPaths.spaceDetail(space.data.id) },
          { label: 'Assessments' },
        ]
      : [{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Assessments' }],
  );

  const columns: TableColumn<ReadinessAssessment>[] = [
    {
      id: 'assessedAt',
      header: 'Assessed',
      width: 190,
      cell: ({ row }) => formatDateTime(row.assessedAt),
    },
    { id: 'assessedBy', header: 'By', accessorKey: 'assessedBy' },
    {
      id: 'checklist',
      header: 'Checklist',
      cell: ({ row }) => (row.checklistCode ? `${row.checklistCode} v${row.checklistVersion}` : '-'),
    },
    {
      id: 'mode',
      header: 'Mode',
      width: 130,
      cell: ({ row }) => (
        <StatusBadge
          value={row.operatingMode}
          tone={row.operatingMode === 'EXAMINATION' ? 'accent' : 'neutral'}
        />
      ),
    },
    {
      id: 'score',
      header: 'Score',
      align: 'right',
      width: 90,
      cell: ({ row }) => (
        <span
          className={
            scoreTone(row.score) === 'blocked'
              ? 'font-medium text-error-text'
              : scoreTone(row.score) === 'caution'
                ? 'font-medium text-warning-text'
                : 'text-foreground'
          }
        >
          {row.score}%
        </span>
      ),
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
      <PageSection>
        <SectionHeader>
          <SectionTitle>Readiness assessments</SectionTitle>
          <SectionDescription>
            {space.data
              ? `${space.data.roomCode} - ${space.data.name}`
              : 'Every inspection recorded against a space'}
          </SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            {!roomId && (
              <SiteSelect value={siteCode} onChange={setSiteCode} allowEmpty emptyLabel="All sites" />
            )}
            {roomId && (
              <Button variant="ghost" size="sm" onClick={() => setParams({})}>
                Show every space instead
              </Button>
            )}
            {space.data && canAssessReadiness() && (
              <Button variant="primary" onClick={() => setAssessing(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                New assessment
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={assessments.error} onRetry={assessments.refetch}>
          <Table paramPrefix="assessments" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={assessments.data?.items ?? []}
                rowKey={(row) => row.id}
                loading={assessments.loading}
                onRowClick={(row) => navigate(facilitiesPaths.assessmentDetail(row.id))}
                aria-label="Readiness assessments"
                emptyContent={
                  <EmptyState
                    title="Nothing assessed yet"
                    description={
                      space.data
                        ? 'This space has never been assessed. An unassessed space reports as UNKNOWN, not ready.'
                        : 'No assessment has been recorded for this site.'
                    }
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {assessing && space.data && (
        <SubmitAssessmentDialog
          space={space.data}
          checklists={checklists.data ?? []}
          onClose={() => setAssessing(false)}
          onSubmitted={async (request) => {
            const result = await submitAssessment(request);
            setAssessing(false);
            notify.notifySuccess(
              `Assessment recorded - ${result.outcome.toLowerCase()} at ${result.score}%.`,
              result.outcome === 'BLOCKED'
                ? 'Critical checks failed, so the space is blocked until they are resolved.'
                : undefined,
            );
            assessments.refetch();
            space.refetch();
          }}
        />
      )}
    </>
  );
};

export default ReadinessAssessmentsPage;

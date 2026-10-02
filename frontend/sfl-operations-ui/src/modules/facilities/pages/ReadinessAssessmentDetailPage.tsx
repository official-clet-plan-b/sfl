import { useParams } from 'react-router';
import { Banner } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import DataTable, { FacilitiesColumn as Column } from '../components/FacilitiesDataTable';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import PageHeading from 'modules/emergency/components/PageHeading';
import TitledSection from '../components/TitledSection';
import StatusBadge from '../components/StatusBadge';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { AssessmentItem } from '../api/dto';
import { getAssessment, getSpace } from '../api/facilitiesApi';
import {
  formatDateTime,
  orDash,
  readinessTone,
  severityTone,
} from '../components/facilitiesFormat';

/**
 * One assessment, exactly as it was submitted.
 *
 * Read-only, because it is: an assessment is a statement about a space at a moment, signed by a named
 * assessor, and the service refuses any change to one. A space that has moved on gets a new
 * assessment rather than an edit to this.
 *
 * The item wording and severity are the ones stored *on the assessment*, not looked up from the
 * checklist as it stands today - the checklist is versioned and will have changed, and a result from
 * March has to stay readable against the questions that were asked in March.
 */
const ReadinessAssessmentDetailPage = () => {
  const { assessmentId = '' } = useParams();

  const assessment = useApiQuery((signal) => getAssessment(assessmentId, signal), [assessmentId]);
  const space = useApiQuery(
    (signal) => (assessment.data ? getSpace(assessment.data.roomId, signal) : Promise.resolve(null)),
    [assessment.data?.roomId],
  );

  const columns: Column<AssessmentItem>[] = [
    {
      key: 'result',
      header: '',
      width: 90,
      cell: (item) => (
        <StatusBadge
          value={item.passed ? 'PASS' : 'FAIL'}
          tone={item.passed ? 'ready' : 'blocked'}
        />
      ),
    },
    {
      key: 'itemCode',
      header: 'Check',
      width: 150,
      cell: (item) => <span className="font-medium text-gray-900">{item.itemCode}</span>,
    },
    { key: 'description', header: 'Question', cell: (item) => item.description },
    {
      key: 'severity',
      header: 'If failed',
      width: 130,
      hideBelowLg: true,
      cell: (item) => (
        <StatusBadge value={item.severityIfFailed} tone={severityTone(item.severityIfFailed)} />
      ),
    },
    {
      key: 'comment',
      header: 'Comment',
      cell: (item) => <span className="text-gray-600">{orDash(item.comment)}</span>,
    },
  ];

  return (
    <DataState
      loading={assessment.loading}
      error={assessment.error}
      empty={!assessment.data}
      onRetry={assessment.refetch}
      minHeight={280}
    >
      {assessment.data && (
        <>
          <PageHeading
            title="Readiness assessment"
            subtitle={`${space.data ? `${space.data.roomCode} - ${space.data.name} · ` : ''}${formatDateTime(assessment.data.assessedAt)}`}
            crumbs={[
              { label: 'Facilities', to: facilitiesPaths.dashboard },
              { label: 'Assessments', to: facilitiesPaths.assessments },
              { label: assessment.data.checklistCode ?? 'Assessment' },
            ]}
            meta={
              <StatusBadge
                value={assessment.data.outcome}
                tone={readinessTone(assessment.data.outcome)}
                size="md"
              />
            }
          />

          <div className="space-y-5">
            {assessment.data.hasMandatoryFailure && (
              <Banner variant="warning" heading="A mandatory check failed" subtext="At least one item marked mandatory was not passed, whatever the score says." />
            )}

            <TitledSection title="Assessment">
              <KeyValueGrid
                items={[
                  {
                    label: 'Outcome',
                    value: (
                      <StatusBadge
                        value={assessment.data.outcome}
                        tone={readinessTone(assessment.data.outcome)}
                      />
                    ),
                  },
                  { label: 'Score', value: `${assessment.data.score}%` },
                  {
                    label: 'Checklist',
                    value: assessment.data.checklistCode
                      ? `${assessment.data.checklistCode} v${assessment.data.checklistVersion}`
                      : 'No checklist applied',
                  },
                  {
                    label: 'Operating mode',
                    value: (
                      <StatusBadge
                        value={assessment.data.operatingMode}
                        tone={
                          assessment.data.operatingMode === 'EXAMINATION' ? 'accent' : 'neutral'
                        }
                      />
                    ),
                  },
                  { label: 'Assessed by', value: assessment.data.assessedBy },
                  { label: 'Assessed at', value: formatDateTime(assessment.data.assessedAt) },
                  { label: 'Notes', value: orDash(assessment.data.notes), span: 2 },
                ]}
              />
            </TitledSection>

            <TitledSection
              title="Answers"
              subtitle="The questions as they were worded when this was taken"
            >
              <DataTable
                rows={assessment.data.items}
                columns={columns}
                getRowId={(item) => item.id}
                emptyMessage="This assessment recorded no answers - no checklist applied to the space."
                dense
              />
            </TitledSection>
          </div>
        </>
      )}
    </DataState>
  );
};

export default ReadinessAssessmentDetailPage;

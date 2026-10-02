import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import Button from 'shared/components/Button';
import DataTable, { CellStack, type Column } from 'shared/components/DataTable';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { EnumSelect } from 'shared/components/fields';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { incidentPaths, riskAssessmentPaths } from 'shared/layout/navigation';
import type { ReviewFlag, ReviewFlagPage } from '../api/dto';
import { reviewFlagStatuses, type ReviewFlagStatus } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { riskWorkflow } from '../api/workflow';
import { ReviewFlagChip } from '../components/riskChips';
import { CompleteReviewDialog, DeferFlagDialog } from '../dialogs/reviewFlagDialogs';

/**
 * SRS-SFL-S165-04's queue: assessments an incident has put back up for review, oldest first. A flag
 * leaves the queue by a completed review with findings, or for a while by a deferral with a reason and a
 * date - never by dismissal, which the service does not offer.
 */
const ReviewFlagsPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [status, setStatus] = useState<ReviewFlagStatus | ''>('OPEN');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [working, setWorking] = useState<{ flag: ReviewFlag; kind: 'complete' | 'defer' } | null>(null);
  const query = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.reviewFlags({ siteCode, status: status || undefined, page, size }, signal) : Promise.resolve<ReviewFlagPage | undefined>(undefined)),
    [siteCode, status, page, size],
  );
  const canManage = permits('RISK_ASSESSMENT_REVIEW_FLAG_MANAGE');

  const columns = useMemo<Column<ReviewFlag>[]>(() => [
    { key: 'assessment', header: 'Assessment', width: 200, cell: (row) => <CellStack primary={row.assessmentReference} secondary={`Version ${row.versionNumber} in force`} /> },
    {
      key: 'incident',
      header: 'Raised by',
      width: 200,
      cell: (row) => (
        <CellStack
          primary={permits('INCIDENT_REPORT_READ') ? <Button variant="link" size="sm" onClick={(event) => { event.stopPropagation(); navigate(incidentPaths.detail(row.sourceId)); }}>{row.sourceReference ?? 'Incident'}</Button> : (row.sourceReference ?? 'Incident')}
          secondary={formatDateTime(row.raisedAt)}
        />
      ),
    },
    { key: 'deferred', header: 'Deferred', width: 220, hideBelowLg: true, cell: (row) => (row.deferredUntil ? <CellStack primary={`Until ${formatDate(row.deferredUntil)}`} secondary={row.deferralReason} /> : '-') },
    { key: 'status', header: 'Status', width: 110, cell: (row) => <ReviewFlagChip status={row.status} /> },
    {
      key: 'actions',
      header: '',
      width: 220,
      align: 'right',
      cell: (row) => canManage && riskWorkflow.canWorkFlag(row) ? (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="primary" onClick={(event) => { event.stopPropagation(); setWorking({ flag: row, kind: 'complete' }); }}>Complete review</Button>
          <Button size="sm" variant="outline" onClick={(event) => { event.stopPropagation(); setWorking({ flag: row, kind: 'defer' }); }}>Defer</Button>
        </div>
      ) : null,
    },
  ], [canManage, navigate]);

  const done = () => { setWorking(null); query.refetch(); };
  return (
    <div>
      <PageHeader title="Review flags" subtitle="Assessments an incident has put back up for review, ahead of their normal cycle." crumbs={[{ label: 'Risk assessments', to: riskAssessmentPaths.dashboard }, { label: 'Review flags' }]} />
      <SectionCard title="Queue" subtitle="Oldest first." flush>
        <div className="grid gap-4 px-5 py-4 sm:grid-cols-3">
          <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); setPage(0); }} required />
          <EnumSelect label="Status" value={status} options={reviewFlagStatuses} allowEmpty onChange={(value) => { setStatus(value); setPage(0); }} />
        </div>
        <DataTable
          rows={query.data?.content ?? []}
          columns={columns}
          getRowId={(row) => row.id}
          loading={query.loading}
          onRowClick={(row) => navigate(riskAssessmentPaths.detail(row.assessmentId))}
          caption="Review flags"
          page={page}
          pageSize={size}
          totalElements={query.data?.totalElements ?? 0}
          onPageChange={setPage}
          onPageSizeChange={(value) => { setSize(value); setPage(0); }}
          emptyMessage={siteCode ? 'Nothing on the queue with this status.' : 'Choose a site.'}
        />
      </SectionCard>
      {working?.kind === 'complete' && <CompleteReviewDialog flag={working.flag} onClose={() => setWorking(null)} onDone={done} />}
      {working?.kind === 'defer' && <DeferFlagDialog flag={working.flag} onClose={() => setWorking(null)} onDone={done} />}
    </div>
  );
};

export default ReviewFlagsPage;

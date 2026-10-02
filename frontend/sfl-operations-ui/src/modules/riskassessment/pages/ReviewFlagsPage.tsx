import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
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
import { Button, type TableColumn } from '@rfdtech/components';
import RegisterTable from 'modules/emergency/components/RegisterTable';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import { EnumField } from 'modules/emergency/components/FormFields';
import { CellStack } from 'modules/emergency/components/RegisterTable';

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
  const [size] = useState(25);
  const [working, setWorking] = useState<{ flag: ReviewFlag; kind: 'complete' | 'defer' } | null>(null);
  const query = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.reviewFlags({ siteCode, status: status || undefined, page, size }, signal) : Promise.resolve<ReviewFlagPage | undefined>(undefined)),
    [siteCode, status, page, size],
  );
  const canManage = permits('RISK_ASSESSMENT_REVIEW_FLAG_MANAGE');

  const columns = useMemo<TableColumn<ReviewFlag>[]>(() => [
    { id: 'assessment', header: 'Assessment', width: 200, cell: ({ row }) => <CellStack primary={row.assessmentReference} secondary={`Version ${row.versionNumber} in force`} /> },
    {
      id: 'incident',
      header: 'Raised by',
      width: 200,
      cell: ({ row }) => (
        <CellStack
          primary={permits('INCIDENT_REPORT_READ') ? <Button variant="ghost" size="sm" onClick={(event) => { event.stopPropagation(); navigate(incidentPaths.detail(row.sourceId)); }}>{row.sourceReference ?? 'Incident'}</Button> : (row.sourceReference ?? 'Incident')}
          secondary={formatDateTime(row.raisedAt)}
        />
      ),
    },
    { id: 'deferred', header: 'Deferred', width: 220, cell: ({ row }) => (row.deferredUntil ? <CellStack primary={`Until ${formatDate(row.deferredUntil)}`} secondary={row.deferralReason} /> : '-') },
    { id: 'status', header: 'Status', width: 110, cell: ({ row }) => <ReviewFlagChip status={row.status} /> },
    {
      id: 'actions',
      header: '',
      width: 220,
      align: 'right',
      cell: ({ row }) => canManage && riskWorkflow.canWorkFlag(row) ? (
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
      <PageHeading title="Review flags" subtitle="Assessments an incident has put back up for review, ahead of their normal cycle." crumbs={[{ label: 'Risk assessments', to: riskAssessmentPaths.dashboard }, { label: 'Review flags' }]} />
      <Panel title="Queue" subtitle="Oldest first.">
        <div className="grid gap-4 px-5 py-4 sm:grid-cols-3">
          <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); setPage(0); }} required />
          <EnumField label="Status" value={status} options={reviewFlagStatuses} allowEmpty onChange={(value) => { setStatus(value); setPage(0); }} />
        </div>
        <RegisterTable
          paramPrefix="risk-review-flags"
          rows={query.data?.content ?? []}
          columns={columns}
          rowKey={(row) => row.id}
          loading={query.loading}
          onRowClick={(row) => navigate(riskAssessmentPaths.detail(row.assessmentId))}
          emptyTitle={siteCode ? 'Nothing on the queue with this status.' : 'Choose a site.'}
          totalItems={query.data?.totalElements ?? 0}
          size={query.data?.size ?? size}
          framed={false}
        />
      </Panel>
      {working?.kind === 'complete' && <CompleteReviewDialog flag={working.flag} onClose={() => setWorking(null)} onDone={done} />}
      {working?.kind === 'defer' && <DeferFlagDialog flag={working.flag} onClose={() => setWorking(null)} onDone={done} />}
    </div>
  );
};

export default ReviewFlagsPage;

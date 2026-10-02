import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import WorkflowTimeline, { type TimelineEntry } from 'shared/components/WorkflowTimeline';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { riskAssessmentPaths } from 'shared/layout/navigation';
import type { AssessmentDetail, ReviewFlag, VersionView } from '../api/dto';
import { currencyReasonLabel } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { currentOf, draftOf, riskWorkflow } from '../api/workflow';
import HazardTable from '../components/HazardTable';
import { ReviewFlagChip, RiskLevelChip, StandingChip, VersionStatusBadge } from '../components/riskChips';
import { EditDraftDialog, OpenRevisionDialog, PublishDialog, SignOffDialog } from '../dialogs/assessmentDialogs';
import { CompleteReviewDialog, DeferFlagDialog } from '../dialogs/reviewFlagDialogs';
import { Button, Banner } from '@rfdtech/components';
import Tabs from 'shared/components/Tabs';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import Icon from 'shared/components/Icon';

type Action = 'edit' | 'publish' | 'revise' | 'sign-off' | null;

/**
 * The trail is assembled from the records this screen already holds - versions, sign-offs, flags - and
 * says so. It is not the hash-chained audit log, which S165 does not expose (see the UI gap report).
 */
const timeline = (detail: AssessmentDetail): TimelineEntry[] => {
  const entries: TimelineEntry[] = [];
  detail.versions.forEach(({ version }) => {
    entries.push({ id: `${version.id}-created`, title: `Version ${version.versionNumber} drafted`, actor: version.authorName ?? version.authorId, occurredAt: version.metadata.createdAt });
    if (version.publishedAt) {
      entries.push({ id: `${version.id}-published`, title: `Version ${version.versionNumber} published`, actor: version.publishedBy, occurredAt: version.publishedAt, tone: 'accent' });
    }
    if (version.reviewLapsedAt) {
      entries.push({ id: `${version.id}-lapsed`, title: `Version ${version.versionNumber} review lapsed`, detail: `Due ${formatDate(version.reviewDueAt)}`, occurredAt: version.reviewLapsedAt, tone: 'danger' });
    }
    if (version.supersededAt) {
      entries.push({ id: `${version.id}-superseded`, title: `Version ${version.versionNumber} superseded`, occurredAt: version.supersededAt });
    }
  });
  detail.signOffs.forEach((signOff) => entries.push({
    id: signOff.id,
    title: `Version ${signOff.versionNumber} signed off${signOff.independent ? '' : ' by its author'}`,
    detail: `${signOff.notes ? `${signOff.notes} · ` : ''}review renewed to ${formatDate(signOff.reviewDueAt)}`,
    actor: signOff.reviewerName ?? signOff.reviewerId,
    occurredAt: signOff.signedOffAt,
  }));
  detail.reviewFlags.forEach((flag) => {
    entries.push({ id: `${flag.id}-raised`, title: `Review flagged by ${flag.sourceReference ?? 'an incident'}`, detail: flag.reason, occurredAt: flag.raisedAt, tone: 'danger' });
    if (flag.clearedAt) {
      entries.push({ id: `${flag.id}-cleared`, title: 'Review completed, flag cleared', detail: flag.findings, actor: flag.clearedBy, occurredAt: flag.clearedAt });
    }
  });
  return entries.sort((a, b) => b.occurredAt.localeCompare(a.occurredAt));
};

const VersionPanel = ({ view }: { view: VersionView }) => {
  const { version } = view;
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <VersionStatusBadge status={version.status} />
        <RiskLevelChip level={view.riskLevel} />
        {version.status !== 'DRAFT' && !view.current && view.currencyReason && (
          <span className="text-theme-xs font-medium text-gray-700">Not current: {currencyReasonLabel[view.currencyReason]}</span>
        )}
      </div>
      {version.status === 'SUPERSEDED' && (
        <Banner variant="info" heading="Superseded - kept for the record"
  subtext={<>Version {version.versionNumber} was replaced on {formatDateTime(version.supersededAt)}. Nothing can newly link to it.</>} />
      )}
      <KeyValueGrid
        columns={4}
        items={[
          { label: 'Author', value: version.authorName ?? version.authorId },
          { label: 'Published', value: version.publishedAt ? `${formatDateTime(version.publishedAt)} by ${version.publishedBy}` : 'Not published' },
          { label: 'Signed off', value: version.signedOffAt ? `${formatDateTime(version.signedOffAt)} by ${version.signedOffByName ?? version.signedOffBy}` : 'Not signed off' },
          { label: 'Review due', value: version.reviewDueAt ? `${formatDate(version.reviewDueAt)} (every ${version.reviewIntervalDays} days)` : '-' },
          { label: 'Summary', value: version.content.summary, span: 2 },
          { label: 'Highest residual score', value: view.residualScore || '-' },
          { label: 'Record version', value: version.metadata.version },
        ]}
      />
      <HazardTable hazards={view.hazards} />
    </div>
  );
};

const RiskAssessmentDetailPage = () => {
  const { assessmentId = '' } = useParams();
  const navigate = useNavigate();
  const query = useApiQuery((signal) => riskAssessmentApi.get(assessmentId, signal), [assessmentId]);
  const [action, setAction] = useState<Action>(null);
  const [flagAction, setFlagAction] = useState<{ flag: ReviewFlag; kind: 'complete' | 'defer' } | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<string>('');
  const detail = query.data;
  const assessment = detail?.assessment;
  const draft = detail ? draftOf(detail.versions) : null;
  const current = detail ? currentOf(detail.versions) : null;
  const shown = useMemo(() => {
    if (!detail) return null;
    return detail.versions.find((view) => String(view.version.versionNumber) === selectedVersion) ?? current ?? detail.versions[0] ?? null;
  }, [detail, selectedVersion, current]);
  const done = () => { setAction(null); setFlagAction(null); query.refetch(); };

  return (
    <div>
      <PageHeading
        title={assessment ? `${assessment.reference} · ${assessment.title}` : 'Risk assessment'}
        subtitle={assessment ? [assessment.activityType, assessment.locationCode && `location ${assessment.locationCode}`, assessment.siteCode].filter(Boolean).join(' · ') : undefined}
        crumbs={[{ label: 'Assessment register', to: riskAssessmentPaths.register }, { label: assessment?.reference ?? 'Assessment' }]}
        actions={assessment ? (
          <>
            {riskWorkflow.canEditDraft(assessment) && permits('RISK_ASSESSMENT_AUTHOR') && <Button variant="outline" onClick={() => setAction('edit')}><Icon name="edit" size={14} aria-hidden="true" />Edit draft</Button>}
            {riskWorkflow.canOpenRevision(assessment) && permits('RISK_ASSESSMENT_AUTHOR') && <Button variant="outline" onClick={() => setAction('revise')}>Revise</Button>}
            {riskWorkflow.canSignOff(assessment) && permits('RISK_ASSESSMENT_SIGN_OFF') && <Button variant="outline" onClick={() => setAction('sign-off')}><Icon name="check-circle" size={14} aria-hidden="true" />Sign off</Button>}
            {riskWorkflow.canPublish(assessment) && permits('RISK_ASSESSMENT_PUBLISH') && <Button variant="primary" onClick={() => setAction('publish')}>Publish v{assessment.draftVersion}</Button>}
          </>
        ) : undefined}
      />
      <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
        {detail && assessment && (
          <div className="space-y-5">
            {assessment.standing === 'LAPSED' && (
              <Banner variant="danger" heading="Lapsed Assessment"
  subtext={<>The review date ({formatDate(assessment.reviewDueAt)}) passed without a renewed sign-off. It is not current and cannot be
                newly linked to a permit, project or event until it is signed off again.</>} />
            )}
            {assessment.standing === 'AWAITING_INDEPENDENT_SIGN_OFF' && (
              <Banner variant="warning" heading="Awaiting independent sign-off"
  subtext={<>At {assessment.riskLevel} this assessment is not current until someone other than its author signs it off.</>} />
            )}
            <Panel title="Standing" actions={<StandingChip standing={assessment.standing} size="md" />}>
              <KeyValueGrid
                columns={4}
                items={[
                  { label: 'Current version', value: assessment.currentVersion ? `v${assessment.currentVersion}` : 'None published' },
                  { label: 'Risk level', value: <RiskLevelChip level={assessment.riskLevel} /> },
                  { label: 'Review due', value: formatDate(assessment.reviewDueAt) },
                  { label: 'Signed off by', value: assessment.signedOffBy ?? 'Nobody yet' },
                  { label: 'Draft open', value: assessment.draftVersion ? `v${assessment.draftVersion}` : 'No' },
                  { label: 'Activity type', value: assessment.activityType },
                  { label: 'S152 location', value: assessment.locationCode },
                  { label: 'Open review flags', value: detail.reviewFlags.filter((flag) => flag.status !== 'CLEARED').length },
                ]}
              />
            </Panel>

            <Panel title="Versions" subtitle="Every version is kept. Superseded versions stay readable and are marked not current.">
              <Tabs
                variant="pill"
                value={shown ? String(shown.version.versionNumber) : ''}
                onChange={setSelectedVersion}
                items={detail.versions.map((view) => ({ value: String(view.version.versionNumber), label: `v${view.version.versionNumber} · ${view.version.status.toLowerCase()}` }))}
              />
              <div className="mt-4">{shown && <VersionPanel view={shown} />}</div>
            </Panel>

            <div className="grid gap-5 lg:grid-cols-2">
              <Panel title="Review flags" subtitle="Raised by incidents that happened under this assessment.">
                {detail.reviewFlags.length ? (
                  <div className="space-y-3">
                    {detail.reviewFlags.map((flag) => (
                      <div key={flag.id} className="rounded-md border border-gray-200 p-4">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <p className="font-semibold text-gray-900">{flag.sourceReference ?? 'Incident'} · v{flag.versionNumber}</p>
                          <ReviewFlagChip status={flag.status} />
                        </div>
                        <p className="mt-1 text-theme-xs text-gray-600">
                          Raised {formatDateTime(flag.raisedAt)}
                          {flag.status === 'DEFERRED' && ` · deferred to ${formatDate(flag.deferredUntil)}: ${flag.deferralReason}`}
                        </p>
                        {flag.findings && <p className="mt-2 whitespace-pre-wrap text-theme-sm text-gray-800">{flag.findings}</p>}
                        {riskWorkflow.canWorkFlag(flag) && permits('RISK_ASSESSMENT_REVIEW_FLAG_MANAGE') && (
                          <div className="mt-3 flex gap-2">
                            <Button size="sm" variant="primary" onClick={() => setFlagAction({ flag, kind: 'complete' })}>Complete review</Button>
                            <Button size="sm" variant="outline" onClick={() => setFlagAction({ flag, kind: 'defer' })}>Defer</Button>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-theme-sm text-gray-600">No incident has flagged this assessment.</p>
                )}
              </Panel>
              <Panel title="History" subtitle="Assembled from the versions, sign-offs and flags above.">
                <WorkflowTimeline entries={timeline(detail)} emptyMessage="No history yet." />
              </Panel>
            </div>
          </div>
        )}
      </DataState>

      {action === 'edit' && draft && <EditDraftDialog assessmentId={assessmentId} draft={draft} onClose={() => setAction(null)} onSaved={done} />}
      {action === 'publish' && draft && assessment && <PublishDialog assessmentId={assessmentId} draft={draft} currentVersion={assessment.currentVersion} onClose={() => setAction(null)} onPublished={done} />}
      {action === 'revise' && assessment?.currentVersion && <OpenRevisionDialog assessmentId={assessmentId} currentVersion={assessment.currentVersion} onClose={() => setAction(null)} onOpened={done} />}
      {action === 'sign-off' && current && <SignOffDialog assessmentId={assessmentId} current={current} onClose={() => setAction(null)} onSignedOff={done} />}
      {flagAction?.kind === 'complete' && <CompleteReviewDialog flag={flagAction.flag} onClose={() => setFlagAction(null)} onDone={done} />}
      {flagAction?.kind === 'defer' && <DeferFlagDialog flag={flagAction.flag} onClose={() => setFlagAction(null)} onDone={done} />}
      {!query.initialising && query.error?.status === 404 && (
        <Button variant="ghost" onClick={() => navigate(riskAssessmentPaths.register)}>Back to the register</Button>
      )}
    </div>
  );
};

export default RiskAssessmentDetailPage;

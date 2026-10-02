import { useCallback, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Banner, Button } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { useNotifier } from 'shared/components/Notifier';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { drillPaths } from 'shared/layout/navigation';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import { drillApi } from '../api/drillApi';
import type { DrillCorrectiveAction, DrillDetail, DrillFinding, RollCallGap } from '../api/dto';
import { drillModuleLabel, drillTypeLabel } from '../api/enums';
import { drillWorkflow, formatDuration, participation, submitBlockers } from '../api/workflow';
import RollCallPanel from '../components/RollCallPanel';
import { CapaChip, DrillMarker, DrillStatusChip, FollowUpChip, OutcomeChip } from '../components/drillChips';
import { PlanDialog, ReasonDialog, ScheduleDialog } from '../dialogs/planDialogs';
import {
  ActionTransitionDialog,
  CloseDrillDialog,
  CloseRollCallDialog,
  FindingDialog,
  GapFollowUpDialog,
  JudgeExpectationsDialog,
  NoActionDialog,
  OpenActionDialog,
  ReviewNarrativeDialog,
  StartDrillDialog,
  SubmitReviewDialog,
} from '../dialogs/runReviewDialogs';

type Dialog =
  | { kind: 'revise' | 'schedule' | 'postpone' | 'cancel' | 'start' | 'close-roll-call' | 'review' | 'finding' | 'judge' | 'submit' | 'close' }
  | { kind: 'follow-up'; gap: RollCallGap }
  | { kind: 'no-action' | 'open-action'; finding: DrillFinding }
  | { kind: 'verify' | 'cancel-action'; action: DrillCorrectiveAction }
  | null;

/**
 * One drill from plan to closure - SRS-SFL-S175-01 to -05. The screen shows the stage the drill is at: the plan
 * before it runs, the live roll-call while it runs, then the gap list, review, findings and corrective actions.
 * Every action offered is one the service will accept from this status, for this actor.
 */
const DrillDetailPage = () => {
  const { drillId = '' } = useParams();
  const navigate = useNavigate();
  const notify = useNotifier();
  const query = useApiQuery<DrillDetail>((signal) => drillApi.get(drillId, signal), [drillId]);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [outstanding, setOutstanding] = useState(0);
  const detail = query.data;
  const drill = detail?.drill;
  const records = useSiteRecords(drill?.siteCode ?? '');
  const done = () => { setDialog(null); query.refetch(); };
  const onOutstanding = useCallback((count: number) => setOutstanding(count), []);
  const status = drill?.status;

  const startAction = async (action: DrillCorrectiveAction) => {
    try {
      await drillApi.startAction(drillId, action.id);
      query.refetch();
    } catch (error) {
      notify.notifyError(error);
    }
  };

  const blockers = detail ? submitBlockers(detail) : [];

  return (
    <div>
      <PageHeading
        title={drill ? `${drill.reference} · ${drill.plan.title}` : 'Drill'}
        subtitle={drill ? `${drillTypeLabel[drill.plan.drillType]} · ${drill.siteCode}${drill.plan.scheduledFor ? ` · ${formatDateTime(drill.plan.scheduledFor)}` : ''}` : undefined}
        crumbs={[{ label: 'Drill register', to: drillPaths.register }, { label: drill?.reference ?? 'Drill' }]}
        meta={drill ? <span className="flex gap-2"><DrillMarker /><DrillStatusChip status={drill.status} size="md" /></span> : undefined}
        actions={status ? (
          <>
            {drillWorkflow.canRevise(status) && permits('DRILL_PLAN') && <Button variant="outline" onClick={() => setDialog({ kind: 'revise' })}><Icon name="edit" size={14} aria-hidden="true" />Revise plan</Button>}
            {drillWorkflow.canPostpone(status) && permits('DRILL_PLAN') && <Button variant="outline" onClick={() => setDialog({ kind: 'postpone' })}>Postpone</Button>}
            {drillWorkflow.canCancel(status) && permits('DRILL_PLAN') && <Button variant="outline" onClick={() => setDialog({ kind: 'cancel' })}>Cancel</Button>}
            {drillWorkflow.canSchedule(status) && permits('DRILL_PLAN') && <Button variant="primary" onClick={() => setDialog({ kind: 'schedule' })}><Icon name="calendar" size={14} aria-hidden="true" />{status === 'POSTPONED' ? 'Reschedule' : 'Schedule'}</Button>}
            {drillWorkflow.canStart(status) && permits('DRILL_EXECUTE') && <Button variant="primary" onClick={() => setDialog({ kind: 'start' })}><Icon name="play" size={14} aria-hidden="true" />Start drill</Button>}
            {drillWorkflow.isRunning(status) && permits('DRILL_EXECUTE') && <Button variant="primary" onClick={() => setDialog({ kind: 'close-roll-call' })}><Icon name="stop" size={14} aria-hidden="true" />Close roll-call</Button>}
            {drillWorkflow.isUnderReview(status) && permits('DRILL_REVIEW') && <Button variant="primary" disabled={blockers.length > 0} onClick={() => setDialog({ kind: 'submit' })}>Submit review</Button>}
            {drillWorkflow.canClose(status) && permits('DRILL_REVIEW') && <Button variant="primary" onClick={() => setDialog({ kind: 'close' })}>Close drill</Button>}
          </>
        ) : undefined}
      />
      <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
        {detail && drill && (
          <div className="space-y-5">
            {(status === 'POSTPONED' || status === 'CANCELLED') && drill.statusReason && (
              <Banner variant={status === 'CANCELLED' ? 'danger' : 'warning'} heading={status === 'CANCELLED' ? 'Cancelled' : 'Postponed'} subtext={drill.statusReason} />
            )}
            {status === 'CLOSED' && drill.statusReason && (
              <Banner variant="info" heading="Closed with overdue actions deferred" subtext={drill.statusReason} />
            )}
            {detail.execution?.baselineStale && status !== 'IN_PROGRESS' && (
              <Banner variant="warning" heading="Stale Baseline" subtext="The access data behind this drill's baseline was out of date when it started, so it does not fully validate roll-call accuracy." />
            )}

            {status === 'IN_PROGRESS' && <RollCallPanel drillId={drill.id} canCheckIn={permits('DRILL_EXECUTE')} onOutstanding={onOutstanding} />}

            {detail.execution && status !== 'IN_PROGRESS' && (
              <Panel title="What happened" subtitle="Measured on the day - S175-03's timing and participation.">
                <KeyValueGrid
                  columns={4}
                  items={[
                    { label: 'Started', value: `${formatDateTime(detail.execution.startedAt)} by ${detail.execution.startedBy}` },
                    { label: 'Notification', value: detail.execution.notificationNumber ? `${detail.execution.notificationNumber}, sent ${formatDateTime(detail.execution.notificationSentAt)}` : '-' },
                    { label: 'Notification to muster', value: formatDuration(detail.execution.notificationToMusterSeconds) },
                    { label: 'Roll-call closed', value: detail.execution.rollCallClosedAt ? formatDateTime(detail.execution.rollCallClosedAt) : '-' },
                    { label: 'On site at start', value: detail.execution.baselineCount },
                    { label: 'Checked in', value: detail.execution.checkedInCount ?? '-' },
                    { label: 'Participation', value: participation(detail.execution.checkedInCount, detail.execution.baselineCount) },
                    { label: 'Gaps', value: detail.execution.gapCount ?? '-' },
                  ]}
                />
              </Panel>
            )}

            {detail.gaps.length > 0 && (
              <Panel title="Roll-call gaps" subtitle="On site per S160/S160a at the start and not checked in when roll-call closed.">
                <ul className="divide-y divide-[var(--clet-border-subtle)]">
                  {detail.gaps.map((gap) => (
                    <li key={gap.id} className="flex flex-wrap items-center justify-between gap-2 py-3">
                      <div>
                        <p className="font-medium text-gray-900">{gap.displayName ?? gap.personRef} <span className="text-theme-xs font-normal text-gray-600">{gap.personRef}</span></p>
                        {gap.followUpNotes && <p className="text-theme-xs text-gray-600">{gap.followUpNotes}</p>}
                      </div>
                      <div className="flex items-center gap-2">
                        <FollowUpChip followUp={gap.followUp} />
                        {drillWorkflow.isUnderReview(drill.status) && permits('DRILL_EXECUTE') && (
                          <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'follow-up', gap })}>{gap.followUp ? 'Change' : 'Follow up'}</Button>
                        )}
                      </div>
                    </li>
                  ))}
                </ul>
              </Panel>
            )}

            {['COMPLETED', 'REVIEWED', 'CLOSED'].includes(drill.status) && (
              <Panel
                title="After-action review"
                subtitle={detail.review?.submittedAt ? `Submitted ${formatDateTime(detail.review.submittedAt)} by ${detail.review.submittedBy}` : 'Not yet submitted'}
                actions={drillWorkflow.isUnderReview(drill.status) && permits('DRILL_REVIEW') ? <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'review' })}>{detail.review?.summary ? 'Edit' : 'Write review'}</Button> : undefined}
              >
                {detail.review?.summary ? (
                  <div className="space-y-2 text-theme-sm text-gray-800">
                    <p className="whitespace-pre-wrap">{detail.review.summary}</p>
                    {detail.review.timingNotes && <p className="whitespace-pre-wrap text-gray-600">{detail.review.timingNotes}</p>}
                  </div>
                ) : (
                  <p className="text-theme-sm text-gray-600">No summary recorded yet.</p>
                )}
                {drillWorkflow.isUnderReview(drill.status) && blockers.length > 0 && (
                  <div className="mt-4">
                    <Banner variant="info" heading="Before the review can be submitted" subtext={<ul className="list-disc pl-5">{blockers.map((b) => <li key={b}>{b}</li>)}</ul>} />
                  </div>
                )}
              </Panel>
            )}

            {['COMPLETED', 'REVIEWED', 'CLOSED'].includes(drill.status) && (
              <Panel
                title="Findings and corrective actions"
                subtitle="Each finding ends with a corrective action or a recorded reason none is needed."
                actions={drillWorkflow.isUnderReview(drill.status) && permits('DRILL_REVIEW') ? <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'finding' })}>Record finding</Button> : undefined}
              >
                {detail.findings.length ? (
                  <div className="space-y-3">
                    {detail.findings.map((finding) => {
                      const actions = detail.correctiveActions.filter((a) => a.findingId === finding.id);
                      const unactioned = detail.unactionedFindingIds.includes(finding.id);
                      return (
                        <div key={finding.id} className="rounded-md border border-gray-200 p-4">
                          <div className="flex flex-wrap items-start justify-between gap-2">
                            <p className="font-semibold text-gray-900">#{finding.sequenceNo} {finding.description}</p>
                            {unactioned && <span className="text-theme-xs font-medium text-[var(--clet-error-text)]">Unactioned Finding</span>}
                          </div>
                          {finding.noActionJustification && <p className="mt-1 text-theme-sm text-gray-600">No action needed: {finding.noActionJustification}</p>}
                          {actions.map((action) => {
                            const overdue = detail.overdueActionIds.includes(action.id);
                            return (
                              <div key={action.id} className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-[var(--clet-border-subtle)] pt-3">
                                <div className="text-theme-sm">
                                  <p className="text-gray-900">{action.description}</p>
                                  <p className="text-theme-xs text-gray-600">Owner {action.ownerId} · due {formatDate(action.dueDate)}{action.verificationNotes ? ` · ${action.verificationNotes}` : ''}</p>
                                </div>
                                <div className="flex items-center gap-2">
                                  <CapaChip status={action.status} overdue={overdue} />
                                  {drillWorkflow.actionOpen(action) && permits('DRILL_CAPA_MANAGE') && (
                                    <>
                                      {action.status === 'OPEN' && <Button size="sm" variant="ghost" onClick={() => void startAction(action)}>Start</Button>}
                                      <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'verify', action })}>Verify</Button>
                                      <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'cancel-action', action })}>Cancel</Button>
                                    </>
                                  )}
                                </div>
                              </div>
                            );
                          })}
                          {!finding.noActionJustification && (
                            <div className="mt-3 flex gap-2">
                              {drillWorkflow.canRaiseAction(drill.status) && permits('DRILL_CAPA_MANAGE') && <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'open-action', finding })}>Raise corrective action</Button>}
                              {actions.length === 0 && drillWorkflow.isUnderReview(drill.status) && permits('DRILL_REVIEW') && <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: 'no-action', finding })}>No action needed</Button>}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ) : (
                  <p className="text-theme-sm text-gray-600">No findings recorded.</p>
                )}
              </Panel>
            )}

            <Panel title="Plan" subtitle={`Record version ${drill.metadata.version} · last changed ${formatDateTime(drill.metadata.lastModifiedAt)} by ${drill.metadata.lastModifiedBy}`}>
              <KeyValueGrid
                columns={4}
                items={[
                  { label: 'Scenario', value: drill.plan.scenario, span: 2 },
                  { label: 'Expected participants', value: drill.plan.expectedParticipants, span: 2 },
                  { label: 'Assembly point', value: drill.plan.assemblyZone },
                  { label: 'S174 drill template', value: drill.plan.notificationTemplateId ? records.templateName(drill.plan.notificationTemplateId) : 'Not chosen' },
                  { label: 'Audience groups', value: drill.plan.audienceGroupIds.map(records.audienceName).join(', ') || '-' },
                  { label: 'Recipient zones', value: drill.plan.recipientZoneIds.map(records.zoneName).join(', ') || '-' },
                ]}
              />
              {drill.plan.expectations.length > 0 && (
                <div className="mt-5">
                  <div className="mb-2 flex items-center justify-between">
                    <p className="font-semibold text-gray-900">Module expectations</p>
                    {drillWorkflow.isUnderReview(drill.status) && permits('DRILL_REVIEW') && <Button size="sm" variant="outline" onClick={() => setDialog({ kind: 'judge' })}>Record outcomes</Button>}
                  </div>
                  <ul className="divide-y divide-[var(--clet-border-subtle)] rounded-md border border-[var(--clet-border-subtle)]">
                    {drill.plan.expectations.map((e, index) => (
                      <li key={index} className="flex flex-wrap items-start justify-between gap-2 px-3 py-2 text-theme-sm">
                        <div>
                          <p className="font-medium text-gray-900">{drillModuleLabel[e.module]}: {e.expectation}</p>
                          <p className="text-theme-xs text-gray-600">Success: {e.successCriterion ?? 'not stated'}{e.outcomeNotes ? ` · ${e.outcomeNotes}` : ''}</p>
                        </div>
                        {['COMPLETED', 'REVIEWED', 'CLOSED'].includes(drill.status) && <OutcomeChip outcome={e.outcome} />}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </Panel>
          </div>
        )}
      </DataState>

      {detail && dialog?.kind === 'revise' && <PlanDialog siteCode={detail.drill.siteCode} existing={detail} onClose={() => setDialog(null)} onSaved={done} />}
      {detail && dialog?.kind === 'schedule' && <ScheduleDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {detail && (dialog?.kind === 'postpone' || dialog?.kind === 'cancel') && <ReasonDialog detail={detail} kind={dialog.kind} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'start' && <StartDrillDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'close-roll-call' && <CloseRollCallDialog detail={detail} outstanding={outstanding} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'follow-up' && <GapFollowUpDialog detail={detail} gap={dialog.gap} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'review' && <ReviewNarrativeDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'finding' && <FindingDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'no-action' && <NoActionDialog detail={detail} finding={dialog.finding} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'open-action' && <OpenActionDialog detail={detail} finding={dialog.finding} onClose={() => setDialog(null)} onDone={done} />}
      {detail && (dialog?.kind === 'verify' || dialog?.kind === 'cancel-action') && <ActionTransitionDialog detail={detail} action={dialog.action} kind={dialog.kind === 'verify' ? 'verify' : 'cancel'} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'judge' && <JudgeExpectationsDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'submit' && <SubmitReviewDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {detail && dialog?.kind === 'close' && <CloseDrillDialog detail={detail} onClose={() => setDialog(null)} onDone={done} />}
      {!query.initialising && query.error?.status === 404 && (
        <Button variant="ghost" onClick={() => navigate(drillPaths.register)}>Back to the register</Button>
      )}
    </div>
  );
};

export default DrillDetailPage;

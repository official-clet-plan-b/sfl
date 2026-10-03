import { useState } from 'react';
import { Banner, Button, Modal, ModalBody, ModalContent, ModalDescription, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import ReasonDialog from 'shared/components/ReasonDialog';
import StepTimeline, { historySteps } from 'shared/components/StepTimeline';
import { formatDateTime } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { permitApi, type Extension, type Flag, type Isolation, type Worker } from '../api/permitApi';
import { AddIsolationDialog, AddWorkerDialog, CompetencyDialog, EvidenceDialog, ExtensionDialog, IncidentLinkDialog } from './PermitDialogs';
import { PermitBadge, stageLabel } from './permitUi';

type Pending =
  | { kind: 'cancel' | 'reject' | 'suspend' | 'complete' | 'verified' | 'extension' | 'evidence' | 'incident' | 'worker' | 'isolation' | 'approve' }
  | { kind: 'competency'; worker: Worker }
  | { kind: 'decide'; extension: Extension; approve: boolean }
  | { kind: 'remove'; isolation: Isolation }
  | { kind: 'verify'; isolation: Isolation }
  | { kind: 'flag'; flag: Flag };

const Section = ({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) => (
  <section className="space-y-3 border-b border-border px-6 py-5">
    <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-semibold">{title}</h3>{actions}</div>
    {children}
  </section>
);

/**
 * One permit, from request to close-out: what stands between it and its next step, who has verified what, every approval and its
 * conditions, the people who were told when it was suspended, and the history that makes it evidence. Controls appear for a role the
 * service lets act and for a status the permit can act from; the service still refuses the independence rules (requester, verifier and
 * approver are different people), so a control that is shown may still be refused, with the reason.
 */
const PermitDetailDialog = ({ permitId, onClose, onChanged }: { permitId: string; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const detail = useApiQuery((signal) => permitApi.get(permitId, signal), [permitId]);
  const d = detail.data;
  const p = d?.permit;
  const status = p?.status;
  const can = {
    request: permits('PERMIT_REQUEST'), verify: permits('PERMIT_VERIFY_ISOLATION'), approve: permits('PERMIT_APPROVE'), signOff: permits('PERMIT_SAFETY_SIGN_OFF'), suspend: permits('PERMIT_SUSPEND'),
  };
  const canDecide = can.approve || can.signOff;
  const refresh = () => { detail.refetch(); onChanged(); };
  const done = () => { setPending(undefined); refresh(); };
  const close = () => setPending(undefined);
  const act = async (write: () => Promise<unknown>, success: string) => {
    try {
      await write();
      notifier.notifySuccess(success);
    } catch (cause) {
      notifier.notifyError(cause);
    }
    refresh();
  };

  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{p ? `${p.reference} · ${p.title}` : 'Permit'}</ModalTitle>
              <ModalDescription>Verification, approvals, suspension, extension, close-out and the full history.</ModalDescription>
            </ModalHeader>
            <ModalBody className="p-0">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {p && d && (
                  <>
                    <Section title="Summary" actions={<div className="flex flex-wrap items-center gap-2"><PermitBadge value={p.status} />{p.riskLevel && <PermitBadge value={p.riskLevel} label={`${humanise(p.riskLevel)} risk`} />}{d.overdue && <PermitBadge value="OVERDUE" label="Past its validity" />}</div>}>
                      {d.overdue && <Banner variant="warning" heading="Past its validity window without close-out" subtext="The authoriser has been told. It stays open until it is closed out; nothing closes it automatically." />}
                      {d.blockers.length > 0 && <Banner variant="warning" heading="What stands between this permit and its next step" subtext={d.blockers.join(' ')} />}
                      {d.flags.some((f) => f.status === 'OPEN') && <Banner variant="warning" heading="Flagged for review" subtext="An incident or an emergency touches this permit. Review it, and suspend if the work should stop." />}
                      {p.statusReason && (p.status === 'SUSPENDED' || p.status === 'REJECTED' || p.status === 'CANCELLED') && <p className="text-theme-sm text-gray-700">Reason: {p.statusReason}</p>}
                      <KeyValueGrid columns={3} items={[
                        { label: 'Type', value: d.type.name },
                        { label: 'Location', value: `${p.locationCode}${p.zoneCode ? ` · zone ${p.zoneCode}` : ''}` },
                        { label: 'Contractor', value: p.contractorReference ?? 'Not recorded' },
                        { label: 'Valid from', value: formatDateTime(p.startsAt) },
                        { label: 'Valid to', value: formatDateTime(p.endsAt) },
                        { label: 'Supervisor', value: p.supervisorReference },
                        { label: 'Risk assessment', value: p.riskAssessmentReference ? `${p.riskAssessmentReference} v${p.riskAssessmentVersion}` : 'None linked' },
                        { label: 'Assessment now', value: d.riskAssessment.assessmentId ? (d.riskAssessment.current ? 'Current' : `Not current (${humanise(d.riskAssessment.reason)})`) : '-' },
                        { label: 'Requested by', value: p.requestedBy },
                        { label: 'Approval round', value: String(p.approvalRound) },
                        { label: 'Originating work', value: p.originReference ? `${p.originSystem} ${p.originReference}` : 'None' },
                        { label: 'Waiting on', value: d.nextStage ? stageLabel(d.nextStage) : '-' },
                      ]} />
                      <p className="whitespace-pre-line text-theme-sm text-gray-700">{p.workDescription}</p>
                      {d.unverified.length > 0 && <p className="text-theme-xs text-gray-600">{d.unverified.join(' ')}</p>}
                      <div className="flex flex-wrap gap-2">
                        {status === 'DRAFT' && can.request && <Button size="sm" variant="primary" onClick={() => void act(() => permitApi.submit(p), 'Submitted for verification and approval')}>Submit</Button>}
                        {status === 'SUBMITTED' && can.verify && <Button size="sm" variant="primary" onClick={() => setPending({ kind: 'verified' })}>Record isolation verification complete</Button>}
                        {(status === 'ISOLATION_VERIFIED' || status === 'STAGE1_APPROVED' || status === 'RESUMPTION_PENDING') && d.nextStage && (d.nextStage === 'SAFETY_SIGN_OFF' ? can.signOff : can.approve) &&
                          <Button size="sm" variant="primary" onClick={() => setPending({ kind: 'approve' })}>Approve: {stageLabel(d.nextStage)}</Button>}
                        {(status === 'SUBMITTED' || status === 'ISOLATION_VERIFIED' || status === 'STAGE1_APPROVED' || status === 'RESUMPTION_PENDING') && canDecide && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'reject' })}>Reject</Button>}
                        {status === 'ACTIVE' && can.suspend && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'suspend' })}>Suspend</Button>}
                        {status === 'SUSPENDED' && (can.request || can.suspend) && <Button size="sm" variant="primary" onClick={() => void act(() => permitApi.requestResumption(p), 'Resumption needs a fresh approval')}>Request resumption</Button>}
                        {status === 'ACTIVE' && can.request && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'extension' })}>Request extension</Button>}
                        {(status === 'ACTIVE' || status === 'SUSPENDED' || status === 'WORK_COMPLETE') && can.request && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'evidence' })}>File evidence</Button>}
                        {(status === 'ACTIVE' || status === 'SUSPENDED') && can.request && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'complete' })}>Work is complete</Button>}
                        {status === 'WORK_COMPLETE' && (can.request || can.approve) && <Button size="sm" variant="primary" onClick={() => void act(() => permitApi.close(p), 'Permit closed')}>Close permit</Button>}
                        {status !== undefined && ['DRAFT', 'SUBMITTED', 'ISOLATION_VERIFIED', 'STAGE1_APPROVED'].includes(status) && (can.request || can.approve) && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'cancel' })}>Cancel permit</Button>}
                        {(status === 'ACTIVE' || status === 'SUSPENDED' || status === 'WORK_COMPLETE') && can.suspend && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'incident' })}>Link incident</Button>}
                      </div>
                    </Section>

                    <Section title={`Workers and competence (${d.workers.length})`} actions={status === 'DRAFT' && can.request ? <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'worker' })}>Add worker</Button> : undefined}>
                      {d.type.requiredCompetencies.length > 0 && <p className="text-theme-xs text-gray-600">This type checks: {d.type.requiredCompetencies.map(humanise).join(', ')}.</p>}
                      <ul className="space-y-2">
                        {d.workers.map((w) => {
                          const mine = d.competencyExceptions.filter((e) => e.workerId === w.id);
                          return (
                            <li key={w.id} className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border px-4 py-3">
                              <div className="min-w-0">
                                <p className="text-sm font-medium">{w.displayName} <span className="font-normal text-gray-600">· {w.personReference} · {humanise(w.workRole)}</span></p>
                                {mine.length === 0 ? <p className="text-theme-xs text-gray-600">{d.type.requiredCompetencies.length ? 'Every required competence is checked.' : 'No competence checks required.'}</p> :
                                  <p className="text-theme-xs text-[var(--clet-error-text)]">{mine.map((e) => `${humanise(e.competency)}: ${humanise(e.reason)}`).join(' · ')}</p>}
                              </div>
                              {can.verify && d.type.requiredCompetencies.length > 0 && ['SUBMITTED', 'ISOLATION_VERIFIED', 'STAGE1_APPROVED', 'RESUMPTION_PENDING'].includes(status ?? '') &&
                                <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'competency', worker: w })}>Record check</Button>}
                            </li>
                          );
                        })}
                      </ul>
                    </Section>

                    <Section title={`Isolations (${d.isolations.length})`} actions={status === 'DRAFT' && can.request ? <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'isolation' })}>Add isolation</Button> : undefined}>
                      {d.isolations.length === 0 && <p className="text-theme-sm text-gray-600">{d.type.requiresIsolation ? 'This type requires isolations: none are listed yet.' : 'No isolations listed.'}</p>}
                      <ul className="space-y-2">
                        {d.isolations.map((i) => (
                          <li key={i.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{humanise(i.kind)}: {i.description}{i.tagReference ? ` · ${i.tagReference}` : ''}</p>
                              <p className="text-theme-xs text-gray-600">{i.verifiedBy ? `Verified by ${i.verifiedBy} ${formatDateTime(i.verifiedAt)}` : 'Not yet verified'}{i.removedBy ? ` · removed by ${i.removedBy} ${formatDateTime(i.removedAt)}` : ''}</p>
                            </div>
                            <div className="flex items-center gap-2">
                              <PermitBadge value={i.status} />
                              {status === 'SUBMITTED' && i.status === 'REQUIRED' && can.verify && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'verify', isolation: i })}>Verify</Button>}
                              {status === 'WORK_COMPLETE' && i.status === 'VERIFIED' && can.verify && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'remove', isolation: i })}>Record removal</Button>}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Approvals (${d.approvals.length})`}>
                      {d.approvals.length === 0 && <p className="text-theme-sm text-gray-600">None yet. {d.type.twoStage ? 'This type needs an issuing authority and then an independent safety sign-off.' : 'This type needs one approval.'}</p>}
                      <ul className="space-y-1">
                        {d.approvals.map((a) => (
                          <li key={a.id} className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
                            <span>{humanise(a.purpose)} (round {a.approvalRound}) · {stageLabel(a.stage)} · {humanise(a.decision)} by {a.decidedBy}</span>
                            <span className="text-theme-xs text-gray-600">{formatDateTime(a.decidedAt)}{a.conditions ? ` · conditions: ${a.conditions}` : ''}{a.comment ? ` · ${a.comment}` : ''}</span>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    {d.extensions.length > 0 && (
                      <Section title={`Extensions (${d.extensions.length})`}>
                        <ul className="space-y-2">
                          {d.extensions.map((e) => (
                            <li key={e.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                              <div className="min-w-0"><p className="text-sm font-medium">To {formatDateTime(e.newEndsAt)}</p><p className="text-theme-xs text-gray-600">{e.reason} · requested by {e.requestedBy}{e.decidedBy ? ` · decided by ${e.decidedBy}` : ''}</p></div>
                              <div className="flex items-center gap-2">
                                <PermitBadge value={e.status} />
                                {e.status === 'PENDING' && canDecide && <><Button size="sm" variant="outline" onClick={() => setPending({ kind: 'decide', extension: e, approve: true })}>Approve</Button><Button size="sm" variant="outline" onClick={() => setPending({ kind: 'decide', extension: e, approve: false })}>Reject</Button></>}
                              </div>
                            </li>
                          ))}
                        </ul>
                      </Section>
                    )}

                    {d.suspensions.length > 0 && (
                      <Section title={`Suspensions (${d.suspensions.length})`}>
                        <ul className="space-y-1">{d.suspensions.map((s) => <li key={s.id} className="text-sm">{formatDateTime(s.suspendedAt)} · {s.suspendedBy}: {s.reason}{s.resumedAt ? ` · resumed ${formatDateTime(s.resumedAt)}` : ' · not resumed'}</li>)}</ul>
                        <p className="text-theme-xs text-gray-600">People told: {d.notifications.length === 0 ? 'none' : d.notifications.map((n) => `${n.recipientName ?? n.recipientReference} (${humanise(n.recipientRole)}, ${humanise(n.state)})`).join('; ')}. Queued means queued for the notification channel, not yet delivered.</p>
                      </Section>
                    )}

                    <Section title={`Close-out evidence (${d.evidence.length})`}>
                      {d.evidence.length === 0 && <p className="text-theme-sm text-gray-600">None filed. Close-out needs a completion statement and evidence.</p>}
                      <ul className="space-y-1">{d.evidence.map((e) => <li key={e.id} className="text-sm">{humanise(e.kind)} · {e.fileName} · {e.reference} <span className="text-theme-xs text-gray-600">· {e.submittedBy} {formatDateTime(e.submittedAt)}</span></li>)}</ul>
                      {p.completionStatement && <p className="text-theme-sm text-gray-700">Completion: {p.completionStatement}</p>}
                    </Section>

                    {d.flags.length > 0 && (
                      <Section title={`Flags (${d.flags.length})`}>
                        <ul className="space-y-2">
                          {d.flags.map((f) => (
                            <li key={f.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                              <div className="min-w-0"><p className="text-sm font-medium">{f.flagType === 'INCIDENT' ? 'Incident' : 'Emergency in zone'} {f.reference}</p><p className="text-theme-xs text-gray-600">{f.detail}{f.reviewNote ? ` · reviewed by ${f.reviewedBy}: ${f.reviewNote}` : ''}</p></div>
                              <div className="flex items-center gap-2"><PermitBadge value={f.status} />{f.status === 'OPEN' && can.suspend && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'flag', flag: f })}>Mark reviewed</Button>}</div>
                            </li>
                          ))}
                        </ul>
                      </Section>
                    )}

                    {d.escalations.length > 0 && <Section title="Expiry escalations"><ul className="space-y-1">{d.escalations.map((e) => <li key={e.id} className="flex items-center justify-between text-sm"><span>{formatDateTime(e.raisedAt)}</span><PermitBadge value={e.level} /></li>)}</ul></Section>}

                    <Section title="History"><StepTimeline empty="No recorded activity yet." steps={historySteps(d.history.map((h) => ({ ...h, subjectType: 'PERMIT' })))} /></Section>
                  </>
                )}
              </DataState>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>

      {p && pending?.kind === 'worker' && <AddWorkerDialog permitId={p.id} onClose={close} onDone={done} />}
      {p && pending?.kind === 'isolation' && <AddIsolationDialog permitId={p.id} onClose={close} onDone={done} />}
      {p && d && pending?.kind === 'competency' && <CompetencyDialog permit={p} workerId={pending.worker.id} workerName={pending.worker.displayName} competencies={d.type.requiredCompetencies} onClose={close} onDone={done} />}
      {p && pending?.kind === 'extension' && <ExtensionDialog permit={p} onClose={close} onDone={done} />}
      {p && pending?.kind === 'evidence' && <EvidenceDialog permitId={p.id} onClose={close} onDone={done} />}
      {p && pending?.kind === 'incident' && <IncidentLinkDialog permitId={p.id} onClose={close} onDone={done} />}
      {p && pending?.kind === 'cancel' && <ReasonDialog title="Cancel the permit" description="It has not been issued. The reason is kept." label="Reason" submitLabel="Cancel permit" destructive write={(r) => permitApi.cancel(p, r)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'reject' && <ReasonDialog title="Reject the permit" description="Ends the request at the stage it is waiting on. A rejected resumption leaves the permit suspended. The reason is kept." label="Reason" submitLabel="Reject" destructive write={(r) => permitApi.reject(p, r)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'suspend' && <ReasonDialog title="Suspend the permit" description="Work stops now. The supervisor and every worker on the permit are queued for notification as part of this action, and resuming needs a fresh approval." label="Why" submitLabel="Suspend" destructive write={(r) => permitApi.suspend(p, r)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'complete' && <ReasonDialog title="Work is complete" description="State what was done and the condition the area was left in. Needs at least one piece of evidence filed. Isolations are removed in a separate step by someone else." label="Completion statement" submitLabel="Record completion" write={(r) => permitApi.complete(p, r)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'verified' && <ReasonDialog title="Isolation verification complete" description="Confirms every required isolation was checked in place, by you, and is recorded against your name. Approval is refused until this exists." label="What you checked" submitLabel="Record verification" minimum={0} write={(r) => permitApi.completeVerification(p, r || undefined)} onClose={close} onDone={done} />}
      {p && d && pending?.kind === 'approve' && <ReasonDialog title={`Approve: ${stageLabel(d.nextStage)}`} description="Names any conditions of the permit. You cannot approve a permit you requested, and the safety sign-off cannot be the issuing authority." label="Conditions" submitLabel="Approve" minimum={0} write={(r) => permitApi.approve(p, r || undefined)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'verify' && <ReasonDialog title="Verify the isolation" description="Say how you confirmed it. Someone other than the requester must verify." label="What you checked" submitLabel="Verify" minimum={0} write={(r) => permitApi.verifyIsolation(p.id, pending.isolation.id, r || undefined)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'remove' && <ReasonDialog title="Record the isolation's removal" description="By a competent person who is not the requester. The permit cannot close until every isolation has one." label="Note" submitLabel="Record removal" minimum={0} write={(r) => permitApi.removeIsolation(p.id, pending.isolation.id, r || undefined)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'decide' && <ReasonDialog title={pending.approve ? 'Approve the extension' : 'Reject the extension'} description={pending.approve ? 'Same stages as an issue. Validity moves only when the last stage approves.' : 'The permit keeps its present end.'} label={pending.approve ? 'Note' : 'Why'} submitLabel={pending.approve ? 'Approve' : 'Reject'} minimum={pending.approve ? 0 : 1} destructive={!pending.approve} write={(r) => permitApi.decideExtension(p, pending.extension.id, pending.approve, r || undefined)} onClose={close} onDone={done} />}
      {p && pending?.kind === 'flag' && <ReasonDialog title="Mark the flag reviewed" description="Say what you decided. This does not suspend the permit; suspend it separately if the work should stop." label="Review note" submitLabel="Mark reviewed" write={(r) => permitApi.reviewFlag(p.id, pending.flag.id, r)} onClose={close} onDone={done} />}
    </>
  );
};

export default PermitDetailDialog;

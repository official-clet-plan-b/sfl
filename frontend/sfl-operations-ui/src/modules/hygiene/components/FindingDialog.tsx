import { useState } from 'react';
import { Banner, Button, Modal, ModalBody, ModalContent, ModalDescription, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { hygieneApi, type ActionStatus, type HygieneAction, type HygieneEvidence } from '../api/hygieneApi';
import { ActionFormDialog, EvidenceFormDialog, ReasonDialog } from './HygieneDialogs';
import HistoryList from './HistoryList';
import { HygieneBadge, linkText } from './hygieneUi';

type Pending =
  | { kind: 'action' }
  | { kind: 'evidence' }
  | { kind: 'exception' }
  | { kind: 'reopen' }
  | { kind: 'incident' }
  | { kind: 'reject-action'; action: HygieneAction }
  | { kind: 'reject-evidence'; evidence: HygieneEvidence };

const Section = ({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) => (
  <section className="space-y-3 border-b border-border px-6 py-5">
    <div className="flex items-center justify-between gap-3">
      <h3 className="text-sm font-semibold">{title}</h3>
      {actions}
    </div>
    {children}
  </section>
);

/**
 * One finding, everything about it: what was found, what is being done about it, what proves it, who
 * decided what and when. Every button is offered only to a role the service would let press it; a refusal
 * the screen could not foresee - a stale version, someone else got there first - comes back in the
 * service's own words.
 */
const FindingDialog = ({ findingId, onClose, onChanged }: { findingId: string; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_HYGIENE_MANAGE');
  const canVerify = permits('FACILITIES_HYGIENE_VERIFY');
  const canSeeEvidence = permits('FACILITIES_HYGIENE_EVIDENCE_READ');

  const detail = useApiQuery((signal) => hygieneApi.finding(findingId, signal), [findingId]);
  const evidence = useApiQuery(
    (signal) => (canSeeEvidence ? hygieneApi.evidence(findingId, signal) : Promise.resolve([] as HygieneEvidence[])),
    [findingId, canSeeEvidence],
  );

  const refresh = () => { detail.refetch(); evidence.refetch(); onChanged(); };
  const act = async (write: () => Promise<unknown>, success: string) => {
    try {
      await write();
      notifier.notifySuccess(success);
      refresh();
    } catch (cause) {
      notifier.notifyError(cause);
    }
  };

  const finding = detail.data?.finding;
  const open = finding && finding.status !== 'CLOSED';
  const done = () => { setPending(undefined); refresh(); };

  const nextActions = (action: HygieneAction): Array<{ label: string; status: ActionStatus; needsVerify?: boolean; reason?: boolean }> => {
    switch (action.status) {
      case 'OPEN': return [{ label: 'Start', status: 'IN_PROGRESS' }, { label: 'Mark done', status: 'COMPLETED' }];
      case 'IN_PROGRESS': return [{ label: 'Mark done', status: 'COMPLETED' }];
      case 'REJECTED': return [{ label: 'Rework', status: 'IN_PROGRESS' }, { label: 'Mark done', status: 'COMPLETED' }];
      case 'COMPLETED': return [{ label: 'Verify', status: 'VERIFIED', needsVerify: true }, { label: 'Reject', status: 'REJECTED', needsVerify: true, reason: true }];
      default: return [];
    }
  };

  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{finding ? `${finding.reference} · ${finding.title}` : 'Finding'}</ModalTitle>
              <ModalDescription>Severity, ownership, corrective actions, evidence and the record of every decision.</ModalDescription>
            </ModalHeader>
            <ModalBody className="p-0">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {finding && (
                  <>
                    <Section title="Summary" actions={
                      <div className="flex flex-wrap items-center gap-2">
                        <HygieneBadge value={finding.severity} />
                        <HygieneBadge value={detail.data?.overdue ? 'OVERDUE' : finding.status} />
                        {finding.escalationLevel !== 'NONE' && <HygieneBadge value={finding.escalationLevel} label={`Escalated to ${humanise(finding.escalationLevel)}`} />}
                      </div>
                    }>
                      {finding.severity === 'CRITICAL' && finding.status !== 'CLOSED' && (
                        <Banner variant="warning" heading="Critical finding" subtext="HSE has been notified. It closes only on accepted evidence with every action verified, or an approved exception." />
                      )}
                      <KeyValueGrid columns={3} items={[
                        { label: 'Category', value: humanise(finding.category) },
                        { label: 'Owner', value: finding.ownerReference ?? 'Not assigned' },
                        { label: 'Target date', value: finding.targetDate ? formatDate(finding.targetDate) : '-' },
                        { label: 'Status', value: humanise(finding.status) },
                        { label: 'Maintenance work order (S153)', value: <>{linkText(finding.workOrderState, finding.workOrderNumber, 'S153')}</> },
                        { label: 'Safety incident (S163)', value: finding.requiresIncident ? linkText(finding.incidentState, finding.incidentReference, 'S163') : 'Not required' },
                        { label: 'Description', value: finding.description ?? '-', span: 2 },
                        ...(finding.closureMode ? [{ label: 'Closed by', value: `${humanise(finding.closureMode)}${finding.closureApprovedBy ? ` · approved by ${finding.closureApprovedBy}` : ''}${finding.closedAt ? ` · ${formatDateTime(finding.closedAt)}` : ''}`, span: 2 as const }] : []),
                        ...(finding.closureReason ? [{ label: 'Closure reason', value: finding.closureReason, span: 2 as const }] : []),
                      ]} />
                      <div className="flex flex-wrap gap-2">
                        {canManage && finding.workOrderState === 'PENDING_MANUAL' && (
                          <Button size="sm" variant="outline" onClick={() => void act(() => hygieneApi.retryWorkOrder(finding.id), 'Work order request sent')}>Retry work order</Button>
                        )}
                        {canManage && open && finding.requiresIncident && finding.incidentState !== 'LINKED' && (
                          <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'incident' })}>Link incident</Button>
                        )}
                        {canManage && finding.status === 'OPEN' && (
                          <Button size="sm" variant="outline" onClick={() => void act(() => hygieneApi.startFinding(finding), 'Finding started')}>Start work</Button>
                        )}
                      </div>
                    </Section>

                    <Section title={`Corrective actions (${detail.data?.actions.length ?? 0})`} actions={canManage && open && (
                      <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'action' })}>Add action</Button>
                    )}>
                      {(detail.data?.actions ?? []).length === 0 && <p className="text-theme-sm text-gray-600">No corrective actions yet.</p>}
                      <ul className="space-y-2">
                        {(detail.data?.actions ?? []).map((action) => (
                          <li key={action.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{action.description}</p>
                              <p className="text-theme-xs text-gray-600">
                                {action.ownerReference} · due {formatDate(action.dueOn)}
                                {action.verifiedBy ? ` · verified by ${action.verifiedBy}` : action.completedBy ? ` · done by ${action.completedBy}` : ''}
                              </p>
                              {action.rejectionReason && <p className="text-theme-xs text-[var(--clet-error-text)]">Rejected: {action.rejectionReason}</p>}
                            </div>
                            <div className="flex items-center gap-2">
                              <HygieneBadge value={action.status} />
                              {open && nextActions(action).filter((next) => (next.needsVerify ? canVerify : canManage)).map((next) => (
                                <Button key={next.label} size="sm" variant="outline"
                                  onClick={() => next.reason
                                    ? setPending({ kind: 'reject-action', action })
                                    : void act(() => hygieneApi.moveAction(action, next.status), `Action ${humanise(next.status).toLowerCase()}`)}>
                                  {next.label}
                                </Button>
                              ))}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Evidence (${detail.data?.evidenceCount ?? 0})`} actions={canManage && open && (
                      <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'evidence' })}>Submit evidence</Button>
                    )}>
                      {!canSeeEvidence && <p className="text-theme-sm text-gray-600">Your role can see that evidence exists but not its content.</p>}
                      {canSeeEvidence && (
                        <DataState loading={evidence.initialising} error={evidence.error} onRetry={evidence.refetch}>
                          {(evidence.data ?? []).length === 0 && <p className="text-theme-sm text-gray-600">No evidence submitted yet.</p>}
                          <ul className="space-y-2">
                            {(evidence.data ?? []).map((item) => (
                              <li key={item.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                                <div className="min-w-0">
                                  <p className="text-sm font-medium">{item.fileName}</p>
                                  <p className="text-theme-xs text-gray-600">
                                    {item.reference} · {humanise(item.retentionClass)} retention · submitted by {item.submittedBy}
                                  </p>
                                  <p className="font-mono text-theme-xs text-gray-600" title="SHA-256">{item.contentHash.slice(0, 16)}…</p>
                                  {item.reviewReason && <p className="text-theme-xs text-[var(--clet-error-text)]">Rejected: {item.reviewReason}</p>}
                                </div>
                                <div className="flex items-center gap-2">
                                  <HygieneBadge value={item.status} />
                                  {open && canVerify && item.status === 'SUBMITTED' && (
                                    <>
                                      <Button size="sm" variant="outline" onClick={() => void act(() => hygieneApi.reviewEvidence(item.id, true), 'Evidence accepted')}>Accept</Button>
                                      <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'reject-evidence', evidence: item })}>Reject</Button>
                                    </>
                                  )}
                                </div>
                              </li>
                            ))}
                          </ul>
                        </DataState>
                      )}
                    </Section>

                    {canVerify && (
                      <Section title="Closure">
                        {open ? (
                          <div className="flex flex-wrap gap-2">
                            <Button size="sm" variant="primary" onClick={() => void act(() => hygieneApi.closeFinding(finding, 'EVIDENCE'), 'Finding closed')}>Close with evidence</Button>
                            <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'exception' })}>Close by exception</Button>
                          </div>
                        ) : (
                          <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'reopen' })}>Reopen</Button>
                        )}
                      </Section>
                    )}

                    <Section title="History">
                      <HistoryList history={detail.data?.history ?? []} />
                    </Section>
                  </>
                )}
              </DataState>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>

      {finding && pending?.kind === 'action' && <ActionFormDialog finding={finding} onClose={() => setPending(undefined)} onDone={done} />}
      {finding && pending?.kind === 'evidence' && <EvidenceFormDialog finding={finding} actions={detail.data?.actions ?? []} onClose={() => setPending(undefined)} onDone={done} />}
      {finding && pending?.kind === 'incident' && (
        <ReasonDialog title="Link the incident" description="Enter the reference Incident Reporting (S163) gave this incident." label="Incident reference" submitLabel="Link incident"
          write={(reference) => hygieneApi.linkIncident(finding.id, reference)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {finding && pending?.kind === 'exception' && (
        <ReasonDialog title="Close by exception" description="The finding is accepted as it stands. You must give the reason, and you cannot be the person who raised it." label="Reason for the exception" minimum={10}
          submitLabel="Close finding" write={(reason) => hygieneApi.closeFinding(finding, 'EXCEPTION', reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {finding && pending?.kind === 'reopen' && (
        <ReasonDialog title="Reopen the finding" description="It returns to in progress and its closure details are cleared. The reopening is recorded." label="Why it is being reopened"
          submitLabel="Reopen" write={(reason) => hygieneApi.reopenFinding(finding.id, reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {pending?.kind === 'reject-action' && (
        <ReasonDialog title="Reject the action" description="It goes back to its owner to be done properly." label="What is wrong with it" submitLabel="Reject action" destructive
          write={(reason) => hygieneApi.moveAction(pending.action, 'REJECTED', reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {pending?.kind === 'reject-evidence' && (
        <ReasonDialog title="Reject the evidence" description="Say why it does not prove the correction." label="Reason" submitLabel="Reject evidence" destructive
          write={(reason) => hygieneApi.reviewEvidence(pending.evidence.id, false, reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
    </>
  );
};

export default FindingDialog;

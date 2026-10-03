import { useState } from 'react';
import { Banner, Button, Modal, ModalBody, ModalContent, ModalDescription, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import ReasonDialog from 'shared/components/ReasonDialog';
import StepTimeline, { historySteps } from 'shared/components/StepTimeline';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { leaseApi, type Amendment, type Obligation } from '../api/leaseApi';
import { AmendmentDialog, DocumentDialog, ObligationDialog } from './LeaseDialogs';
import { LeaseBadge, describeAmendment, money } from './leaseUi';

type Pending =
  | { kind: 'amend' | 'document' | 'obligation' | 'owner' | 'return' | 'work' }
  | { kind: 'decide'; amendment: Amendment; approve: boolean }
  | { kind: 'legal'; amendment: Amendment }
  | { kind: 'complete' | 'waive'; obligation: Obligation };

const Section = ({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) => (
  <section className="space-y-3 border-b border-border px-6 py-5">
    <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-semibold">{title}</h3>{actions}</div>
    {children}
  </section>
);

/**
 * One agreement, with what stops it being approved, every version it has had and who approved each, the
 * amendments waiting on someone, and the dates being watched. Money is shown only to the financial grant; to
 * anyone else the screen says so rather than showing a blank that could be read as zero.
 */
const AgreementDialog = ({ agreementId, onClose, onChanged }: { agreementId: string; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_LEASE_MANAGE');
  const canApprove = permits('FACILITIES_LEASE_APPROVE');
  const canLegal = permits('FACILITIES_LEASE_LEGAL_REVIEW');
  const detail = useApiQuery((signal) => leaseApi.agreement(agreementId, signal), [agreementId]);
  const d = detail.data;
  const a = d?.agreement;
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
  const live = a?.status === 'ACTIVE';
  const ended = a && ['EXPIRED', 'TERMINATED', 'ARCHIVED'].includes(a.status);

  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{a ? `${a.reference} · ${a.title}` : 'Agreement'}</ModalTitle>
              <ModalDescription>Terms, what is needed for approval, every version, amendments, obligations and documents.</ModalDescription>
            </ModalHeader>
            <ModalBody className="p-0">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {a && d && (
                  <>
                    <Section title="Summary" actions={<div className="flex flex-wrap items-center gap-2"><LeaseBadge value={a.status} /><LeaseBadge value={a.counterpartyState} label={a.counterpartyState === 'UNRESOLVED' ? 'Counterparty unresolved' : 'Counterparty verified'} /></div>}>
                      {d.pastEndDate && <Banner variant="warning" heading="Past its end date" subtext="The daily control marks it expired, tells the director and legal, and asks S153 for a review of anything that depends on it." />}
                      {(a.status === 'DRAFT' || a.status === 'IN_REVIEW') && d.blockers.length > 0 && (
                        <Banner variant="warning" heading="Incomplete - cannot be approved yet" subtext={d.blockers.join(' ')} />
                      )}
                      {d.warnings.length > 0 && <p className="text-theme-xs text-gray-600">{d.warnings.join(' ')}</p>}
                      {!d.financialView && <p className="text-theme-xs text-gray-600">Rent and deposit are not shown to your role.</p>}
                      <KeyValueGrid columns={3} items={[
                        { label: 'Property', value: a.propertyReference },
                        { label: 'CLET is the', value: a.direction === 'INBOUND' ? 'Tenant' : 'Landlord' },
                        { label: 'Owner', value: a.ownerReference ?? 'Not assigned' },
                        { label: 'Term', value: `${formatDate(a.startDate)} to ${formatDate(a.endDate)}` },
                        { label: 'Notice period', value: a.noticeDays == null ? 'Missing' : `${a.noticeDays} days` },
                        { label: 'Notice date', value: a.noticeDate ? formatDate(a.noticeDate) : 'Cannot be calculated' },
                        { label: 'Renewal', value: `${humanise(a.renewalType)}${a.renewalTermMonths ? ` · ${a.renewalTermMonths} months` : ''}` },
                        { label: 'Rent review', value: a.rentReviewDate ? formatDate(a.rentReviewDate) : '-' },
                        { label: 'Counterparty', value: a.counterpartyReference ?? 'Not recorded' },
                        { label: 'Annual rent', value: money(a.annualRent, a.currency, d.financialView) },
                        { label: 'Deposit', value: money(a.depositAmount, a.currency, d.financialView) },
                        { label: 'Version', value: `${a.versionNumber}${a.approvedBy ? ` · approved by ${a.approvedBy}` : ''}` },
                      ]} />
                      <div className="flex flex-wrap gap-2">
                        {canManage && a.status === 'DRAFT' && <Button size="sm" variant="primary" onClick={() => void act(() => leaseApi.submit(a), 'Submitted for review')}>Submit for approval</Button>}
                        {canApprove && a.status === 'IN_REVIEW' && <Button size="sm" variant="primary" onClick={() => void act(() => leaseApi.approve(a), 'Agreement approved and active')}>Approve</Button>}
                        {canApprove && a.status === 'IN_REVIEW' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'return' })}>Return to draft</Button>}
                        {canManage && live && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'amend' })}>Propose amendment</Button>}
                        {canManage && !ended && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'owner' })}>Change owner</Button>}
                        {canManage && !ended && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'document' })}>File document</Button>}
                        {canManage && !ended && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'obligation' })}>Add obligation</Button>}
                      </div>
                    </Section>

                    <Section title={`Amendments (${d.amendments.length})`}>
                      {d.amendments.length === 0 && <p className="text-theme-sm text-gray-600">No amendments. Rent, term, renewal and termination change only by an approved amendment.</p>}
                      <ul className="space-y-2">
                        {d.amendments.map((m) => (
                          <li key={m.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{m.reference} · {describeAmendment(m, a.currency)}</p>
                              <p className="text-theme-xs text-gray-600">{m.reason} · proposed by {m.proposedBy}{m.decidedBy ? ` · decided by ${m.decidedBy}` : ''}</p>
                              {m.status === 'LEGAL_REVIEW' && <p className="text-theme-xs text-[var(--clet-error-text)]">Held: it conflicts with another open amendment. It cannot be approved until legal clears it.</p>}
                              {m.decisionReason && <p className="text-theme-xs text-gray-600">{m.decisionReason}</p>}
                            </div>
                            <div className="flex flex-wrap items-center gap-2">
                              <LeaseBadge value={m.status} />
                              {canApprove && m.status === 'PROPOSED' && (
                                <>
                                  <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'decide', amendment: m, approve: true })}>Approve</Button>
                                  <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'decide', amendment: m, approve: false })}>Reject</Button>
                                </>
                              )}
                              {canLegal && m.status === 'LEGAL_REVIEW' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'legal', amendment: m })}>Clear legal review</Button>}
                              {canManage && (m.status === 'PROPOSED' || m.status === 'LEGAL_REVIEW') && <Button size="sm" variant="outline" onClick={() => void act(() => leaseApi.withdraw(m.id), 'Amendment withdrawn')}>Withdraw</Button>}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Versions (${d.versions.length})`}>
                      {d.versions.length === 0 && <p className="text-theme-sm text-gray-600">Version 1 is recorded when the agreement is approved.</p>}
                      <ul className="space-y-1">
                        {d.versions.map((ver) => (
                          <li key={ver.id} className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
                            <span className="font-medium">Version {ver.versionNumber}</span>
                            <span className="text-theme-xs text-gray-600">Ends {formatDate(ver.endDate)} · rent {money(ver.annualRent, a.currency, d.financialView)} · notice {ver.noticeDays ?? '-'} days · {ver.approvedBy ? `approved by ${ver.approvedBy}` : `recorded by system`}</span>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Obligations (${d.obligations.length})`}>
                      {d.obligations.length === 0 && <p className="text-theme-sm text-gray-600">None yet. They are generated when the agreement is approved.</p>}
                      <ul className="space-y-2">
                        {d.obligations.map((o) => (
                          <li key={o.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{o.title}</p>
                              <p className="text-theme-xs text-gray-600">{humanise(o.kind)} · due {formatDate(o.dueOn)} · {o.ownerReference ?? 'no owner'}{o.completionNote ? ` · ${o.completionNote}` : ''}</p>
                            </div>
                            <div className="flex items-center gap-2">
                              <LeaseBadge value={o.status} />
                              {canManage && o.status === 'OPEN' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'complete', obligation: o })}>Complete</Button>}
                              {canApprove && o.status === 'OPEN' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'waive', obligation: o })}>Waive</Button>}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Documents (${d.documents.length})`}>
                      {d.documents.length === 0 && <p className="text-theme-sm text-gray-600">No documents filed. Approval evidence is needed before approval.</p>}
                      <ul className="space-y-1">
                        {d.documents.map(({ document, expired }) => (
                          <li key={document.id} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                            <span>{humanise(document.kind)} · {document.fileName} · {document.reference}{document.expiresOn ? ` · expires ${formatDate(document.expiresOn)}` : ''}</span>
                            {expired && <LeaseBadge value="EXPIRED" label="Expired" />}
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Corrective work (${d.workOrders.length})`} actions={canManage && !ended ? <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'work' })}>Raise work order</Button> : undefined}>
                      {d.workOrders.length === 0 && <p className="text-theme-sm text-gray-600">None. A work order is raised in S153 automatically when an agreement lapses, or by hand here.</p>}
                      <ul className="space-y-2">
                        {d.workOrders.map((w) => (
                          <li key={w.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{w.state === 'RAISED' ? `S153 ${w.workOrderNumber}` : 'Not yet raised in S153'} · {humanise(w.trigger)}</p>
                              <p className="text-theme-xs text-gray-600">{w.description}</p>
                              {w.state === 'PENDING_MANUAL' && <p className="text-theme-xs text-[var(--clet-error-text)]">S153 has not confirmed it. It is retried each day, or retry it now.</p>}
                            </div>
                            <div className="flex items-center gap-2">
                              <LeaseBadge value={w.state} label={w.state === 'RAISED' ? 'Raised' : 'Pending'} />
                              {canManage && w.state === 'PENDING_MANUAL' && <Button size="sm" variant="outline" onClick={() => void act(() => leaseApi.retryWorkOrder(w.id), 'Retried')}>Retry</Button>}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    {d.alerts.length > 0 && (
                      <Section title={`Alerts (${d.alerts.length})`}>
                        <ul className="space-y-1">
                          {d.alerts.map((al) => <li key={al.id} className="flex flex-wrap items-center justify-between gap-2 text-sm"><span>{al.detail ?? humanise(al.reason)} · {formatDateTime(al.raisedAt)}</span><LeaseBadge value={al.level} /></li>)}
                        </ul>
                      </Section>
                    )}

                    <Section title="History"><StepTimeline empty="No recorded activity yet." steps={historySteps(d.history)} /></Section>
                  </>
                )}
              </DataState>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>

      {a && pending?.kind === 'amend' && <AmendmentDialog agreement={a} canSeeMoney={d?.financialView ?? false} onClose={close} onDone={done} />}
      {a && pending?.kind === 'document' && <DocumentDialog agreement={a} onClose={close} onDone={done} />}
      {a && pending?.kind === 'obligation' && <ObligationDialog agreement={a} onClose={close} onDone={done} />}
      {a && pending?.kind === 'owner' && <ReasonDialog title="Change the owner" description="An administrative change: it moves nobody's money or time, and writes a new version." label="New owner" submitLabel="Change owner" write={(owner) => leaseApi.reassign(a, owner)} onClose={close} onDone={done} />}
      {a && pending?.kind === 'return' && <ReasonDialog title="Return to draft" description="Sends it back to be completed. The reason is kept." label="What is missing" submitLabel="Return to draft" write={(reason) => leaseApi.returnToDraft(a, reason)} onClose={close} onDone={done} />}
      {a && pending?.kind === 'work' && <ReasonDialog title="Raise a work order" description="Asks S153 for corrective work on this agreement's property. If S153 does not answer it stays pending and can be retried." label="What needs doing" submitLabel="Raise work order" write={(text) => leaseApi.raiseWorkOrder(a.id, text)} onClose={close} onDone={done} />}
      {pending?.kind === 'decide' && (
        <ReasonDialog title={pending.approve ? 'Approve the amendment' : 'Reject the amendment'} description={pending.approve ? 'Writes a new version and keeps the prior one. You cannot approve an amendment you proposed.' : 'Nothing changes. The reason is kept.'}
          label={pending.approve ? 'Why it is approved' : 'Why it is rejected'} submitLabel={pending.approve ? 'Approve' : 'Reject'} destructive={!pending.approve} minimum={pending.approve ? 0 : 1}
          write={(reason) => leaseApi.decide(pending.amendment, pending.approve, reason)} onClose={close} onDone={done} />
      )}
      {pending?.kind === 'legal' && <ReasonDialog title="Clear legal review" description="Say how the conflict is resolved. The amendment returns to the approval queue." label="Legal note" submitLabel="Clear" write={(note) => leaseApi.clearLegal(pending.amendment.id, note)} onClose={close} onDone={done} />}
      {pending?.kind === 'complete' && <ReasonDialog title="Complete the obligation" description="Record what was done." label="Note" submitLabel="Mark done" write={(note) => leaseApi.complete(pending.obligation, note)} onClose={close} onDone={done} />}
      {pending?.kind === 'waive' && <ReasonDialog title="Waive the obligation" description="Deciding it need not be done takes an approver and a reason; otherwise it would be a way to make an alert go away." label="Reason" submitLabel="Waive" destructive write={(reason) => leaseApi.waive(pending.obligation, reason)} onClose={close} onDone={done} />}
    </>
  );
};

export default AgreementDialog;

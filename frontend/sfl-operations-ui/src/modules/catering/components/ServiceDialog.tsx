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
import { cateringApi, type DietaryRequest, type Variance } from '../api/cateringApi';
import { ApproveDialog, ChangeDialog, CheckDialog, DeliverDialog, EvidenceDialog, ExceptionFormDialog, NeedDialog, ReconcileDialog, SubstituteDialog, VarianceDialog } from './CateringDialogs';
import { CateringBadge, labelsOf } from './cateringUi';

type Pending =
  | { kind: 'change' | 'need' | 'approve' | 'deliver' | 'check' | 'variance' | 'reconcile' | 'evidence' | 'exception' | 'cancel' }
  | { kind: 'substitute' | 'waive'; request: DietaryRequest };

const Section = ({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) => (
  <section className="space-y-3 border-b border-border px-6 py-5">
    <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-semibold">{title}</h3>{actions}</div>
    {children}
  </section>
);

/**
 * One service. The menu's allergen and diet labels and the controls that stand between it and confirmation are
 * on the screen from the moment it is opened - a service whose supplier check has lapsed says so here rather
 * than looking compliant until someone tries to confirm it. Every button is offered only to a role the service
 * would let press it; a refusal the screen could not foresee comes back in the service's own words.
 */
const ServiceDialog = ({ serviceId, onClose, onChanged }: { serviceId: string; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_CATERING_MANAGE');
  const canApprove = permits('FACILITIES_CATERING_APPROVE');
  const detail = useApiQuery((signal) => cateringApi.service(serviceId, signal), [serviceId]);
  const d = detail.data;
  const s = d?.service;
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
  const planning = s && ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED'].includes(s.status);
  const post = s && ['DELIVERED', 'RECONCILED'].includes(s.status);

  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{s ? `${s.reference} · ${s.title}` : 'Service'}</ModalTitle>
              <ModalDescription>Menu labels, controls, dietary needs, checks, variances and the record of every decision.</ModalDescription>
            </ModalHeader>
            <ModalBody className="p-0">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {s && d && (
                  <>
                    <Section title="Summary" actions={<div className="flex flex-wrap items-center gap-2"><CateringBadge value={s.status} />{s.financeState !== 'NOT_STARTED' && <CateringBadge value={s.financeState} />}</div>}>
                      {planning && d.readiness.length > 0 && (
                        <Banner variant="warning" heading="Not valid to confirm yet" subtext={d.readiness.map((b) => b.message).join(' ')} />
                      )}
                      {planning && d.readiness.length === 0 && s.status !== 'DRAFT' && <Banner variant="info" heading="Controls clear" subtext="Venue, menu, allergens, supplier and capacity are all valid today. They are checked again at approval and at confirmation." />}
                      <KeyValueGrid columns={3} items={[
                        { label: 'Venue', value: `${d.venue.name} (capacity ${d.venue.capacity})` },
                        { label: 'Supplier', value: `${d.supplier.name}${d.supplier.status === 'SUSPENDED' ? ' - suspended' : ''}` },
                        { label: 'Menu', value: `${d.menu.name} (${humanise(d.menu.status)})` },
                        { label: 'Serves', value: `${humanise(s.contextType)}${s.contextReference ? ` · ${s.contextReference}` : ''}` },
                        { label: 'Starts', value: formatDateTime(s.startsAt) },
                        { label: 'Cancellation cut-off', value: formatDateTime(s.cancellationCutoff) },
                        { label: 'Guests', value: String(s.expectedGuests) },
                        { label: 'Portions', value: s.deliveredPortions == null ? `${s.plannedPortions} planned` : `${s.deliveredPortions} of ${s.plannedPortions}` },
                        { label: 'Approved by', value: s.approvedBy ?? 'Not approved' },
                        ...(s.capacityExceptionReason ? [{ label: 'Capacity exception', value: s.capacityExceptionReason, span: 2 as const }] : []),
                        ...(s.supplierExceptionReason ? [{ label: 'Supplier exception', value: s.supplierExceptionReason, span: 2 as const }] : []),
                        ...(s.purchaseReference ? [{ label: 'Purchase / invoice', value: `${s.purchaseReference} / ${s.invoiceReference ?? 'pending'}` }] : []),
                      ]} />
                      <div className="flex flex-wrap gap-2">
                        {canManage && s.status === 'DRAFT' && <Button size="sm" variant="primary" onClick={() => void act(() => cateringApi.submit(s), 'Submitted for approval')}>Submit for approval</Button>}
                        {canApprove && s.status === 'PENDING_APPROVAL' && <Button size="sm" variant="primary" onClick={() => setPending({ kind: 'approve' })}>Approve</Button>}
                        {canManage && s.status === 'APPROVED' && <Button size="sm" variant="primary" onClick={() => void act(() => cateringApi.confirm(s), 'Service confirmed')}>Confirm</Button>}
                        {canManage && s.status === 'CONFIRMED' && <Button size="sm" variant="primary" onClick={() => setPending({ kind: 'deliver' })}>Record delivery</Button>}
                        {canManage && s.status === 'RECONCILED' && <Button size="sm" variant="primary" onClick={() => void act(() => cateringApi.close(s), 'Service closed')}>Close with evidence</Button>}
                        {canManage && planning && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'change' })}>Change plan</Button>}
                        {canManage && planning && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'need' })}>Add dietary need</Button>}
                        {canManage && (s.status === 'CONFIRMED' || post) && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'check' })}>Record check</Button>}
                        {canManage && s.status === 'DELIVERED' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'reconcile' })}>Reconcile</Button>}
                        {canManage && post && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'variance' })}>Record variance</Button>}
                        {canManage && (post || s.status === 'CONFIRMED') && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'evidence' })}>File evidence</Button>}
                        {canManage && s.status !== 'CLOSED' && s.status !== 'CANCELLED' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'exception' })}>Raise exception</Button>}
                        {(canManage || canApprove) && planning && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'cancel' })}>Cancel service</Button>}
                      </div>
                    </Section>

                    <Section title={`Menu - ${d.menu.name}`}>
                      <ul className="space-y-1">
                        {d.items.map((item) => (
                          <li key={item.id} className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
                            <span className="font-medium">{item.name}</span><span className="text-theme-xs text-gray-600">{labelsOf(item)}</span>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Dietary needs (${d.dietaryCount})`}>
                      {!d.dietaryView && <p className="text-theme-sm text-gray-600">Your role sees how many dietary needs there are, not who needs what.</p>}
                      {d.dietaryView && d.needs.length === 0 && <p className="text-theme-sm text-gray-600">No dietary needs recorded.</p>}
                      <ul className="space-y-2">
                        {d.needs.map((need) => (
                          <li key={need.request.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{need.request.personReference} · {humanise(need.request.needType)}: {humanise(need.request.needCode)}</p>
                              <p className="text-theme-xs text-gray-600">Authorised by {need.request.authorisedBy}{need.flaggedItems.length ? ` · avoid: ${need.flaggedItems.join(', ')}` : ''}</p>
                              {need.blocked && need.message && <p className="text-theme-xs text-[var(--clet-error-text)]">{need.message}</p>}
                              {need.request.waiverReason && <p className="text-theme-xs text-gray-600">Waived: {need.request.waiverReason}</p>}
                            </div>
                            <div className="flex items-center gap-2">
                              <CateringBadge value={need.request.status} />
                              {canApprove && need.request.status === 'OPEN' && planning && (
                                <>
                                  <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'substitute', request: need.request })}>Substitute</Button>
                                  <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'waive', request: need.request })}>Waive</Button>
                                </>
                              )}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title={`Checks (${d.checks.length})`}>
                      {d.checks.length === 0 && <p className="text-theme-sm text-gray-600">No checks recorded. A service cannot be delivered without a passing temperature check.</p>}
                      <ul className="space-y-1">
                        {d.checks.map((c) => (
                          <li key={c.id} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                            <span>{humanise(c.checkType)}{c.temperatureC != null ? ` · ${humanise(c.holdType)} ${c.temperatureC} C` : ''} · {formatDateTime(c.checkedAt)} · {c.checkedBy}</span><CateringBadge value={c.result} />
                          </li>
                        ))}
                      </ul>
                    </Section>

                    {d.exceptions.length > 0 && (
                      <Section title={`Exceptions (${d.exceptions.length})`}>
                        <ul className="space-y-1">
                          {d.exceptions.map((e) => (
                            <li key={e.id} className="flex flex-wrap items-center justify-between gap-2 text-sm">
                              <span>{e.reference} · {humanise(e.exceptionType)} · {e.description}{e.incidentState === 'PENDING_MANUAL' ? ' · incident pending (S163 has not confirmed it)' : ''}</span><CateringBadge value={e.status} />
                            </li>
                          ))}
                        </ul>
                      </Section>
                    )}

                    <Section title={`Variances (${d.variances.length})`}>
                      {d.variances.length === 0 && <p className="text-theme-sm text-gray-600">No variances.</p>}
                      <ul className="space-y-2">
                        {d.variances.map((v: Variance) => (
                          <li key={v.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{humanise(v.kind)}: {v.planned} planned, {v.actual} actual ({v.difference > 0 ? '+' : ''}{v.difference})</p>
                              <p className="text-theme-xs text-gray-600">{v.ownerReference} · {v.reason}{v.approvedBy ? ` · approved by ${v.approvedBy}` : ''}</p>
                            </div>
                            <div className="flex items-center gap-2">
                              <CateringBadge value={v.status} />
                              {canApprove && v.status === 'OPEN' && <Button size="sm" variant="outline" onClick={() => void act(() => cateringApi.approveVariance(v.id), 'Variance approved')}>Approve</Button>}
                            </div>
                          </li>
                        ))}
                      </ul>
                      <p className="text-theme-xs text-gray-600">{d.evidenceCount} evidence item(s) filed.</p>
                    </Section>

                    <Section title="History"><StepTimeline empty="No recorded activity yet." steps={historySteps(d.history)} /></Section>
                  </>
                )}
              </DataState>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>

      {s && pending?.kind === 'change' && <ChangeDialog service={s} onClose={close} onDone={done} />}
      {s && pending?.kind === 'need' && <NeedDialog service={s} onClose={close} onDone={done} />}
      {s && d && pending?.kind === 'approve' && <ApproveDialog service={s} blockers={d.readiness.map((b) => b.code)} onClose={close} onDone={done} />}
      {s && pending?.kind === 'deliver' && <DeliverDialog service={s} onClose={close} onDone={done} />}
      {s && pending?.kind === 'check' && <CheckDialog service={s} onClose={close} onDone={done} />}
      {s && pending?.kind === 'variance' && <VarianceDialog service={s} onClose={close} onDone={done} />}
      {s && pending?.kind === 'reconcile' && <ReconcileDialog service={s} onClose={close} onDone={done} />}
      {s && pending?.kind === 'evidence' && <EvidenceDialog service={s} onClose={close} onDone={done} />}
      {s && pending?.kind === 'exception' && <ExceptionFormDialog siteCode={s.siteCode} serviceId={s.id} onClose={close} onDone={done} />}
      {s && pending?.kind === 'cancel' && (
        <ReasonDialog title="Cancel the service" description="Free before the cancellation cut-off; after it, only an approver can cancel. The reason is kept." label="Why it is cancelled" submitLabel="Cancel service" destructive
          write={(reason) => cateringApi.cancel(s, reason)} onClose={close} onDone={done} />
      )}
      {d && pending?.kind === 'substitute' && <SubstituteDialog items={d.items} onSubstitute={(itemId) => cateringApi.substitute(pending.request, itemId)} onClose={close} onDone={done} />}
      {pending?.kind === 'waive' && (
        <ReasonDialog title="Waive the need" description="Only with a written reason of at least ten characters, and not by whoever recorded it." label="Reason" submitLabel="Waive" minimum={10}
          write={(reason) => cateringApi.waive(pending.request, reason)} onClose={close} onDone={done} />
      )}
    </>
  );
};

export default ServiceDialog;

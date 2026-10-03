import { useState } from 'react';
import { Banner, Button, Modal, ModalBody, ModalContent, ModalDescription, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import ReasonDialog from 'shared/components/ReasonDialog';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { wasteApi, type Configuration, type WasteEvidence } from '../api/wasteApi';
import { EvidenceDialog, ExceptionDialog, HandOverDialog, ReconcileDialog, RecordDialog } from './WasteDialogs';
import StepTimeline, { historySteps } from 'shared/components/StepTimeline';
import { WasteBadge, quantityText } from './wasteUi';

type Pending = 'record' | 'handover' | 'certificate' | 'evidence' | 'contaminated' | 'reconcile' | 'missed' | 'cancel' | 'exception' | { reject: WasteEvidence };

const Section = ({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) => (
  <section className="space-y-3 border-b border-border px-6 py-5">
    <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-semibold">{title}</h3>{actions}</div>
    {children}
  </section>
);

/**
 * One collection and its chain of custody: what was collected and how it was measured, who has had it,
 * what proves it, and what is keeping the chain open. A refusal the screen cannot foresee - an unapproved
 * destination, a chain still open - comes back in the service's own words, and the exception it raised
 * appears here after the refresh.
 */
const CollectionDialog = ({ collectionId, config, onClose, onChanged }: { collectionId: string; config: Configuration; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_WASTE_MANAGE');
  const canVerify = permits('FACILITIES_WASTE_VERIFY');
  const detail = useApiQuery((signal) => wasteApi.collection(collectionId, signal), [collectionId]);
  const c = detail.data?.collection;
  const open = c && c.status !== 'CLOSED' && c.status !== 'CANCELLED';

  const refresh = () => { detail.refetch(); onChanged(); };
  const done = () => { setPending(undefined); refresh(); };
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
              <ModalTitle>{c ? `${c.reference} · ${detail.data?.stream.name}` : 'Collection'}</ModalTitle>
              <ModalDescription>Measurement, carrier, destination, chain of custody, evidence and open exceptions.</ModalDescription>
            </ModalHeader>
            <ModalBody className="p-0">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {c && detail.data && (
                  <>
                    <Section title="Summary" actions={
                      <div className="flex flex-wrap items-center gap-2">
                        <WasteBadge value={c.status} />
                        {c.hazardous && <WasteBadge value="HAZARDOUS" label="Hazardous" />}
                        {c.quantityBasis && <WasteBadge value={c.quantityBasis} />}
                        {c.contaminated && <WasteBadge value="MISSED" label={c.quantityReconciled ? 'Contaminated, reconciled' : 'Contaminated, unreconciled'} />}
                      </div>
                    }>
                      {detail.data.openExceptions.length > 0 && (
                        <Banner variant="warning" heading="The chain is open"
                          subtext={detail.data.openExceptions.map((e) => `${humanise(e.exceptionType)} (${e.reference})`).join(' · ')} />
                      )}
                      <KeyValueGrid columns={3} items={[
                        { label: 'Point', value: detail.data.point.name },
                        { label: 'Carrier', value: detail.data.carrier.name },
                        { label: 'Destination', value: detail.data.destination?.name ?? 'Not yet handed over' },
                        { label: 'Scheduled for', value: formatDate(c.scheduledFor) },
                        { label: 'Collected on', value: c.collectedOn ? formatDate(c.collectedOn) : '-' },
                        { label: 'Quantity', value: c.quantity == null ? '-' : `${quantityText(c)} · ${humanise(c.quantityBasis)}` },
                        { label: 'Manifest', value: c.manifestReference ?? '-' },
                        { label: 'Certificate', value: c.certificateReference ?? 'Not recorded' },
                      ]} />
                      {canManage && open && (
                        <div className="flex flex-wrap gap-2">
                          {(c.status === 'SCHEDULED' || c.status === 'MISSED') && <Button size="sm" variant="primary" onClick={() => setPending('record')}>Record collection</Button>}
                          {c.status === 'COLLECTED' && <Button size="sm" variant="primary" onClick={() => setPending('handover')}>Hand over</Button>}
                          {c.status === 'HANDED_OVER' && <Button size="sm" variant="primary" onClick={() => void act(() => wasteApi.confirmDestination(c), 'Destination confirmed')}>Confirm destination</Button>}
                          {(c.status === 'HANDED_OVER' || c.status === 'DESTINATION_CONFIRMED') && !c.certificateReference && <Button size="sm" variant="outline" onClick={() => setPending('certificate')}>Record certificate</Button>}
                          {(c.status === 'HANDED_OVER' || c.status === 'DESTINATION_CONFIRMED') && <Button size="sm" variant="primary" onClick={() => void act(() => wasteApi.close(c), 'Collection closed')}>Close chain</Button>}
                          {c.status !== 'SCHEDULED' && c.status !== 'MISSED' && !c.contaminated && <Button size="sm" variant="outline" onClick={() => setPending('contaminated')}>Mark contaminated</Button>}
                          {c.contaminated && !c.quantityReconciled && <Button size="sm" variant="outline" onClick={() => setPending('reconcile')}>Reconcile quantity</Button>}
                          {c.status === 'SCHEDULED' && <Button size="sm" variant="outline" onClick={() => setPending('missed')}>Mark missed</Button>}
                          <Button size="sm" variant="outline" onClick={() => setPending('exception')}>Report exception</Button>
                          {c.status !== 'HANDED_OVER' && c.status !== 'DESTINATION_CONFIRMED' && <Button size="sm" variant="outline" onClick={() => setPending('cancel')}>Cancel</Button>}
                        </div>
                      )}
                    </Section>

                    <Section title="Chain of custody">
                      <StepTimeline empty="Nothing has been recorded yet." steps={detail.data.custody.map((e) => ({
                        id: e.id, title: `${humanise(e.step)} · ${e.fromParty}${e.toParty ? ` → ${e.toParty}` : ''}`,
                        detail: [e.location, e.evidenceReference].filter(Boolean).join(' · ') || null,
                        footer: `${formatDateTime(e.occurredAt)} · recorded by ${e.recordedBy}`,
                      }))} />
                    </Section>

                    <Section title={`Evidence (${detail.data.evidence.length})`} actions={canManage && open && <Button size="sm" variant="outline" onClick={() => setPending('evidence')}>Submit evidence</Button>}>
                      {detail.data.evidence.length === 0 && <p className="text-theme-sm text-gray-600">No evidence submitted yet. Hazardous waste needs accepted receiving evidence from the destination.</p>}
                      <ul className="space-y-2">
                        {detail.data.evidence.map((item) => (
                          <li key={item.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{humanise(item.kind)} · {item.fileName}</p>
                              <p className="text-theme-xs text-gray-600">{item.reference} · {humanise(item.retentionClass)} retention · submitted by {item.submittedBy}</p>
                              <p className="font-mono text-theme-xs text-gray-600" title="SHA-256">{item.contentHash.slice(0, 16)}…</p>
                              {item.reviewReason && <p className="text-theme-xs text-[var(--clet-error-text)]">Rejected: {item.reviewReason}</p>}
                            </div>
                            <div className="flex items-center gap-2">
                              <WasteBadge value={item.status} />
                              {open && canVerify && item.status === 'SUBMITTED' && (
                                <>
                                  <Button size="sm" variant="outline" onClick={() => void act(() => wasteApi.reviewEvidence(item.id, true), 'Evidence accepted')}>Accept</Button>
                                  <Button size="sm" variant="outline" onClick={() => setPending({ reject: item })}>Reject</Button>
                                </>
                              )}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title="History"><StepTimeline empty="No recorded activity yet." steps={historySteps(detail.data.history)} /></Section>
                  </>
                )}
              </DataState>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>

      {c && pending === 'record' && <RecordDialog collection={c} units={config.units} onClose={() => setPending(undefined)} onDone={done} />}
      {c && pending === 'reconcile' && <ReconcileDialog collection={c} units={config.units} onClose={() => setPending(undefined)} onDone={done} />}
      {c && pending === 'handover' && <HandOverDialog collection={c} destinations={config.destinations} onClose={() => setPending(undefined)} onDone={done} />}
      {c && pending === 'evidence' && <EvidenceDialog collectionId={c.id} reference={c.reference} onClose={() => setPending(undefined)} onDone={done} />}
      {c && pending === 'exception' && <ExceptionDialog siteCode={c.siteCode} collectionId={c.id} onClose={() => setPending(undefined)} onDone={done} />}
      {c && pending === 'certificate' && (
        <ReasonDialog title="Record the certificate" description="The treatment or recycling certificate the destination issued. Accepted certificate evidence is also needed to close the chain." label="Certificate reference"
          submitLabel="Record certificate" write={(reference) => wasteApi.certificate(c, reference)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {c && pending === 'contaminated' && (
        <ReasonDialog title="Mark as contaminated" description="Its quantity stays unreconciled and a corrective action is raised. The collection cannot close until it is reconciled." label="What contaminated it"
          submitLabel="Mark contaminated" destructive write={(text) => wasteApi.contaminated(c.id, text)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {c && pending === 'missed' && (
        <ReasonDialog title="Mark as missed" description="The collection did not happen. The facilities owner is told." label="Why it was missed" submitLabel="Mark missed" destructive
          write={(reason) => wasteApi.missed(c, reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {c && pending === 'cancel' && (
        <ReasonDialog title="Cancel the collection" description="A cancelled collection is final." label="Why it is cancelled" submitLabel="Cancel collection" destructive
          write={(reason) => wasteApi.cancel(c, reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
      {typeof pending === 'object' && (
        <ReasonDialog title="Reject the evidence" description="Say why it does not prove the step." label="Reason" submitLabel="Reject evidence" destructive
          write={(reason) => wasteApi.reviewEvidence(pending.reject.id, false, reason)} onClose={() => setPending(undefined)} onDone={done} />
      )}
    </>
  );
};

export default CollectionDialog;

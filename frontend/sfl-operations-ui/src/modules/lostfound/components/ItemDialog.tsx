import { useState } from 'react';
import { Banner, Button, Modal, ModalBody, ModalContent, ModalDescription, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import ReasonDialog from 'shared/components/ReasonDialog';
import StepTimeline, { historySteps } from 'shared/components/StepTimeline';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { lostFoundApi, type Claim, type Configuration, type LfEvidence } from '../api/lostFoundApi';
import { ClaimDialog, ConfirmDialog, EvidenceDialog, ReleaseDialog, StoreDialog, TransferDialog, VerifyDialog } from './LfDialogs';
import { LfBadge } from './lfUi';

type Pending =
  | { kind: 'store' | 'transfer' | 'claim' | 'evidence' | 'unsafe' | 'dispose' | 'authorities' }
  | { kind: 'verify' | 'release' | 'approve' | 'refuse' | 'withdraw' | 'view'; claim: Claim };

const Section = ({ title, actions, children }: { title: string; actions?: React.ReactNode; children: React.ReactNode }) => (
  <section className="space-y-3 border-b border-border px-6 py-5">
    <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-semibold">{title}</h3>{actions}</div>
    {children}
  </section>
);

/**
 * One found item. Without the private grant the service sends the masked view - no private detail, no finder, no
 * claimant - and this screen says so rather than showing blanks. Every action is offered only to a role the
 * service would let press it; a refusal the screen cannot foresee comes back in the service's own words.
 */
const ItemDialog = ({ itemId, config, onClose, onChanged }: { itemId: string; config: Configuration; onClose: () => void; onChanged: () => void }) => {
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_LOSTFOUND_MANAGE');
  const canApprove = permits('FACILITIES_LOSTFOUND_APPROVE');
  const canSeePrivate = permits('FACILITIES_LOSTFOUND_PRIVATE_READ');
  const detail = useApiQuery((signal) => lostFoundApi.item(itemId, signal), [itemId]);
  const evidence = useApiQuery((signal) => (canSeePrivate ? lostFoundApi.evidence(itemId, signal) : Promise.resolve([] as LfEvidence[])), [itemId, canSeePrivate]);
  const d = detail.data;
  const item = d?.item;
  const open = item && ['REGISTERED', 'STORED', 'ISOLATED'].includes(item.status);

  const refresh = () => { detail.refetch(); evidence.refetch(); onChanged(); };
  const done = () => { setPending(undefined); refresh(); };
  const close = () => setPending(undefined);

  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{item ? `${item.reference} · ${item.publicDescription}` : 'Found item'}</ModalTitle>
              <ModalDescription>Custody, claims, evidence and the record of every decision.</ModalDescription>
            </ModalHeader>
            <ModalBody className="p-0">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {item && d && (
                  <>
                    <Section title="Summary" actions={<div className="flex flex-wrap items-center gap-2"><LfBadge value={item.status} />{item.unsafe && <LfBadge value="ISOLATED" label="Unsafe" />}</div>}>
                      {!d.privateView && <Banner variant="info" heading="Masked view" subtext="Your role sees the controlled description and the status. Private detail, the finder and claimant details are not shown." />}
                      {item.unsafe && <Banner variant="warning" heading="Isolated as unsafe" subtext={`${item.unsafeReason ?? 'Escalated to security and the emergency procedures.'} It can only be handed to the authorities or disposed of with approval.`} />}
                      <KeyValueGrid columns={3} items={[
                        { label: 'Claim reference', value: <span className="font-mono">{item.claimReference}</span> },
                        { label: 'Category', value: humanise(item.category) },
                        { label: 'Stored in', value: d.storageLocation ? `${d.storageLocation.name}${d.storageLocation.secure ? ' (secure)' : ''}` : 'Not yet stored' },
                        { label: 'Found at', value: item.foundLocation },
                        { label: 'Found on', value: formatDateTime(item.foundAt) },
                        { label: 'Keep until', value: formatDate(item.retentionUntil) },
                        { label: 'Condition', value: item.initialCondition },
                        { label: 'Finder', value: d.privateView ? item.finderReference ?? '-' : 'Not shown' },
                        { label: 'Custody chain', value: d.custodyComplete ? 'Complete' : 'Incomplete' },
                        ...(d.privateView ? [{ label: 'Private detail', value: item.privateDescription ?? '-', span: 2 as const }] : []),
                      ]} />
                      {canManage && open && (
                        <div className="flex flex-wrap gap-2">
                          {item.status !== 'ISOLATED' && <Button size="sm" variant="primary" onClick={() => setPending({ kind: 'store' })}>{item.storageLocationId ? 'Move to storage' : 'Place in storage'}</Button>}
                          <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'transfer' })}>Transfer custody</Button>
                          {item.status !== 'ISOLATED' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'claim' })}>Receive a claim</Button>}
                          <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'evidence' })}>File evidence</Button>
                          {item.status !== 'ISOLATED' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'unsafe' })}>Mark unsafe</Button>}
                        </div>
                      )}
                      {canApprove && open && (
                        <div className="flex flex-wrap gap-2">
                          <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'authorities' })}>Hand to authorities</Button>
                          <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'dispose' })}>Dispose</Button>
                        </div>
                      )}
                    </Section>

                    <Section title={`Claims (${d.claims.length})`}>
                      {d.claims.length === 0 && <p className="text-theme-sm text-gray-600">No claims.</p>}
                      <ul className="space-y-2">
                        {d.claims.map((claim) => (
                          <li key={claim.id} className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border px-4 py-3">
                            <div className="min-w-0">
                              <p className="text-sm font-medium">{claim.reference}{d.privateView && claim.claimantName ? ` · ${claim.claimantName}` : ''}</p>
                              <p className="text-theme-xs text-gray-600">
                                {claim.identityVerified ? `Identity verified by ${claim.verifiedBy ?? 'staff'} (${humanise(claim.verificationMethod)})` : 'Identity not verified'}
                                {claim.personalDataPurgedAt ? ' · personal data erased' : ''}
                              </p>
                              {d.privateView && claim.claimantContact && <p className="text-theme-xs text-gray-600">{claim.claimantContact}</p>}
                              {claim.decisionReason && <p className="text-theme-xs text-gray-600">{claim.decisionReason}</p>}
                            </div>
                            <div className="flex flex-wrap items-center gap-2">
                              <LfBadge value={claim.status} />
                              <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'view', claim })}>What they may see</Button>
                              {canManage && claim.status === 'RECEIVED' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'verify', claim })}>Verify identity</Button>}
                              {canApprove && claim.status === 'VERIFIED' && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'approve', claim })}>Approve release</Button>}
                              {canManage && ['VERIFIED', 'APPROVED'].includes(claim.status) && <Button size="sm" variant="primary" onClick={() => setPending({ kind: 'release', claim })}>Release</Button>}
                              {canManage && ['RECEIVED', 'VERIFIED', 'APPROVED'].includes(claim.status) && <Button size="sm" variant="outline" onClick={() => setPending({ kind: 'refuse', claim })}>Refuse</Button>}
                            </div>
                          </li>
                        ))}
                      </ul>
                    </Section>

                    <Section title="Chain of custody">
                      <StepTimeline empty="Nothing recorded." steps={d.custody.map((e) => ({
                        id: e.id, title: `${e.fromParty} → ${e.toParty}`, detail: [e.location, e.reason].filter(Boolean).join(' · ') || null,
                        footer: `${formatDateTime(e.occurredAt)} · recorded by ${e.recordedBy}`,
                      }))} />
                    </Section>

                    <Section title={`Evidence (${d.evidenceCount})`}>
                      {!canSeePrivate && <p className="text-theme-sm text-gray-600">Photographs and receipts can show people, so they are shown only to roles with private access.</p>}
                      {canSeePrivate && (
                        <DataState loading={evidence.initialising} error={evidence.error} onRetry={evidence.refetch}>
                          {(evidence.data ?? []).length === 0 && <p className="text-theme-sm text-gray-600">No evidence filed.</p>}
                          <ul className="space-y-2">
                            {(evidence.data ?? []).map((e) => (
                              <li key={e.id} className="rounded-lg border border-border px-4 py-3">
                                <p className="text-sm font-medium">{humanise(e.kind)} · {e.fileName}</p>
                                <p className="text-theme-xs text-gray-600">{e.reference} · filed by {e.submittedBy} · {formatDateTime(e.submittedAt)}</p>
                                <p className="font-mono text-theme-xs text-gray-600" title="SHA-256">{e.contentHash.slice(0, 16)}…</p>
                              </li>
                            ))}
                          </ul>
                        </DataState>
                      )}
                    </Section>

                    <Section title="History"><StepTimeline empty="No recorded activity yet." steps={historySteps(d.history)} /></Section>
                  </>
                )}
              </DataState>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>

      {item && pending?.kind === 'store' && <StoreDialog item={item} config={config} onClose={close} onDone={done} />}
      {item && pending?.kind === 'transfer' && <TransferDialog item={item} onClose={close} onDone={done} />}
      {item && pending?.kind === 'claim' && <ClaimDialog item={item} onClose={close} onDone={done} />}
      {item && pending?.kind === 'evidence' && <EvidenceDialog item={item} claims={d?.claims ?? []} onClose={close} onDone={done} />}
      {item && pending?.kind === 'unsafe' && (
        <ReasonDialog title="Mark as unsafe" description="The item is isolated, open claims are refused, and security and the emergency procedures are told. An incident is requested." label="Why it is unsafe"
          submitLabel="Isolate item" destructive write={(reason) => lostFoundApi.unsafe(item.id, reason)} onClose={close} onDone={done} />
      )}
      {item && pending?.kind === 'dispose' && (
        <ConfirmDialog title="Dispose of the item" description="Needs the retention period to have ended (an unsafe item excepted), a disposal authorisation filed by someone else, and no open claim. Disposal is final."
          submitLabel="Dispose" write={() => lostFoundApi.dispose(item)} onClose={close} onDone={done} />
      )}
      {item && pending?.kind === 'authorities' && (
        <ReasonDialog title="Hand to the authorities" description="Needs the authority's receipt filed first." label="Which authority" submitLabel="Hand over"
          write={(authority) => lostFoundApi.handToAuthorities(item, authority)} onClose={close} onDone={done} />
      )}
      {pending?.kind === 'verify' && <VerifyDialog claim={pending.claim} onClose={close} onDone={done} />}
      {pending?.kind === 'release' && <ReleaseDialog claim={pending.claim} onClose={close} onDone={done} />}
      {pending?.kind === 'approve' && (
        <ReasonDialog title="Approve the release" description="You cannot approve a claim whose identity you verified yourself, and competing claims block approval." label="Why it is approved" submitLabel="Approve release"
          write={(reason) => lostFoundApi.approve(pending.claim, reason)} onClose={close} onDone={done} />
      )}
      {pending?.kind === 'refuse' && (
        <ReasonDialog title="Refuse the claim" description="The reason is kept on the record." label="Reason" submitLabel="Refuse claim" destructive
          write={(reason) => lostFoundApi.refuse(pending.claim, reason)} onClose={close} onDone={done} />
      )}
      {pending?.kind === 'view' && <ClaimantViewDialog claim={pending.claim} onClose={close} />}
    </>
  );
};

const ClaimantViewDialog = ({ claim, onClose }: { claim: Claim; onClose: () => void }) => {
  const query = useApiQuery((signal) => lostFoundApi.claimantView(claim.id, signal), [claim.id]);
  return (
    <Modal open onOpenChange={(next) => !next && onClose()}>
      <ModalPortal>
        <ModalOverlay />
        <ModalContent showCloseButton size="lg">
          <ModalHeader>
            <ModalTitle>What {claim.reference} may see</ModalTitle>
            <ModalDescription>The claimant sees the controlled description and the status. Private detail appears only once identity is verified.</ModalDescription>
          </ModalHeader>
          <ModalBody>
            <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
              {query.data && (
                <KeyValueGrid columns={2} items={[
                  { label: 'Claim reference', value: <span className="font-mono">{query.data.itemClaimReference}</span> },
                  { label: 'Status', value: humanise(query.data.status) },
                  { label: 'Category', value: humanise(query.data.category) },
                  { label: 'Description', value: query.data.publicDescription },
                  { label: 'Private detail', value: query.data.identityVerified ? query.data.privateDescription ?? '-' : 'Hidden until identity is verified', span: 2 },
                ]} />
              )}
            </DataState>
          </ModalBody>
        </ModalContent>
      </ModalPortal>
    </Modal>
  );
};

export default ItemDialog;

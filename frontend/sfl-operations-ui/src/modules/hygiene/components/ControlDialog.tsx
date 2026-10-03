import { useState } from 'react';
import { Banner, Button, Modal, ModalBody, ModalContent, ModalDescription, ModalFooter, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import { formatDate } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { hygieneApi, type Completion } from '../api/hygieneApi';
import { FindingFormDialog } from './HygieneDialogs';
import ReasonDialog from 'shared/components/ReasonDialog';
import HistoryList from './HistoryList';
import { HygieneBadge } from './hygieneUi';

type Pending = 'finding' | 'missed' | 'cancel';

/**
 * One control: when it is due, who owns it, whether its provider has confirmed, and what can be done next.
 * Completing or missing a recurring control tells the operator about the occurrence it just scheduled, so
 * the schedule never silently continues.
 */
const ControlDialog = ({ controlId, onClose, onChanged }: { controlId: string; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_HYGIENE_MANAGE');
  const detail = useApiQuery((signal) => hygieneApi.control(controlId, signal), [controlId]);
  const control = detail.data?.control;
  const effective = detail.data?.effectiveStatus;
  const open = control && (control.status === 'SCHEDULED' || control.status === 'IN_PROGRESS' || control.status === 'MISSED');
  const awaitingProvider = Boolean(control && control.controlType === 'PEST_VISIT' && control.providerReference && !control.providerConfirmed);

  const refresh = () => { detail.refetch(); onChanged(); };
  const announce = (result: Completion | undefined, success: string) => {
    notifier.notifySuccess(success, result?.next ? `Next occurrence scheduled for ${formatDate(result.next.dueOn)} (${result.next.reference}).` : undefined);
    refresh();
  };
  const act = async (write: () => Promise<unknown>, success: string) => {
    try {
      announce(await write() as Completion | undefined, success);
    } catch (cause) {
      notifier.notifyError(cause);
    }
  };

  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{control ? `${control.reference} · ${control.title}` : 'Control'}</ModalTitle>
              <ModalDescription>Schedule, ownership and the record of what has happened to this control.</ModalDescription>
            </ModalHeader>
            <ModalBody className="space-y-5">
              <DataState loading={detail.initialising} error={detail.error} onRetry={detail.refetch}>
                {control && (
                  <>
                    <div className="flex flex-wrap items-center gap-2">
                      <HygieneBadge value={effective} />
                      <HygieneBadge value={control.controlType} />
                    </div>
                    {awaitingProvider && open && (
                      <Banner variant="warning" heading="Waiting for the provider" subtext={`${control.providerReference} has not confirmed this visit. It cannot be completed until the confirmation is recorded.`} />
                    )}
                    <KeyValueGrid columns={3} items={[
                      { label: 'Site', value: control.siteCode },
                      { label: 'Location', value: control.locationLabel ?? '-' },
                      { label: 'Risk category', value: humanise(control.riskCategory) },
                      { label: 'Owner', value: control.ownerReference },
                      { label: 'Frequency', value: humanise(control.frequency) },
                      { label: 'Due on', value: formatDate(control.dueOn) },
                      ...(control.controlType === 'PEST_VISIT' ? [
                        { label: 'Provider', value: control.providerReference ?? 'None named' },
                        { label: 'Provider confirmation', value: control.providerReference ? (control.providerConfirmed ? 'Confirmed' : 'Not yet confirmed') : '-' },
                      ] : []),
                      ...(control.completedOn ? [{ label: 'Completed on', value: formatDate(control.completedOn) }] : []),
                      { label: 'Notes', value: control.notes ?? '-', span: 2 as const },
                    ]} />
                    <section className="space-y-3">
                      <h3 className="text-sm font-semibold">History</h3>
                      <HistoryList history={detail.data?.history ?? []} />
                    </section>
                  </>
                )}
              </DataState>
            </ModalBody>
            {control && canManage && (
              <ModalFooter>
                {open && awaitingProvider && (
                  <Button variant="outline" onClick={() => void act(() => hygieneApi.confirmProvider(control), 'Provider visit confirmed')}>Confirm provider visit</Button>
                )}
                {control.status !== 'CANCELLED' && (
                  <Button variant="outline" onClick={() => setPending('finding')}>Record a finding</Button>
                )}
                {open && <Button variant="outline" onClick={() => setPending('missed')}>Mark missed</Button>}
                {open && <Button variant="outline" onClick={() => setPending('cancel')}>Cancel</Button>}
                {control.status === 'SCHEDULED' && (
                  <Button variant="outline" onClick={() => void act(() => hygieneApi.startControl(control), 'Control started')}>Start</Button>
                )}
                {open && (
                  <Button variant="primary" disabled={awaitingProvider} title={awaitingProvider ? 'The provider has not confirmed this visit' : undefined}
                    onClick={() => void act(() => hygieneApi.completeControl(control), 'Control completed')}>Complete</Button>
                )}
              </ModalFooter>
            )}
          </ModalContent>
        </ModalPortal>
      </Modal>
      {control && pending === 'finding' && (
        <FindingFormDialog controlId={control.id} controlRef={control.reference} onClose={() => setPending(undefined)}
          onDone={() => { setPending(undefined); notifier.notifySuccess('Finding recorded'); refresh(); }} />
      )}
      {control && pending === 'missed' && (
        <ReasonDialog title="Mark as missed" description="The control was not carried out. HSE is told, and a recurring control schedules its next occurrence." label="Why it was missed" submitLabel="Mark missed" destructive
          write={(reason) => hygieneApi.missControl(control, reason).then((result) => announce(result, 'Control marked missed'))} onClose={() => setPending(undefined)} onDone={() => setPending(undefined)} />
      )}
      {control && pending === 'cancel' && (
        <ReasonDialog title="Cancel the control" description="A cancelled control is final and schedules nothing further." label="Why it is cancelled" submitLabel="Cancel control" destructive
          write={(reason) => hygieneApi.cancelControl(control, reason)} onClose={() => setPending(undefined)} onDone={() => { setPending(undefined); refresh(); }} />
      )}
    </>
  );
};

export default ControlDialog;

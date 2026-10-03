import { useState } from 'react';
import { Button, Modal, ModalBody, ModalContent, ModalDescription, ModalFooter, ModalHeader, ModalOverlay, ModalPortal, ModalTitle } from '@rfdtech/components';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import ReasonDialog from 'shared/components/ReasonDialog';
import { formatDate } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import { wasteApi, type WasteException } from '../api/wasteApi';
import { WasteBadge } from './wasteUi';

type Pending = 'assign' | 'incident' | 'resolve';

const link = (state: string, reference: string | null, system: string) =>
  state === 'NOT_REQUIRED' ? 'Not required' : state === 'PENDING_MANUAL' ? `Pending - ${system} has not confirmed it` : `${system} ${reference ?? 'done'}`;

/** A corrective action: what went wrong, who owns it, when it is due, and whether S153 and S163 have been told. */
const ExceptionPanel = ({ exception, onClose, onChanged }: { exception: WasteException; onClose: () => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [pending, setPending] = useState<Pending>();
  const canManage = permits('FACILITIES_WASTE_MANAGE');
  const open = exception.status !== 'RESOLVED';
  const act = async (write: () => Promise<unknown>, success: string) => {
    try {
      await write();
      notifier.notifySuccess(success);
      onChanged();
      onClose();
    } catch (cause) {
      notifier.notifyError(cause);
    }
  };
  const done = () => { setPending(undefined); onChanged(); onClose(); };
  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="lg">
            <ModalHeader>
              <ModalTitle>{exception.reference} · {humanise(exception.exceptionType)}</ModalTitle>
              <ModalDescription>{exception.description}</ModalDescription>
            </ModalHeader>
            <ModalBody className="space-y-4">
              <div className="flex gap-2"><WasteBadge value={exception.status} /><WasteBadge value="PENDING_MANUAL" label={`Escalated to ${humanise(exception.escalatedTo)}`} /></div>
              <KeyValueGrid columns={2} items={[
                { label: 'Owner', value: exception.ownerReference ?? 'Not assigned' },
                { label: 'Due', value: formatDate(exception.dueOn) },
                { label: 'Work order (S153)', value: link(exception.workOrderState, exception.workOrderNumber, 'S153') },
                { label: 'Incident (S163)', value: link(exception.incidentState, exception.incidentReference, 'S163') },
                ...(exception.resolution ? [{ label: 'Resolution', value: exception.resolution, span: 2 as const }] : []),
              ]} />
            </ModalBody>
            {canManage && open && (
              <ModalFooter>
                {exception.workOrderState === 'PENDING_MANUAL' && <Button variant="outline" onClick={() => void act(() => wasteApi.retryWorkOrder(exception.id), 'Work order request sent')}>Retry work order</Button>}
                {exception.incidentState === 'PENDING_MANUAL' && <Button variant="outline" onClick={() => setPending('incident')}>Link incident</Button>}
                <Button variant="outline" onClick={() => setPending('assign')}>Assign owner</Button>
                {exception.status === 'OPEN' && <Button variant="outline" onClick={() => void act(() => wasteApi.startException(exception), 'Exception started')}>Start</Button>}
                <Button variant="primary" onClick={() => setPending('resolve')}>Resolve</Button>
              </ModalFooter>
            )}
          </ModalContent>
        </ModalPortal>
      </Modal>
      {pending === 'assign' && <ReasonDialog title="Assign an owner" description="Who is accountable for correcting this." label="Owner" submitLabel="Assign" write={(text) => wasteApi.assignException(exception.id, text)} onClose={() => setPending(undefined)} onDone={done} />}
      {pending === 'incident' && <ReasonDialog title="Link the incident" description="Enter the reference Incident Reporting (S163) gave this incident." label="Incident reference" submitLabel="Link incident" write={(text) => wasteApi.linkIncident(exception.id, text)} onClose={() => setPending(undefined)} onDone={done} />}
      {pending === 'resolve' && <ReasonDialog title="Resolve the exception" description="Say what was done to correct it." label="Resolution" submitLabel="Resolve" write={(text) => wasteApi.resolveException(exception, text)} onClose={() => setPending(undefined)} onDone={done} />}
    </>
  );
};

export default ExceptionPanel;

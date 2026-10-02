import { useState } from 'react';
import {
  Button,
  Modal,
  ModalBody,
  ModalContent,
  ModalDescription,
  ModalHeader,
  ModalOverlay,
  ModalPortal,
  ModalTitle,
} from '@rfdtech/components';
import { cn } from 'shared/components/cn';
import { IfimpRecord } from '../api/ifimpPhase2Api';
import IfimpCreateDialog, { CreateAction } from './IfimpCreateDialog';
import IfimpValue, { humaniseIfimpField } from './IfimpValue';

interface Props {
  record?: IfimpRecord;
  title: string;
  siteCode: string;
  actions?: CreateAction[];
  onClose: () => void;
  onChanged: () => void;
}

export const visibleRecordActions = (actions: CreateAction[], record: IfimpRecord) =>
  actions.filter((candidate) => !candidate.visible || candidate.visible(record));

const IfimpRecordDialog = ({ record, title, siteCode, actions = [], onClose, onChanged }: Props) => {
  const [action, setAction] = useState<CreateAction>();
  if (!record) return null;
  const available = visibleRecordActions(actions, record);
  return (
    <>
      <Modal open onOpenChange={(next) => !next && onClose()}>
        <ModalPortal>
          <ModalOverlay />
          <ModalContent showCloseButton size="2xl">
            <ModalHeader>
              <ModalTitle>{title}</ModalTitle>
              <ModalDescription>Record details and available workflow actions</ModalDescription>
            </ModalHeader>
            {available.length > 0 && (
              <div className="flex flex-wrap gap-2 border-b border-border bg-surface-muted/20 px-6 py-3">
                {available.map((item) => {
                  const disabledReason = item.disabledReason?.(record);
                  return (
                    <Button
                      key={item.label}
                      size="sm"
                      variant={item.destructive ? 'destructive' : 'outline'}
                      disabled={Boolean(disabledReason)}
                      title={disabledReason}
                      onClick={() => setAction(item)}
                    >
                      {item.label}
                    </Button>
                  );
                })}
              </div>
            )}
            <ModalBody className="p-0">
              <dl className="grid grid-cols-1 sm:grid-cols-2">
                {Object.entries(record).map(([key, entry]) => (
                  <div
                    key={key}
                    className={cn(
                      'border-b border-border px-6 py-4 even:bg-surface-muted/20',
                      entry !== null && typeof entry === 'object' && 'sm:col-span-2',
                    )}
                  >
                    <dt className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                      {humaniseIfimpField(key)}
                    </dt>
                    <dd className="mt-2 text-sm text-foreground">
                      <IfimpValue value={entry} />
                    </dd>
                  </div>
                ))}
              </dl>
            </ModalBody>
          </ModalContent>
        </ModalPortal>
      </Modal>
      {action && (
        <IfimpCreateDialog
          key={action.label}
          action={action}
          record={record}
          siteCode={siteCode}
          open
          onClose={() => setAction(undefined)}
          onCreated={() => {
            setAction(undefined);
            onChanged();
            onClose();
          }}
        />
      )}
    </>
  );
};

export default IfimpRecordDialog;

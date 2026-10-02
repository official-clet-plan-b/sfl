import { useState } from 'react';
import Button from 'shared/components/Button';
import Modal, { ModalCloseButton } from 'shared/components/Modal';
import StatusChip from 'shared/components/StatusChip';
import { IfimpRecord } from '../api/ifimpPhase2Api';
import IfimpCreateDialog, { CreateAction } from './IfimpCreateDialog';

interface Props {
  record?: IfimpRecord;
  title: string;
  siteCode: string;
  actions?: CreateAction[];
  onClose: () => void;
  onChanged: () => void;
}

const label = (key: string) => key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/_/g, ' ').replace(/^./, (letter) => letter.toUpperCase());
const value = (entry: unknown) => {
  if (entry === null || entry === undefined || entry === '') return <span className="text-gray-400">—</span>;
  if (typeof entry === 'boolean') return <StatusChip value={entry ? 'Yes' : 'No'} tone={entry ? 'ready' : 'neutral'} />;
  if (typeof entry === 'object') return <pre className="max-w-xl overflow-x-auto whitespace-pre-wrap text-theme-xs">{JSON.stringify(entry, null, 2)}</pre>;
  return <span className="break-words">{String(entry)}</span>;
};

export const visibleRecordActions = (actions: CreateAction[], record: IfimpRecord) =>
  actions.filter((candidate) => !candidate.visible || candidate.visible(record));

const IfimpRecordDialog = ({ record, title, siteCode, actions = [], onClose, onChanged }: Props) => {
  const [action, setAction] = useState<CreateAction>();
  if (!record) return null;
  const available = visibleRecordActions(actions, record);
  return (
    <>
      <Modal open onClose={onClose} size="xl" labelledBy="ifimp-record-title">
        <header className="flex items-start justify-between gap-4 border-b border-gray-200 px-6 py-4">
          <div><h2 id="ifimp-record-title" className="text-theme-xl font-bold text-gray-900">{title}</h2><p className="mt-1 text-theme-sm text-gray-600">Record details and available workflow actions</p></div>
          <ModalCloseButton onClose={onClose} />
        </header>
        {available.length > 0 && <div className="flex flex-wrap gap-2 border-b border-gray-200 bg-gray-50 px-6 py-3">{available.map((item) => {
          const disabledReason = item.disabledReason?.(record);
          return <Button key={item.label} size="sm" variant={item.destructive ? 'danger' : 'outline'} disabled={Boolean(disabledReason)} title={disabledReason} onClick={() => setAction(item)}>{item.label}</Button>;
        })}</div>}
        <dl className="custom-scrollbar grid max-h-[68vh] grid-cols-1 overflow-y-auto sm:grid-cols-2">
          {Object.entries(record).map(([key, entry]) => <div key={key} className="border-b border-gray-100 px-6 py-4 even:bg-gray-25"><dt className="text-theme-xs font-semibold tracking-wide text-gray-500 uppercase">{label(key)}</dt><dd className="mt-1 text-theme-sm text-gray-800">{value(entry)}</dd></div>)}
        </dl>
      </Modal>
      {action && <IfimpCreateDialog key={action.label} action={action} record={record} siteCode={siteCode} open onClose={() => setAction(undefined)} onCreated={() => { setAction(undefined); onChanged(); onClose(); }} />}
    </>
  );
};

export default IfimpRecordDialog;

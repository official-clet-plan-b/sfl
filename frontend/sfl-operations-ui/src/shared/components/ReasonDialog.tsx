import { useState } from 'react';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';
import { useSubmit } from 'shared/hooks/useSubmit';

interface ReasonDialogProps {
  title: string;
  description: string;
  label: string;
  submitLabel: string;
  minimum?: number;
  destructive?: boolean;
  write: (reason: string) => Promise<unknown>;
  onClose: () => void;
  onDone: () => void;
}

/** Asks for one piece of free text - a reason - and hands it to `write`. Used wherever the service wants a reason on record. */
const ReasonDialog = ({ title, description, label, submitLabel, minimum = 1, destructive, write, onClose, onDone }: ReasonDialogProps) => {
  const [reason, setReason] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog
      open
      title={title}
      description={description}
      submitLabel={submitLabel}
      destructive={destructive}
      submitting={submitting}
      submitDisabled={reason.trim().length < minimum}
      formError={error}
      onClose={onClose}
      onSubmit={() => void run(() => write(reason.trim()), onDone)}
    >
      <TextAreaInput label={label} required value={reason} onChange={setReason} maxLength={2000} autoFocus
        helperText={minimum > 1 ? `At least ${minimum} characters.` : undefined} />
    </FormDialog>
  );
};

export default ReasonDialog;

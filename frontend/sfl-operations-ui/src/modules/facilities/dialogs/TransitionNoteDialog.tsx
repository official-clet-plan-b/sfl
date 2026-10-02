import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface TransitionNoteDialogProps {
  title: string;
  description: string;
  label: string;
  placeholder: string;
  /** The consequence, stated before the button rather than discovered after it. */
  note?: string;
  submitLabel: string;
  destructive?: boolean;
  onClose: () => void;
  onSubmit: (notes: string) => Promise<void>;
}

/**
 * The transitions whose only payload is a required note: hold, reopen, cancel.
 *
 * One dialog rather than three, because the three differ in nothing but their wording and their
 * endpoint. Three near-identical files would be three places to forget the version check, and the
 * consequence text - which is the part that actually helps somebody - would drift apart between
 * them.
 *
 * Every one of these notes is mandatory in the service. A hold with no reason, a reopen with no
 * explanation and a cancellation with no justification are all things a later review cannot read, so
 * the field is required here and the dialog says why rather than presenting a bare asterisk.
 */
const TransitionNoteDialog = ({
  title,
  description,
  label,
  placeholder,
  note,
  submitLabel,
  destructive,
  onClose,
  onSubmit,
}: TransitionNoteDialogProps) => {
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const missing = notes.trim().length === 0;

  const submit = async () => {
    setTouched(true);
    if (missing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit(notes.trim());
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={title}
      description={description}
      submitLabel={submitLabel}
      submitting={submitting}
      destructive={destructive}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextAreaInput
          label={label}
          value={notes}
          onChange={setNotes}
          onBlur={() => setTouched(true)}
          required
          rows={3}
          placeholder={placeholder}
          error={touched && missing}
          helperText={
            touched && missing
              ? 'A reason is required.'
              : 'Recorded on the work order and in the audit trail.'
          }
        />
        {note && (
          <Notice variant={destructive ? 'warning' : 'info'} title="Worth knowing">
            <p className="text-sm">{note}</p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

export default TransitionNoteDialog;

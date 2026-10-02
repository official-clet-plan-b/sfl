import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { ExportEvidenceRequest, MaintenanceEvidence } from '../api/dto';
import { humaniseCode } from '../components/facilitiesFormat';
import { FormDialog, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface ExportEvidenceDialogProps {
  evidence: MaintenanceEvidence;
  onClose: () => void;
  onSubmit: (request: ExportEvidenceRequest) => Promise<void>;
}

/**
 * Approving an export of evidence - SRS-SFL-S153-03.
 *
 * The requirement asks for "role permission, justification and audit logging", and all three are
 * real here. The permission is checked before this dialog is offered; the justification and the
 * recipient are both mandatory; and the service writes the audit entry **before** returning the
 * reference, so an export that fails halfway still leaves a record that it was attempted and by
 * whom.
 *
 * The recipient is a separate field rather than something to mention in the reason, because "who
 * received this" is the question an investigation asks first and prose is not searchable.
 */
const ExportEvidenceDialog = ({ evidence, onClose, onSubmit }: ExportEvidenceDialogProps) => {
  const [reason, setReason] = useState('');
  const [recipient, setRecipient] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const missingReason = reason.trim().length === 0;
  const missingRecipient = recipient.trim().length === 0;
  const invalid = missingReason || missingRecipient;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({ reason: reason.trim(), recipient: recipient.trim() });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Export evidence"
      description={`${humaniseCode(evidence.evidenceType)} · ${evidence.retentionClass} retention`}
      submitLabel="Approve export"
      submitting={submitting}
      submitDisabled={touched && invalid}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="Recipient"
          value={recipient}
          onChange={setRecipient}
          onBlur={() => setTouched(true)}
          required
          maxLength={200}
          placeholder="e.g. compliance@clet.gov.gh"
          error={touched && missingRecipient}
          helperText={
            touched && missingRecipient
              ? 'A named recipient is required.'
              : 'Recorded against the export. Who received it is the first question an investigation asks.'
          }
        />

        <TextAreaInput
          label="Reason"
          value={reason}
          onChange={setReason}
          onBlur={() => setTouched(true)}
          required
          rows={3}
          placeholder="e.g. Audit query AQ-2026-11, evidence of fire-egress remediation."
          error={touched && missingReason}
          helperText={
            touched && missingReason
              ? 'A recorded reason is required.'
              : 'The service refuses an export without one, and audits the reason with it.'
          }
        />

        <Notice variant="warning" title="This is recorded before anything is handed over">
          <p className="text-sm">
            Approving writes an audit entry naming you, the recipient and the reason, and then
            returns the storage reference to fetch. The entry is written first on purpose: a reason
            recorded after a successful export is a reason that is missing exactly when the export
            went wrong.
          </p>
        </Notice>
      </div>
    </FormDialog>
  );
};

export default ExportEvidenceDialog;

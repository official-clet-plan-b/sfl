import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Site } from '../api/dto';
import type { OperatingMode } from '../api/enums';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface OperatingModeDialogProps {
  site: Site;
  onClose: () => void;
  onChanged: (mode: OperatingMode, reason: string) => Promise<void>;
}

/**
 * Declaring or standing down examination mode.
 *
 * A dialog rather than a toggle, deliberately. NFR 23.3 requires the change to be "explicit, audited
 * and reversible only by authorised roles", and a switch that flips on a single click is none of
 * those things in spirit - it invites the accident. The consequences are spelled out before the
 * confirm, and the reason is captured because the audit entry is worth more with one.
 */
const OperatingModeDialog = ({ site, onClose, onChanged }: OperatingModeDialogProps) => {
  const target: OperatingMode = site.operatingMode === 'EXAMINATION' ? 'ROUTINE' : 'EXAMINATION';
  const declaring = target === 'EXAMINATION';

  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const submit = async () => {
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onChanged(target, reason.trim());
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={declaring ? 'Declare examination mode' : 'Stand down examination mode'}
      description={`${site.name} (${site.siteCode})`}
      submitLabel={declaring ? 'Declare examination mode' : 'Return to routine'}
      submitting={submitting}
      formError={formError}
      destructive={declaring}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <Notice variant={declaring ? 'warning' : 'info'} title="What this changes">
          {declaring ? (
            <ul className="mt-1 list-disc space-y-1 pl-4 text-sm">
              <li>Readiness is assessed against the examination checklist for this centre.</li>
              <li>The staleness threshold tightens, so more spaces will report as needing reassessment.</li>
              <li>Examination-readiness risk is reported separately on the dashboard.</li>
              <li>The change is recorded in the audit trail against your name.</li>
            </ul>
          ) : (
            <ul className="mt-1 list-disc space-y-1 pl-4 text-sm">
              <li>Readiness returns to the routine checklist and the routine staleness window.</li>
              <li>Spaces locked for examination stay locked until released individually.</li>
              <li>The change is recorded in the audit trail against your name.</li>
            </ul>
          )}
        </Notice>

        <TextAreaInput
          label="Reason"
          value={reason}
          onChange={setReason}
          rows={3}
          placeholder={
            declaring ? 'e.g. Bar Part II finals, 4–15 August.' : 'e.g. Final paper collected.'
          }
          helperText="Optional, but it is what makes the audit entry legible a year from now."
        />
      </div>
    </FormDialog>
  );
};

export default OperatingModeDialog;

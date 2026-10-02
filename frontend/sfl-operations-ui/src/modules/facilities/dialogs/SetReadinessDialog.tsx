import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { ReadinessBlocker, Space } from '../api/dto';
import type { LocationReadinessStatus } from '../api/enums';
import { setReadinessAction } from '../api/workflow';
import { humaniseCode } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface SetReadinessDialogProps {
  space: Space;
  openBlockers: ReadinessBlocker[];
  onClose: () => void;
  onSubmit: (status: LocationReadinessStatus, notes: string) => Promise<void>;
}

/**
 * Setting a space's readiness by hand.
 *
 * ## Why this exists alongside the assessment flow
 *
 * An assessment is the ordinary route and it computes the outcome, which is right: two officers
 * answering the same checklist should reach the same status. But an estate does not always have a
 * checklist to hand - a space is taken out of use for a burst pipe, or brought back after one - and
 * the service exposes `PATCH /rooms/{id}/readiness` for exactly that. Without this dialog that
 * endpoint has no way in, and an operator's only recourse is to invent an assessment.
 *
 * ## Why READY can be disabled before it is submitted
 *
 * The critical-blocker rule is enforced by the service and would refuse this write anyway. It is
 * checked here as well, because being told *after* filling in a note that the thing was never
 * possible is a worse experience than being shown the count up front - and because the count is the
 * useful part of the message. {@link setReadinessAction} is the single place that rule is expressed
 * on this side; the disabled option carries its reason rather than restating it.
 *
 * This narrows an option, it does not grant one. The service refuses regardless of what is sent.
 */
const READINESS_TARGETS: LocationReadinessStatus[] = ['READY', 'DEGRADED', 'BLOCKED', 'UNKNOWN'];

const SetReadinessDialog = ({ space, openBlockers, onClose, onSubmit }: SetReadinessDialogProps) => {
  const [status, setStatus] = useState<LocationReadinessStatus>(space.readinessStatus);
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const action = setReadinessAction(status, openBlockers);
  const unchanged = status === space.readinessStatus;
  const missingNote = notes.trim().length === 0;

  const submit = async () => {
    setTouched(true);
    if (!action.allowed || unchanged || missingNote) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit(status, notes.trim());
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Set readiness"
      description={`${space.roomCode} - ${space.name}`}
      submitLabel="Set readiness"
      submitting={submitting}
      submitDisabled={!action.allowed || unchanged}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Readiness"
          value={status}
          onChange={(value) => setStatus(value as LocationReadinessStatus)}
          options={READINESS_TARGETS.map((value) => ({ value, label: humaniseCode(value) }))}
          required
          error={!action.allowed}
          helperText={
            action.reason ??
            (unchanged
              ? `This space is already ${humaniseCode(space.readinessStatus).toLowerCase()}.`
              : 'Overrides the assessed status. The assessment history is not rewritten.')
          }
        />

        <TextAreaInput
          label="Why"
          value={notes}
          onChange={setNotes}
          onBlur={() => setTouched(true)}
          required
          rows={3}
          placeholder="e.g. Taken out of use after a burst pipe in the ceiling void."
          error={touched && missingNote}
          helperText={
            touched && missingNote
              ? 'A reason is required.'
              : 'Shown on the space and recorded in the audit trail. A status set by hand with no reason cannot be reviewed.'
          }
        />
      </div>
    </FormDialog>
  );
};

export default SetReadinessDialog;

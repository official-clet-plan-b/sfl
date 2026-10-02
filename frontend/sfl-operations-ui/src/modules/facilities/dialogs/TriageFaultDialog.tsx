import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { FacilityFault, TriageFaultRequest } from '../api/dto';
import type { FaultPriority } from '../api/enums';
import { faultPriorities } from '../api/enums';
import { faultBlockerSeverity } from '../api/workflow';
import { humaniseCode, severityTone } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

interface TriageFaultDialogProps {
  fault: FacilityFault;
  onClose: () => void;
  onSubmit: (request: TriageFaultRequest) => Promise<void>;
}

/**
 * Triage - the point at which a fault gets a deadline. SRS-SFL-S153-02.
 *
 * ## Why the priority can be changed here and only here
 *
 * The SLA is computed from the priority at triage and then stored. Letting the priority be edited
 * afterwards would mean either a stale due date or a due date that moves, and a due date that moves
 * is not a deadline. So this dialog is the one place it is editable, and it says so.
 *
 * ## What the dialog cannot tell you, and does not pretend to
 *
 * The deadline itself depends on the site's configured SLA for the confirmed priority **and** on
 * whether the site is in examination mode, which halves it. Both live in runtime configuration that
 * this screen does not read. Rather than compute a figure that might be wrong, it says what the
 * inputs are and lets the service answer - the due date appears on the fault the moment this
 * returns.
 */
const TriageFaultDialog = ({ fault, onClose, onSubmit }: TriageFaultDialogProps) => {
  const [priority, setPriority] = useState<FaultPriority>(fault.priority);
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const changed = priority !== fault.priority;
  const severity = fault.roomId ? faultBlockerSeverity(priority) : null;
  const wasBlocking = fault.blockerRaised;

  const submit = async () => {
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        priority,
        notes: notes.trim() || null,
        expectedVersion: fault.metadata.version,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Triage fault"
      description={`${fault.faultNumber} - ${fault.title}`}
      submitLabel="Triage and start the clock"
      submitting={submitting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Confirmed priority"
          value={priority}
          onChange={(value) => setPriority(value as FaultPriority)}
          required
          options={faultPriorities.map((value) => ({ value, label: humaniseCode(value) }))}
          helperText={
            changed
              ? `Reported as ${humaniseCode(fault.priority).toLowerCase()}. This is the last point it can be changed.`
              : 'The SLA is computed from this and stored. It cannot be changed afterwards.'
          }
        />

        <TextAreaInput
          label="Triage notes"
          value={notes}
          onChange={setNotes}
          rows={3}
          placeholder="What you found, and anything the assignee should know before attending."
          helperText="Recorded on the fault and in the audit trail."
        />

        <Notice variant="info" title="What happens next">
          <p className="text-sm">
            The deadline is worked out by the service from this site&rsquo;s configured SLA for a{' '}
            {humaniseCode(priority).toLowerCase()} fault, halved if the site is in examination mode.
            Once it passes, the scheduled sweep escalates the fault on its own.
          </p>
        </Notice>

        {severity && !wasBlocking && (
          <Notice
            variant={severity === 'CRITICAL' ? 'error' : 'warning'}
            title="This will block the space"
          >
            <p className="text-sm">
              At {humaniseCode(priority).toLowerCase()} priority this fault raises a{' '}
              <StatusBadge value={severity} tone={severityTone(severity)} /> blocker on the space it
              was reported in.
            </p>
          </Notice>
        )}

        {!severity && wasBlocking && (
          <Notice variant="success" title="This will unblock the space">
            <p className="text-sm">
              Lowering the priority takes this fault below the site&rsquo;s blocking threshold, so the
              readiness blocker it currently holds will be resolved.
            </p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

export default TriageFaultDialog;

import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { ReadinessBlocker } from '../api/dto';
import { humaniseCode, relativeTime, severityTone } from '../components/facilitiesFormat';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

interface ResolveBlockerDialogProps {
  blocker: ReadinessBlocker;
  onClose: () => void;
  onResolved: (resolutionNotes: string) => Promise<void>;
}

/**
 * Closing a readiness blocker.
 *
 * The note is required, and not only because the service refuses without one: a blocker cleared with
 * no explanation leaves a reviewer unable to tell a fix from a dismissal, which is precisely what an
 * examination post-mortem is trying to establish. The dialog says so rather than presenting an
 * unexplained mandatory field.
 *
 * Resolving the last open critical blocker is what lets a space become READY again, so the dialog
 * names that consequence for a critical one - the operator is not just closing a row.
 */
const ResolveBlockerDialog = ({ blocker, onClose, onResolved }: ResolveBlockerDialogProps) => {
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const missingNote = notes.trim().length === 0;

  const submit = async () => {
    setTouched(true);
    if (missingNote) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onResolved(notes.trim());
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Resolve blocker"
      description={
        blocker.severity === 'CRITICAL'
          ? 'This is the kind of blocker that stops the space being used. Resolving it may return the space to ready.'
          : 'Record what was done, so a later review can tell a fix from a dismissal.'
      }
      submitLabel="Resolve"
      submitting={submitting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="rounded-lg bg-surface-muted/20 p-3">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge value={blocker.severity} tone={severityTone(blocker.severity)} />
            <span className="text-xs text-muted-foreground">
              {humaniseCode(blocker.source)} · raised {relativeTime(blocker.raisedAt)} by{' '}
              {blocker.raisedBy}
            </span>
          </div>
          <p className="mt-1.5 text-sm text-foreground">{blocker.description}</p>
        </div>

        <TextAreaInput
          label="What was done"
          value={notes}
          onChange={setNotes}
          onBlur={() => setTouched(true)}
          required
          rows={4}
          placeholder="e.g. Latch replaced and retested against the fire-egress check."
          error={touched && missingNote}
          helperText={
            touched && missingNote
              ? 'A resolution note is required.'
              : 'Recorded on the blocker and in the audit trail.'
          }
        />
      </div>
    </FormDialog>
  );
};

export default ResolveBlockerDialog;

import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { ResolveSetupTaskBody, SetupTask } from '../api/dto';
import type { SetupTaskStatus } from '../api/enums';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface ResolveSetupTaskDialogProps {
  task: SetupTask;
  onClose: () => void;
  onSubmit: (body: ResolveSetupTaskBody) => Promise<void>;
}

/**
 * Mark a turnaround task done, or deliberately skipped.
 *
 * ## Why skipping needs a reason and finishing does not
 *
 * A skipped task that says nothing cannot be told from one nobody got to, and those are the two
 * different failures the queue exists to separate: one is a decision somebody took, the other is a
 * hall that will be wrong when the lecture starts. The service enforces it; this asks for it up front
 * so the refusal is not the first the operator hears of the rule.
 */
const ResolveSetupTaskDialog = ({ task, onClose, onSubmit }: ResolveSetupTaskDialogProps) => {
  const [outcome, setOutcome] = useState<SetupTaskStatus>('DONE');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const skipping = outcome === 'SKIPPED';
  const reasonMissing = skipping && notes.trim().length === 0;

  const submit = async () => {
    if (reasonMissing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({ outcome, notes: notes.trim() || null });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Resolve this task"
      description={task.description}
      submitLabel={skipping ? 'Mark it skipped' : 'Mark it done'}
      submitting={submitting}
      submitDisabled={reasonMissing}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Outcome"
          value={outcome}
          onChange={(value) => setOutcome(value as SetupTaskStatus)}
          required
          options={[
            { value: 'DONE', label: 'Done - the room is ready' },
            { value: 'SKIPPED', label: 'Skipped - deliberately not done' },
          ]}
        />

        <TextAreaInput
          label={skipping ? 'Why it was skipped' : 'Note'}
          value={notes}
          onChange={setNotes}
          required={skipping}
          rows={3}
          maxLength={2000}
          placeholder={
            skipping
              ? 'What was decided, and what the room will be like without it.'
              : 'Optional.'
          }
          helperText={
            skipping
              ? 'Required. A skipped task with no reason cannot be told from one nobody got to.'
              : 'Optional, and recorded on the task.'
          }
        />

        {task.overdue && (
          <Notice variant="warning" title="This task is past when the room was needed">
            <p className="text-sm">
              Resolving it now still records the outcome, but the booking it belongs to may already
              have started.
            </p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

export default ResolveSetupTaskDialog;

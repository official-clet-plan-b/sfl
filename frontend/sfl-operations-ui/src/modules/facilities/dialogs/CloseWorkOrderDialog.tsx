import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { CloseWorkOrderRequest, WorkOrder } from '../api/dto';
import { closeAction, completeAction } from '../api/workflow';
import { evidenceGap } from '../components/facilitiesFormat';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface CloseWorkOrderDialogProps {
  order: WorkOrder;
  attachedEvidence: number;
  onClose: () => void;
  /** Marking complete - what a technician does, since they cannot close. */
  onComplete: (notes: string) => Promise<void>;
  onSubmit: (request: CloseWorkOrderRequest) => Promise<void>;
}

/**
 * Finishing a work order - the two halves of it, in one dialog.
 *
 * ## Why complete and close share a screen
 *
 * They are the same moment from two chairs. A technician marks work `COMPLETED`; a supervisor
 * accepts it and marks it `CLOSED`. Which of the two this dialog offers depends on what the actor
 * may do, and a technician never sees a close button they would be refused - no technician holds
 * `FACILITIES_WORK_ORDER_CLOSE`, which is the whole point of separating the states.
 *
 * ## The evidence gate
 *
 * SRS-SFL-S153-02 refuses closure without the evidence the order required when it was raised. The
 * shortfall is shown as a count and the submit button carries the service's own sentence, so
 * somebody who is blocked knows exactly how many items short they are rather than being told to
 * "attach evidence" and left to guess.
 *
 * Completion is deliberately **not** gated on evidence. A technician who has finished the work has
 * finished it; the evidence requirement is a condition of acceptance, and holding up the report of
 * a completed job on a missing photograph would make the queue lie about what is outstanding.
 */
const CloseWorkOrderDialog = ({
  order,
  attachedEvidence,
  onClose,
  onComplete,
  onSubmit,
}: CloseWorkOrderDialogProps) => {
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const canClose = closeAction(order, attachedEvidence);
  const canComplete = completeAction(order);
  /** Closing when permitted; otherwise this is the completion path. */
  const closing = canClose.allowed || !canComplete.allowed;
  const shortfall = evidenceGap(attachedEvidence, order.evidenceRequired);
  const missing = notes.trim().length === 0;

  const submit = async () => {
    setTouched(true);
    if (missing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      if (closing) {
        await onSubmit({ closureNotes: notes.trim(), expectedVersion: order.metadata.version });
      } else {
        await onComplete(notes.trim());
      }
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={closing ? 'Close work order' : 'Mark complete'}
      description={`${order.workOrderNumber} - ${order.title}`}
      submitLabel={closing ? 'Close' : 'Mark complete'}
      submitting={submitting}
      submitDisabled={closing && !canClose.allowed}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextAreaInput
          label={closing ? 'What was done' : 'What you did'}
          value={notes}
          onChange={setNotes}
          onBlur={() => setTouched(true)}
          required
          rows={4}
          placeholder="e.g. Chairs cleared from the rear egress and a magnetic hold-open fitted."
          error={touched && missing}
          helperText={
            touched && missing
              ? 'A closure reason is required.'
              : 'Recorded on the work order and in the audit trail.'
          }
        />

        {closing && order.evidenceRequired > 0 && (
          <Notice
            variant={canClose.allowed ? 'success' : 'error'}
            title={canClose.allowed ? 'Evidence attached' : 'Evidence missing'}
          >
            <p className="text-sm">
              {canClose.allowed
                ? `${attachedEvidence} item(s) attached, ${order.evidenceRequired} required.`
                : `${shortfall}. Attach the shortfall on the work order before closing - the service refuses closure without it.`}
            </p>
          </Notice>
        )}

        {!closing && (
          <Notice variant="info" title="Not closed yet">
            <p className="text-sm">
              Marking complete tells a supervisor the work is done. They accept it and close it,
              which is where the evidence requirement is checked.
            </p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

export default CloseWorkOrderDialog;

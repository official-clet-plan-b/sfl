import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Booking, TransitionBookingBody } from '../api/dto';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface CompleteBookingDialogProps {
  booking: Booking;
  onClose: () => void;
  onSubmit: (body: TransitionBookingBody) => Promise<void>;
}

/**
 * The booking ran and has finished.
 *
 * A dialog rather than a bare button because completion releases every resource the booking was
 * holding, and because the note is the last thing anybody writes about this room on this day - a
 * projector that failed or a hall left in the wrong layout is recorded here or nowhere.
 *
 * The note is optional. Requiring it would produce a screen full of "done", which is worse than
 * nothing because it reads as a report.
 */
const CompleteBookingDialog = ({ booking, onClose, onSubmit }: CompleteBookingDialogProps) => {
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const submit = async () => {
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        notes: notes.trim() || null,
        expectedVersion: booking.metadata.version,
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
      title="Complete this booking"
      description={`${booking.bookingReference} - ${booking.title}`}
      submitLabel="Complete it"
      submitting={submitting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextAreaInput
          label="Closure note"
          value={notes}
          onChange={setNotes}
          rows={3}
          maxLength={2000}
          placeholder="Anything the next person in this room should know - equipment that failed, layout left changed."
          helperText="Optional, and recorded on the booking."
        />

      </div>
    </FormDialog>
  );
};

export default CompleteBookingDialog;

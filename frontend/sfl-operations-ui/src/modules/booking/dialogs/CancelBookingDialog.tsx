import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Booking, CancelBookingBody } from '../api/dto';
import { isOwnBooking } from '../api/workflow';
import { FormDialog, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface CancelBookingDialogProps {
  booking: Booking;
  onClose: () => void;
  onSubmit: (body: CancelBookingBody) => Promise<void>;
}

/**
 * Withdraw a booking.
 *
 * A reason is required whoever cancels and however late - `Booking.cancel` refuses a blank one. The
 * notice differs by whose booking it is, because the two acts are genuinely different: withdrawing
 * your own is housekeeping, and cancelling somebody else's takes a room out of their diary and they
 * will read what you typed.
 */
const CancelBookingDialog = ({ booking, onClose, onSubmit }: CancelBookingDialogProps) => {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const own = isOwnBooking(booking);
  const missing = reason.trim().length === 0;

  const submit = async () => {
    if (missing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({ reason: reason.trim(), expectedVersion: booking.metadata.version });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={own ? 'Withdraw your booking' : 'Cancel this booking'}
      description={`${booking.bookingReference} - ${booking.title}`}
      submitLabel={own ? 'Withdraw it' : 'Cancel it'}
      submitting={submitting}
      submitDisabled={missing}
      destructive
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextAreaInput
          label="Reason"
          value={reason}
          onChange={setReason}
          required
          rows={3}
          maxLength={2000}
          placeholder="Why the booking is being withdrawn."
          helperText={
            own
              ? 'Required. Recorded on the booking and in the audit trail.'
              : 'Required. This is what the requester will be shown.'
          }
        />

        {!own && (
          <Notice variant="warning" title="This is somebody else’s booking">
            <p className="text-sm">
              {booking.requestedBy} requested it. Cancelling takes the room out of their diary, and
              your reason is what they will read.
            </p>
          </Notice>
        )}

      </div>
    </FormDialog>
  );
};

export default CancelBookingDialog;

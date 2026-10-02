import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Booking, DecideBookingBody } from '../api/dto';
import { formatWindow } from '../components/bookingFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface DecideBookingDialogProps {
  booking: Booking;
  onClose: () => void;
  onSubmit: (body: DecideBookingBody) => Promise<void>;
}

/**
 * Approve or reject a request - SRS-SFL-S159-02.
 *
 * ## Why a rejection cannot be submitted without a reason
 *
 * The service requires it (`BookingApproval.decide` rejects a blank reason), and the requirement is
 * not ceremony: the reason is the only thing the requester will be shown. A rejection with nothing
 * attached tells somebody planning an examination that they cannot have the hall and gives them no
 * way to ask for a better one.
 *
 * ## What approving re-checks, which is more than it looks
 *
 * The space is tested for conflict **again** at approval, not only at request. A hall free when it
 * was asked for on Monday can be taken by an override or a rescheduled booking before Thursday's
 * decision, and confirming into a clash would produce two confirmed bookings for one room. So an
 * approval can be refused with `BOOKING_CONFLICT`, and that is not a bug to work around here - it is
 * the constraint doing exactly what it is for. The error surfaces with the service's own wording.
 */
const DecideBookingDialog = ({ booking, onClose, onSubmit }: DecideBookingDialogProps) => {
  const [decision, setDecision] = useState('APPROVE');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const rejecting = decision === 'REJECT';
  const reasonMissing = rejecting && reason.trim().length === 0;

  const submit = async () => {
    if (reasonMissing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        approve: !rejecting,
        reason: reason.trim() || null,
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
      title="Decide this booking"
      description={`${booking.bookingReference} - ${booking.title}`}
      submitLabel={rejecting ? 'Reject the request' : 'Approve and confirm'}
      submitting={submitting}
      submitDisabled={reasonMissing}
      destructive={rejecting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Decision"
          value={decision}
          onChange={setDecision}
          required
          options={[
            { value: 'APPROVE', label: 'Approve - confirm the booking' },
            { value: 'REJECT', label: 'Reject - release the space' },
          ]}
        />

        <TextAreaInput
          label={rejecting ? 'Why it is refused' : 'Note'}
          value={reason}
          onChange={setReason}
          required={rejecting}
          error={reasonMissing && reason.length > 0}
          rows={3}
          maxLength={2000}
          placeholder={
            rejecting
              ? 'What the requester should know, and what they could ask for instead.'
              : 'Optional. Recorded on the approval.'
          }
          helperText={
            rejecting
              ? 'Required. This is the only thing the requester will be shown.'
              : 'Recorded against the decision and in the audit trail.'
          }
        />

        {rejecting && (
          <Notice variant="warning" title="What rejecting does">
            <p className="text-sm">
              The space and every resource this booking holds are released immediately, and its setup
              tasks are marked skipped with your reason. {formatWindow(booking.startsAt, booking.endsAt)}{' '}
              becomes free for the next requester.
            </p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

export default DecideBookingDialog;

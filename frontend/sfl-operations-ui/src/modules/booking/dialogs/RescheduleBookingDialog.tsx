import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Booking, RescheduleBookingBody } from '../api/dto';
import { canOverrideReadiness } from '../api/workflow';
import { fromLocalInput, toLocalInput, windowProblem } from '../components/bookingFormat';
import { DateTimeField, FormDialog, NumberInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface RescheduleBookingDialogProps {
  booking: Booking;
  onClose: () => void;
  onSubmit: (body: RescheduleBookingBody) => Promise<void>;
}

/**
 * Move a booking to a different window.
 *
 * ## What moves with it, and why that is one transaction
 *
 * Every resource the booking holds moves too. An allocation left on the old window would hold a
 * projector at a time nothing is happening and release it at a time something is - so the service
 * does both in one transaction, and this dialog says so rather than leaving an operator to discover
 * it on the allocations list afterwards.
 *
 * ## What a move clears, and what it deliberately does not
 *
 * It clears any readiness hold: the hold was a statement about a specific window on a specific space,
 * and carrying it across would assert something nothing has checked. The reconciliation sweep
 * re-places it within the minute if it still applies.
 *
 * It does **not** reset approval. That is the arguable call and it was made deliberately: moving an
 * approved booking by ten minutes does not warrant sending it back round the approver. A site that
 * disagrees cancels and re-requests.
 */
const RescheduleBookingDialog = ({ booking, onClose, onSubmit }: RescheduleBookingDialogProps) => {
  const [startsAt, setStartsAt] = useState(toLocalInput(booking.startsAt));
  const [endsAt, setEndsAt] = useState(toLocalInput(booking.endsAt));
  const [setupMinutes, setSetupMinutes] = useState(String(booking.setupMinutes));
  const [teardownMinutes, setTeardownMinutes] = useState(String(booking.teardownMinutes));
  const [overrideReason, setOverrideReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const problem = windowProblem(startsAt, endsAt);
  const mayOverride = canOverrideReadiness().kind === 'allowed';

  const submit = async () => {
    if (problem) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        startsAt: fromLocalInput(startsAt),
        endsAt: fromLocalInput(endsAt),
        setupMinutes: setupMinutes === '' ? null : Number(setupMinutes),
        teardownMinutes: teardownMinutes === '' ? null : Number(teardownMinutes),
        overrideReason: overrideReason.trim() || null,
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
      title="Move this booking"
      description={`${booking.bookingReference} - ${booking.title}`}
      submitLabel="Move it"
      submitting={submitting}
      submitDisabled={Boolean(problem)}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <DateTimeField label="Starts" value={startsAt} onChange={setStartsAt} required />
          <DateTimeField
            label="Ends"
            value={endsAt}
            onChange={setEndsAt}
            required
            error={Boolean(problem)}
            helperText={problem ?? undefined}
          />
          <NumberInput
            label="Setup buffer"
            value={setupMinutes}
            onChange={setSetupMinutes}
            min={0}
            suffix="min"
            helperText="Blank keeps the site's default for this purpose."
          />
          <NumberInput
            label="Teardown buffer"
            value={teardownMinutes}
            onChange={setTeardownMinutes}
            min={0}
            suffix="min"
          />
        </div>

        {mayOverride && (
          <TextAreaInput
            label="Override reason"
            value={overrideReason}
            onChange={setOverrideReason}
            rows={2}
            maxLength={2000}
            placeholder="Only needed if the new window's readiness would otherwise refuse."
            helperText="Recorded on the booking. Leave blank unless readiness refuses the move."
          />
        )}

      </div>
    </FormDialog>
  );
};

export default RescheduleBookingDialog;

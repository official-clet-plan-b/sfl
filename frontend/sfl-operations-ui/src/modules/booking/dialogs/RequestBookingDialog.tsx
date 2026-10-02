import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { humaniseCode, readinessTone } from 'modules/facilities/components/facilitiesFormat';
import type { RequestBookingBody, ResourceAvailability, SpaceAvailability } from '../api/dto';
import { BOOKING_PURPOSES, HOLD_REASON_DESCRIPTIONS } from '../api/enums';
import type { BookingPurpose } from '../api/enums';
import { canOverrideReadiness } from '../api/workflow';
import { formatWindow } from '../components/bookingFormat';
import { FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

interface RequestBookingDialogProps {
  space: SpaceAvailability;
  /** The window the availability search was run for. Not editable here - see below. */
  startsAt: string;
  endsAt: string;
  setupMinutes: number;
  teardownMinutes: number;
  purpose: BookingPurpose | '';
  /** What is free for this window, so a requester can take a projector with the hall. */
  resources: ResourceAvailability[];
  onClose: () => void;
  onSubmit: (body: RequestBookingBody) => Promise<void>;
}

/**
 * Request a booking for a space the availability search has just answered on.
 *
 * ## Why the window is fixed here
 *
 * It is the window the verdict on this space was given for. Letting it be edited in the dialog would
 * mean submitting against a "free" that was computed for a different window - the operator would read
 * *available*, change the time, and be refused with no idea why. Changing the window means going back
 * and asking again, which is one click and always tells the truth.
 *
 * ## What asking does not do
 *
 * Nothing is reserved by looking. Two people can both be told Hall A is free and both request it; the
 * first wins and the second is refused with `BOOKING_CONFLICT`. Holding a space during a five-minute
 * browse would mean the estate's diary was mostly locked by people who had wandered off.
 *
 * ## The override
 *
 * Only offered when readiness would otherwise refuse **and** the actor holds
 * `FACILITIES_BOOKING_OVERRIDE`. The reason is required in that case and is recorded against the
 * booking - it is what an auditor reads when asked why an examination ran in a degraded hall.
 */
const RequestBookingDialog = ({
  space,
  startsAt,
  endsAt,
  setupMinutes,
  teardownMinutes,
  purpose: initialPurpose,
  resources,
  onClose,
  onSubmit,
}: RequestBookingDialogProps) => {
  const [purpose, setPurpose] = useState<BookingPurpose>(initialPurpose || 'MEETING');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [attendees, setAttendees] = useState('');
  const [requestedFor, setRequestedFor] = useState('');
  const [wanted, setWanted] = useState<Record<string, string>>({});
  const [overrideReason, setOverrideReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const needsOverride = !space.free && space.availableWithOverride;
  const mayOverride = canOverrideReadiness().kind === 'allowed';
  const overrideMissing = needsOverride && overrideReason.trim().length === 0;
  const titleMissing = title.trim().length === 0;
  const overCapacity =
    space.capacity !== null && attendees !== '' && Number(attendees) > space.capacity;

  /** Only the resources actually asked for, and only positive quantities - the service rejects `0`. */
  const requestedResources = (): Record<string, number> => {
    const chosen: Record<string, number> = {};
    Object.entries(wanted).forEach(([resourceId, quantity]) => {
      const amount = Number(quantity);
      if (Number.isFinite(amount) && amount > 0) {
        chosen[resourceId] = amount;
      }
    });
    return chosen;
  };

  const submit = async () => {
    if (titleMissing || overrideMissing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      const chosen = requestedResources();
      await onSubmit({
        roomId: space.roomId,
        purpose,
        title: title.trim(),
        description: description.trim() || null,
        startsAt,
        endsAt,
        setupMinutes,
        teardownMinutes,
        expectedAttendees: attendees === '' ? 0 : Number(attendees),
        requestedFor: requestedFor.trim() || null,
        resources: Object.keys(chosen).length > 0 ? chosen : undefined,
        overrideReason: needsOverride ? overrideReason.trim() : null,
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
      title={`Book ${space.roomCode}`}
      description={`${formatWindow(startsAt, endsAt)}${
        setupMinutes || teardownMinutes ? ` · ${setupMinutes} min setup, ${teardownMinutes} min teardown` : ''
      }`}
      submitLabel="Request it"
      submitting={submitting}
      submitDisabled={titleMissing || overrideMissing}
      maxWidth="lg"
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="What is it"
          value={title}
          onChange={setTitle}
          required
          maxLength={200}
          placeholder="Contract Law II - Week 6 lecture"
          helperText="Shown in the diary. Say what would let somebody else recognise it."
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput
            label="Purpose"
            value={purpose}
            onChange={(value) => setPurpose(value as BookingPurpose)}
            required
            options={BOOKING_PURPOSES.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText="Decides the default buffers and whether approval is needed."
          />
          <NumberInput
            label="Expected attendees"
            value={attendees}
            onChange={setAttendees}
            min={0}
            error={overCapacity}
            helperText={
              space.capacity === null
                ? 'No capacity recorded for this space.'
                : overCapacity
                  ? `This space seats ${space.capacity}. The service will still take the booking.`
                  : `This space seats ${space.capacity}.`
            }
          />
        </div>

        <TextInput
          label="Booked on behalf of"
          value={requestedFor}
          onChange={setRequestedFor}
          maxLength={200}
          placeholder="Leave blank if it is for you"
          helperText="Who the room is actually for. You remain the requester."
        />

        <TextAreaInput
          label="Notes"
          value={description}
          onChange={setDescription}
          rows={2}
          maxLength={4000}
          placeholder="Layout, access, anything the setup crew needs to know."
        />

        {resources.length > 0 && (
          <fieldset className="rounded-lg border border-border p-4">
            <legend className="px-1 text-sm font-medium text-foreground">
              Resources for this window
            </legend>
            <p className="mb-3 text-xs text-muted-foreground">
              Free counts are for this window and are not reserved until the booking is made. A
              resource that needs setting up raises a turnaround task automatically.
            </p>
            <div className="space-y-3">
              {resources.map((resource) => (
                <div key={resource.resourceId} className="flex items-end gap-3">
                  <div className="min-w-0 flex-1 pb-1">
                    <p className="truncate text-sm font-medium text-foreground">{resource.name}</p>
                    <p className="text-xs text-muted-foreground">
                      {humaniseCode(resource.category)} · {resource.free} of {resource.quantity} free
                    </p>
                  </div>
                  {/* Labelled per row rather than once above: the field's name has to say which
                      resource it counts, or a screen reader hears four identical "Quantity" boxes. */}
                  <NumberInput
                    label={`How many ${resource.name}`}
                    value={wanted[resource.resourceId] ?? ''}
                    onChange={(value) =>
                      setWanted((current) => ({ ...current, [resource.resourceId]: value }))
                    }
                    min={0}
                    max={resource.free}
                    disabled={resource.free === 0}
                    className="w-24 [&>label]:sr-only"
                  />
                </div>
              ))}
            </div>
          </fieldset>
        )}

        {needsOverride && mayOverride && (
          <>
            <Notice variant="warning" title="Readiness would refuse this space">
              <p className="text-sm">
                {space.readinessIssue ? HOLD_REASON_DESCRIPTIONS[space.readinessIssue] : null}{' '}
                {space.readinessDetail}
              </p>
              <p className="mt-2 text-sm">
                Current readiness{' '}
                <StatusBadge value={space.readinessStatus} tone={readinessTone(space.readinessStatus)} />. You may book into it anyway; the reason
                below is recorded against the booking and is what an auditor reads.
              </p>
            </Notice>
            <TextAreaInput
              label="Why this override is justified"
              value={overrideReason}
              onChange={setOverrideReason}
              required
              rows={2}
              maxLength={2000}
              placeholder="What has been checked, and who decided the space is fit for this booking."
            />
          </>
        )}

      </div>
    </FormDialog>
  );
};

export default RequestBookingDialog;

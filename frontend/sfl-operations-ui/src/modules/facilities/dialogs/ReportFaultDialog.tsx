import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import type { ReportFaultRequest } from '../api/dto';
import type { FaultPriority } from '../api/enums';
import { faultPriorities } from '../api/enums';
import { searchSpaces } from '../api/facilitiesApi';
import { faultBlockerSeverity } from '../api/workflow';
import { humaniseCode, severityTone } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

interface ReportFaultDialogProps {
  siteCode: string;
  /** Pre-selects the space, when reporting from a space detail screen. */
  roomId?: string;
  onClose: () => void;
  onSubmit: (request: ReportFaultRequest) => Promise<void>;
}

/**
 * Reporting a fault - SRS-SFL-S153-01.
 *
 * ## Two things this dialog does that a plain form would not
 *
 * **It requires a place, and says which kinds count.** The service refuses a fault carrying neither
 * a room nor a location code: one with a site and nothing else cannot be dispatched anywhere, which
 * makes it a complaint rather than a fault. So the form asks for a space *or* free text, and refuses
 * neither - matching the aggregate rather than discovering the rule on submit. Free text is there on
 * purpose: a corridor, a car park or an external wall has no room in the estate model, deliberately,
 * because none of them is a bookable space.
 *
 * **It says what the priority will do to the space.** A critical fault in an examination hall takes
 * that hall out of service the moment it is submitted. That is the right behaviour and it is a
 * surprising one, so it is stated before the button rather than discovered afterwards by whoever
 * tried to book the hall. Mirrors `FaultReadinessPolicy.severityFor`.
 */
const ReportFaultDialog = ({ siteCode, roomId, onClose, onSubmit }: ReportFaultDialogProps) => {
  const [selectedRoom, setSelectedRoom] = useState(roomId ?? '');
  const [locationCode, setLocationCode] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('');
  const [priority, setPriority] = useState<FaultPriority>('MEDIUM');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const spaces = useApiQuery(
    (signal) => searchSpaces({ siteCode: siteCode || undefined, size: 200 }, signal),
    [siteCode],
  );

  const missingPlace = !selectedRoom && locationCode.trim().length === 0;
  const missingTitle = title.trim().length === 0;
  const missingDescription = description.trim().length === 0;
  const invalid = missingPlace || missingTitle || missingDescription;

  /** What this priority will do to the space, if it is in one. */
  const severity = selectedRoom ? faultBlockerSeverity(priority) : null;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        siteCode: siteCode || undefined,
        roomId: selectedRoom || null,
        locationCode: locationCode.trim() || null,
        title: title.trim(),
        description: description.trim(),
        category: category.trim() || null,
        priority,
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
      title="Report a fault"
      description="Something is wrong somewhere. Say what and where."
      submitLabel="Report fault"
      submitting={submitting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="What is wrong"
          value={title}
          onChange={setTitle}
          onBlur={() => setTouched(true)}
          required
          maxLength={200}
          placeholder="e.g. Fire exit blocked"
          error={touched && missingTitle}
          helperText={touched && missingTitle ? 'A short summary is required.' : undefined}
        />

        <TextAreaInput
          label="Detail"
          value={description}
          onChange={setDescription}
          onBlur={() => setTouched(true)}
          required
          rows={3}
          placeholder="What you can see, and anything that would help whoever attends."
          error={touched && missingDescription}
          helperText={touched && missingDescription ? 'A description is required.' : undefined}
        />

        <SelectInput
          label="Space"
          value={selectedRoom}
          onChange={(value) => {
            setSelectedRoom(value);
            setTouched(true);
          }}
          allowEmpty
          emptyLabel="Not in a listed space"
          options={(spaces.data?.items ?? []).map((space) => ({
            value: space.id,
            label: `${space.roomCode} - ${space.name}`,
          }))}
          helperText="Choosing a space is what lets a fault affect whether it can be used."
        />

        <TextInput
          label="Or where else"
          value={locationCode}
          onChange={setLocationCode}
          onBlur={() => setTouched(true)}
          maxLength={120}
          disabled={Boolean(selectedRoom)}
          placeholder="e.g. CAR-PARK-B, CORRIDOR-1"
          error={touched && missingPlace}
          helperText={
            touched && missingPlace
              ? 'Pick a space, or name where it is. A fault with neither cannot be dispatched.'
              : 'For a corridor, a car park or anywhere that is not a bookable space.'
          }
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput
            label="Priority"
            value={priority}
            onChange={(value) => setPriority(value as FaultPriority)}
            required
            options={faultPriorities.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText="Confirmed at triage, which is when the SLA clock starts."
          />
          <TextInput
            label="Category"
            value={category}
            onChange={setCategory}
            maxLength={120}
            placeholder="e.g. Electrical"
          />
        </div>

        {severity && (
          <Notice
            variant={severity === 'CRITICAL' ? 'error' : 'warning'}
            title="What this does to the space"
          >
            <p className="text-sm">
              A {humaniseCode(priority).toLowerCase()} fault raises a{' '}
              <StatusBadge value={severity} tone={severityTone(severity)} /> blocker on the space.
              {severity === 'CRITICAL'
                ? ' It will be marked BLOCKED and cannot be booked or used for an examination until this is resolved.'
                : ' Its readiness will be degraded.'}
            </p>
          </Notice>
        )}
      </div>
    </FormDialog>
  );
};

export default ReportFaultDialog;

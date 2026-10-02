import { useEffect, useMemo, useRef } from 'react';
import { DriverLogbook } from 'modules/fuel/api/dto';
import { LOGBOOK_USE_CLASSIFICATIONS, LogbookUseClassification } from 'modules/fuel/api/enums';
import { LogbookTransition, driverLogbooksApi } from 'modules/fuel/api/fuelApi';
import {
  LOGBOOK_RULES,
  logbookSubmissionBlockers,
  logbookSubmitTarget,
} from 'modules/fuel/api/workflow';
import {
  DriverSelect,
  TripSelect,
  VehicleSelect,
} from 'modules/fleet/components/FleetReferenceSelect';
import { driversApi, tripsApi, vehiclesApi } from 'modules/fleet/api/fleetApi';
import { humanise } from 'modules/fleet/api/enums';
import { Alert, FormDialog } from 'modules/fuel/components/fuelUi';
import { Banner } from '@rfdtech/components';
import SiteSelect from 'shared/components/SiteSelect';
import { DateField, DateTimeField, CheckboxField as Checkbox, EnumSelect, NumberInput, TextAreaInput, TextInput } from 'modules/fuel/components/fuelUi';
import { useFleetForm } from 'shared/validation/useFleetForm';
import {
  compose,
  integerAtLeast,
  maxLength,
  required,
  validDateTime,
} from 'shared/validation/validators';
import { readSession } from 'shared/auth/session';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { isPersona } from 'shared/layout/personas';
import { useRecentValues } from 'shared/hooks/useRecentValues';
import FormSummary from 'shared/components/FormSummary';
import PlaceField from 'shared/components/PlaceField';

const twoColumn = 'grid gap-4 sm:grid-cols-2';
const REFERENCE_WINDOW = 200;

const toDateInput = (value: string | null | undefined): string => {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
};

const toDateTimeInput = (value: string | null | undefined): string => {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return `${toDateInput(value)}T${String(date.getHours()).padStart(2, '0')}:${String(
    date.getMinutes(),
  ).padStart(2, '0')}`;
};

const normalise = (value: string | null | undefined): string =>
  (value ?? '').trim().toLowerCase();

interface CreateLogbookDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: (logbook: DriverLogbook) => void;
  defaultSiteCode: string;
  logbook?: DriverLogbook;
}

/**
 * Create a draft logbook - `POST /api/v1/fuel/logbooks`.
 *
 * The record is always created as `DRAFT`; there is no create-and-submit. That is deliberate on the
 * service side and it shapes this form: the end of the journey (end time, closing odometer,
 * declaration) is optional here, because a driver opens the logbook when the journey starts. It
 * becomes mandatory at submission, and this dialog says so rather than demanding it up front.
 *
 * The two cross-field rules are the record's own: `endTime` may not precede `startTime`, and
 * `endOdometer` may not be below `startOdometer`. Both are enforced in `DriverLogbook`'s compact
 * constructor, so a violation would be a 400 rather than a field error - checking here turns it
 * into an inline message on the field that caused it.
 */
export const CreateLogbookDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
  logbook,
}: CreateLogbookDialogProps) => {
  const editing = Boolean(logbook);
  const session = readSession();
  const driverOnly = isPersona('driver');
  const appliedTripRef = useRef<string | null>(null);

  // Scoped to the site: a manager holding four centres should not be offered one centre's
  // routes while filing against another.
  const { values: recentOrigins, remember: rememberOrigin } = useRecentValues('origin', logbook?.siteCode.value ?? defaultSiteCode);
  const { values: recentDestinations, remember: rememberDestination } = useRecentValues(
    'destination',
    logbook?.siteCode.value ?? defaultSiteCode,
  );

  const form = useFleetForm({
    initialValues: {
      siteCode: logbook?.siteCode.value ?? defaultSiteCode,
      driverId: logbook?.driverId ?? '',
      vehicleId: logbook?.vehicleId ?? '',
      tripId: logbook?.tripId ?? '',
      journeyDate: logbook?.journeyDate ?? '',
      startTime: toDateTimeInput(logbook?.startTime),
      endTime: toDateTimeInput(logbook?.endTime),
      origin: logbook?.origin ?? '',
      destination: logbook?.destination ?? '',
      routeNotes: logbook?.routeNotes ?? '',
      useClassification: logbook?.useClassification ?? ('OFFICIAL' as LogbookUseClassification),
      purpose: logbook?.purpose ?? '',
      passengerLoadNotes: logbook?.passengerLoadNotes ?? '',
      startOdometer: logbook ? String(logbook.startOdometer) : '',
      endOdometer:
        logbook?.endOdometer === null || logbook?.endOdometer === undefined
          ? ''
          : String(logbook.endOdometer),
      declarationAccepted: logbook?.declarationAccepted ?? false,
      evidenceId: logbook?.evidenceId ?? '',
    },
    schema: {
      siteCode: required('Site code'),
      driverId: required('Driver'),
      vehicleId: required('Vehicle'),
      journeyDate: required('Journey date'),
      startTime: compose(required('Start time'), validDateTime('Start time')),
      endTime: validDateTime('End time'),
      origin: compose(required('Origin'), maxLength('Origin', 200)),
      destination: compose(required('Destination'), maxLength('Destination', 200)),
      useClassification: required('Use classification'),
      purpose: compose(required('Purpose'), maxLength('Purpose', 500)),
      routeNotes: maxLength('Route notes', 1000),
      passengerLoadNotes: maxLength('Passenger and load notes', 1000),
      startOdometer: compose(
        required('Opening odometer'),
        integerAtLeast('Opening odometer', 0),
      ),
      endOdometer: integerAtLeast('Closing odometer', 0),
    },
    crossFieldValidate: (values) => {
      const errors: Record<string, string> = {};
      if (values.startTime && values.endTime) {
        const start = new Date(values.startTime).getTime();
        const end = new Date(values.endTime).getTime();
        // The record allows equality; only a genuinely earlier end is refused.
        if (!Number.isNaN(start) && !Number.isNaN(end) && end < start) {
          errors.endTime = 'End time cannot be before the start time.';
        }
      }
      if (values.startOdometer !== '' && values.endOdometer !== '') {
        if (Number(values.endOdometer) < Number(values.startOdometer)) {
          errors.endOdometer = 'Closing odometer cannot be lower than the opening reading.';
        }
      }
      return errors;
    },
    onSubmit: async (values) => {
      const payload = {
        siteCode: values.siteCode.trim().toUpperCase(),
        driverId: values.driverId,
        vehicleId: values.vehicleId,
        tripId: values.tripId || null,
        journeyDate: values.journeyDate,
        startTime: new Date(values.startTime).toISOString(),
        endTime: values.endTime ? new Date(values.endTime).toISOString() : null,
        origin: values.origin.trim(),
        destination: values.destination.trim(),
        routeNotes: values.routeNotes.trim() || null,
        useClassification: values.useClassification,
        purpose: values.purpose.trim(),
        passengerLoadNotes: values.passengerLoadNotes.trim() || null,
        startOdometer: Number(values.startOdometer),
        endOdometer: values.endOdometer === '' ? null : Number(values.endOdometer),
        declarationAccepted: values.declarationAccepted,
        evidenceId: values.evidenceId.trim() || null,
      };
      const saved = editing && logbook
        ? await driverLogbooksApi.update(logbook.id, payload)
        : await driverLogbooksApi.create(payload);
      rememberOrigin(values.origin);
      rememberDestination(values.destination);
      onSaved(saved);
      onClose();
    },
  });

  const drivers = useApiQuery(
    (signal) =>
      driverOnly && form.values.siteCode
        ? driversApi.search({ siteCode: form.values.siteCode, size: REFERENCE_WINDOW }, signal)
        : Promise.resolve(undefined),
    [driverOnly, form.values.siteCode],
  );

  const ownDriver = useMemo(() => {
    if (!driverOnly || !session) {
      return null;
    }
    const username = normalise(session.username);
    const displayName = normalise(session.displayName);
    return (
      (drivers.data?.content ?? []).find(
        (driver) =>
          normalise(driver.staffReference) === username ||
          normalise(driver.displayName) === displayName,
      ) ?? null
    );
  }, [driverOnly, drivers.data, session]);

  useEffect(() => {
    if (driverOnly && ownDriver && form.values.driverId !== ownDriver.id) {
      form.setValue('driverId', ownDriver.id);
    }
    // Form helpers are intentionally omitted: they are tied to validation closures and would cause
    // this pre-fill effect to re-run on every value change instead of only when the resolved driver
    // changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [driverOnly, ownDriver?.id, form.values.driverId]);

  const trip = useApiQuery(
    (signal) =>
      form.values.tripId
        ? tripsApi.findById(form.values.tripId, signal)
        : Promise.resolve(undefined),
    [form.values.tripId],
  );

  const vehicle = useApiQuery(
    (signal) =>
      trip.data?.vehicleId
        ? vehiclesApi.findById(trip.data.vehicleId, signal)
        : Promise.resolve(undefined),
    [trip.data?.vehicleId],
  );

  useEffect(() => {
    const selectedTrip = trip.data;
    if (!selectedTrip || appliedTripRef.current === selectedTrip.id) {
      return;
    }
    appliedTripRef.current = selectedTrip.id;
    form.setValues({
      siteCode: selectedTrip.siteCode,
      driverId: selectedTrip.driverId ?? form.values.driverId,
      vehicleId: selectedTrip.vehicleId ?? form.values.vehicleId,
      journeyDate: toDateInput(selectedTrip.actualStart ?? selectedTrip.plannedStart),
      startTime: toDateTimeInput(selectedTrip.actualStart ?? selectedTrip.plannedStart),
      endTime: toDateTimeInput(selectedTrip.actualEnd ?? selectedTrip.plannedEnd),
      origin: selectedTrip.origin,
      destination: selectedTrip.destination,
      purpose: selectedTrip.purpose,
      useClassification:
        selectedTrip.operatingMode === 'EMERGENCY' ? 'OPERATIONAL' : 'OFFICIAL',
      startOdometer:
        selectedTrip.startOdometer === null || selectedTrip.startOdometer === undefined
          ? form.values.startOdometer
          : String(selectedTrip.startOdometer),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trip.data?.id]);

  useEffect(() => {
    if (
      !form.values.startOdometer &&
      vehicle.data?.odometerValue !== null &&
      vehicle.data?.odometerValue !== undefined
    ) {
      form.setValue('startOdometer', String(vehicle.data.odometerValue));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form.values.startOdometer, vehicle.data?.odometerValue]);

  const incomplete =
    !form.values.endTime || form.values.endOdometer === '' || !form.values.declarationAccepted;

  const logbookDistance =
    form.values.startOdometer !== '' && form.values.endOdometer !== ''
      ? `${Number(form.values.startOdometer).toLocaleString()} → ${Number(
          form.values.endOdometer,
        ).toLocaleString()} km`
      : form.values.startOdometer !== ''
        ? `from ${Number(form.values.startOdometer).toLocaleString()} km`
        : null;

  return (
    <FormDialog
      open={open}
      title={editing ? 'Complete driver logbook draft' : 'Create a driver logbook'}
      description={
        editing
          ? 'Update the draft details, accept the declaration, then submit it for review.'
          : 'Created as a draft. Select a trip to pre-fill the journey, then complete actual readings.'
      }
      submitLabel={editing ? 'Save draft' : 'Create draft'}
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      summary={
        <FormSummary
          items={[
            {
              label: 'Journey',
              value:
                form.values.origin && form.values.destination
                  ? `${form.values.origin} → ${form.values.destination}`
                  : null,
            },
            { label: 'Date', value: form.values.journeyDate || null },
            { label: 'Odometer', value: logbookDistance },
            // A draft that is missing an end time, a closing reading or the declaration cannot be
            // submitted for review, and `incomplete` already knows it - saying so here means the
            // operator learns it before saving rather than from the next screen.
            { label: 'State', value: incomplete ? 'Draft - not yet complete' : 'Complete' },
          ]}
        />
      }
      onClose={onClose}
      onSubmit={form.submit}
    >
      {!editing && (
        <Banner
          variant="info"
          heading="Create the journey draft in three steps"
          subtext="Select the assigned trip first, check the pre-filled driver and vehicle details, then complete the actual times and odometer readings before saving. Submit the draft from its detail page when the journey is complete."
        />
      )}
      <div className={twoColumn}>
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) =>
            form.setValues({ siteCode: value, driverId: '', vehicleId: '', tripId: '' })
          }
          {...form.fieldProps('siteCode')}
        />
        {/*
          Second field, not eleventh. It was below use classification, which meant an operator had
          already typed origin, destination, purpose, times and odometer by hand before reaching the
          control that would have filled all six from the trip. The shortcut has to be offered before
          the work, not after it.
        */}
        <TripSelect
          siteCode={form.values.siteCode}
          value={form.values.tripId}
          onChange={(value) => form.setValue('tripId', value)}
          {...form.fieldProps(
            'tripId',
            'Fills vehicle, route, date, times, purpose and odometer from the assigned trip.',
          )}
        />
        <DateField
          label="Journey date"
          required
          value={form.values.journeyDate}
          onChange={(value) => form.setValue('journeyDate', value)}
          {...form.fieldProps('journeyDate')}
        />
        {driverOnly ? (
          <TextInput
            label="Driver"
            required
            disabled
            value={ownDriver?.displayName ?? session?.displayName ?? 'Signed-in driver'}
            onChange={() => undefined}
            {...form.fieldProps(
              'driverId',
              ownDriver
                ? `Auto-filled from your signed-in driver profile: ${ownDriver.displayName}.`
                : 'Auto-filled from your signed-in driver profile.',
            )}
          />
        ) : (
          <DriverSelect
            required
            siteCode={form.values.siteCode}
            value={form.values.driverId}
            onChange={(value) => form.setValue('driverId', value)}
            {...form.fieldProps('driverId')}
          />
        )}
        <VehicleSelect
          required
          siteCode={form.values.siteCode}
          value={form.values.vehicleId}
          onChange={(value) => form.setValue('vehicleId', value)}
          {...form.fieldProps('vehicleId')}
        />
        <DateTimeField
          label="Start time"
          required
          value={form.values.startTime}
          onChange={(value) => form.setValue('startTime', value)}
          {...form.fieldProps('startTime')}
        />
        <DateTimeField
          label="End time"
          value={form.values.endTime}
          onChange={(value) => form.setValue('endTime', value)}
          {...form.fieldProps('endTime', 'Required before the logbook can be submitted.')}
        />
        <PlaceField
          label="Origin"
          required
          recent={recentOrigins}
          value={form.values.origin}
          onChange={(value) => form.setValue('origin', value)}
          {...form.fieldProps('origin')}
        />
        <PlaceField
          label="Destination"
          required
          recent={recentDestinations}
          value={form.values.destination}
          onChange={(value) => form.setValue('destination', value)}
          {...form.fieldProps('destination')}
        />
        <NumberInput
          label="Opening odometer"
          required
          suffix="km"
          value={form.values.startOdometer}
          onChange={(value) => form.setValue('startOdometer', value)}
          {...form.fieldProps('startOdometer')}
        />
        <NumberInput
          label="Closing odometer"
          suffix="km"
          value={form.values.endOdometer}
          onChange={(value) => form.setValue('endOdometer', value)}
          {...form.fieldProps('endOdometer', 'Required before the logbook can be submitted.')}
        />
        <EnumSelect
          label="Use classification"
          required
          value={form.values.useClassification}
          options={LOGBOOK_USE_CLASSIFICATIONS}
          onChange={(value) =>
            form.setValue('useClassification', (value || 'OFFICIAL') as LogbookUseClassification)
          }
          {...form.fieldProps('useClassification')}
        />
      </div>

      <TextInput
        label="Purpose"
        required
        value={form.values.purpose}
        onChange={(value) => form.setValue('purpose', value)}
        {...form.fieldProps('purpose')}
      />
      <TextAreaInput
        label="Route notes"
        rows={2}
        value={form.values.routeNotes}
        onChange={(value) => form.setValue('routeNotes', value)}
        {...form.fieldProps('routeNotes')}
      />
      <TextAreaInput
        label="Passenger and load notes"
        rows={2}
        value={form.values.passengerLoadNotes}
        onChange={(value) => form.setValue('passengerLoadNotes', value)}
        {...form.fieldProps('passengerLoadNotes')}
      />
      <TextInput
        label="Evidence reference"
        value={form.values.evidenceId}
        onChange={(value) => form.setValue('evidenceId', value)}
        {...form.fieldProps('evidenceId', 'Optional. Register it under Evidence & audit first.')}
      />

      <Checkbox
        checked={form.values.declarationAccepted}
        onChange={(checked) => form.setValue('declarationAccepted', checked)}
        label="The driver declares this record to be accurate"
        hint="Required before submission. It can be accepted now or when the journey is completed."
      />

      {incomplete && (
        <Alert variant="info" title="This draft cannot be submitted yet">
          Submission needs an end time, a closing odometer reading and the driver declaration. The
          draft saves without them.
        </Alert>
      )}
    </FormDialog>
  );
};

interface LogbookTransitionDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  logbook: DriverLogbook;
  transition: LogbookTransition;
}

/**
 * The six logbook transitions in one dialog.
 *
 * `POST /logbooks/{id}/{submit|review|return|approve|reopen|cancel}` is a single service endpoint
 * taking a single optional `comment`, so this is one dialog rather than six near-identical ones.
 * What differs per transition is what the service does with the comment and whether it is required:
 * `return`, `reopen` and `cancel` all reach a `require(...)` in the domain record and fail with a
 * 400 without it. `approve`'s comment is genuinely optional and is not asked for as though it were.
 */
export const LogbookTransitionDialog = ({
  open,
  onClose,
  onSaved,
  logbook,
  transition,
}: LogbookTransitionDialogProps) => {
  const rule = LOGBOOK_RULES[transition];
  const mandatory = rule.requiredField !== null;
  const blockers = transition === 'submit' ? logbookSubmissionBlockers(logbook) : [];

  const copy: Record<LogbookTransition, { title: string; submit: string; note?: string }> = {
    submit: {
      title: logbook.status === 'RETURNED' ? 'Resubmit this logbook' : 'Submit this logbook',
      submit: logbook.status === 'RETURNED' ? 'Resubmit' : 'Submit',
      note: `It moves to ${humanise(logbookSubmitTarget(logbook.status)).toLowerCase()} and is no longer editable by the driver.`,
    },
    review: {
      title: 'Start reviewing this logbook',
      submit: 'Start review',
      note: 'It moves to under review, from where it can be approved or returned.',
    },
    return: {
      title: 'Return this logbook to the driver',
      submit: 'Return',
      note: 'Your comment is what the driver sees. It is the only guidance they get.',
    },
    approve: {
      title: 'Approve this logbook',
      submit: 'Approve',
      note: 'An approved logbook is locked. Changing it afterwards needs a privileged reopen.',
    },
    reopen: {
      title: 'Reopen this approved logbook',
      submit: 'Reopen',
      note: 'Privileged - needs FUEL_LOGBOOK_REOPEN. The reason is recorded against the record.',
    },
    cancel: {
      title: 'Cancel this logbook',
      submit: 'Cancel logbook',
      note: 'The record stays in the register and in history. It cannot be revived.',
    },
  };

  const form = useFleetForm({
    initialValues: { comment: '' },
    schema: {
      comment: mandatory
        ? compose(required(commentLabel(transition)), maxLength(commentLabel(transition), 1000))
        : maxLength(commentLabel(transition), 1000),
    },
    onSubmit: async (values) => {
      await driverLogbooksApi.transition(logbook.id, transition, {
        comment: values.comment.trim() || null,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FormDialog
      open={open}
      title={copy[transition].title}
      description={`${logbook.logbookNumber} · ${logbook.origin} → ${logbook.destination}`}
      submitLabel={copy[transition].submit}
      submitting={form.submitting}
      // Blocked outright where the record already fails the service's own precondition: submitting
      // an incomplete draft can only produce "completed journey and driver declaration are required".
      submitDisabled={blockers.length > 0}
      formError={form.formError}
      destructive={transition === 'cancel'}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {blockers.length > 0 && (
        <Alert variant="error" title="The service will refuse this submission">
          <ul className="mt-1 list-disc space-y-1 pl-4">
            {blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
          <p className="mt-2">Complete the draft first, save it, then submit it for review.</p>
        </Alert>
      )}

      {copy[transition].note && <Alert variant="info">{copy[transition].note}</Alert>}

      {(mandatory || transition === 'approve') && (
        <TextAreaInput
          label={commentLabel(transition)}
          required={mandatory}
          rows={3}
          value={form.values.comment}
          onChange={(value) => form.setValue('comment', value)}
          {...form.fieldProps(
            'comment',
            mandatory ? undefined : 'Optional. Recorded as the review comment.',
          )}
        />
      )}
    </FormDialog>
  );
};

/** What the single `comment` field is called for each transition, in the operator's terms. */
const commentLabel = (transition: LogbookTransition): string => {
  switch (transition) {
    case 'return':
      return 'What the driver must correct';
    case 'approve':
      return 'Approval comment';
    case 'reopen':
      return 'Reason for reopening';
    case 'cancel':
      return 'Reason for cancelling';
    default:
      return 'Comment';
  }
};

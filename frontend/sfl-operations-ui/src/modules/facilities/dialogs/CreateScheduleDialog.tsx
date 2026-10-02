import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import type { CreateScheduleRequest } from '../api/dto';
import type { FaultPriority, WorkOrderType } from '../api/enums';
import { faultPriorities } from '../api/enums';
import { searchAssets } from '../api/facilitiesApi';
import { humaniseCode } from '../components/facilitiesFormat';
import { DateField, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface CreateScheduleDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: CreateScheduleRequest) => Promise<void>;
}

/** A schedule cannot raise corrective work - that answers a fault, and nothing is wrong yet. */
const SCHEDULE_TYPES: WorkOrderType[] = ['PREVENTIVE', 'INSPECTION'];

/**
 * Creating a preventive schedule.
 *
 * ## The one rule that catches people
 *
 * The lead time must be **shorter** than the interval. A lead time at or beyond the interval raises
 * the next order before the last one could have been done, so the queue fills with overlapping
 * duplicates forever. The service refuses it and so does the database; this refuses it here too,
 * with the reason, because the failure is not obvious from the two numbers on their own.
 *
 * ## Why the asset is mandatory
 *
 * Closing a preventive work order writes the service date back to its asset. Without one there is
 * nothing to write to, and the order would close having silently done nothing - the estate's overdue
 * count would keep climbing while somebody serviced the machine every quarter.
 */
const CreateScheduleDialog = ({ siteCode, onClose, onSubmit }: CreateScheduleDialogProps) => {
  const [scheduleCode, setScheduleCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [assetId, setAssetId] = useState('');
  const [intervalDays, setIntervalDays] = useState('90');
  const [leadTimeDays, setLeadTimeDays] = useState('7');
  const [priority, setPriority] = useState<FaultPriority>('MEDIUM');
  const [workOrderType, setWorkOrderType] = useState<WorkOrderType>('PREVENTIVE');
  const [firstDueOn, setFirstDueOn] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const assets = useApiQuery(
    (signal) => searchAssets({ siteCode: siteCode || undefined, size: 200 }, signal),
    [siteCode],
  );

  const missingCode = scheduleCode.trim().length === 0;
  const missingName = name.trim().length === 0;
  const missingAsset = !assetId;
  const missingDate = firstDueOn.trim().length === 0;
  const badInterval = intervalDays.trim() === '' || Number(intervalDays) < 1;
  // The rule that catches people: a lead time at or beyond the interval raises the next order
  // before the last could have been done, so the queue fills with overlapping duplicates forever.
  const badLead =
    leadTimeDays.trim() === '' ||
    Number(leadTimeDays) < 0 ||
    (!badInterval && Number(leadTimeDays) >= Number(intervalDays));
  const invalid =
    missingCode || missingName || missingAsset || missingDate || badInterval || badLead;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        siteCode,
        scheduleCode: scheduleCode.trim(),
        name: name.trim(),
        description: description.trim() || null,
        assetId,
        intervalDays: Number(intervalDays),
        leadTimeDays: Number(leadTimeDays),
        priority,
        workOrderType,
        firstDueOn,
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
      title="New preventive schedule"
      description="Raises a work order ahead of each service date, and records the service on closure."
      submitLabel="Create schedule"
      submitting={submitting}
      submitDisabled={touched && invalid}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Schedule code"
            value={scheduleCode}
            onChange={setScheduleCode}
            onBlur={() => setTouched(true)}
            required
            maxLength={80}
            placeholder="e.g. GEN-QUARTERLY"
            error={touched && missingCode}
            helperText={touched && missingCode ? 'A code is required.' : undefined}
          />
          <TextInput
            label="Name"
            value={name}
            onChange={setName}
            onBlur={() => setTouched(true)}
            required
            maxLength={200}
            placeholder="e.g. Generator quarterly service"
            error={touched && missingName}
            helperText={touched && missingName ? 'A name is required.' : undefined}
          />
        </div>

        <SelectInput
          label="Asset"
          value={assetId}
          onChange={(value) => {
            setAssetId(value);
            setTouched(true);
          }}
          required
          allowEmpty
          emptyLabel="Choose the asset this services"
          error={touched && missingAsset}
          options={(assets.data?.items ?? []).map((asset) => ({
            value: asset.id,
            label: `${asset.assetCode} - ${asset.name}`,
          }))}
          helperText={
            touched && missingAsset
              ? 'A schedule services an asset. Without one, closing its work would record nothing.'
              : 'Closing the generated work order sets this asset’s last-serviced date.'
          }
        />

        <TextAreaInput
          label="What the service involves"
          value={description}
          onChange={setDescription}
          rows={2}
          placeholder="e.g. Load test, oil and filter change, battery check."
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <NumberInput
            label="Every (days)"
            value={intervalDays}
            onChange={setIntervalDays}
            onBlur={() => setTouched(true)}
            required
            min={1}
            error={touched && badInterval}
            helperText={touched && badInterval ? 'At least one day.' : 'e.g. 90 for quarterly.'}
          />
          <NumberInput
            label="Raise this many days ahead"
            value={leadTimeDays}
            onChange={setLeadTimeDays}
            onBlur={() => setTouched(true)}
            required
            min={0}
            error={touched && badLead}
            helperText={
              touched && badLead
                ? 'Must be shorter than the interval, or each order is raised before the last could be done.'
                : 'Notice for whoever will attend.'
            }
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput
            label="Priority"
            value={priority}
            onChange={(value) => setPriority(value as FaultPriority)}
            required
            options={faultPriorities.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText="Sets the SLA on every order this raises."
          />
          <SelectInput
            label="Type"
            value={workOrderType}
            onChange={(value) => setWorkOrderType(value as WorkOrderType)}
            required
            options={SCHEDULE_TYPES.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText="Only a preventive order records a service against the asset."
          />
        </div>

        <DateField
          label="First due on"
          value={firstDueOn}
          onChange={setFirstDueOn}
          required
          error={touched && missingDate}
          helperText={
            touched && missingDate
              ? 'A first service date is required.'
              : 'Every later date is worked out from this one and the interval.'
          }
        />

      </div>
    </FormDialog>
  );
};

export default CreateScheduleDialog;

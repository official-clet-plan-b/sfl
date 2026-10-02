import { useState } from 'react';
import { Banner, Button } from '@rfdtech/components';
import Icon from 'shared/components/Icon';
import { vehiclesApi } from 'modules/fleet/api/fleetApi';
import { VehicleResponse } from 'modules/fleet/api/dto';
import {
  DEFECT_SEVERITIES,
  DefectSeverity,
  INSPECTION_TYPES,
  InspectionType,
  humanise,
} from 'modules/fleet/api/enums';
import FleetFormDialog from 'modules/fleet/components/FleetFormDialog';
import {
  EnumSelect,
  NumberInput,
  TextAreaInput,
  TextInput,
} from 'modules/fleet/components/formFields';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, nonNegativeInteger, required } from 'shared/validation/validators';
import { EvidenceSelect } from 'shared/components/EvidenceSelect';
import { searchEvidenceChoices } from 'modules/fleet/api/fleetApi';

/**
 * A standalone periodic inspection - the one that has no trip.
 *
 * Until `POST /vehicles/{id}/inspections` existed, an inspection could only be recorded against a
 * trip, so a vehicle sitting in the yard could not be inspected at all. That blocked the
 * periodic-inspection half of SRS-SFL-S166-01, and it is why this dialog exists as a sibling of the
 * trip one rather than a variant of it: the two are reached from different places, by different
 * people, for different reasons.
 *
 * Everything else is deliberately the same as the trip inspection, because it is the same operation
 * running the same rules through the same service. In particular: **the result is derived, not
 * chosen.** No findings passes, a critical finding fails and takes the vehicle out of service, and
 * anything else passes with defects. The dialog previews that verdict rather than offering it as a
 * field.
 */
interface FindingDraft {
  checkCode: string;
  description: string;
  severity: DefectSeverity;
}

interface RecordStandaloneInspectionDialogProps {
  open: boolean;
  vehicle: VehicleResponse;
  onClose: () => void;
  onSaved: () => void;
}

const twoColumn = 'grid gap-4 sm:grid-cols-2';

export const RecordStandaloneInspectionDialog = ({
  open,
  vehicle,
  onClose,
  onSaved,
}: RecordStandaloneInspectionDialogProps) => {
  const [findings, setFindings] = useState<FindingDraft[]>([]);
  const [findingError, setFindingError] = useState<string | undefined>(undefined);

  const form = useFleetForm({
    initialValues: {
      // PERIODIC is the whole reason this dialog exists; DEFECT_FOLLOW_UP is the other type that
      // legitimately has no trip behind it.
      inspectionType: 'PERIODIC' as InspectionType,
      // A newly registered vehicle legitimately reads 0, which is not the same as "no reading".
      odometerReading: String(vehicle.odometerValue),
      evidenceId: '',
      notes: '',
    },
    schema: {
      inspectionType: required('Inspection type'),
      odometerReading: compose(
        required('Odometer reading'),
        nonNegativeInteger('Odometer reading'),
      ),
      notes: maxLength('Notes', 2000),
    },
    onSubmit: async (values) => {
      await vehiclesApi.recordInspection(vehicle.id, {
        inspectionType: values.inspectionType,
        odometerReading: Number(values.odometerReading),
        evidenceId: values.evidenceId.trim() || null,
        findings: findings.map((finding) => ({
          checkCode: finding.checkCode.trim(),
          description: finding.description.trim(),
          severity: finding.severity,
        })),
        notes: values.notes.trim() || null,
      });
      onSaved();
      onClose();
      form.reset();
      setFindings([]);
    },
  });

  const addFinding = () =>
    setFindings((current) => [...current, { checkCode: '', description: '', severity: 'MINOR' }]);

  const updateFinding = (index: number, patch: Partial<FindingDraft>) =>
    setFindings((current) =>
      current.map((finding, position) => (position === index ? { ...finding, ...patch } : finding)),
    );

  const removeFinding = (index: number) =>
    setFindings((current) => current.filter((_finding, position) => position !== index));

  const incompleteFinding = findings.some(
    (finding) => !finding.checkCode.trim() || !finding.description.trim(),
  );
  const hasCritical = findings.some((finding) => finding.severity === 'CRITICAL');
  const predictedResult =
    findings.length === 0 ? 'PASSED' : hasCritical ? 'FAILED' : 'PASSED_WITH_DEFECTS';

  const submit = () => {
    if (incompleteFinding) {
      setFindingError('Every finding needs a check code and a description.');
      return;
    }
    setFindingError(undefined);
    void form.submit();
  };

  return (
    <FleetFormDialog
      open={open}
      title="Record a periodic inspection"
      description={`${vehicle.registrationNumber}. No trip is involved - the findings decide the result.`}
      submitLabel="Record inspection"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={submit}
    >
      <div className={twoColumn}>
        <EnumSelect
          label="Inspection type"
          required
          value={form.values.inspectionType}
          options={INSPECTION_TYPES}
          onChange={(value) =>
            form.setValue('inspectionType', (value || 'PERIODIC') as InspectionType)
          }
          {...form.fieldProps(
            'inspectionType',
            'PERIODIC and DEFECT_FOLLOW_UP are the types that have no trip behind them.',
          )}
        />
        <NumberInput
          label="Odometer reading (km)"
          required
          value={form.values.odometerReading}
          onChange={(value) => form.setValue('odometerReading', value)}
          {...form.fieldProps(
            'odometerReading',
            'Seeded from the vehicle record; correct it if the dial disagrees.',
          )}
        />
      </div>

      <EvidenceSelect
        label="Evidence"
        search={searchEvidenceChoices}
        relatedRecordType="Vehicle"
        relatedRecordId={vehicle.id}
        value={form.values.evidenceId}
        onChange={(value) => form.setValue('evidenceId', value)}
        {...form.fieldProps('evidenceId', 'Optional. The inspection sheet, if one was filed.')}
      />

      <div className="flex items-center justify-between gap-3">
        <h3 className="text-theme-sm font-semibold text-gray-800">Findings ({findings.length})</h3>
        <Button size="sm" variant="outline" onClick={addFinding}>
          <Icon name="plus" size={14} aria-hidden="true" />
          Add finding
        </Button>
      </div>

      {findings.length === 0 ? (
        <p className="text-theme-sm text-gray-500">
          No findings recorded - this inspection will pass. A periodic check with nothing wrong is a
          real and useful record, so this is a legitimate outcome rather than an empty form.
        </p>
      ) : (
        <div className="space-y-3">
          {findings.map((finding, index) => (
            <div
              key={index}
              className="flex flex-col gap-3 rounded-xl border border-gray-200 p-3 sm:flex-row sm:items-start"
            >
              <TextInput
                label="Check code"
                required
                value={finding.checkCode}
                onChange={(value) => updateFinding(index, { checkCode: value })}
                className="sm:w-40"
              />
              <TextInput
                label="Description"
                required
                value={finding.description}
                onChange={(value) => updateFinding(index, { description: value })}
                className="flex-1"
              />
              <EnumSelect
                label="Severity"
                value={finding.severity}
                options={DEFECT_SEVERITIES}
                onChange={(value) =>
                  updateFinding(index, {
                    severity: (value || 'MINOR') as DefectSeverity,
                  })
                }
                className="sm:w-40"
              />
              {/* Aligned to the controls rather than the labels, which sit above them. */}
              <Button
                variant="outline"
                aria-label="Remove finding"
                title="Remove finding"
                onClick={() => removeFinding(index)}
                className="shrink-0 self-end sm:mt-6 sm:self-auto"
              >
                <Icon name="close" size={16} aria-hidden="true" />
              </Button>
            </div>
          ))}
        </div>
      )}

      {findingError && <Banner variant="danger" heading={<>{findingError}</>} />}

      <TextAreaInput
        label="Notes"
        rows={2}
        value={form.values.notes}
        onChange={(value) => form.setValue('notes', value)}
        {...form.fieldProps('notes')}
      />

      <Banner
        variant={hasCritical ? 'danger' : findings.length > 0 ? 'warning' : 'success'}
        heading={
          <>
            Expected result: <strong>{humanise(predictedResult)}</strong>
            {hasCritical &&
              ' - a critical defect fails the inspection, takes the vehicle out of service and opens a defect workflow item so somebody owns the rectification.'}
          </>
        }
      />
    </FleetFormDialog>
  );
};

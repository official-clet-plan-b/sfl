import { useState } from 'react';
import { Banner, Button } from '@rfdtech/components';
import { VehicleResponse } from 'modules/fleet/api/dto';
import {
  COMPLIANCE_DOCUMENT_TYPES,
  ComplianceDocumentType,
  EVIDENCE_RETENTION_CLASSES,
  EvidenceRetentionClass,
  MANDATORY_COMPLIANCE_DOCUMENT_TYPES,
  OPERATING_MODES,
  SERVICE_OUTCOMES,
  SERVICE_TYPES,
  ServiceOutcome,
  ServiceType,
  VEHICLE_CATEGORIES,
  VEHICLE_LIFECYCLE_STATUSES,
  VehicleCategory,
  VehicleLifecycleStatus,
  humanise,
} from 'modules/fleet/api/enums';
import { vehiclesApi } from 'modules/fleet/api/fleetApi';
import FleetFormDialog from 'modules/fleet/components/FleetFormDialog';
import SiteSelect from 'shared/components/SiteSelect';
import {
  DateField,
  EnumSelect,
  NumberInput,
  TextAreaInput,
  TextInput,
} from 'modules/fleet/components/formFields';
import { todayIsoDate } from 'shared/components/format';
import { useFleetForm } from 'shared/validation/useFleetForm';
import {
  compose,
  maxLength,
  nonNegativeInteger,
  numberBetween,
  odometerNotBelow,
  required,
} from 'shared/validation/validators';
import { EvidenceSelect } from 'shared/components/EvidenceSelect';
import { searchEvidenceChoices } from 'modules/fleet/api/fleetApi';
import FormSummary from 'shared/components/FormSummary';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { ACCEPTED_FILE_DESCRIPTION, evidenceFilesApi } from 'shared/evidence/evidenceFilesApi';
import { FleetApiError } from 'shared/errors/FleetApiError';

interface BaseDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

/** Two columns from `sm` up: these forms are field-dense and read badly as one long stack. */
const twoColumn = 'grid gap-4 sm:grid-cols-2';
const sectionHeading = 'border-t border-gray-200 pt-4 text-theme-sm font-semibold text-brand-900';

/* ---------------------------------------------------------------------------------------------
 * Register a vehicle - POST /api/v1/fleet/vehicles
 * ------------------------------------------------------------------------------------------- */

interface RegisterVehicleDialogProps extends BaseDialogProps {
  defaultSiteCode: string;
}

export const RegisterVehicleDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
}: RegisterVehicleDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      registrationNumber: '',
      vin: '',
      make: '',
      model: '',
      manufactureYear: String(new Date().getFullYear()),
      category: '' as VehicleCategory | '',
      capacity: '5',
      siteCode: defaultSiteCode,
      responsibleUnit: '',
      operationalOwner: '',
      acquisitionReference: '',
      initialOdometer: '0',
      emergencyOnly: 'false',
    },
    schema: {
      registrationNumber: compose(
        required('Registration number'),
        maxLength('Registration number', 40),
      ),
      vin: maxLength('Chassis number', 40),
      make: compose(required('Make'), maxLength('Make', 80)),
      model: compose(required('Model'), maxLength('Model', 80)),
      manufactureYear: compose(
        required('Manufacture year'),
        numberBetween('Manufacture year', 1950, 2100),
      ),
      category: required('Category'),
      capacity: compose(required('Capacity'), numberBetween('Capacity', 1, 200)),
      siteCode: compose(required('Site code'), maxLength('Site code', 40)),
      responsibleUnit: compose(required('Responsible unit'), maxLength('Responsible unit', 160)),
      operationalOwner: compose(required('Operational owner'), maxLength('Operational owner', 160)),
      acquisitionReference: maxLength('Acquisition reference', 120),
      initialOdometer: compose(
        required('Initial odometer'),
        nonNegativeInteger('Initial odometer'),
      ),
    },
    onSubmit: async (values) => {
      await vehiclesApi.register({
        registrationNumber: values.registrationNumber.trim(),
        vin: values.vin.trim() || null,
        make: values.make.trim(),
        model: values.model.trim(),
        manufactureYear: Number(values.manufactureYear),
        category: values.category as VehicleCategory,
        capacity: Number(values.capacity),
        siteCode: values.siteCode.trim().toUpperCase(),
        responsibleUnit: values.responsibleUnit.trim(),
        operationalOwner: values.operationalOwner.trim(),
        acquisitionReference: values.acquisitionReference.trim() || null,
        initialOdometer: Number(values.initialOdometer),
        emergencyOnly: values.emergencyOnly === 'true',
      });
      onSaved();
      onClose();
      form.reset();
    },
  });

  /** Any error on a field that lives inside "More details" - see the note on the element itself. */
  const moreDetailsHasError = (
    ['vin', 'capacity', 'initialOdometer', 'acquisitionReference'] as const
  ).some((field) => Boolean(form.errors[field]));

  return (
    <FleetFormDialog
      open={open}
      title="Register a vehicle"
      description="Adds a vehicle to the register for this site. Registration number must be unique for the site."
      submitLabel="Register vehicle"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      summary={
        <FormSummary
          items={[
            { label: 'Vehicle', value: form.values.registrationNumber },
            {
              label: 'Model',
              value:
                [form.values.make, form.values.model, form.values.manufactureYear]
                  .filter(Boolean)
                  .join(' ') || null,
            },
            { label: 'Site', value: form.values.siteCode },
            { label: 'Owner', value: form.values.operationalOwner },
          ]}
        />
      }
      onClose={onClose}
      onSubmit={form.submit}
    >
      {/*
        Thirteen fields in one grid, ten of them required, is the form this dialog used to be. It is
        now three groups: what the vehicle *is*, who *answers for it*, and the rest.

        Only fields that are optional or carry a defensible default sit behind the disclosure, which
        is the rule that makes hiding a required field safe - capacity defaults to 5 and the odometer
        to 0, so the form submits correctly without it ever being opened. Manufacture year stays
        visible despite having a default, because "this year" is a guess about a real vehicle and a
        wrong year submitted unseen is worse than one more field on the page.
      */}
      <h3 className={sectionHeading}>Identity</h3>
      <div className={twoColumn}>
        <TextInput
          label="Registration number"
          required
          value={form.values.registrationNumber}
          onChange={(value) => form.setValue('registrationNumber', value)}
          {...form.fieldProps('registrationNumber')}
        />
        <EnumSelect
          label="Category"
          required
          value={form.values.category}
          options={VEHICLE_CATEGORIES}
          onChange={(value) => form.setValue('category', value)}
          {...form.fieldProps('category')}
        />
        <TextInput
          label="Make"
          required
          value={form.values.make}
          onChange={(value) => form.setValue('make', value)}
          {...form.fieldProps('make')}
        />
        <TextInput
          label="Model"
          required
          value={form.values.model}
          onChange={(value) => form.setValue('model', value)}
          {...form.fieldProps('model')}
        />
        <NumberInput
          label="Manufacture year"
          required
          min={1950}
          value={form.values.manufactureYear}
          onChange={(value) => form.setValue('manufactureYear', value)}
          {...form.fieldProps('manufactureYear')}
        />
      </div>

      <h3 className={sectionHeading}>Who answers for it</h3>
      <div className={twoColumn}>
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <TextInput
          label="Responsible unit"
          required
          value={form.values.responsibleUnit}
          onChange={(value) => form.setValue('responsibleUnit', value)}
          {...form.fieldProps(
            'responsibleUnit',
            'The unit the vehicle belongs to - Transport, Estates.',
          )}
        />
        <TextInput
          label="Operational owner"
          required
          value={form.values.operationalOwner}
          onChange={(value) => form.setValue('operationalOwner', value)}
          {...form.fieldProps(
            'operationalOwner',
            'The named person accountable for it day to day.',
          )}
        />
      </div>

      {/*
        Forced open when anything inside it is in error. A required field failing validation while
        hidden is the one failure mode progressive disclosure introduces: the operator sees "fix the
        errors" and no error anywhere on screen.
      */}
      <details className="rounded-lg border border-gray-200 px-4 py-3" open={moreDetailsHasError}>
        <summary className="cursor-pointer text-theme-sm font-medium text-gray-800 select-none">
          More details
          <span className="ml-1 font-normal text-gray-500">
            - chassis number, capacity, opening odometer, acquisition, emergency use
          </span>
        </summary>
        <div className={`mt-4 ${twoColumn}`}>
          {/*
            Labelled for the person filling it in, not for the column behind it.

            The field is stored as `vin` and always accepted either - `VehicleIdentificationNumber`
            says so in as many words - but "VIN" is the North American term and the number stamped on
            a vehicle here is called its chassis number. An operator holding a registration document
            that says "chassis" should not have to guess that the two are the same field.
          */}
          <TextInput
            label="Chassis number"
            value={form.values.vin}
            onChange={(value) => form.setValue('vin', value)}
            {...form.fieldProps(
              'vin',
              'The VIN or chassis number stamped on the vehicle. Optional.',
            )}
          />
          <NumberInput
            label="Capacity"
            required
            min={1}
            value={form.values.capacity}
            onChange={(value) => form.setValue('capacity', value)}
            {...form.fieldProps('capacity', 'Seats, including the driver.')}
          />
          <NumberInput
            label="Initial odometer"
            required
            suffix="km"
            value={form.values.initialOdometer}
            onChange={(value) => form.setValue('initialOdometer', value)}
            {...form.fieldProps('initialOdometer', 'The reading on the day it joins the register.')}
          />
          <TextInput
            label="Acquisition reference"
            value={form.values.acquisitionReference}
            onChange={(value) => form.setValue('acquisitionReference', value)}
            {...form.fieldProps('acquisitionReference')}
          />
          <EnumSelect
            label="Emergency use only"
            value={form.values.emergencyOnly}
            options={['false', 'true'] as const}
            onChange={(value) => form.setValue('emergencyOnly', value || 'false')}
            renderOptionLabel={(option) => (option === 'true' ? 'Yes' : 'No')}
          />
        </div>
      </details>
    </FleetFormDialog>
  );
};

/* ---------------------------------------------------------------------------------------------
 * Lifecycle transition - PATCH /api/v1/fleet/vehicles/{id}/lifecycle
 * ------------------------------------------------------------------------------------------- */

interface LifecycleDialogProps extends BaseDialogProps {
  vehicle: VehicleResponse;
}

export const ChangeVehicleLifecycleDialog = ({
  open,
  onClose,
  onSaved,
  vehicle,
}: LifecycleDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      targetStatus: '' as VehicleLifecycleStatus | '',
      reason: '',
    },
    schema: {
      targetStatus: required('Target status'),
      reason: compose(required('Reason'), maxLength('Reason', 1000)),
    },
    onSubmit: async (values) => {
      await vehiclesApi.changeLifecycle(vehicle.id, {
        targetStatus: values.targetStatus as VehicleLifecycleStatus,
        reason: values.reason.trim(),
        expectedVersion: vehicle.version,
      });
      onSaved();
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Change vehicle lifecycle status"
      description={`${vehicle.registrationNumber} is currently ${humanise(vehicle.lifecycleStatus)}. The service rejects transitions that are not permitted from this status.`}
      submitLabel="Apply transition"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      <EnumSelect
        label="Target status"
        required
        value={form.values.targetStatus}
        options={VEHICLE_LIFECYCLE_STATUSES.filter((status) => status !== vehicle.lifecycleStatus)}
        onChange={(value) => form.setValue('targetStatus', value)}
        {...form.fieldProps('targetStatus')}
      />
      <TextAreaInput
        label="Reason"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
    </FleetFormDialog>
  );
};

/* ---------------------------------------------------------------------------------------------
 * Compliance document - POST /api/v1/fleet/vehicles/{id}/compliance-documents
 * ------------------------------------------------------------------------------------------- */

interface ComplianceDialogProps extends BaseDialogProps {
  vehicleId: string;
  /** The vehicle's own site. Evidence is filed against a site, and it is not the operator's choice. */
  siteCode: string;
}

/**
 * Register a compliance document by uploading it.
 *
 * <h2>The document is now the point</h2>
 *
 * <p>This form used to record that a certificate existed: a type, a reference number, two dates and
 * an optional pointer at evidence somebody was expected to have registered elsewhere. Nothing made
 * anyone attach the certificate, and in practice nobody did - so "the fleet's roadworthiness
 * position" was a table of numbers typed from documents nobody could produce. A compliance register
 * that cannot show the certificate is an honour system with extra steps.
 *
 * <p>The file is therefore required, uploaded before the document is registered, and the id it
 * returns is what the record points at. Two calls rather than one, in this order deliberately: if the
 * upload is refused the operator is told why while still holding the form, and no compliance record
 * exists claiming a document that was never accepted. The reverse order would leave exactly that.
 *
 * <p>Evidence already filed against the vehicle is still selectable, because one PDF genuinely can
 * cover two records - a single insurance certificate listing several vehicles is the ordinary case.
 */
export const RegisterComplianceDocumentDialog = ({
  open,
  onClose,
  onSaved,
  vehicleId,
  siteCode,
}: ComplianceDialogProps) => {
  const [file, setFile] = useState<File | null>(null);
  const [useExisting, setUseExisting] = useState(false);

  const form = useFleetForm({
    initialValues: {
      documentType: '' as ComplianceDocumentType | '',
      documentReference: '',
      issuingAuthority: '',
      issuedOn: todayIsoDate(),
      expiresOn: '',
      evidenceId: '',
      retentionClass: 'COMPLIANCE_7_YEARS' as EvidenceRetentionClass,
    },
    schema: {
      documentType: required('Document type'),
      documentReference: compose(
        required('Document reference'),
        maxLength('Document reference', 160),
      ),
      issuingAuthority: compose(required('Issuing authority'), maxLength('Issuing authority', 160)),
      issuedOn: required('Issued on'),
      expiresOn: required('Expires on'),
      retentionClass: required('Retention class'),
    },
    crossFieldValidate: (values) => {
      const errors: Record<string, string> = {};
      if (values.issuedOn && values.expiresOn && values.expiresOn <= values.issuedOn) {
        errors.expiresOn = 'Expiry must be after the issue date.';
      }
      if (useExisting && !values.evidenceId.trim()) {
        errors.evidenceId = 'Choose the evidence this document is filed under.';
      }
      return errors;
    },
    onSubmit: async (values) => {
      if (!useExisting && !file) {
        throw FleetApiError.validation('Attach the document itself before registering it.');
      }
      const evidenceId = useExisting
        ? values.evidenceId.trim()
        : (
            await evidenceFilesApi.upload({
              siteCode,
              relatedRecordType: 'Vehicle',
              relatedRecordId: vehicleId,
              // The document type doubles as the evidence type, so a vehicle's evidence list reads
              // "ROADWORTHINESS_CERTIFICATE" rather than a generic "COMPLIANCE_DOCUMENT" repeated
              // five times - which is the difference between a list and a useful one.
              evidenceType: values.documentType as ComplianceDocumentType,
              retentionClass: values.retentionClass,
              file: file as File,
            })
          ).id;

      await vehiclesApi.registerComplianceDocument(vehicleId, {
        documentType: values.documentType as ComplianceDocumentType,
        documentReference: values.documentReference.trim(),
        issuingAuthority: values.issuingAuthority.trim(),
        issuedOn: values.issuedOn,
        expiresOn: values.expiresOn,
        evidenceId,
        retentionClass: values.retentionClass,
      });
      onSaved();
      onClose();
      setFile(null);
      form.reset();
    },
  });

  const isMandatory =
    form.values.documentType !== '' &&
    MANDATORY_COMPLIANCE_DOCUMENT_TYPES.includes(form.values.documentType);

  return (
    <FleetFormDialog
      open={open}
      title="Register a compliance document"
      description="A retention class is mandatory on every fleet compliance record."
      submitLabel="Register document"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <EnumSelect
          label="Document type"
          required
          value={form.values.documentType}
          options={COMPLIANCE_DOCUMENT_TYPES}
          onChange={(value) => form.setValue('documentType', value)}
          {...form.fieldProps('documentType')}
        />
        <TextInput
          label="Document reference"
          required
          value={form.values.documentReference}
          onChange={(value) => form.setValue('documentReference', value)}
          {...form.fieldProps('documentReference')}
        />
        <TextInput
          label="Issuing authority"
          required
          value={form.values.issuingAuthority}
          onChange={(value) => form.setValue('issuingAuthority', value)}
          {...form.fieldProps('issuingAuthority')}
        />
        <EnumSelect
          label="Retention class"
          required
          value={form.values.retentionClass}
          options={EVIDENCE_RETENTION_CLASSES}
          onChange={(value) =>
            form.setValue(
              'retentionClass',
              (value || 'COMPLIANCE_7_YEARS') as EvidenceRetentionClass,
            )
          }
          {...form.fieldProps('retentionClass')}
        />
        <DateField
          label="Issued on"
          required
          value={form.values.issuedOn}
          onChange={(value) => form.setValue('issuedOn', value)}
          {...form.fieldProps('issuedOn')}
        />
        <DateField
          label="Expires on"
          required
          value={form.values.expiresOn}
          onChange={(value) => form.setValue('expiresOn', value)}
          {...form.fieldProps('expiresOn')}
        />
      </div>

      {useExisting ? (
        <div>
          <EvidenceSelect
            label="Evidence"
            required
            search={searchEvidenceChoices}
            relatedRecordType="Vehicle"
            relatedRecordId={vehicleId}
            value={form.values.evidenceId}
            onChange={(value) => form.setValue('evidenceId', value)}
            {...form.fieldProps(
              'evidenceId',
              'A document already filed against this vehicle - a multi-vehicle certificate, for example.',
            )}
          />
          <Button
            size="sm"
            variant="ghost"
            className="mt-1.5"
            onClick={() => {
              setUseExisting(false);
              form.setValue('evidenceId', '');
            }}
          >
            Upload a new document instead
          </Button>
        </div>
      ) : (
        <div>
          <EvidenceFileField
            label="The document"
            required
            value={file}
            onChange={setFile}
            helperText={`Scan or photograph of the certificate itself. ${ACCEPTED_FILE_DESCRIPTION}.`}
          />
          <Button size="sm" variant="ghost" className="mt-1.5" onClick={() => setUseExisting(true)}>
            Use a document already filed against this vehicle
          </Button>
        </div>
      )}

      {isMandatory && (
        <Banner
          variant="info"
          heading={
            <>
              {humanise(form.values.documentType)} is a mandatory document - while it is missing or
              expired the vehicle carries a blocking readiness blocker.
            </>
          }
        />
      )}
    </FleetFormDialog>
  );
};

/* ---------------------------------------------------------------------------------------------
 * Service record - POST /api/v1/fleet/vehicles/{id}/service-records
 * ------------------------------------------------------------------------------------------- */

interface ServiceDialogProps extends BaseDialogProps {
  vehicleId: string;
  currentOdometer: number;
}

export const RecordServiceDialog = ({
  open,
  onClose,
  onSaved,
  vehicleId,
  currentOdometer,
}: ServiceDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      serviceType: '' as ServiceType | '',
      performedOn: todayIsoDate(),
      odometerAtService: String(currentOdometer),
      nextDueOn: '',
      nextDueOdometer: '',
      providerReference: '',
      workSummary: '',
      outcome: '' as ServiceOutcome | '',
      evidenceId: '',
    },
    schema: {
      serviceType: required('Service type'),
      performedOn: required('Performed on'),
      odometerAtService: compose(
        required('Odometer at service'),
        nonNegativeInteger('Odometer at service'),
      ),
      nextDueOdometer: nonNegativeInteger('Next due odometer'),
      providerReference: maxLength('Provider reference', 160),
      workSummary: compose(required('Work summary'), maxLength('Work summary', 2000)),
      outcome: required('Outcome'),
    },
    crossFieldValidate: (values) => {
      const errors: { odometerAtService?: string; nextDueOn?: string } = {};
      // The service refuses a regressed reading outright (FLEET_ODOMETER_REGRESSION), so this is a
      // validation failure rather than an advisory the operator can submit past.
      const regression = odometerNotBelow(values.odometerAtService, currentOdometer);
      if (regression) {
        errors.odometerAtService = regression;
      }
      // Both are date-only strings, so a lexicographic comparison orders them correctly.
      if (values.nextDueOn && values.performedOn && values.nextDueOn <= values.performedOn) {
        errors.nextDueOn = 'The next service must fall after the date this one was performed.';
      }
      return errors;
    },
    onSubmit: async (values) => {
      await vehiclesApi.recordService(vehicleId, {
        serviceType: values.serviceType as ServiceType,
        performedOn: values.performedOn,
        odometerAtService: Number(values.odometerAtService),
        nextDueOn: values.nextDueOn || null,
        nextDueOdometer: values.nextDueOdometer ? Number(values.nextDueOdometer) : null,
        providerReference: values.providerReference.trim() || null,
        workSummary: values.workSummary.trim(),
        outcome: values.outcome as ServiceOutcome,
        evidenceId: values.evidenceId.trim() || null,
      });
      onSaved();
      onClose();
      form.reset();
    },
  });

  const regression = odometerNotBelow(form.values.odometerAtService, currentOdometer);

  return (
    <FleetFormDialog
      open={open}
      title="Record a service event"
      description="Service history drives the vehicle's service status and its readiness blockers."
      submitLabel="Record service"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <EnumSelect
          label="Service type"
          required
          value={form.values.serviceType}
          options={SERVICE_TYPES}
          onChange={(value) => form.setValue('serviceType', value)}
          {...form.fieldProps('serviceType')}
        />
        <EnumSelect
          label="Outcome"
          required
          value={form.values.outcome}
          options={SERVICE_OUTCOMES}
          onChange={(value) => form.setValue('outcome', value)}
          {...form.fieldProps('outcome')}
        />
        <DateField
          label="Performed on"
          required
          value={form.values.performedOn}
          onChange={(value) => form.setValue('performedOn', value)}
          {...form.fieldProps('performedOn')}
        />
        <NumberInput
          label="Odometer at service"
          required
          suffix="km"
          value={form.values.odometerAtService}
          onChange={(value) => form.setValue('odometerAtService', value)}
          {...form.fieldProps('odometerAtService')}
        />
        <DateField
          label="Next due on"
          value={form.values.nextDueOn}
          onChange={(value) => form.setValue('nextDueOn', value)}
          {...form.fieldProps('nextDueOn')}
        />
        <NumberInput
          label="Next due odometer"
          suffix="km"
          value={form.values.nextDueOdometer}
          onChange={(value) => form.setValue('nextDueOdometer', value)}
          {...form.fieldProps('nextDueOdometer')}
        />
        <TextInput
          label="Provider reference"
          value={form.values.providerReference}
          onChange={(value) => form.setValue('providerReference', value)}
          {...form.fieldProps('providerReference')}
        />
        <EvidenceSelect
          label="Evidence"
          search={searchEvidenceChoices}
          relatedRecordType="Vehicle"
          relatedRecordId={vehicleId}
          value={form.values.evidenceId}
          onChange={(value) => form.setValue('evidenceId', value)}
          {...form.fieldProps('evidenceId', 'Optional. The job card or invoice.')}
        />
      </div>
      <TextAreaInput
        label="Work summary"
        required
        rows={3}
        value={form.values.workSummary}
        onChange={(value) => form.setValue('workSummary', value)}
        {...form.fieldProps('workSummary')}
      />
      {regression && (
        <Banner
          variant="warning"
          heading={
            <>
              {regression} The service will reject this with FLEET_ODOMETER_REGRESSION. Use an
              authorised odometer correction instead.
            </>
          }
        />
      )}
    </FleetFormDialog>
  );
};

/* ---------------------------------------------------------------------------------------------
 * Odometer correction - POST /api/v1/fleet/vehicles/{id}/odometer-corrections
 * ------------------------------------------------------------------------------------------- */

interface OdometerDialogProps extends BaseDialogProps {
  vehicle: VehicleResponse;
}

export const CorrectOdometerDialog = ({ open, onClose, onSaved, vehicle }: OdometerDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      correctedReading: String(vehicle.odometerValue),
      reason: '',
      evidenceId: '',
    },
    schema: {
      correctedReading: compose(
        required('Corrected reading'),
        nonNegativeInteger('Corrected reading'),
      ),
      reason: compose(required('Reason'), maxLength('Reason', 1000)),
      evidenceId: required('Evidence reference ID'),
    },
    onSubmit: async (values) => {
      await vehiclesApi.correctOdometer(vehicle.id, {
        correctedReading: Number(values.correctedReading),
        reason: values.reason.trim(),
        evidenceId: values.evidenceId.trim(),
        expectedVersion: vehicle.version,
      });
      onSaved();
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Correct the odometer"
      description="The one operation allowed to move a reading backwards. Reason and evidence are both mandatory."
      submitLabel="Apply correction"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      {/* The reading being replaced, with its provenance: this overwrites a recorded fact. */}
      <div>
        <p className="text-theme-xs text-gray-500">Current reading</p>
        <p className="mt-0.5 text-theme-sm font-semibold text-gray-800">
          {vehicle.odometerValue.toLocaleString()} km · source {humanise(vehicle.odometerSource)}
        </p>
      </div>
      <NumberInput
        label="Corrected reading"
        required
        suffix="km"
        value={form.values.correctedReading}
        onChange={(value) => form.setValue('correctedReading', value)}
        {...form.fieldProps('correctedReading')}
      />
      <TextAreaInput
        label="Reason"
        required
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
      <EvidenceSelect
        label="Evidence"
        required
        search={searchEvidenceChoices}
        relatedRecordType="Vehicle"
        relatedRecordId={vehicle.id}
        value={form.values.evidenceId}
        onChange={(value) => form.setValue('evidenceId', value)}
        {...form.fieldProps(
          'evidenceId',
          'What shows the true reading - a photograph of the dial, or the service record that corrected it.',
        )}
      />
    </FleetFormDialog>
  );
};

/* ---------------------------------------------------------------------------------------------
 * Edit vehicle - PATCH /api/v1/fleet/vehicles/{id}
 * ------------------------------------------------------------------------------------------- */

interface EditVehicleDialogProps extends BaseDialogProps {
  vehicle: VehicleResponse;
}

export const EditVehicleDialog = ({ open, onClose, onSaved, vehicle }: EditVehicleDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      vin: vehicle.vinMasked ? '' : (vehicle.vin ?? ''),
      make: vehicle.make,
      model: vehicle.model,
      manufactureYear: String(vehicle.manufactureYear),
      category: vehicle.category as VehicleCategory | '',
      capacity: String(vehicle.capacity),
      responsibleUnit: vehicle.responsibleUnit,
      operationalOwner: vehicle.operationalOwner,
      acquisitionReference: vehicle.acquisitionReference ?? '',
      emergencyOnly: vehicle.emergencyOnly ? 'true' : 'false',
    },
    schema: {
      vin: maxLength('VIN', 40),
      make: compose(required('Make'), maxLength('Make', 80)),
      model: compose(required('Model'), maxLength('Model', 80)),
      manufactureYear: compose(
        required('Manufacture year'),
        numberBetween('Manufacture year', 1950, 2100),
      ),
      category: required('Category'),
      capacity: compose(required('Capacity'), numberBetween('Capacity', 1, 200)),
      responsibleUnit: compose(required('Responsible unit'), maxLength('Responsible unit', 160)),
      operationalOwner: compose(required('Operational owner'), maxLength('Operational owner', 160)),
      acquisitionReference: maxLength('Acquisition reference', 120),
    },
    onSubmit: async (values) => {
      await vehiclesApi.update(vehicle.id, {
        vin: values.vin.trim() || null,
        make: values.make.trim(),
        model: values.model.trim(),
        manufactureYear: Number(values.manufactureYear),
        category: values.category as VehicleCategory,
        capacity: Number(values.capacity),
        responsibleUnit: values.responsibleUnit.trim(),
        operationalOwner: values.operationalOwner.trim(),
        acquisitionReference: values.acquisitionReference.trim() || null,
        emergencyOnly: values.emergencyOnly === 'true',
        expectedVersion: vehicle.version,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title={`Edit ${vehicle.registrationNumber}`}
      description="Submitting sends the version you loaded, so a concurrent edit is refused rather than silently overwritten."
      submitLabel="Save changes"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={form.submit}
    >
      {vehicle.vinMasked && (
        <Banner
          variant="warning"
          heading={
            <>
              The VIN is masked for your role. Leaving this field blank clears the stored VIN - only
              fill it in if you hold the real value.
            </>
          }
        />
      )}
      <div className={twoColumn}>
        <TextInput
          label="VIN"
          value={form.values.vin}
          onChange={(value) => form.setValue('vin', value)}
          {...form.fieldProps('vin')}
        />
        <EnumSelect
          label="Category"
          required
          value={form.values.category}
          options={VEHICLE_CATEGORIES}
          onChange={(value) => form.setValue('category', value)}
          {...form.fieldProps('category')}
        />
        <TextInput
          label="Make"
          required
          value={form.values.make}
          onChange={(value) => form.setValue('make', value)}
          {...form.fieldProps('make')}
        />
        <TextInput
          label="Model"
          required
          value={form.values.model}
          onChange={(value) => form.setValue('model', value)}
          {...form.fieldProps('model')}
        />
        <NumberInput
          label="Manufacture year"
          required
          min={1950}
          value={form.values.manufactureYear}
          onChange={(value) => form.setValue('manufactureYear', value)}
          {...form.fieldProps('manufactureYear')}
        />
        <NumberInput
          label="Capacity"
          required
          min={1}
          value={form.values.capacity}
          onChange={(value) => form.setValue('capacity', value)}
          {...form.fieldProps('capacity')}
        />
        <TextInput
          label="Responsible unit"
          required
          value={form.values.responsibleUnit}
          onChange={(value) => form.setValue('responsibleUnit', value)}
          {...form.fieldProps('responsibleUnit')}
        />
        <TextInput
          label="Operational owner"
          required
          value={form.values.operationalOwner}
          onChange={(value) => form.setValue('operationalOwner', value)}
          {...form.fieldProps('operationalOwner')}
        />
        <TextInput
          label="Acquisition reference"
          value={form.values.acquisitionReference}
          onChange={(value) => form.setValue('acquisitionReference', value)}
          {...form.fieldProps('acquisitionReference')}
        />
        <EnumSelect
          label="Emergency use only"
          value={form.values.emergencyOnly}
          options={['false', 'true'] as const}
          onChange={(value) => form.setValue('emergencyOnly', value || 'false')}
          renderOptionLabel={(option) => (option === 'true' ? 'Yes' : 'No')}
        />
      </div>
      <p className="text-theme-xs text-gray-500">
        Allowed operating modes are managed by the service; the current set is{' '}
        {(vehicle.allowedOperatingModes ?? OPERATING_MODES).map(humanise).join(', ')}.
      </p>
    </FleetFormDialog>
  );
};

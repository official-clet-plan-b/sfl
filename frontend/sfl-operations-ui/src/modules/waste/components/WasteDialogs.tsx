import { useState } from 'react';
import { Checkbox, DateField, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { todayIsoDate } from 'shared/components/format';
import { useSubmit } from 'shared/hooks/useSubmit';
import {
  wasteApi,
  type Configuration,
  type DestinationType,
  type EvidenceKind,
  type ExceptionType,
  type QuantityBasis,
  type WasteCategory,
  type WasteCollection,
  type WasteDestination,
} from '../api/wasteApi';
import { categories, destinationTypes, evidenceKinds, exceptionTypes, options } from './wasteUi';

interface Closeable {
  onClose: () => void;
  onDone: () => void;
}

const basisOptions = options(['MEASURED', 'ESTIMATED']);

export const ScheduleDialog = ({ siteCode, config, onClose, onDone }: Closeable & { siteCode: string; config: Configuration }) => {
  const [values, setValues] = useState({ streamId: '', pointId: '', carrierId: '', scheduledFor: todayIsoDate() });
  const { submitting, error, run } = useSubmit();
  const set = (key: keyof typeof values) => (value: string) => setValues((current) => ({ ...current, [key]: value }));
  return (
    <FormDialog open title="Schedule a collection" description={`At ${siteCode}. The carrier must be approved and licensed - for hazardous waste, approved for it.`}
      submitLabel="Schedule collection" submitting={submitting} submitDisabled={!values.streamId || !values.pointId || !values.carrierId || !values.scheduledFor}
      formError={error} onClose={onClose} onSubmit={() => void run(() => wasteApi.schedule({ siteCode, ...values }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectInput label="Waste stream" required value={values.streamId} onChange={set('streamId')}
          options={config.streams.filter((s) => s.active).map((s) => ({ value: s.id, label: `${s.name}${s.hazardous ? ' (hazardous)' : ''}` }))} />
        <SelectInput label="Collection point" required value={values.pointId} onChange={set('pointId')}
          options={config.points.filter((p) => p.active).map((p) => ({ value: p.id, label: p.name }))} />
        <SelectInput label="Carrier" required value={values.carrierId} onChange={set('carrierId')}
          options={config.carriers.filter((c) => c.status === 'APPROVED').map((c) => ({ value: c.id, label: c.name }))} />
        <DateField label="Scheduled for" required value={values.scheduledFor} onChange={set('scheduledFor')} />
      </div>
    </FormDialog>
  );
};

export const RecordDialog = ({ collection, units, onClose, onDone }: Closeable & { collection: WasteCollection; units: Configuration['units'] }) => {
  const [values, setValues] = useState({ collectedOn: todayIsoDate(), quantity: '', unit: 'KG', basis: 'MEASURED' as QuantityBasis | '', manifestReference: '' });
  const { submitting, error, run } = useSubmit();
  const set = (key: keyof typeof values) => (value: string) => setValues((current) => ({ ...current, [key]: value }));
  const invalid = !values.quantity || Number(values.quantity) <= 0 || !values.unit || !values.basis || (collection.hazardous && !values.manifestReference.trim());
  return (
    <FormDialog open title="Record the collection" description={`${collection.reference}. Say whether the quantity was weighed or estimated - an estimate is kept apart from measured data in every report.`}
      submitLabel="Record collection" submitting={submitting} submitDisabled={invalid} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.record(collection, {
        collectedOn: values.collectedOn || undefined, quantity: Number(values.quantity), unit: values.unit,
        basis: values.basis as QuantityBasis, manifestReference: values.manifestReference.trim() || undefined,
      }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <DateField label="Collected on" required value={values.collectedOn} onChange={set('collectedOn')} />
        <SelectInput label="Quantity is" required value={values.basis} onChange={set('basis')} options={basisOptions} />
        <NumberInput label="Quantity" required value={values.quantity} onChange={set('quantity')} />
        <SelectInput label="Unit" required value={values.unit} onChange={set('unit')} options={units.map((u) => ({ value: u.code, label: `${u.name} (${u.code})` }))} />
        {collection.hazardous && (
          <TextInput label="Manifest reference" required value={values.manifestReference} onChange={set('manifestReference')} maxLength={120} className="sm:col-span-2"
            helperText="Hazardous waste travels with its manifest." />
        )}
      </div>
    </FormDialog>
  );
};

export const ReconcileDialog = ({ collection, units, onClose, onDone }: Closeable & { collection: WasteCollection; units: Configuration['units'] }) => {
  const [values, setValues] = useState({ quantity: '', unit: collection.unit ?? 'KG', basis: 'MEASURED' as QuantityBasis | '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Reconcile the quantity" description={`Restate ${collection.reference} after contamination or a correction. The original entry stays in the audit trail.`}
      submitLabel="Reconcile" submitting={submitting} submitDisabled={!values.quantity || Number(values.quantity) <= 0 || !values.basis} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.reconcile(collection, { quantity: Number(values.quantity), unit: values.unit, basis: values.basis as QuantityBasis }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <NumberInput label="Corrected quantity" required value={values.quantity} onChange={(v) => setValues((c) => ({ ...c, quantity: v }))} />
        <SelectInput label="Unit" required value={values.unit} onChange={(v) => setValues((c) => ({ ...c, unit: v }))} options={units.map((u) => ({ value: u.code, label: u.code }))} />
        <SelectInput label="Is" required value={values.basis} onChange={(v) => setValues((c) => ({ ...c, basis: v as QuantityBasis }))} options={basisOptions} />
      </div>
    </FormDialog>
  );
};

export const HandOverDialog = ({ collection, destinations, onClose, onDone }: Closeable & { collection: WasteCollection; destinations: WasteDestination[] }) => {
  const [destinationId, setDestinationId] = useState('');
  const [location, setLocation] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Hand over to the carrier" description={`${collection.reference}. A destination that is suspended, past its permit, or not accepting hazardous waste blocks the handover and raises an exception.`}
      submitLabel="Hand over" submitting={submitting} submitDisabled={!destinationId} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.handOver(collection, destinationId, location.trim() || undefined), onDone)}>
      <SelectInput label="Destination" required value={destinationId} onChange={setDestinationId}
        options={destinations.map((d) => ({ value: d.id, label: `${d.name} - ${d.destinationType.toLowerCase()}${d.status === 'SUSPENDED' ? ' (suspended)' : ''}` }))} />
      <TextInput label="Handover location" value={location} onChange={setLocation} maxLength={160} />
    </FormDialog>
  );
};

const sha256 = async (file: File): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
};

/** Evidence is held by reference: pick the file to have its name, type, size and SHA-256 worked out here, and name where it is filed. */
export const EvidenceDialog = ({ collectionId, reference, initialKind, onClose, onDone }: Closeable & { collectionId: string; reference: string; initialKind?: EvidenceKind }) => {
  const [kind, setKind] = useState<EvidenceKind | ''>(initialKind ?? 'RECEIVING');
  const [file, setFile] = useState<File | null>(null);
  const [filed, setFiled] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Submit evidence" description={`For ${reference}. A different person with the verification grant must accept it.`}
      submitLabel="Submit evidence" submitting={submitting} submitDisabled={!kind || !file || !filed.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(async () => {
        if (!file || !kind) return;
        await wasteApi.submitEvidence(collectionId, {
          kind, reference: filed.trim(), fileName: file.name, mediaType: file.type || 'application/octet-stream',
          sizeBytes: file.size, contentHash: await sha256(file),
        });
      }, onDone)}>
      <SelectInput label="What it proves" required value={kind} onChange={(v) => setKind(v as EvidenceKind)} options={options(evidenceKinds)} />
      <EvidenceFileField label="File" value={file} onChange={setFile} required />
      <TextInput label="Records-store reference" required value={filed} onChange={setFiled} maxLength={240} helperText="Where the file is filed." />
    </FormDialog>
  );
};

export const ExceptionDialog = ({ siteCode, collectionId, onClose, onDone }: Closeable & { siteCode: string; collectionId?: string }) => {
  const [values, setValues] = useState({ exceptionType: 'SPILL' as ExceptionType | '', description: '', ownerReference: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Report an exception" description="A spill also asks Incident Reporting for an incident and raises a maintenance work order."
      submitLabel="Report exception" submitting={submitting} submitDisabled={!values.exceptionType || !values.description.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.reportException({ siteCode, collectionId, exceptionType: values.exceptionType as ExceptionType, description: values.description.trim(), ownerReference: values.ownerReference.trim() || undefined }), onDone)}>
      <SelectInput label="Type" required value={values.exceptionType} onChange={(v) => setValues((c) => ({ ...c, exceptionType: v as ExceptionType }))} options={options(exceptionTypes)} />
      <TextAreaInput label="What happened" required value={values.description} onChange={(v) => setValues((c) => ({ ...c, description: v }))} maxLength={2000} />
      <TextInput label="Owner" value={values.ownerReference} onChange={(v) => setValues((c) => ({ ...c, ownerReference: v }))} maxLength={160} />
    </FormDialog>
  );
};

// ---- setup

export const StreamDialog = ({ onClose, onDone }: Closeable) => {
  const [values, setValues] = useState({ code: '', name: '', category: 'GENERAL' as WasteCategory | '', hazardous: false, diverted: false, handlingRules: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a waste stream" description="Streams are organisation-wide. A hazardous stream cannot be counted as diverted." submitLabel="Add stream"
      submitting={submitting} submitDisabled={!values.code.trim() || !values.name.trim() || !values.category || (values.hazardous && values.diverted)} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.createStream({ code: values.code.trim(), name: values.name.trim(), category: values.category as WasteCategory, hazardous: values.hazardous, diverted: values.diverted, handlingRules: values.handlingRules.trim() || undefined }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <SelectInput label="Category" required value={values.category} onChange={(v) => setValues((c) => ({ ...c, category: v as WasteCategory }))} options={options(categories)} />
        <div className="space-y-2 self-end pb-2">
          <Checkbox checked={values.hazardous} onChange={(v) => setValues((c) => ({ ...c, hazardous: v }))} label="Hazardous or controlled" />
          <Checkbox checked={values.diverted} onChange={(v) => setValues((c) => ({ ...c, diverted: v }))} label="Counts towards diversion" hint="Recycled, reused or composted." />
        </div>
        <TextAreaInput label="Handling rules" value={values.handlingRules} onChange={(v) => setValues((c) => ({ ...c, handlingRules: v }))} maxLength={2000} className="sm:col-span-2" />
      </div>
    </FormDialog>
  );
};

export const PointDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const [values, setValues] = useState({ code: '', name: '', containerDescription: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a collection point" description={`At ${siteCode}.`} submitLabel="Add point" submitting={submitting}
      submitDisabled={!values.code.trim() || !values.name.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.createPoint({ siteCode, code: values.code.trim(), name: values.name.trim(), containerDescription: values.containerDescription.trim() || undefined }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <TextInput label="Containers" value={values.containerDescription} onChange={(v) => setValues((c) => ({ ...c, containerDescription: v }))} maxLength={240} className="sm:col-span-2" />
      </div>
    </FormDialog>
  );
};

export const CarrierDialog = ({ onClose, onDone }: Closeable) => {
  const [values, setValues] = useState({ code: '', name: '', licenceReference: '', licenceExpiresOn: '', hazardousApproved: false });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add an approved carrier" description="A carrier is checked when waste is scheduled and again when it is handed over." submitLabel="Add carrier" submitting={submitting}
      submitDisabled={!values.code.trim() || !values.name.trim() || !values.licenceReference.trim() || !values.licenceExpiresOn} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.createCarrier({ ...values, code: values.code.trim(), name: values.name.trim(), licenceReference: values.licenceReference.trim() }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <TextInput label="Licence reference" required value={values.licenceReference} onChange={(v) => setValues((c) => ({ ...c, licenceReference: v }))} maxLength={120} />
        <DateField label="Licence expires on" required value={values.licenceExpiresOn} onChange={(v) => setValues((c) => ({ ...c, licenceExpiresOn: v }))} />
        <Checkbox checked={values.hazardousApproved} onChange={(v) => setValues((c) => ({ ...c, hazardousApproved: v }))} label="Approved for hazardous waste" />
      </div>
    </FormDialog>
  );
};

export const DestinationDialog = ({ onClose, onDone }: Closeable) => {
  const [values, setValues] = useState({ code: '', name: '', destinationType: 'RECYCLER' as DestinationType | '', permitReference: '', permitExpiresOn: '', acceptsHazardous: false });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add an approved destination" description="Landfill is disposal, not diversion." submitLabel="Add destination" submitting={submitting}
      submitDisabled={!values.code.trim() || !values.name.trim() || !values.destinationType || !values.permitReference.trim() || !values.permitExpiresOn} formError={error} onClose={onClose}
      onSubmit={() => void run(() => wasteApi.createDestination({ code: values.code.trim(), name: values.name.trim(), destinationType: values.destinationType as DestinationType, permitReference: values.permitReference.trim(), permitExpiresOn: values.permitExpiresOn, acceptsHazardous: values.acceptsHazardous }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <SelectInput label="Type" required value={values.destinationType} onChange={(v) => setValues((c) => ({ ...c, destinationType: v as DestinationType }))} options={options(destinationTypes)} />
        <TextInput label="Permit reference" required value={values.permitReference} onChange={(v) => setValues((c) => ({ ...c, permitReference: v }))} maxLength={120} />
        <DateField label="Permit expires on" required value={values.permitExpiresOn} onChange={(v) => setValues((c) => ({ ...c, permitExpiresOn: v }))} />
        <Checkbox checked={values.acceptsHazardous} onChange={(v) => setValues((c) => ({ ...c, acceptsHazardous: v }))} label="Accepts hazardous waste" />
      </div>
    </FormDialog>
  );
};

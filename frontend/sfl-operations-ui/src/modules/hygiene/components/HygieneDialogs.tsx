import { useState } from 'react';
import { Checkbox, DateField, FormDialog, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { todayIsoDate } from 'shared/components/format';
import {
  hygieneApi,
  type ControlType,
  type Frequency,
  type HygieneAction,
  type HygieneFinding,
  type NewControl,
  type RiskCategory,
  type Severity,
} from '../api/hygieneApi';
import { controlTypes, frequencies, options, riskCategories, severities } from './hygieneUi';
import { useSubmit } from './useSubmit';

interface Closeable {
  onClose: () => void;
  onDone: () => void;
}

/** Asks for one piece of free text - a reason - and hands it to `write`. Used wherever the service wants a reason on record. */
export const ReasonDialog = ({ title, description, label, submitLabel, minimum = 1, destructive, write, onClose, onDone }: Closeable & {
  title: string;
  description: string;
  label: string;
  submitLabel: string;
  minimum?: number;
  destructive?: boolean;
  write: (reason: string) => Promise<unknown>;
}) => {
  const [reason, setReason] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog
      open
      title={title}
      description={description}
      submitLabel={submitLabel}
      destructive={destructive}
      submitting={submitting}
      submitDisabled={reason.trim().length < minimum}
      formError={error}
      onClose={onClose}
      onSubmit={() => void run(() => write(reason.trim()), onDone)}
    >
      <TextAreaInput label={label} required value={reason} onChange={setReason} maxLength={2000} autoFocus
        helperText={minimum > 1 ? `At least ${minimum} characters.` : undefined} />
    </FormDialog>
  );
};

export const ControlFormDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const [values, setValues] = useState({
    controlType: 'AUDIT' as ControlType | '', riskCategory: 'FOOD_SAFETY' as RiskCategory | '', title: '', ownerReference: '',
    frequency: 'MONTHLY' as Frequency | '', dueOn: todayIsoDate(), locationLabel: '', providerReference: '', notes: '',
  });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof values>(key: K) => (value: (typeof values)[K]) => setValues((current) => ({ ...current, [key]: value }));
  const pest = values.controlType === 'PEST_VISIT';
  const invalid = !values.controlType || !values.riskCategory || !values.frequency || !values.title.trim() || !values.ownerReference.trim() || !values.dueOn;

  return (
    <FormDialog
      open
      title="Schedule a control"
      description={`A hygiene audit, pest-control visit or statutory check at ${siteCode}. A recurring control schedules its next occurrence when it is completed or missed.`}
      submitLabel="Schedule control"
      submitting={submitting}
      submitDisabled={invalid}
      formError={error}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={() => void run(() => hygieneApi.createControl({
        siteCode,
        controlType: values.controlType as ControlType,
        riskCategory: values.riskCategory as RiskCategory,
        frequency: values.frequency as Frequency,
        title: values.title.trim(),
        ownerReference: values.ownerReference.trim(),
        dueOn: values.dueOn,
        locationLabel: values.locationLabel.trim() || undefined,
        providerReference: pest ? values.providerReference.trim() || undefined : undefined,
        notes: values.notes.trim() || undefined,
      } satisfies NewControl), onDone)}
    >
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Site" value={siteCode} onChange={() => undefined} disabled />
        <SelectInput label="Type" required value={values.controlType} onChange={(v) => set('controlType')(v as ControlType)} options={options(controlTypes)} />
        <TextInput label="Title" required value={values.title} onChange={set('title')} maxLength={240} className="sm:col-span-2" />
        <SelectInput label="Risk category" required value={values.riskCategory} onChange={(v) => set('riskCategory')(v as RiskCategory)} options={options(riskCategories)} />
        <SelectInput label="Frequency" required value={values.frequency} onChange={(v) => set('frequency')(v as Frequency)} options={options(frequencies)} />
        <TextInput label="Owner" required value={values.ownerReference} onChange={set('ownerReference')} maxLength={160} helperText="Who is accountable for carrying it out." />
        <DateField label="Due on" required value={values.dueOn} onChange={set('dueOn')} />
        <TextInput label="Location" value={values.locationLabel} onChange={set('locationLabel')} maxLength={160} helperText="Building, kitchen, store - free text." />
        {pest && <TextInput label="Pest-control provider" value={values.providerReference} onChange={set('providerReference')} maxLength={160}
          helperText="With a provider named, the visit cannot be completed until the provider's confirmation is recorded." />}
        <TextAreaInput label="Notes" value={values.notes} onChange={set('notes')} maxLength={2000} className="sm:col-span-2" />
      </div>
    </FormDialog>
  );
};

export const FindingFormDialog = ({ controlId, controlRef, onClose, onDone }: Closeable & { controlId: string; controlRef: string }) => {
  const [values, setValues] = useState({ title: '', description: '', severity: 'MEDIUM' as Severity | '', ownerReference: '', targetDate: '', requiresIncident: false });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof values>(key: K) => (value: (typeof values)[K]) => setValues((current) => ({ ...current, [key]: value }));
  const critical = values.severity === 'CRITICAL';
  const needsOwner = values.severity === 'HIGH' || critical;
  const invalid = !values.title.trim() || !values.severity || (needsOwner && !values.ownerReference.trim()) || (critical && !values.targetDate);

  return (
    <FormDialog
      open
      title="Record a finding"
      description={`Raised against ${controlRef}. High and critical findings raise a maintenance work order; a critical finding is also escalated to HSE at once.`}
      submitLabel="Record finding"
      submitting={submitting}
      submitDisabled={invalid}
      formError={error}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={() => void run(() => hygieneApi.createFinding(controlId, {
        title: values.title.trim(),
        description: values.description.trim() || undefined,
        severity: values.severity as Severity,
        ownerReference: values.ownerReference.trim() || undefined,
        targetDate: values.targetDate || undefined,
        requiresIncident: values.requiresIncident,
      }), onDone)}
    >
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Finding" required value={values.title} onChange={set('title')} maxLength={240} className="sm:col-span-2" />
        <SelectInput label="Severity" required value={values.severity} onChange={(v) => set('severity')(v as Severity)} options={options(severities)} />
        <TextInput label="Owner" required={needsOwner} value={values.ownerReference} onChange={set('ownerReference')} maxLength={160}
          helperText={needsOwner ? 'Required for a high or critical finding.' : undefined} />
        <DateField label="Target date" required={critical} value={values.targetDate} onChange={set('targetDate')}
          helperText={critical ? 'Required for a critical finding.' : 'Left blank, it is set from the severity.'} />
        <div className="self-end pb-2">
          <Checkbox checked={values.requiresIncident || critical} disabled={critical} onChange={set('requiresIncident')} label="Also a safety incident"
            hint={critical ? 'Always on for a critical finding.' : 'Asks Incident Reporting (S163) to open one.'} />
        </div>
        <TextAreaInput label="What was found" value={values.description} onChange={set('description')} maxLength={4000} className="sm:col-span-2" />
      </div>
    </FormDialog>
  );
};

export const ActionFormDialog = ({ finding, onClose, onDone }: Closeable & { finding: HygieneFinding }) => {
  const [values, setValues] = useState({ description: '', ownerReference: finding.ownerReference ?? '', dueOn: finding.targetDate ?? '' });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof values>(key: K) => (value: string) => setValues((current) => ({ ...current, [key]: value }));
  return (
    <FormDialog
      open
      title="Add a corrective action"
      description={`For ${finding.reference}. The finding cannot close until every action is verified.`}
      submitLabel="Add action"
      submitting={submitting}
      submitDisabled={!values.description.trim() || !values.ownerReference.trim() || !values.dueOn}
      formError={error}
      onClose={onClose}
      onSubmit={() => void run(() => hygieneApi.addAction(finding.id, {
        description: values.description.trim(), ownerReference: values.ownerReference.trim(), dueOn: values.dueOn,
      }), onDone)}
    >
      <TextAreaInput label="Action" required value={values.description} onChange={set('description')} maxLength={2000} autoFocus />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Owner" required value={values.ownerReference} onChange={set('ownerReference')} maxLength={160} />
        <DateField label="Due on" required value={values.dueOn} onChange={set('dueOn')} />
      </div>
    </FormDialog>
  );
};

const sha256 = async (file: File): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
};

/**
 * Evidence is held by reference: the service records what was filed, its size, hash and retention, and
 * the bytes live in the records store. So the operator names where it was filed and picks the file to
 * have its name, type, size and SHA-256 worked out here, never typed.
 */
export const EvidenceFormDialog = ({ finding, actions, onClose, onDone }: Closeable & { finding: HygieneFinding; actions: HygieneAction[] }) => {
  const [file, setFile] = useState<File | null>(null);
  const [reference, setReference] = useState('');
  const [actionId, setActionId] = useState('');
  const [notes, setNotes] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog
      open
      title="Submit evidence"
      description={`For ${finding.reference}. A different person with verification authority must accept it before it counts towards closing.`}
      submitLabel="Submit evidence"
      submitting={submitting}
      submitDisabled={!file || !reference.trim()}
      formError={error}
      onClose={onClose}
      onSubmit={() => void run(async () => {
        if (!file) return;
        await hygieneApi.submitEvidence(finding.id, {
          actionId: actionId || undefined,
          reference: reference.trim(),
          fileName: file.name,
          mediaType: file.type || 'application/octet-stream',
          sizeBytes: file.size,
          contentHash: await sha256(file),
          notes: notes.trim() || undefined,
        });
      }, onDone)}
    >
      <EvidenceFileField label="File" value={file} onChange={setFile} required />
      <TextInput label="Records-store reference" required value={reference} onChange={setReference} maxLength={240}
        helperText="Where the file is filed - the document or record reference." />
      {actions.length > 0 && (
        <SelectInput label="Proves action" allowEmpty emptyLabel="The finding as a whole" value={actionId} onChange={setActionId}
          options={actions.map((action) => ({ value: action.id, label: action.description.slice(0, 80) }))} />
      )}
      <TextAreaInput label="Notes" value={notes} onChange={setNotes} maxLength={2000} />
    </FormDialog>
  );
};

import { useMemo, useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Banner, Button } from '@rfdtech/components';
import { Checkbox, DateField, DateTimeField, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { fromLocalInputValue } from 'shared/components/format';
import { useSubmit } from 'shared/hooks/useSubmit';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { humanise } from 'modules/fleet/api/enums';
import {
  permitApi, type EvidenceKind, type IsolationInput, type IsolationKind, type OriginSystem, type Permit, type PermitType, type RiskLevel, type WorkRole, type WorkerInput,
} from '../api/permitApi';
import { evidenceKinds, hoursFromNow, isolationKinds, options, originSystems, riskLevels, workRoles } from './permitUi';

interface Closeable {
  onClose: () => void;
  onDone: () => void;
}

const sha256 = async (file: File): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
};

// ---- request ------------------------------------------------------------------------------------

export const RequestDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const types = useApiQuery((signal) => permitApi.types(true, signal), []);
  const [v, setV] = useState({
    permitTypeId: '', title: '', workDescription: '', locationCode: '', zoneId: '', startsAt: hoursFromNow(1), endsAt: hoursFromNow(5), riskAssessmentId: '', contractorReference: '',
    supervisorReference: '', supervisorContact: '', originSystem: 'NONE' as OriginSystem, originReference: '',
  });
  const [workers, setWorkers] = useState<WorkerInput[]>([{ personReference: '', displayName: '', workRole: 'SUPERVISOR' }]);
  const [isolations, setIsolations] = useState<IsolationInput[]>([]);
  const { submitting, error, run } = useSubmit();
  const type = useMemo(() => (types.data ?? []).find((t) => t.id === v.permitTypeId), [types.data, v.permitTypeId]);
  const set = <K extends keyof typeof v>(key: K) => (value: (typeof v)[K]) => setV((current) => ({ ...current, [key]: value }));
  const filledWorkers = workers.filter((w) => w.personReference.trim() && w.displayName.trim());
  const invalid = !v.permitTypeId || !v.title.trim() || !v.workDescription.trim() || !v.locationCode.trim() || !v.supervisorReference.trim() || !v.startsAt || !v.endsAt || filledWorkers.length < 1;
  return (
    <FormDialog open title="Request a permit" description={`At ${siteCode}. The request starts as a draft. It can be submitted only with a current S165 risk assessment linked, workers named and, where the type needs them, isolations listed.`}
      submitLabel="Save draft" submitting={submitting} submitDisabled={invalid} formError={error} maxWidth="lg" onClose={onClose}
      onSubmit={() => void run(() => permitApi.create(siteCode, {
        permitTypeId: v.permitTypeId, title: v.title.trim(), workDescription: v.workDescription.trim(), locationCode: v.locationCode.trim(), zoneId: v.zoneId.trim() || undefined,
        startsAt: fromLocalInputValue(v.startsAt), endsAt: fromLocalInputValue(v.endsAt), riskAssessmentId: v.riskAssessmentId.trim() || undefined,
        contractorReference: v.contractorReference.trim() || undefined, supervisorReference: v.supervisorReference.trim(), supervisorContact: v.supervisorContact.trim() || undefined,
        originSystem: v.originReference.trim() ? v.originSystem : 'NONE', originReference: v.originReference.trim() || undefined,
      }, filledWorkers, isolations.filter((i) => i.description.trim())), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectInput label="Permit type" required value={v.permitTypeId} onChange={set('permitTypeId')} className="sm:col-span-2"
          options={(types.data ?? []).map((t: PermitType) => ({ value: t.id, label: `${t.name} (${humanise(t.riskLevel)} risk)` }))} />
        {type && <p className="text-theme-xs text-gray-600 sm:col-span-2">{type.twoStage ? 'Needs an issuing authority and an independent safety sign-off. ' : 'Needs one approval. '}Valid for at most {type.maxValidityHours} hours.
          {type.requiresIsolation ? ' Isolations are required.' : ''}{type.requiredCompetencies.length ? ` Workers are checked for: ${type.requiredCompetencies.map(humanise).join(', ')}.` : ''}</p>}
        <TextInput label="Title" required value={v.title} onChange={set('title')} maxLength={200} className="sm:col-span-2" />
        <TextAreaInput label="The work" required value={v.workDescription} onChange={set('workDescription')} maxLength={4000} className="sm:col-span-2" />
        <TextInput label="Location (S152 reference)" required value={v.locationCode} onChange={set('locationCode')} maxLength={120} helperText="Recorded here, not checked from this service." />
        <TextInput label="Access-control zone id (S160a)" value={v.zoneId} onChange={set('zoneId')} maxLength={80} helperText="Checked: a zone that does not exist at this site is refused." />
        <DateTimeField label="Starts" required value={v.startsAt} onChange={set('startsAt')} />
        <DateTimeField label="Ends" required value={v.endsAt} onChange={set('endsAt')} />
        <TextInput label="Risk assessment id (S165)" value={v.riskAssessmentId} onChange={set('riskAssessmentId')} maxLength={80} helperText="Checked at submission: it must be current." className="sm:col-span-2" />
        <TextInput label="Contractor" value={v.contractorReference} onChange={set('contractorReference')} maxLength={160} helperText="Recorded, not verified against the vendor master (S133)." />
        <TextInput label="Supervisor" required value={v.supervisorReference} onChange={set('supervisorReference')} maxLength={160} helperText="Told at once if the permit is suspended." />
        <TextInput label="Supervisor contact" value={v.supervisorContact} onChange={set('supervisorContact')} maxLength={200} />
        <SelectInput label="Originating work in" value={v.originSystem} onChange={(x) => set('originSystem')(x as OriginSystem)} options={options(originSystems)} />
        <TextInput label="Originating work reference" value={v.originReference} onChange={set('originReference')} maxLength={160} helperText="A work order (S153) or project (S176). Recorded, not verified." />
      </div>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Workers</legend>
        {workers.map((w, i) => (
          <div key={i} className="grid grid-cols-[1fr_1fr_8rem_auto] items-end gap-2">
            <TextInput label={i === 0 ? 'Reference' : undefined} aria-label="Worker reference" value={w.personReference} onChange={(x) => setWorkers((c) => c.map((r, j) => (j === i ? { ...r, personReference: x } : r)))} maxLength={160} />
            <TextInput label={i === 0 ? 'Name' : undefined} aria-label="Worker name" value={w.displayName} onChange={(x) => setWorkers((c) => c.map((r, j) => (j === i ? { ...r, displayName: x } : r)))} maxLength={200} />
            <SelectInput label={i === 0 ? 'Role' : undefined} value={w.workRole} onChange={(x) => setWorkers((c) => c.map((r, j) => (j === i ? { ...r, workRole: x as WorkRole } : r)))} options={options(workRoles)} />
            <Button size="sm" variant="outline" aria-label="Remove worker" onClick={() => setWorkers((c) => c.filter((_, j) => j !== i))}><Trash2 size={14} strokeWidth={1.5} aria-hidden /></Button>
          </div>
        ))}
        <Button size="sm" variant="outline" onClick={() => setWorkers((c) => [...c, { personReference: '', displayName: '', workRole: 'OPERATIVE' }])}><Plus size={14} strokeWidth={1.5} aria-hidden /> Add worker</Button>
      </fieldset>
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Isolations</legend>
        {isolations.map((x, i) => (
          <div key={i} className="grid grid-cols-[9rem_1fr_9rem_auto] items-end gap-2">
            <SelectInput label={i === 0 ? 'Kind' : undefined} value={x.kind} onChange={(k) => setIsolations((c) => c.map((r, j) => (j === i ? { ...r, kind: k as IsolationKind } : r)))} options={options(isolationKinds)} />
            <TextInput label={i === 0 ? 'What is isolated' : undefined} aria-label="Isolation" value={x.description} onChange={(d) => setIsolations((c) => c.map((r, j) => (j === i ? { ...r, description: d } : r)))} maxLength={1000} />
            <TextInput label={i === 0 ? 'Tag' : undefined} aria-label="Lock or tag" value={x.tagReference ?? ''} onChange={(d) => setIsolations((c) => c.map((r, j) => (j === i ? { ...r, tagReference: d } : r)))} maxLength={160} />
            <Button size="sm" variant="outline" aria-label="Remove isolation" onClick={() => setIsolations((c) => c.filter((_, j) => j !== i))}><Trash2 size={14} strokeWidth={1.5} aria-hidden /></Button>
          </div>
        ))}
        <Button size="sm" variant="outline" onClick={() => setIsolations((c) => [...c, { kind: 'ELECTRICAL', description: '' }])}><Plus size={14} strokeWidth={1.5} aria-hidden /> Add isolation</Button>
        {type?.requiresIsolation && isolations.filter((i) => i.description.trim()).length === 0 && <Banner variant="info" heading="Isolations are required for this type" subtext="Add at least one before the request is submitted." />}
      </fieldset>
    </FormDialog>
  );
};

// ---- small dialogs on an existing permit --------------------------------------------------------

export const AddWorkerDialog = ({ permitId, onClose, onDone }: Closeable & { permitId: string }) => {
  const [w, setW] = useState<WorkerInput>({ personReference: '', displayName: '', workRole: 'OPERATIVE' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a worker" description="Only on a draft. A name and a reference; nothing else about the person is held." submitLabel="Add worker" submitting={submitting}
      submitDisabled={!w.personReference.trim() || !w.displayName.trim()} formError={error} onClose={onClose} onSubmit={() => void run(() => permitApi.addWorker(permitId, w), onDone)}>
      <TextInput label="Reference" required value={w.personReference} onChange={(x) => setW({ ...w, personReference: x })} maxLength={160} />
      <TextInput label="Name" required value={w.displayName} onChange={(x) => setW({ ...w, displayName: x })} maxLength={200} />
      <SelectInput label="Role" value={w.workRole} onChange={(x) => setW({ ...w, workRole: x as WorkRole })} options={options(workRoles)} />
    </FormDialog>
  );
};

export const AddIsolationDialog = ({ permitId, onClose, onDone }: Closeable & { permitId: string }) => {
  const [i, setI] = useState<IsolationInput>({ kind: 'ELECTRICAL', description: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add an isolation" description="Only on a draft. Someone other than you must verify it before the permit can be approved." submitLabel="Add isolation" submitting={submitting}
      submitDisabled={!i.description.trim()} formError={error} onClose={onClose} onSubmit={() => void run(() => permitApi.addIsolation(permitId, i), onDone)}>
      <SelectInput label="Kind" value={i.kind} onChange={(x) => setI({ ...i, kind: x as IsolationKind })} options={options(isolationKinds)} />
      <TextAreaInput label="What is isolated" required value={i.description} onChange={(x) => setI({ ...i, description: x })} maxLength={1000} />
      <TextInput label="Lock or tag reference" value={i.tagReference ?? ''} onChange={(x) => setI({ ...i, tagReference: x })} maxLength={160} />
    </FormDialog>
  );
};

export const CompetencyDialog = ({ permit, workerId, workerName, competencies, onClose, onDone }: Closeable & { permit: Permit; workerId: string; workerName: string; competencies: string[] }) => {
  const [code, setCode] = useState(competencies[0] ?? '');
  const [competent, setCompetent] = useState(true);
  const [evidence, setEvidence] = useState('');
  const [until, setUntil] = useState('');
  const [note, setNote] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title={`Competence check: ${workerName}`} description="Record what you checked. Approval is refused for a worker with no current, competent check for each competence this type needs."
      submitLabel="Record check" submitting={submitting} submitDisabled={!code} formError={error} onClose={onClose}
      onSubmit={() => void run(() => permitApi.competency(permit.id, workerId, { competencyCode: code, competent, evidenceReference: evidence.trim() || undefined, validUntil: until || undefined, note: note.trim() || undefined }), onDone)}>
      <SelectInput label="Competence" required value={code} onChange={setCode} options={options(competencies)} />
      <Checkbox checked={competent} onChange={() => setCompetent(!competent)} label="Competent" />
      <TextInput label="Evidence reference" value={evidence} onChange={setEvidence} maxLength={240} helperText="The certificate or record you saw." />
      <DateField label="Valid until" value={until} onChange={setUntil} />
      <TextAreaInput label="Note" value={note} onChange={setNote} maxLength={1000} />
    </FormDialog>
  );
};

export const ExtensionDialog = ({ permit, onClose, onDone }: Closeable & { permit: Permit }) => {
  const [endsAt, setEndsAt] = useState('');
  const [reason, setReason] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Request an extension" description={`Now ends ${new Date(permit.endsAt).toLocaleString()}. An extension gets the same checks as a new request - the risk assessment must be current, isolations standing, competence valid - and the same approvals.`}
      submitLabel="Request extension" submitting={submitting} submitDisabled={!endsAt || !reason.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => permitApi.requestExtension(permit, fromLocalInputValue(endsAt), reason.trim()), onDone)}>
      <DateTimeField label="New end" required value={endsAt} onChange={setEndsAt} />
      <TextAreaInput label="Why" required value={reason} onChange={setReason} maxLength={2000} />
    </FormDialog>
  );
};

export const EvidenceDialog = ({ permitId, onClose, onDone }: Closeable & { permitId: string }) => {
  const [kind, setKind] = useState<EvidenceKind>('PHOTO');
  const [file, setFile] = useState<File | null>(null);
  const [reference, setReference] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="File close-out evidence" description="A photograph or checklist showing the work is complete. Held by reference; the file lives in the records store." submitLabel="File evidence" submitting={submitting}
      submitDisabled={!file || !reference.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(async () => { if (file) await permitApi.evidence(permitId, { kind, reference: reference.trim(), fileName: file.name, mediaType: file.type || 'application/octet-stream', sizeBytes: file.size, contentHash: await sha256(file) }); }, onDone)}>
      <SelectInput label="What it is" value={kind} onChange={(x) => setKind(x as EvidenceKind)} options={options(evidenceKinds)} />
      <EvidenceFileField label="File" value={file} onChange={setFile} required />
      <TextInput label="Records-store reference" required value={reference} onChange={setReference} maxLength={240} />
    </FormDialog>
  );
};

export const IncidentLinkDialog = ({ permitId, onClose, onDone }: Closeable & { permitId: string }) => {
  const [reference, setReference] = useState('');
  const [detail, setDetail] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Link an incident" description="Flags this permit for the incident investigation. An incident that names the permit's risk assessment is linked automatically; use this when it did not." submitLabel="Link incident"
      submitting={submitting} submitDisabled={!reference.trim()} formError={error} onClose={onClose} onSubmit={() => void run(() => permitApi.linkIncident(permitId, reference.trim(), detail.trim() || undefined), onDone)}>
      <TextInput label="Incident reference (S163)" required value={reference} onChange={setReference} maxLength={80} />
      <TextAreaInput label="Why it relates" value={detail} onChange={setDetail} maxLength={1000} />
    </FormDialog>
  );
};

// ---- types ---------------------------------------------------------------------------------------

export const TypeDialog = ({ existing, onClose, onDone }: Closeable & { existing?: PermitType }) => {
  const [v, setV] = useState({
    code: existing?.code ?? '', name: existing?.name ?? '', description: existing?.description ?? '', riskLevel: (existing?.riskLevel ?? 'HIGH') as RiskLevel, activityType: existing?.activityType ?? '',
    riskAssessmentRequired: existing?.riskAssessmentRequired ?? true, twoStage: existing?.twoStage ?? true, requiresIsolation: existing?.requiresIsolation ?? false,
    maxValidityHours: String(existing?.maxValidityHours ?? 8), competencies: (existing?.requiredCompetencies ?? []).join(', '), active: existing?.active ?? true,
  });
  const { submitting, error, run } = useSubmit();
  const higher = v.riskLevel === 'HIGH' || v.riskLevel === 'CRITICAL';
  const body = () => ({
    code: v.code.trim(), name: v.name.trim(), description: v.description.trim() || undefined, riskLevel: v.riskLevel, activityType: v.activityType.trim() || undefined,
    riskAssessmentRequired: v.riskAssessmentRequired, twoStage: higher ? true : v.twoStage, requiresIsolation: v.requiresIsolation, maxValidityHours: Number(v.maxValidityHours),
    requiredCompetencies: v.competencies.split(',').map((c) => c.trim()).filter(Boolean), active: v.active, version: existing?.version,
  });
  return (
    <FormDialog open title={existing ? `Edit ${existing.name}` : 'Add a permit type'} description="Each type has its own approvals and validity rules. A type of high or critical risk always needs the independent safety sign-off." submitLabel="Save type"
      submitting={submitting} submitDisabled={!v.code.trim() || !v.name.trim() || !Number(v.maxValidityHours)} formError={error} maxWidth="lg" onClose={onClose}
      onSubmit={() => void run(() => (existing ? permitApi.updateType(existing.id, body()) : permitApi.createType(body())), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={v.code} onChange={(x) => setV({ ...v, code: x })} maxLength={40} disabled={Boolean(existing)} />
        <TextInput label="Name" required value={v.name} onChange={(x) => setV({ ...v, name: x })} maxLength={120} />
        <SelectInput label="Risk level" required value={v.riskLevel} onChange={(x) => setV({ ...v, riskLevel: x as RiskLevel })} options={options(riskLevels)} />
        <NumberInput label="Valid for at most (hours)" required value={v.maxValidityHours} onChange={(x) => setV({ ...v, maxValidityHours: x })} />
        <TextInput label="S165 activity type an assessment must cover" value={v.activityType} onChange={(x) => setV({ ...v, activityType: x })} maxLength={80} className="sm:col-span-2" />
        <TextInput label="Competences to check (comma separated)" value={v.competencies} onChange={(x) => setV({ ...v, competencies: x })} className="sm:col-span-2" />
        <TextAreaInput label="Description" value={v.description} onChange={(x) => setV({ ...v, description: x })} maxLength={1000} className="sm:col-span-2" />
        <Checkbox checked={v.riskAssessmentRequired} onChange={() => setV({ ...v, riskAssessmentRequired: !v.riskAssessmentRequired })} label="A current risk assessment is required" />
        <Checkbox checked={higher ? true : v.twoStage} disabled={higher} onChange={() => setV({ ...v, twoStage: !v.twoStage })} label="Two-stage approval" hint={higher ? 'Always on for high and critical risk.' : undefined} />
        <Checkbox checked={v.requiresIsolation} onChange={() => setV({ ...v, requiresIsolation: !v.requiresIsolation })} label="Isolations are required" />
        <Checkbox checked={v.active} onChange={() => setV({ ...v, active: !v.active })} label="In use" />
      </div>
    </FormDialog>
  );
};

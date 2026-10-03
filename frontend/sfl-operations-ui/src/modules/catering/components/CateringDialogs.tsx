import { useState } from 'react';
import { Checkbox, DateField, DateTimeField, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { fromLocalInputValue, nowLocalInputValue } from 'shared/components/format';
import { useSubmit } from 'shared/hooks/useSubmit';
import { humanise } from 'modules/fleet/api/enums';
import {
  cateringApi,
  type Allergen,
  type CateringService,
  type CheckType,
  type Configuration,
  type ContextType,
  type DietaryTag,
  type EvidenceKind,
  type ExceptionType,
  type HoldType,
  type MenuItem,
  type NeedType,
  type VarianceKind,
} from '../api/cateringApi';
import { allergens, contextTypes, dietaryTags, evidenceKinds, exceptionTypes, options, varianceKinds } from './cateringUi';

interface Closeable {
  onClose: () => void;
  onDone: () => void;
}

const toggle = <T,>(list: T[], value: T): T[] => (list.includes(value) ? list.filter((entry) => entry !== value) : [...list, value]);

export const ServiceFormDialog = ({ siteCode, config, onClose, onDone }: Closeable & { siteCode: string; config: Configuration }) => {
  const [values, setValues] = useState({
    title: '', venueId: '', menuId: '', supplierId: '', contextType: 'ROUTINE' as ContextType | '', contextReference: '',
    startsAt: nowLocalInputValue(72), expectedGuests: '', plannedPortions: '',
  });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof values>(key: K) => (value: (typeof values)[K]) => setValues((current) => ({ ...current, [key]: value }));
  const needsContext = values.contextType !== '' && values.contextType !== 'ROUTINE';
  const invalid = !values.title.trim() || !values.venueId || !values.menuId || !values.supplierId || !values.contextType || !values.startsAt
    || Number(values.expectedGuests) <= 0 || Number(values.plannedPortions) <= 0 || (needsContext && !values.contextReference.trim());
  return (
    <FormDialog open title="Plan a service" description={`At ${siteCode}. Approval checks the venue, the menu's allergens, the supplier's certificate and check, and capacity.`}
      submitLabel="Create service" submitting={submitting} submitDisabled={invalid} formError={error} maxWidth="lg" onClose={onClose}
      onSubmit={() => void run(() => cateringApi.createService({
        siteCode, venueId: values.venueId, menuId: values.menuId, supplierId: values.supplierId, contextType: values.contextType as ContextType,
        contextReference: values.contextReference.trim() || undefined, title: values.title.trim(), startsAt: fromLocalInputValue(values.startsAt),
        expectedGuests: Number(values.expectedGuests), plannedPortions: Number(values.plannedPortions),
      }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Title" required value={values.title} onChange={set('title')} maxLength={240} className="sm:col-span-2" />
        <SelectInput label="Venue" required value={values.venueId} onChange={set('venueId')} options={config.venues.filter((v) => v.active).map((v) => ({ value: v.id, label: `${v.name} (${v.capacity})` }))} />
        <SelectInput label="Menu" required value={values.menuId} onChange={set('menuId')} options={config.menus.filter((m) => m.menu.status !== 'RETIRED').map((m) => ({ value: m.menu.id, label: `${m.menu.name}${m.menu.status === 'APPROVED' ? '' : ' (not approved)'}` }))} />
        <SelectInput label="Supplier" required value={values.supplierId} onChange={set('supplierId')} options={config.suppliers.map((s) => ({ value: s.id, label: `${s.name}${s.status === 'SUSPENDED' ? ' (suspended)' : ''}` }))} />
        <DateTimeField label="Starts" required value={values.startsAt} onChange={set('startsAt')} />
        <SelectInput label="Serves" required value={values.contextType} onChange={(v) => set('contextType')(v as ContextType)} options={options(contextTypes)} />
        {needsContext && <TextInput label={`${humanise(values.contextType)} reference`} required value={values.contextReference} onChange={set('contextReference')} maxLength={160} helperText="The event, booking or examination this service is for." />}
        <NumberInput label="Expected guests" required value={values.expectedGuests} onChange={set('expectedGuests')} />
        <NumberInput label="Planned portions" required value={values.plannedPortions} onChange={set('plannedPortions')} />
      </div>
    </FormDialog>
  );
};

export const ChangeDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [values, setValues] = useState({ expectedGuests: String(service.expectedGuests), plannedPortions: String(service.plannedPortions), reason: '' });
  const { submitting, error, run } = useSubmit();
  const controlled = service.status !== 'DRAFT';
  return (
    <FormDialog open title="Change the plan" description={controlled ? 'This service is past draft: a change needs a reason, sends it back for approval, and after the cancellation cut-off needs an approver.' : 'A draft changes freely.'}
      submitLabel="Save change" submitting={submitting} submitDisabled={Number(values.expectedGuests) <= 0 || Number(values.plannedPortions) <= 0 || (controlled && !values.reason.trim())} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.change(service, { expectedGuests: Number(values.expectedGuests), plannedPortions: Number(values.plannedPortions), reason: values.reason.trim() || undefined }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <NumberInput label="Expected guests" required value={values.expectedGuests} onChange={(v) => setValues((c) => ({ ...c, expectedGuests: v }))} />
        <NumberInput label="Planned portions" required value={values.plannedPortions} onChange={(v) => setValues((c) => ({ ...c, plannedPortions: v }))} />
        {controlled && <TextAreaInput label="Reason" required value={values.reason} onChange={(v) => setValues((c) => ({ ...c, reason: v }))} maxLength={2000} className="sm:col-span-2" />}
      </div>
    </FormDialog>
  );
};

export const NeedDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [values, setValues] = useState({ personReference: '', needType: 'ALLERGY' as NeedType | '', needCode: '', authorisedBy: '' });
  const { submitting, error, run } = useSubmit();
  const codes = values.needType === 'ALLERGY' ? allergens : dietaryTags;
  return (
    <FormDialog open title="Record a dietary need" description={`For ${service.reference}. Only what is needed: an opaque reference for the person - never a name - and who authorised collecting it.`}
      submitLabel="Record need" submitting={submitting} submitDisabled={!/^[A-Za-z0-9._-]{1,80}$/.test(values.personReference) || !values.needType || !values.needCode || !values.authorisedBy.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.addDietary(service.id, { personReference: values.personReference, needType: values.needType as NeedType, needCode: values.needCode, authorisedBy: values.authorisedBy.trim() }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Person reference" required value={values.personReference} onChange={(v) => setValues((c) => ({ ...c, personReference: v.trim() }))} maxLength={80}
          helperText="Letters, digits and . _ - only, for example P-17." />
        <TextInput label="Authorised by" required value={values.authorisedBy} onChange={(v) => setValues((c) => ({ ...c, authorisedBy: v }))} maxLength={160} />
        <SelectInput label="Kind" required value={values.needType} onChange={(v) => setValues((c) => ({ ...c, needType: v as NeedType, needCode: '' }))} options={options(['ALLERGY', 'DIETARY'])} />
        <SelectInput label={values.needType === 'ALLERGY' ? 'Allergen' : 'Diet'} required value={values.needCode} onChange={(v) => setValues((c) => ({ ...c, needCode: v }))} options={options(codes)} />
      </div>
    </FormDialog>
  );
};

export const SubstituteDialog = ({ items, onSubstitute, onClose, onDone }: Closeable & { items: MenuItem[]; onSubstitute: (itemId: string) => Promise<unknown> }) => {
  const [itemId, setItemId] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Approve a substitution" description="Choose a dish that is safe for this need. The service refuses one that is not, and you cannot approve a need you recorded yourself."
      submitLabel="Approve substitution" submitting={submitting} submitDisabled={!itemId} formError={error} onClose={onClose} onSubmit={() => void run(() => onSubstitute(itemId), onDone)}>
      <SelectInput label="Substitute dish" required value={itemId} onChange={setItemId} options={items.map((i) => ({ value: i.id, label: i.name }))} />
    </FormDialog>
  );
};

export const ApproveDialog = ({ service, blockers, onClose, onDone }: Closeable & { service: CateringService; blockers: string[] }) => {
  const [capacityReason, setCapacityReason] = useState('');
  const [supplierReason, setSupplierReason] = useState('');
  const { submitting, error, run } = useSubmit();
  const capacity = blockers.includes('CAPACITY_EXCEEDED');
  const supplier = blockers.includes('SUPPLIER_CERTIFICATE_EXPIRED') || blockers.includes('SUPPLIER_CHECK_OVERDUE');
  return (
    <FormDialog open title="Approve the service" description={`${service.reference}. Capacity and supplier problems can be accepted here with a reason; allergen, menu, venue and suspended-supplier problems cannot. You cannot approve a service you requested.`}
      submitLabel="Approve" submitting={submitting} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.approve(service, capacityReason.trim() || undefined, supplierReason.trim() || undefined), onDone)}>
      {capacity && <TextAreaInput label="Accept the capacity overrun because" value={capacityReason} onChange={setCapacityReason} maxLength={1000} />}
      {supplier && <TextAreaInput label="Accept the supplier's certificate or overdue check because" value={supplierReason} onChange={setSupplierReason} maxLength={1000} />}
      {!capacity && !supplier && <p className="text-theme-sm text-gray-600">Nothing needs an exception. The controls are rechecked when you approve.</p>}
    </FormDialog>
  );
};

export const DeliverDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [portions, setPortions] = useState(String(service.plannedPortions));
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Record delivery" description="Needs a passing temperature check on record and no open food-safety incident." submitLabel="Mark delivered" submitting={submitting}
      submitDisabled={portions === '' || Number(portions) < 0} formError={error} onClose={onClose} onSubmit={() => void run(() => cateringApi.deliver(service, Number(portions)), onDone)}>
      <NumberInput label="Portions delivered" required value={portions} onChange={setPortions} helperText={`${service.plannedPortions} were planned.`} />
    </FormDialog>
  );
};

export const CheckDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [values, setValues] = useState({ checkType: 'TEMPERATURE' as CheckType | '', holdType: 'HOT' as HoldType | '', temperatureC: '', passed: true, notes: '' });
  const { submitting, error, run } = useSubmit();
  const temperature = values.checkType === 'TEMPERATURE';
  return (
    <FormDialog open title="Record a check" description={temperature ? 'The result comes from the reading: hot food passes at 63 C or above, cold at 5 C or below. A failure raises a food-safety incident.' : 'A supplier or food-safety check.'}
      submitLabel="Record check" submitting={submitting} submitDisabled={!values.checkType || (temperature && (!values.holdType || values.temperatureC === ''))} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.recordCheck({
        serviceId: service.id, checkType: values.checkType as CheckType, holdType: temperature ? (values.holdType as HoldType) : undefined,
        temperatureC: temperature ? Number(values.temperatureC) : undefined, passed: temperature ? undefined : values.passed, notes: values.notes.trim() || undefined,
      }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectInput label="Check" required value={values.checkType} onChange={(v) => setValues((c) => ({ ...c, checkType: v as CheckType }))} options={options(['TEMPERATURE', 'FOOD_SAFETY', 'SUPPLIER'])} />
        {temperature && <SelectInput label="Food" required value={values.holdType} onChange={(v) => setValues((c) => ({ ...c, holdType: v as HoldType }))} options={options(['HOT', 'COLD'])} />}
        {temperature && <NumberInput label="Temperature (C)" required value={values.temperatureC} onChange={(v) => setValues((c) => ({ ...c, temperatureC: v }))} />}
        {!temperature && <div className="self-end pb-2"><Checkbox checked={values.passed} onChange={(v) => setValues((c) => ({ ...c, passed: v }))} label="Passed" /></div>}
        <TextAreaInput label="Notes" value={values.notes} onChange={(v) => setValues((c) => ({ ...c, notes: v }))} maxLength={1000} className="sm:col-span-2" />
      </div>
    </FormDialog>
  );
};

export const VarianceDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [values, setValues] = useState({ kind: 'QUANTITY' as VarianceKind | '', planned: String(service.plannedPortions), actual: String(service.deliveredPortions ?? ''), ownerReference: '', reason: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Record a variance" description="Every variance has an owner and a reason, and is approved by someone other than whoever recorded it." submitLabel="Record variance" submitting={submitting}
      submitDisabled={!values.kind || values.planned === '' || values.actual === '' || !values.ownerReference.trim() || values.reason.trim().length < 5} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.recordVariance(service.id, { kind: values.kind as VarianceKind, planned: Number(values.planned), actual: Number(values.actual), ownerReference: values.ownerReference.trim(), reason: values.reason.trim() }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <SelectInput label="Kind" required value={values.kind} onChange={(v) => setValues((c) => ({ ...c, kind: v as VarianceKind }))} options={options(varianceKinds)} />
        <NumberInput label="Planned" required value={values.planned} onChange={(v) => setValues((c) => ({ ...c, planned: v }))} />
        <NumberInput label="Actual" required value={values.actual} onChange={(v) => setValues((c) => ({ ...c, actual: v }))} />
        <TextInput label="Owner" required value={values.ownerReference} onChange={(v) => setValues((c) => ({ ...c, ownerReference: v }))} maxLength={160} className="sm:col-span-3" />
        <TextAreaInput label="Reason" required value={values.reason} onChange={(v) => setValues((c) => ({ ...c, reason: v }))} maxLength={1000} className="sm:col-span-3" />
      </div>
    </FormDialog>
  );
};

export const ReconcileDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [purchase, setPurchase] = useState(service.purchaseReference ?? '');
  const [invoice, setInvoice] = useState(service.invoiceReference ?? '');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Reconcile" description="Record the purchase and invoice references. This is not the finance ledger and no finance system is connected yet: the references are recorded, not verified, and without an invoice reference the service stays pending finance."
      submitLabel="Record references" submitting={submitting} submitDisabled={!purchase.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.reconcile(service, purchase.trim(), invoice.trim() || undefined), onDone)}>
      <TextInput label="Purchase reference" required value={purchase} onChange={setPurchase} maxLength={120} />
      <TextInput label="Approved invoice reference" value={invoice} onChange={setInvoice} maxLength={120} />
    </FormDialog>
  );
};

export const ExceptionFormDialog = ({ siteCode, serviceId, onClose, onDone }: Closeable & { siteCode: string; serviceId?: string }) => {
  const [values, setValues] = useState({ exceptionType: 'SHORTAGE' as ExceptionType | '', description: '', ownerReference: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Raise an exception" description="A shortage, a substitution, a service exception, or a food-safety incident (which also asks Incident Reporting for an incident)." submitLabel="Raise exception"
      submitting={submitting} submitDisabled={!values.exceptionType || !values.description.trim() || !values.ownerReference.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.raiseException({ siteCode, serviceId, exceptionType: values.exceptionType as ExceptionType, description: values.description.trim(), ownerReference: values.ownerReference.trim() }), onDone)}>
      <SelectInput label="Type" required value={values.exceptionType} onChange={(v) => setValues((c) => ({ ...c, exceptionType: v as ExceptionType }))} options={options(exceptionTypes)} />
      <TextAreaInput label="What happened" required value={values.description} onChange={(v) => setValues((c) => ({ ...c, description: v }))} maxLength={2000} />
      <TextInput label="Owner" required value={values.ownerReference} onChange={(v) => setValues((c) => ({ ...c, ownerReference: v }))} maxLength={160} />
    </FormDialog>
  );
};

const sha256 = async (file: File): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
};

export const EvidenceDialog = ({ service, onClose, onDone }: Closeable & { service: CateringService }) => {
  const [kind, setKind] = useState<EvidenceKind | ''>('DELIVERY_NOTE');
  const [file, setFile] = useState<File | null>(null);
  const [filed, setFiled] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="File evidence" description={`For ${service.reference}. The service closes only with the delivery note and the invoice on file.`} submitLabel="File evidence" submitting={submitting}
      submitDisabled={!kind || !file || !filed.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(async () => {
        if (!file || !kind) return;
        await cateringApi.submitEvidence(service.id, { kind, reference: filed.trim(), fileName: file.name, mediaType: file.type || 'application/octet-stream', sizeBytes: file.size, contentHash: await sha256(file) });
      }, onDone)}>
      <SelectInput label="What it is" required value={kind} onChange={(v) => setKind(v as EvidenceKind)} options={options(evidenceKinds)} />
      <EvidenceFileField label="File" value={file} onChange={setFile} required />
      <TextInput label="Records-store reference" required value={filed} onChange={setFiled} maxLength={240} />
    </FormDialog>
  );
};

// ---- setup

export const SupplierDialog = ({ onClose, onDone }: Closeable) => {
  const [values, setValues] = useState({ code: '', name: '', certificateReference: '', certificateExpiresOn: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a supplier" description="Suppliers are organisation-wide. A supplier needs a current certificate and a passing check in the last 30 days before a service can be approved." submitLabel="Add supplier"
      submitting={submitting} submitDisabled={!values.code.trim() || !values.name.trim() || !values.certificateReference.trim() || !values.certificateExpiresOn} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.createSupplier({ code: values.code.trim(), name: values.name.trim(), certificateReference: values.certificateReference.trim(), certificateExpiresOn: values.certificateExpiresOn }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <TextInput label="Certificate reference" required value={values.certificateReference} onChange={(v) => setValues((c) => ({ ...c, certificateReference: v }))} maxLength={120} />
        <DateField label="Certificate expires on" required value={values.certificateExpiresOn} onChange={(v) => setValues((c) => ({ ...c, certificateExpiresOn: v }))} />
      </div>
    </FormDialog>
  );
};

export const VenueDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const [values, setValues] = useState({ code: '', name: '', capacity: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a venue" description={`At ${siteCode}.`} submitLabel="Add venue" submitting={submitting}
      submitDisabled={!values.code.trim() || !values.name.trim() || Number(values.capacity) <= 0} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.createVenue({ siteCode, code: values.code.trim(), name: values.name.trim(), capacity: Number(values.capacity) }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <NumberInput label="Capacity" required value={values.capacity} onChange={(v) => setValues((c) => ({ ...c, capacity: v }))} />
      </div>
    </FormDialog>
  );
};

export const MenuDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const [values, setValues] = useState({ code: '', name: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a menu" description="A menu is approved only when every dish has its allergens declared. Changing an approved menu sends the services planned on it back for approval." submitLabel="Add menu"
      submitting={submitting} submitDisabled={!values.code.trim() || !values.name.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.createMenu({ siteCode, code: values.code.trim(), name: values.name.trim() }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
      </div>
    </FormDialog>
  );
};

export const ItemDialog = ({ menuId, onClose, onDone }: Closeable & { menuId: string }) => {
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<Allergen[]>([]);
  const [tags, setTags] = useState<DietaryTag[]>([]);
  const [declared, setDeclared] = useState(false);
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a dish" description="Tick every allergen the dish contains, then confirm the declaration. A dish whose allergens are not declared is never treated as safe." submitLabel="Add dish" submitting={submitting}
      submitDisabled={!name.trim()} formError={error} maxWidth="lg" onClose={onClose}
      onSubmit={() => void run(() => cateringApi.addItem(menuId, { name: name.trim(), allergens: selected, allergensDeclared: declared, dietaryTags: tags }), onDone)}>
      <TextInput label="Dish" required value={name} onChange={setName} maxLength={160} />
      <fieldset className="space-y-2"><legend className="text-sm font-medium">Contains</legend>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">{allergens.map((a) => <Checkbox key={a} checked={selected.includes(a)} onChange={() => setSelected(toggle(selected, a))} label={humanise(a)} />)}</div>
      </fieldset>
      <fieldset className="space-y-2"><legend className="text-sm font-medium">Suitable for</legend>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">{dietaryTags.map((t) => <Checkbox key={t} checked={tags.includes(t)} onChange={() => setTags(toggle(tags, t))} label={humanise(t)} />)}</div>
      </fieldset>
      <Checkbox checked={declared} onChange={setDeclared} label="Allergens declared" hint="Confirms the list above is complete, including an empty one." />
    </FormDialog>
  );
};


export const SupplierCheckDialog = ({ supplierId, supplierName, onClose, onDone }: Closeable & { supplierId: string; supplierName: string }) => {
  const [passed, setPassed] = useState(true);
  const [notes, setNotes] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title={`Check ${supplierName}`} description="A passing check keeps the supplier current for 30 days. Without one, a service cannot be approved unless an approver accepts the exception."
      submitLabel="Record check" submitting={submitting} formError={error} onClose={onClose}
      onSubmit={() => void run(() => cateringApi.recordCheck({ supplierId, checkType: 'SUPPLIER', passed, notes: notes.trim() || undefined, siteCode: undefined }), onDone)}>
      <Checkbox checked={passed} onChange={setPassed} label="Passed" />
      <TextAreaInput label="Notes" value={notes} onChange={setNotes} maxLength={1000} />
    </FormDialog>
  );
};

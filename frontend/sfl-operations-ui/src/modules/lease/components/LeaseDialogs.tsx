import { useState } from 'react';
import { Checkbox, DateField, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { todayIsoDate } from 'shared/components/format';
import { useSubmit } from 'shared/hooks/useSubmit';
import { humanise } from 'modules/fleet/api/enums';
import { leaseApi, type Agreement, type AgreementKind, type AmendmentKind, type Direction, type DocumentKind, type ObligationKind, type RenewalType } from '../api/leaseApi';
import { amendmentKinds, directions, documentKinds, kinds, obligationKinds, options, renewalTypes } from './leaseUi';

interface Closeable {
  onClose: () => void;
  onDone: () => void;
}

const num = (value: string): number | undefined => (value.trim() === '' ? undefined : Number(value));

export const RegisterDialog = ({ siteCode, canSeeMoney, onClose, onDone }: Closeable & { siteCode: string; canSeeMoney: boolean }) => {
  const [v, setV] = useState({
    title: '', propertyReference: '', kind: 'LEASE' as AgreementKind | '', direction: 'INBOUND' as Direction | '', counterpartyReference: '', contractReference: '',
    financeReference: '', ownerReference: '', startDate: todayIsoDate(), endDate: '', renewalType: 'OPTION' as RenewalType | '', renewalTermMonths: '', noticeDays: '',
    rentReviewDate: '', annualRent: '', depositAmount: '', currency: 'GHS',
  });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof v>(key: K) => (value: (typeof v)[K]) => setV((current) => ({ ...current, [key]: value }));
  const invalid = !v.title.trim() || !v.propertyReference.trim() || !v.kind || !v.direction || !v.renewalType || !v.startDate || !v.endDate || v.endDate <= v.startDate;
  return (
    <FormDialog open title="Register an agreement" description={`At ${siteCode}. It starts as a draft. The notice date is calculated from the end date and notice period against the business calendar. To be approved it needs a counterparty, an owner, a notice period and approval evidence.`}
      submitLabel="Register agreement" submitting={submitting} submitDisabled={invalid} formError={error} maxWidth="lg" onClose={onClose}
      onSubmit={() => void run(() => leaseApi.register({
        siteCode, title: v.title.trim(), propertyReference: v.propertyReference.trim(), kind: v.kind as AgreementKind, direction: v.direction as Direction,
        counterpartyReference: v.counterpartyReference.trim() || undefined, contractReference: v.contractReference.trim() || undefined,
        financeReference: v.financeReference.trim() || undefined, ownerReference: v.ownerReference.trim() || undefined, startDate: v.startDate, endDate: v.endDate,
        renewalType: v.renewalType as RenewalType, renewalTermMonths: num(v.renewalTermMonths), noticeDays: num(v.noticeDays), rentReviewDate: v.rentReviewDate || undefined,
        annualRent: canSeeMoney ? num(v.annualRent) : undefined, depositAmount: canSeeMoney ? num(v.depositAmount) : undefined,
        currency: canSeeMoney && (v.annualRent || v.depositAmount) ? v.currency : undefined,
      }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Title" required value={v.title} onChange={set('title')} maxLength={240} className="sm:col-span-2" />
        <TextInput label="Property" required value={v.propertyReference} onChange={set('propertyReference')} maxLength={240} helperText="The S152 building or space this is for." />
        <TextInput label="Internal owner" value={v.ownerReference} onChange={set('ownerReference')} maxLength={160} helperText="Told when a date approaches." />
        <SelectInput label="Kind" required value={v.kind} onChange={(x) => set('kind')(x as AgreementKind)} options={options(kinds)} />
        <SelectInput label="CLET is the" required value={v.direction} onChange={(x) => set('direction')(x as Direction)} options={directions.map((d) => ({ value: d, label: d === 'INBOUND' ? 'Tenant (pays rent)' : 'Landlord (receives rent)' }))} />
        <TextInput label="Counterparty reference" value={v.counterpartyReference} onChange={set('counterpartyReference')} maxLength={160} helperText="Recorded, not verified: no counterparty system is connected." />
        <TextInput label="Contract reference (S136)" value={v.contractReference} onChange={set('contractReference')} maxLength={160} />
        <DateField label="Starts" required value={v.startDate} onChange={set('startDate')} />
        <DateField label="Ends" required value={v.endDate} onChange={set('endDate')} />
        <SelectInput label="Renewal" required value={v.renewalType} onChange={(x) => set('renewalType')(x as RenewalType)} options={options(renewalTypes)} />
        <NumberInput label="Renewal term (months)" value={v.renewalTermMonths} onChange={set('renewalTermMonths')} />
        <NumberInput label="Notice period (days)" value={v.noticeDays} onChange={set('noticeDays')} helperText="Without it no notice date can be calculated, and the agreement cannot be approved." />
        <DateField label="Rent review" value={v.rentReviewDate} onChange={set('rentReviewDate')} />
        <TextInput label="Finance reference" value={v.financeReference} onChange={set('financeReference')} maxLength={160} />
        {canSeeMoney && <NumberInput label="Annual rent" value={v.annualRent} onChange={set('annualRent')} />}
        {canSeeMoney && <NumberInput label="Deposit" value={v.depositAmount} onChange={set('depositAmount')} />}
        {canSeeMoney && <TextInput label="Currency" value={v.currency} onChange={(x) => set('currency')(x.toUpperCase())} maxLength={3} />}
      </div>
    </FormDialog>
  );
};

export const AmendmentDialog = ({ agreement, canSeeMoney, onClose, onDone }: Closeable & { agreement: Agreement; canSeeMoney: boolean }) => {
  const [v, setV] = useState({ kind: 'RENT_CHANGE' as AmendmentKind | '', newEndDate: '', newAnnualRent: '', newDepositAmount: '', newNoticeDays: '', newRentReviewDate: '', newRenewalTermMonths: '', effectiveOn: '', reason: '' });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof v>(key: K) => (value: (typeof v)[K]) => setV((current) => ({ ...current, [key]: value }));
  const kind = v.kind;
  return (
    <FormDialog open title="Propose an amendment" description={`To ${agreement.reference}. Nothing changes until an approver other than you approves it. If it touches the same terms as another open amendment it is held for legal review.`}
      submitLabel="Propose amendment" submitting={submitting} submitDisabled={!kind || !v.reason.trim() || (kind === 'TERMINATION' && !v.effectiveOn)} formError={error} onClose={onClose}
      onSubmit={() => void run(() => leaseApi.propose(agreement.id, {
        kind: kind as AmendmentKind, newEndDate: v.newEndDate || undefined, newAnnualRent: num(v.newAnnualRent), newDepositAmount: num(v.newDepositAmount),
        newNoticeDays: num(v.newNoticeDays), newRentReviewDate: v.newRentReviewDate || undefined, newRenewalTermMonths: num(v.newRenewalTermMonths),
        effectiveOn: v.effectiveOn || undefined, reason: v.reason.trim(),
      }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectInput label="Kind" required value={kind} onChange={(x) => set('kind')(x as AmendmentKind)} options={options(amendmentKinds)} className="sm:col-span-2" />
        {kind === 'RENT_CHANGE' && canSeeMoney && <NumberInput label="New annual rent" value={v.newAnnualRent} onChange={set('newAnnualRent')} />}
        {kind === 'RENT_CHANGE' && canSeeMoney && <NumberInput label="New deposit" value={v.newDepositAmount} onChange={set('newDepositAmount')} />}
        {kind === 'RENT_CHANGE' && !canSeeMoney && <p className="text-theme-sm text-gray-600 sm:col-span-2">Your role is not shown rent, so it cannot propose a rent change.</p>}
        {kind === 'TERM_CHANGE' && <DateField label="New end date" value={v.newEndDate} onChange={set('newEndDate')} />}
        {kind === 'TERM_CHANGE' && <NumberInput label="New notice period (days)" value={v.newNoticeDays} onChange={set('newNoticeDays')} />}
        {kind === 'TERM_CHANGE' && <DateField label="New rent review date" value={v.newRentReviewDate} onChange={set('newRentReviewDate')} />}
        {kind === 'RENEWAL' && <DateField label="New end date" value={v.newEndDate} onChange={set('newEndDate')} helperText={agreement.renewalTermMonths ? `Left blank: ${agreement.renewalTermMonths} months from the current end.` : 'Required: no renewal term is set.'} />}
        {kind === 'TERMINATION' && <DateField label="Takes effect on" required value={v.effectiveOn} onChange={set('effectiveOn')} helperText="Needs the termination notice filed before it can be approved." />}
        <TextAreaInput label="Reason" required value={v.reason} onChange={set('reason')} maxLength={2000} className="sm:col-span-2" />
      </div>
    </FormDialog>
  );
};

const sha256 = async (file: File): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
};

export const DocumentDialog = ({ agreement, initialKind, onClose, onDone }: Closeable & { agreement: Agreement; initialKind?: DocumentKind }) => {
  const [kind, setKind] = useState<DocumentKind | ''>(initialKind ?? 'APPROVAL_EVIDENCE');
  const [file, setFile] = useState<File | null>(null);
  const [filed, setFiled] = useState('');
  const [expires, setExpires] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="File a document" description={`For ${agreement.reference}. Approval evidence is needed before the agreement can be approved; a document with an expiry raises an alert when it lapses.`}
      submitLabel="File document" submitting={submitting} submitDisabled={!kind || !file || !filed.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(async () => {
        if (!file || !kind) return;
        await leaseApi.fileDocument(agreement.id, { kind, reference: filed.trim(), fileName: file.name, mediaType: file.type || 'application/octet-stream', sizeBytes: file.size, contentHash: await sha256(file), expiresOn: expires || undefined });
      }, onDone)}>
      <SelectInput label="What it is" required value={kind} onChange={(x) => setKind(x as DocumentKind)} options={options(documentKinds)} />
      <EvidenceFileField label="File" value={file} onChange={setFile} required />
      <TextInput label="Records-store reference" required value={filed} onChange={setFiled} maxLength={240} />
      <DateField label="Expires on" value={expires} onChange={setExpires} />
    </FormDialog>
  );
};

export const ObligationDialog = ({ agreement, onClose, onDone }: Closeable & { agreement: Agreement }) => {
  const [v, setV] = useState({ kind: 'INSURANCE' as ObligationKind | '', title: '', dueOn: '', ownerReference: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add an obligation" description={`To ${agreement.reference}. Notice, renewal and rent review obligations are generated from the terms; add insurance, compliance and others here. The owner is told as the date approaches.`}
      submitLabel="Add obligation" submitting={submitting} submitDisabled={!v.kind || !v.title.trim() || !v.dueOn} formError={error} onClose={onClose}
      onSubmit={() => void run(() => leaseApi.addObligation(agreement.id, { kind: v.kind as ObligationKind, title: v.title.trim(), dueOn: v.dueOn, ownerReference: v.ownerReference.trim() || undefined }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectInput label="Kind" required value={v.kind} onChange={(x) => setV((c) => ({ ...c, kind: x as ObligationKind }))} options={options(obligationKinds)} />
        <DateField label="Due on" required value={v.dueOn} onChange={(x) => setV((c) => ({ ...c, dueOn: x }))} />
        <TextInput label="Title" required value={v.title} onChange={(x) => setV((c) => ({ ...c, title: x }))} maxLength={240} className="sm:col-span-2" />
        <TextInput label="Owner" value={v.ownerReference} onChange={(x) => setV((c) => ({ ...c, ownerReference: x }))} maxLength={160} helperText={`Defaults to ${agreement.ownerReference ?? 'the agreement owner'}.`} />
      </div>
    </FormDialog>
  );
};

const weekdays = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export const CalendarDialog = ({ timezone, weekend, onClose, onDone }: Closeable & { timezone: string; weekend: string[] }) => {
  const [zone, setZone] = useState(timezone);
  const [days, setDays] = useState<string[]>(weekend);
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Calendar and timezone" description="Notice and renewal dates are worked out in this timezone and move back to the last business day. Changing it shifts every notice date in the portfolio at the next daily control."
      submitLabel="Save" submitting={submitting} submitDisabled={!zone.trim()} formError={error} onClose={onClose} onSubmit={() => void run(() => leaseApi.saveSettings(zone.trim(), days), onDone)}>
      <TextInput label="Timezone" required value={zone} onChange={setZone} maxLength={60} helperText="An IANA name, for example Africa/Accra." />
      <fieldset className="space-y-2"><legend className="text-sm font-medium">Weekend</legend>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">{weekdays.map((d) => <Checkbox key={d} checked={days.includes(d)} onChange={() => setDays(days.includes(d) ? days.filter((x) => x !== d) : [...days, d])} label={humanise(d)} />)}</div>
      </fieldset>
    </FormDialog>
  );
};

export const HolidayDialog = ({ onClose, onDone }: Closeable) => {
  const [date, setDate] = useState('');
  const [name, setName] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a holiday" description="A deadline that falls on a holiday moves back to the working day before it." submitLabel="Add holiday" submitting={submitting}
      submitDisabled={!date || !name.trim()} formError={error} onClose={onClose} onSubmit={() => void run(() => leaseApi.addHoliday(date, name.trim()), onDone)}>
      <DateField label="Date" required value={date} onChange={setDate} />
      <TextInput label="Name" required value={name} onChange={setName} maxLength={160} />
    </FormDialog>
  );
};

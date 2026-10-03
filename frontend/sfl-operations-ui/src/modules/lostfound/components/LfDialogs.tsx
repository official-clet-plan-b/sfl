import { useState } from 'react';
import { Checkbox, FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';
import EvidenceFileField from 'shared/components/EvidenceFileField';
import { useSubmit } from 'shared/hooks/useSubmit';
import { humanise } from 'modules/fleet/api/enums';
import {
  lostFoundApi,
  type Claim,
  type Configuration,
  type EvidenceKind,
  type FoundItem,
  type ItemCategory,
  type VerificationMethod,
} from '../api/lostFoundApi';
import { categories, evidenceKinds, options, secureCategories, verificationMethods } from './lfUi';

interface Closeable {
  onClose: () => void;
  onDone: () => void;
}

/** A destructive step with nothing to type: say what it does, and ask once. */
export const ConfirmDialog = ({ title, description, submitLabel, write, onClose, onDone }: Closeable & { title: string; description: string; submitLabel: string; write: () => Promise<unknown> }) => {
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title={title} description={description} submitLabel={submitLabel} destructive submitting={submitting} formError={error} onClose={onClose}
      onSubmit={() => void run(write, onDone)}>
      <p className="text-theme-sm text-gray-600">The service refuses it, in its own words, if a condition is not met.</p>
    </FormDialog>
  );
};

export const RegisterDialog = ({ siteCode, config, onClose, onDone }: Closeable & { siteCode: string; config: Configuration }) => {
  const [values, setValues] = useState({
    category: 'BAG' as ItemCategory | '', publicDescription: '', privateDescription: '', foundLocation: '', finderReference: '',
    initialCondition: '', unsafe: false, unsafeReason: '', storageLocationId: '',
  });
  const { submitting, error, run } = useSubmit();
  const set = <K extends keyof typeof values>(key: K) => (value: (typeof values)[K]) => setValues((current) => ({ ...current, [key]: value }));
  const secure = secureCategories.includes(values.category);
  const locations = config.locations.filter((l) => l.active && (!secure || l.secure));
  const invalid = !values.category || !values.publicDescription.trim() || !values.foundLocation.trim() || !values.finderReference.trim()
    || !values.initialCondition.trim() || (values.unsafe && !values.unsafeReason.trim());
  return (
    <FormDialog open title="Register found property" description={`At ${siteCode}. The claim reference given to the owner reveals nothing about the item.`}
      submitLabel="Register item" submitting={submitting} submitDisabled={invalid} formError={error} maxWidth="lg" onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.register({
        siteCode, category: values.category as ItemCategory, publicDescription: values.publicDescription.trim(),
        privateDescription: values.privateDescription.trim() || undefined, foundLocation: values.foundLocation.trim(),
        finderReference: values.finderReference.trim(), initialCondition: values.initialCondition.trim(), unsafe: values.unsafe,
        unsafeReason: values.unsafe ? values.unsafeReason.trim() : undefined, storageLocationId: values.unsafe ? undefined : values.storageLocationId || undefined,
      }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectInput label="Category" required value={values.category} onChange={(v) => set('category')(v as ItemCategory)} options={options(categories)}
          helperText={secure ? 'Kept in secure storage; its detail is private.' : undefined} />
        <TextInput label="Condition" required value={values.initialCondition} onChange={set('initialCondition')} maxLength={240} />
        <TextInput label="Public description" required value={values.publicDescription} onChange={set('publicDescription')} maxLength={240} className="sm:col-span-2"
          helperText="What a claimant may be told - for example 'black rucksack'. Nothing that identifies the owner." />
        <TextAreaInput label="Private detail" value={values.privateDescription} onChange={set('privateDescription')} maxLength={2000} className="sm:col-span-2"
          helperText="Contents, serial numbers, names. Only roles with private access see this; a claimant sees it only once verified." />
        <TextInput label="Found at" required value={values.foundLocation} onChange={set('foundLocation')} maxLength={240} />
        <TextInput label="Finder" required value={values.finderReference} onChange={set('finderReference')} maxLength={160} helperText="Staff reference or visitor record." />
        <SelectInput label="Store in" allowEmpty emptyLabel="Decide later" value={values.storageLocationId} onChange={set('storageLocationId')} disabled={values.unsafe}
          options={locations.map((l) => ({ value: l.id, label: `${l.name}${l.secure ? ' (secure)' : ''}` }))} />
        <div className="self-end pb-2"><Checkbox checked={values.unsafe} onChange={set('unsafe')} label="Unsafe or suspicious" hint="Isolates it and escalates to security." /></div>
        {values.unsafe && <TextInput label="Why it is unsafe" required value={values.unsafeReason} onChange={set('unsafeReason')} maxLength={1000} className="sm:col-span-2" />}
      </div>
    </FormDialog>
  );
};

export const StoreDialog = ({ item, config, onClose, onDone }: Closeable & { item: FoundItem; config: Configuration }) => {
  const [locationId, setLocationId] = useState('');
  const { submitting, error, run } = useSubmit();
  const secure = secureCategories.includes(item.category);
  return (
    <FormDialog open title="Place in storage" description={`${item.reference}. ${secure ? 'This category must go in secure storage.' : 'Recorded as a custody transfer.'}`}
      submitLabel="Store" submitting={submitting} submitDisabled={!locationId} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.store(item, locationId), onDone)}>
      <SelectInput label="Storage location" required value={locationId} onChange={setLocationId}
        options={config.locations.filter((l) => l.active && (!secure || l.secure)).map((l) => ({ value: l.id, label: `${l.name}${l.secure ? ' (secure)' : ''}` }))} />
    </FormDialog>
  );
};

export const TransferDialog = ({ item, onClose, onDone }: Closeable & { item: FoundItem }) => {
  const [values, setValues] = useState({ toParty: '', location: '', reason: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Transfer custody" description={`${item.reference}. The sender is whoever the chain says holds it now; every transfer needs a receiver, a place and a time.`}
      submitLabel="Record transfer" submitting={submitting} submitDisabled={!values.toParty.trim() || !values.location.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.transfer(item.id, { toParty: values.toParty.trim(), location: values.location.trim(), reason: values.reason.trim() || undefined }), onDone)}>
      <TextInput label="Receiver" required value={values.toParty} onChange={(v) => setValues((c) => ({ ...c, toParty: v }))} maxLength={160} />
      <TextInput label="Where" required value={values.location} onChange={(v) => setValues((c) => ({ ...c, location: v }))} maxLength={160} />
      <TextInput label="Reason" value={values.reason} onChange={(v) => setValues((c) => ({ ...c, reason: v }))} maxLength={500} />
    </FormDialog>
  );
};

export const ClaimDialog = ({ item, onClose, onDone }: Closeable & { item: FoundItem }) => {
  const [values, setValues] = useState({ claimantName: '', claimantContact: '', claimantDescription: '' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Receive a claim" description={`For ${item.reference}. The claimant's details are private and are erased a set time after the claim closes.`}
      submitLabel="Record claim" submitting={submitting} submitDisabled={!values.claimantName.trim() || !values.claimantContact.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.receiveClaim(item.id, { claimantName: values.claimantName.trim(), claimantContact: values.claimantContact.trim(), claimantDescription: values.claimantDescription.trim() || undefined }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Claimant" required value={values.claimantName} onChange={(v) => setValues((c) => ({ ...c, claimantName: v }))} maxLength={160} />
        <TextInput label="Contact" required value={values.claimantContact} onChange={(v) => setValues((c) => ({ ...c, claimantContact: v }))} maxLength={160} />
        <TextAreaInput label="What they say it is" value={values.claimantDescription} onChange={(v) => setValues((c) => ({ ...c, claimantDescription: v }))} maxLength={2000} className="sm:col-span-2"
          helperText="Compare with the private detail before verifying." />
      </div>
    </FormDialog>
  );
};

export const VerifyDialog = ({ claim, onClose, onDone }: Closeable & { claim: Claim }) => {
  const [method, setMethod] = useState<VerificationMethod | ''>('ID_DOCUMENT');
  const [reference, setReference] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Verify the claimant's identity" description={`${claim.reference}. Record how it was checked and where the check is filed - not the ID details themselves. Someone else must approve the release.`}
      submitLabel="Record verification" submitting={submitting} submitDisabled={!method || !reference.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.verify(claim, method as VerificationMethod, reference.trim()), onDone)}>
      <SelectInput label="Checked by" required value={method} onChange={(v) => setMethod(v as VerificationMethod)} options={options(verificationMethods)} />
      <TextInput label="Verification record" required value={reference} onChange={setReference} maxLength={160} helperText="The reference of the check in the records store." />
    </FormDialog>
  );
};

export const ReleaseDialog = ({ claim, onClose, onDone }: Closeable & { claim: Claim }) => {
  const [accepted, setAccepted] = useState(true);
  const [note, setNote] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Hand the item over" description={`${claim.reference}. Identity must be verified, the release approved by someone else, and a release receipt filed. If anything is missing the handover is refused and the reason recorded.`}
      submitLabel={accepted ? 'Release item' : 'Record refusal'} submitting={submitting} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.release(claim, accepted, note.trim() || undefined), onDone)}>
      <Checkbox checked={accepted} onChange={setAccepted} label="The claimant accepts the item" hint="Untick if they decline it at the desk; the claim closes and the item stays in store." />
      <TextAreaInput label="Note" value={note} onChange={setNote} maxLength={2000} />
    </FormDialog>
  );
};

const sha256 = async (file: File): Promise<string> => {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, '0')).join('');
};

/** Evidence is held by reference: pick the file to have its name, type, size and SHA-256 worked out here, and name where it is filed. */
export const EvidenceDialog = ({ item, claims, initialKind, onClose, onDone }: Closeable & { item: FoundItem; claims: Claim[]; initialKind?: EvidenceKind }) => {
  const [kind, setKind] = useState<EvidenceKind | ''>(initialKind ?? 'PHOTO');
  const [claimId, setClaimId] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [filed, setFiled] = useState('');
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="File evidence" description={`For ${item.reference}. Photographs and receipts can show people: only roles with private access can read them back, and every read is audited.`}
      submitLabel="File evidence" submitting={submitting} submitDisabled={!kind || !file || !filed.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(async () => {
        if (!file || !kind) return;
        await lostFoundApi.submitEvidence(item.id, { claimId: claimId || undefined, kind, reference: filed.trim(), fileName: file.name, mediaType: file.type || 'application/octet-stream', sizeBytes: file.size, contentHash: await sha256(file) });
      }, onDone)}>
      <SelectInput label="What it is" required value={kind} onChange={(v) => setKind(v as EvidenceKind)} options={options(evidenceKinds)} />
      {(kind === 'RELEASE_RECEIPT' || kind === 'VERIFICATION') && (
        <SelectInput label="For claim" required value={claimId} onChange={setClaimId} options={claims.filter((c) => c.status !== 'REFUSED' && c.status !== 'WITHDRAWN').map((c) => ({ value: c.id, label: `${c.reference} (${humanise(c.status)})` }))} />
      )}
      <EvidenceFileField label="File" value={file} onChange={setFile} required />
      <TextInput label="Records-store reference" required value={filed} onChange={setFiled} maxLength={240} />
    </FormDialog>
  );
};

export const LocationDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const [values, setValues] = useState({ code: '', name: '', secure: false });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Add a storage location" description={`At ${siteCode}. Documents, electronics, valuables and medical items must go in secure storage.`}
      submitLabel="Add location" submitting={submitting} submitDisabled={!values.code.trim() || !values.name.trim()} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.createLocation({ siteCode, code: values.code.trim(), name: values.name.trim(), secure: values.secure }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Code" required value={values.code} onChange={(v) => setValues((c) => ({ ...c, code: v }))} maxLength={40} />
        <TextInput label="Name" required value={values.name} onChange={(v) => setValues((c) => ({ ...c, name: v }))} maxLength={160} />
        <Checkbox checked={values.secure} onChange={(v) => setValues((c) => ({ ...c, secure: v }))} label="Secure storage" hint="Locked, with access controlled." />
      </div>
    </FormDialog>
  );
};

export const PolicyDialog = ({ siteCode, onClose, onDone }: Closeable & { siteCode: string }) => {
  const [values, setValues] = useState({ category: 'KEYS' as ItemCategory | '', unclaimedDays: '60', personalDataDays: '30' });
  const { submitting, error, run } = useSubmit();
  return (
    <FormDialog open title="Set a retention period" description="How long an unclaimed item of this category is kept, and how long a claimant's personal data is kept after their claim closes." submitLabel="Save"
      submitting={submitting} submitDisabled={!values.category || Number(values.unclaimedDays) <= 0 || Number(values.personalDataDays) <= 0} formError={error} onClose={onClose}
      onSubmit={() => void run(() => lostFoundApi.setPolicy({ siteCode, category: values.category as ItemCategory, unclaimedDays: Number(values.unclaimedDays), personalDataDays: Number(values.personalDataDays) }), onDone)}>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <SelectInput label="Category" required value={values.category} onChange={(v) => setValues((c) => ({ ...c, category: v as ItemCategory }))} options={options(categories)} />
        <NumberInput label="Keep unclaimed (days)" required value={values.unclaimedDays} onChange={(v) => setValues((c) => ({ ...c, unclaimedDays: v }))} />
        <NumberInput label="Keep claimant data (days)" required value={values.personalDataDays} onChange={(v) => setValues((c) => ({ ...c, personalDataDays: v }))} />
      </div>
    </FormDialog>
  );
};

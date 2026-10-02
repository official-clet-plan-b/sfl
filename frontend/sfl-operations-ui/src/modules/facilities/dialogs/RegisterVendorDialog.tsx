import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { RegisterVendorRequest } from '../api/dto';
import { DateField, FormDialog, NumberInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface RegisterVendorDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: RegisterVendorRequest) => Promise<void>;
}

/**
 * Registering a maintenance vendor.
 *
 * `externalVendorId` is the field worth understanding. It carries the procurement system's own
 * identifier for the same company, as a **value rather than a link** - this service does not own
 * supplier data and must not look like it does. When S153-04's procurement integration is built,
 * this is the seam it plugs into, and until then it is how somebody reconciles the two registers by
 * hand without guessing from company names.
 */
const RegisterVendorDialog = ({ siteCode, onClose, onSubmit }: RegisterVendorDialogProps) => {
  const [vendorCode, setVendorCode] = useState('');
  const [name, setName] = useState('');
  const [specialisation, setSpecialisation] = useState('');
  const [contactName, setContactName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [responseHours, setResponseHours] = useState('');
  const [contractReference, setContractReference] = useState('');
  const [contractExpiresOn, setContractExpiresOn] = useState('');
  const [externalVendorId, setExternalVendorId] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const missingCode = vendorCode.trim().length === 0;
  const missingName = name.trim().length === 0;
  const invalid = missingCode || missingName;

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
        vendorCode: vendorCode.trim(),
        name: name.trim(),
        specialisation: specialisation.trim() || null,
        contactName: contactName.trim() || null,
        contactEmail: contactEmail.trim() || null,
        contactPhone: contactPhone.trim() || null,
        responseHours: responseHours.trim() === '' ? null : Number(responseHours),
        contractReference: contractReference.trim() || null,
        contractExpiresOn: contractExpiresOn || null,
        externalVendorId: externalVendorId.trim() || null,
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
      title="Register a vendor"
      description={`A local reference for ${siteCode || 'this site'}, not the procurement master.`}
      submitLabel="Register"
      submitting={submitting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Vendor code"
            value={vendorCode}
            onChange={setVendorCode}
            onBlur={() => setTouched(true)}
            required
            maxLength={80}
            placeholder="e.g. ACME"
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
            placeholder="e.g. Acme Facilities Ltd"
            error={touched && missingName}
            helperText={touched && missingName ? 'A name is required.' : undefined}
          />
        </div>

        <TextInput
          label="Specialisation"
          value={specialisation}
          onChange={setSpecialisation}
          maxLength={200}
          placeholder="e.g. Generators and standby power"
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Contact"
            value={contactName}
            onChange={setContactName}
            maxLength={200}
          />
          <TextInput
            label="Phone"
            value={contactPhone}
            onChange={setContactPhone}
            maxLength={60}
          />
        </div>

        <TextInput
          label="Email"
          value={contactEmail}
          onChange={setContactEmail}
          maxLength={200}
          type="email"
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <NumberInput
            label="Contracted response (hours)"
            value={responseHours}
            onChange={setResponseHours}
            min={1}
            helperText="Where this is tighter than the priority’s SLA, it becomes the deadline."
          />
          <DateField
            label="Contract expires"
            value={contractExpiresOn}
            onChange={setContractExpiresOn}
            helperText="After this date, work cannot be assigned to them."
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Contract reference"
            value={contractReference}
            onChange={setContractReference}
            maxLength={120}
          />
          <TextInput
            label="Procurement ID"
            value={externalVendorId}
            onChange={setExternalVendorId}
            maxLength={120}
            helperText="Their identifier in the procurement system, if you have it."
          />
        </div>

      </div>
    </FormDialog>
  );
};

export default RegisterVendorDialog;

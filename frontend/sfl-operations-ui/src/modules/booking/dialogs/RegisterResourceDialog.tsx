import { useState } from 'react';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { humaniseCode } from 'modules/facilities/components/facilitiesFormat';
import type { RegisterResourceBody } from '../api/dto';
import { RESOURCE_CATEGORIES } from '../api/enums';
import type { ResourceCategory } from '../api/enums';
import { FormDialog, NumberInput, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface RegisterResourceDialogProps {
  onClose: () => void;
  onSubmit: (body: RegisterResourceBody) => Promise<void>;
}

/**
 * Register something that can be booked alongside a room.
 *
 * ## One row for forty chairs, not forty rows
 *
 * Quantity is the whole design. A furniture set is one record with a count, and availability is
 * arithmetic against what is already committed for the window. Registering forty chairs individually
 * would turn every availability question into forty, and no one would ever book the fortieth.
 *
 * ## What a quantity of exactly one buys
 *
 * It makes the resource **exclusive**, and exclusivity is enforced by the database rather than by
 * arithmetic - the same GIST exclusion constraint that stops two bookings taking one hall. That is
 * why the field says so: an operator entering `1` for a projector is choosing a stronger guarantee
 * than one entering `2`, and it is not obvious from the number.
 *
 * ## Why this is not the asset register
 *
 * An S152 asset is fixed plant whose condition feeds a space's readiness - a generator, a chiller. A
 * resource is portable and its scarcity is the point. The same physical projector can move between
 * the two over its life, which is why `assetId` links them as a value rather than a foreign key.
 */
const RegisterResourceDialog = ({ onClose, onSubmit }: RegisterResourceDialogProps) => {
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [resourceCode, setResourceCode] = useState('');
  const [name, setName] = useState('');
  const [category, setCategory] = useState<ResourceCategory>('PROJECTOR');
  const [description, setDescription] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [requiresSetup, setRequiresSetup] = useState('false');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const amount = Number(quantity);
  const quantityValid = Number.isInteger(amount) && amount >= 1;
  const incomplete = !resourceCode.trim() || !name.trim() || !siteCode || !quantityValid;

  const submit = async () => {
    if (incomplete) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        siteCode,
        resourceCode: resourceCode.trim(),
        name: name.trim(),
        category,
        description: description.trim() || null,
        quantity: amount,
        requiresSetup: requiresSetup === 'true',
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
      title="Register a bookable resource"
      description="Something portable that is booked alongside a room"
      submitLabel="Register it"
      submitting={submitting}
      submitDisabled={incomplete}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <SiteSelect value={siteCode} onChange={setSiteCode} required />
          <TextInput
            label="Resource code"
            value={resourceCode}
            onChange={setResourceCode}
            required
            maxLength={80}
            placeholder="PROJ-001"
            helperText="Unique within the site."
          />
        </div>

        <TextInput
          label="Name"
          value={name}
          onChange={setName}
          required
          maxLength={200}
          placeholder="Ceiling projector, portable"
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput
            label="Category"
            value={category}
            onChange={(value) => setCategory(value as ResourceCategory)}
            required
            options={RESOURCE_CATEGORIES.map((value) => ({ value, label: humaniseCode(value) }))}
          />
          <NumberInput
            label="Quantity"
            value={quantity}
            onChange={setQuantity}
            min={1}
            required
            error={quantity !== '' && !quantityValid}
            helperText={
              amount === 1
                ? 'One makes it exclusive - the database refuses a second booking of it.'
                : 'One row for a set, not one row per item.'
            }
          />
        </div>

        <SelectInput
          label="Needs setting up before use"
          value={requiresSetup}
          onChange={setRequiresSetup}
          required
          options={[
            { value: 'false', label: 'No - it is ready as it stands' },
            { value: 'true', label: 'Yes - raise a turnaround task when it is booked' },
          ]}
          helperText="A booking taking this resource raises a setup task automatically."
        />

        <TextAreaInput
          label="Description"
          value={description}
          onChange={setDescription}
          rows={2}
          maxLength={2000}
          placeholder="Where it lives, what it needs, anything the setup crew should know."
        />

      </div>
    </FormDialog>
  );
};

export default RegisterResourceDialog;

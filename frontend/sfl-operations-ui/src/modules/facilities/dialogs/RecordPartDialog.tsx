import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { RecordPartRequest } from '../api/dto';
import { FormDialog, NumberInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface RecordPartDialogProps {
  onClose: () => void;
  onSubmit: (request: RecordPartRequest) => Promise<void>;
}

/**
 * Recording a part fitted.
 *
 * ## Why the cost is optional
 *
 * A technician fitting a part from the van often does not know what it cost, and a mandatory field
 * somebody cannot answer is a field that gets a zero typed into it. A zero that means "unknown" is
 * worse than a blank, because it is indistinguishable from a part that was genuinely free, and every
 * report built on the column inherits the lie.
 *
 * ## What this is not
 *
 * Not a stores system. There is no stock level, no reorder point and no reservation, because CLET
 * has no inventory system for this to reconcile against and inventing one here would produce numbers
 * nobody maintains. What is recorded is what was fitted, how many, and what it cost if anybody knows
 * - enough for a job cost, not enough for procurement.
 */
const RecordPartDialog = ({ onClose, onSubmit }: RecordPartDialogProps) => {
  const [partCode, setPartCode] = useState('');
  const [description, setDescription] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [unitCost, setUnitCost] = useState('');
  const [supplier, setSupplier] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const missingCode = partCode.trim().length === 0;
  const missingDescription = description.trim().length === 0;
  const badQuantity = quantity.trim() === '' || Number(quantity) < 1;
  const invalid = missingCode || missingDescription || badQuantity;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        partCode: partCode.trim(),
        description: description.trim(),
        quantity: Number(quantity),
        unitCost: unitCost.trim() === '' ? null : Number(unitCost),
        currency: 'GHS',
        supplier: supplier.trim() || null,
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
      title="Record a part"
      description="What was fitted on this job."
      submitLabel="Record part"
      submitting={submitting}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="Part code"
          value={partCode}
          onChange={setPartCode}
          onBlur={() => setTouched(true)}
          required
          maxLength={80}
          placeholder="e.g. DOOR-STOP-MAG"
          error={touched && missingCode}
          helperText={touched && missingCode ? 'A part code is required.' : undefined}
        />

        <TextInput
          label="Description"
          value={description}
          onChange={setDescription}
          onBlur={() => setTouched(true)}
          required
          maxLength={400}
          placeholder="e.g. Magnetic hold-open, 24V"
          error={touched && missingDescription}
          helperText={touched && missingDescription ? 'A description is required.' : undefined}
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <NumberInput
            label="Quantity"
            value={quantity}
            onChange={setQuantity}
            onBlur={() => setTouched(true)}
            required
            min={1}
            error={touched && badQuantity}
            helperText={touched && badQuantity ? 'At least one.' : undefined}
          />
          <NumberInput
            label="Unit cost (GHS)"
            value={unitCost}
            onChange={setUnitCost}
            min={0}
            helperText="Leave blank if you do not know it. A guessed figure is worse than none."
          />
        </div>

        <TextInput
          label="Supplier"
          value={supplier}
          onChange={setSupplier}
          maxLength={200}
          placeholder="Who it came from"
        />
      </div>
    </FormDialog>
  );
};

export default RecordPartDialog;

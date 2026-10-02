import { useState } from 'react';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Building, CreateBuildingRequest, Site } from '../api/dto';
import { FormDialog, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface CreateBuildingDialogProps {
  site: Site;
  /** What is already on the site, so a duplicate code is caught before the service refuses it. */
  existingBuildings: Building[];
  onClose: () => void;
  onSubmit: (request: CreateBuildingRequest) => Promise<void>;
}

/**
 * Registering a building.
 *
 * Three fields, and the only one that carries a rule is the code: it is unique within the site, it
 * appears on signage and on every work order raised in the building, and it is not editable
 * afterwards through this dashboard. The service refuses a duplicate; checking here saves a round
 * trip and an error banner for something the screen already knows.
 *
 * **A building holds nothing until it has a floor**, because a space is placed on a floor rather than
 * in a building. Saying so here is cheaper than letting somebody register four buildings and then
 * discover they cannot put a room in any of them - which is why this dialog's caller navigates
 * straight to the new building rather than returning to the list.
 */
const CreateBuildingDialog = ({
  site,
  existingBuildings,
  onClose,
  onSubmit,
}: CreateBuildingDialogProps) => {
  const [buildingCode, setBuildingCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const trimmedCode = buildingCode.trim();
  const duplicate = existingBuildings.some(
    (building) => building.buildingCode.toLowerCase() === trimmedCode.toLowerCase(),
  );
  const missing = trimmedCode.length === 0 || name.trim().length === 0;
  const invalid = missing || duplicate;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        siteId: site.id,
        buildingCode: trimmedCode,
        name: name.trim(),
        description: description.trim() || null,
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
      title="Register a building"
      description={`${site.siteCode} - ${site.name}`}
      submitLabel="Register it"
      submitting={submitting}
      submitDisabled={invalid}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <TextInput
          label="Building code"
          value={buildingCode}
          onChange={setBuildingCode}
          onBlur={() => setTouched(true)}
          required
          maxLength={60}
          placeholder="MAIN"
          error={(touched && trimmedCode.length === 0) || duplicate}
          helperText={
            duplicate
              ? `${trimmedCode} already exists on this site.`
              : 'Unique within the site, and what appears on signage. It cannot be changed here later.'
          }
        />

        <TextInput
          label="Name"
          value={name}
          onChange={setName}
          onBlur={() => setTouched(true)}
          required
          maxLength={160}
          placeholder="Main Block"
          error={touched && name.trim().length === 0}
        />

        <TextAreaInput
          label="Description"
          value={description}
          onChange={setDescription}
          rows={2}
          maxLength={1000}
          placeholder="What it is used for, or how to find it."
        />

      </div>
    </FormDialog>
  );
};

export default CreateBuildingDialog;

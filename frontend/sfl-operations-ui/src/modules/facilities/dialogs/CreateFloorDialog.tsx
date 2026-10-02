import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { Building, CreateFloorRequest, Floor } from '../api/dto';
import { floorLabel } from '../components/facilitiesFormat';
import { FormDialog, NumberInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface CreateFloorDialogProps {
  building: Building;
  /** What is already there, so a duplicate code or level is caught before the service refuses it. */
  existingFloors: Floor[];
  onClose: () => void;
  onSubmit: (request: CreateFloorRequest) => Promise<void>;
}

/**
 * Registering a floor.
 *
 * ## Why the level number is optional and may be negative
 *
 * It is what floors sort by, and two real cases break a positive-integer-required field. A basement
 * is below ground and sorts below it, so the column is signed. A mezzanine is between two floors and
 * has no honest number at all, so the column is nullable - and a mezzanine forced to be `1` would
 * file itself above the first floor it sits inside.
 *
 * Floors with no level sort last, which is the service's ordering. This dialog does not invent one.
 *
 * ## Why a duplicate is caught here as well as there
 *
 * The service refuses a duplicate floor code within a building, and refusing at submit is a round
 * trip and an error banner for something the screen already knows. The check is a courtesy, not the
 * rule: the service is still the one that decides, and two people creating "GF" at the same moment
 * are separated there rather than here.
 */
const CreateFloorDialog = ({
  building,
  existingFloors,
  onClose,
  onSubmit,
}: CreateFloorDialogProps) => {
  const [floorCode, setFloorCode] = useState('');
  const [name, setName] = useState('');
  const [levelNumber, setLevelNumber] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const trimmedCode = floorCode.trim();
  const duplicateCode = existingFloors.some(
    (floor) => floor.floorCode.toLowerCase() === trimmedCode.toLowerCase(),
  );
  const levelTaken =
    levelNumber !== '' &&
    existingFloors.find((floor) => floor.levelNumber === Number(levelNumber));
  const missing = trimmedCode.length === 0 || name.trim().length === 0;
  const invalid = missing || duplicateCode;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        buildingId: building.id,
        floorCode: trimmedCode,
        name: name.trim(),
        // Empty means "no honest number", which is a mezzanine - not zero, which is the ground floor.
        levelNumber: levelNumber === '' ? null : Number(levelNumber),
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
      title="Add a floor"
      description={`${building.buildingCode} - ${building.name}`}
      submitLabel="Add it"
      submitting={submitting}
      submitDisabled={invalid}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Floor code"
            value={floorCode}
            onChange={setFloorCode}
            onBlur={() => setTouched(true)}
            required
            maxLength={60}
            placeholder="GF"
            error={(touched && trimmedCode.length === 0) || duplicateCode}
            helperText={
              duplicateCode
                ? `${trimmedCode} already exists in this building.`
                : 'Unique within the building.'
            }
          />
          <NumberInput
            label="Level number"
            value={levelNumber}
            onChange={setLevelNumber}
            // Signed: a basement is -1, and a field that refuses it would refuse a real floor.
            min={-20}
            max={200}
            placeholder="0 for ground"
            helperText={
              levelTaken
                ? `${floorLabel(levelTaken.levelNumber, levelTaken.floorCode)} is already at this level.`
                : 'Leave blank for a mezzanine. Blank sorts last.'
            }
          />
        </div>

        <TextInput
          label="Name"
          value={name}
          onChange={setName}
          onBlur={() => setTouched(true)}
          required
          maxLength={160}
          placeholder="Ground Floor"
          error={touched && name.trim().length === 0}
        />

        {levelTaken && (
          <Notice variant="warning" title="Another floor is already at this level">
            <p className="text-sm">
              {levelTaken.floorCode} - {levelTaken.name}. Two floors at one level is allowed and
              sometimes right, in a building with separate wings. It is worth checking it is what you
              mean.
            </p>
          </Notice>
        )}

        {existingFloors.length > 0 && (
          <p className="text-sm text-muted-foreground">
            Already here:{' '}
            {existingFloors
              .map((floor) => floorLabel(floor.levelNumber, floor.floorCode))
              .join(', ')}
          </p>
        )}
      </div>
    </FormDialog>
  );
};

export default CreateFloorDialog;

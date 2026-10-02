import { useEffect } from 'react';
import { Dropdown, Field, FieldControl, FieldDescription, FieldError, FieldLabel } from '@rfdtech/components';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { listBuildings, listFloors, listSpaces } from '../api/facilitiesApi';
import { floorLabel } from './facilitiesFormat';

/**
 * Choosing a place in the estate, out of the estate rather than by typing an identifier.
 *
 * <h2>Why these exist at all</h2>
 *
 * <p>`CreateRoom` takes a `floorId`, `RegisterAsset` and `RegisterDeviceReference` take a `roomId`.
 * All three are UUIDs an operator has never seen and could not produce. The playbook already
 * records this lesson from the fuel build - "offer real references, not identifier fields" - and the
 * same rule applies here: the dashboard must not be able to offer a parent the service will refuse.
 *
 * <h2>Clearing on the way down</h2>
 *
 * <p>A building belongs to a site and a floor to a building, so changing the site invalidates both
 * of the selections below it. They are cleared rather than left showing a stale label, because a
 * floor whose building is no longer selected still submits its id perfectly well - and would place a
 * new courtroom in a different centre without anything on screen looking wrong.
 */

interface FloorPickerProps {
  siteCode: string;
  buildingId: string;
  floorId: string;
  onBuildingChange: (buildingId: string) => void;
  onFloorChange: (floorId: string) => void;
  buildingError?: boolean;
  floorError?: boolean;
  floorHelperText?: string;
  onFloorBlur?: () => void;
}

/** Site → building → floor, for a space's parent. */
export const FloorPicker = ({
  siteCode,
  buildingId,
  floorId,
  onBuildingChange,
  onFloorChange,
  buildingError,
  floorError,
  floorHelperText,
  onFloorBlur,
}: FloorPickerProps) => {
  const buildings = useApiQuery(
    (signal) => (siteCode ? listBuildings(siteCode, signal) : Promise.resolve([])),
    [siteCode],
  );
  const floors = useApiQuery(
    (signal) => (buildingId ? listFloors(buildingId, signal) : Promise.resolve([])),
    [buildingId],
  );

  // The dependent selects are cleared here rather than in each caller's onChange, so a screen cannot
  // forget one of the two and leave a floor pointing into another centre.
  useEffect(() => {
    onBuildingChange('');
    onFloorChange('');
    // Only when the site itself changes; including the callbacks would clear on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [siteCode]);

  useEffect(() => {
    onFloorChange('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [buildingId]);

  const buildingOptions = (buildings.data ?? []).map((building) => ({
    value: building.id,
    label: `${building.buildingCode} · ${building.name}`,
  }));
  const floorOptions = (floors.data ?? []).map((floor) => ({
    value: floor.id,
    label: floorLabel(floor.levelNumber, floor.floorCode),
  }));

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <PickerField
        label="Building"
        value={buildingId}
        onChange={onBuildingChange}
        error={buildingError}
        disabled={!siteCode || buildings.loading || buildingOptions.length === 0}
        options={buildingOptions}
        helperText={emptyHint(siteCode, buildings.loading, buildingOptions.length, 'buildings')}
      />
      <PickerField
        label="Floor"
        value={floorId}
        onChange={onFloorChange}
        error={floorError}
        onBlur={onFloorBlur}
        disabled={!buildingId || floors.loading || floorOptions.length === 0}
        options={floorOptions}
        helperText={
          floorHelperText ??
          (buildingId
            ? emptyHint(buildingId, floors.loading, floorOptions.length, 'floors')
            : 'Choose a building first.')
        }
      />
    </div>
  );
};

interface SpacePickerProps {
  siteCode: string;
  value: string;
  onChange: (roomId: string) => void;
  label?: string;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  /** Adds a "not in a space" choice, for a record whose location is free text or nowhere yet. */
  allowEmpty?: boolean;
  emptyLabel?: string;
}

/**
 * A space in one site, for an asset's or a device's location.
 *
 * Optional at both call sites - `roomId` is nullable on `RegisterAsset` and
 * `RegisterDeviceReference`, because a standby generator in a yard and a camera on a perimeter fence
 * are both real and neither is in a room. That is what `locationCode` is for, and the dialogs offer
 * it alongside.
 */
export const SpacePicker = ({
  siteCode,
  value,
  onChange,
  label = 'Space',
  error,
  helperText,
  onBlur,
  allowEmpty = true,
  emptyLabel = 'Not in a space',
}: SpacePickerProps) => {
  const spaces = useApiQuery(
    (signal) => (siteCode ? listSpaces(siteCode, signal) : Promise.resolve([])),
    [siteCode],
  );

  useEffect(() => {
    onChange('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [siteCode]);

  const options = (spaces.data ?? []).map((space) => ({
    value: space.id,
    label: `${space.roomCode} · ${space.name}`,
  }));

  return (
    <PickerField
      label={label}
      value={value}
      onChange={onChange}
      error={error}
      onBlur={onBlur}
      emptyLabel={allowEmpty ? emptyLabel : undefined}
      disabled={!siteCode || spaces.loading}
      options={options}
      helperText={helperText ?? emptyHint(siteCode, spaces.loading, options.length, 'spaces')}
    />
  );
};

interface PickerFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  error?: boolean;
  disabled?: boolean;
  onBlur?: () => void;
  helperText?: string;
  /** Present when the field may be left unchosen; it is the placeholder a cleared field shows. */
  emptyLabel?: string;
}

/**
 * One labelled choice out of a loaded list.
 *
 * `required` is deliberately not a prop: the dialogs state it once for the whole form and validate
 * it themselves, so a marker on each label would only repeat it.
 */
const PickerField = ({
  label,
  value,
  onChange,
  options,
  error,
  disabled,
  onBlur,
  helperText,
  emptyLabel,
}: PickerFieldProps) => (
  // Focus leaves the field as a whole rather than the trigger alone: `Dropdown` takes no `onBlur`.
  <Field invalid={error} onBlur={onBlur}>
    <FieldLabel>{label}</FieldLabel>
    <FieldControl>
      <Dropdown
        aria-label={label}
        value={value || null}
        onValueChange={(next) => onChange(next ?? '')}
        options={options}
        placeholder={emptyLabel ?? `Select ${label.toLowerCase()}`}
        clearable={emptyLabel !== undefined}
        disabled={disabled}
        invalid={error}
      />
    </FieldControl>
    {error ? <FieldError>{helperText}</FieldError> : <FieldDescription>{helperText}</FieldDescription>}
  </Field>
);

/**
 * Why a dependent select is empty, in the operator's terms.
 *
 * An empty dropdown with no explanation is the failure this avoids: "there are none" and "you have
 * not chosen the thing above" look identical, and only one of them is the operator's to fix.
 */
const emptyHint = (
  parent: string,
  loading: boolean,
  count: number,
  plural: string,
): string | undefined => {
  if (!parent) {
    return `Choose a site to see its ${plural}.`;
  }
  if (loading) {
    return `Loading ${plural}…`;
  }
  return count === 0 ? `This site has no ${plural} registered yet.` : undefined;
};

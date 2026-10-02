import { Link } from 'react-router';
import { Banner } from '@rfdtech/components';
import SiteSelect from 'shared/components/SiteSelect';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, nonNegativeInteger, nonNegativeNumber, required } from 'shared/validation/validators';
import type { CreateSpaceRequest, Space, UpdateSpaceRequest } from '../api/dto';
import { spaceTypes } from '../api/enums';
import type { SpaceType } from '../api/enums';
import { FloorPicker } from '../components/estatePickers';
import { humaniseCode } from '../components/facilitiesFormat';
import { facilitiesPaths } from 'shared/layout/navigation';
import { StaleWriteNotice } from './common';
import { Checkbox, FormDialog, NumberInput, SelectInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * Creating and editing a space.
 *
 * <h2>The two flags are the whole point of the form</h2>
 *
 * <p>`bookable` and `examinationCapable` are declarations about what the room is *for*, and the
 * service combines each with lifecycle and readiness to derive `availableForBooking` and
 * `availableForExamination`. Those derived answers are not editable and are not offered here: a hall
 * can be examination-capable and still unusable this morning, and conflating the two is how a
 * registry books an examination into a room with an open critical blocker.
 *
 * <h2>Why a space is created from a floor and not from a site</h2>
 *
 * <p>`CreateRoom` takes a `floorId`, so the parent is a floor whatever the form looks like. The site
 * select above it is a filter on the pickers rather than a field on the request - it exists so an
 * operator narrows to their centre before choosing a building, and it is not submitted.
 */

interface CreateSpaceDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: CreateSpaceRequest) => Promise<void>;
}

export const CreateSpaceDialog = ({ siteCode, onClose, onSubmit }: CreateSpaceDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode,
      buildingId: '',
      floorId: '',
      roomCode: '',
      name: '',
      spaceType: 'OFFICE' as SpaceType,
      capacity: '',
      areaSqm: '',
      costCentre: '',
      bookable: true,
      examinationCapable: false,
    },
    schema: {
      siteCode: required('Site'),
      floorId: required('Floor'),
      roomCode: compose(required('Room code'), maxLength('Room code', 80)),
      name: compose(required('Name'), maxLength('Name', 160)),
      capacity: nonNegativeInteger('Capacity'),
      areaSqm: nonNegativeNumber('Floor area'),
      costCentre: maxLength('Cost centre', 60),
    },
    onSubmit: (values) =>
      onSubmit({
        floorId: values.floorId,
        roomCode: values.roomCode.trim(),
        name: values.name.trim(),
        spaceType: values.spaceType,
        capacity: values.capacity === '' ? null : Number(values.capacity),
        areaSqm: values.areaSqm === '' ? null : Number(values.areaSqm),
        costCentre: values.costCentre.trim() || null,
        bookable: values.bookable,
        examinationCapable: values.examinationCapable,
      }),
  });

  return (
    <FormDialog
      open
      title="Add a space"
      description="A room, hall or courtroom, on a floor of a building"
      submitLabel="Add the space"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <Banner
          variant="info"
          heading="Create the space’s parents first"
          subtext={
            <>
              Spaces belong to a floor, and floors belong to a building. Register the building and
              floor from the site record, then return here to add the room or hall.
              <Link className="ml-1 font-medium underline" to={facilitiesPaths.sites} onClick={onClose}>
                Open Sites
              </Link>
            </>
          }
        />
        <SiteSelect
          value={form.values.siteCode}
          onChange={(value) => form.setValue('siteCode', value)}
          required
          {...form.fieldProps('siteCode', 'Narrows the buildings below. Not part of the request.')}
        />

        <FloorPicker
          siteCode={form.values.siteCode}
          buildingId={form.values.buildingId}
          floorId={form.values.floorId}
          onBuildingChange={(value) => form.setValue('buildingId', value)}
          onFloorChange={(value) => form.setValue('floorId', value)}
          floorError={Boolean(form.errors.floorId)}
          floorHelperText={form.errors.floorId}
          onFloorBlur={() => form.blur('floorId')}
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Room code"
            value={form.values.roomCode}
            onChange={(value) => form.setValue('roomCode', value)}
            required
            maxLength={80}
            placeholder="HALL-C"
            {...form.fieldProps('roomCode', 'Unique within the site, and what signage shows.')}
          />
          <TextInput
            label="Name"
            value={form.values.name}
            onChange={(value) => form.setValue('name', value)}
            required
            maxLength={160}
            placeholder="Examination Hall C"
            {...form.fieldProps('name')}
          />
        </div>

        <SpaceAttributes
          spaceType={form.values.spaceType}
          capacity={form.values.capacity}
          areaSqm={form.values.areaSqm}
          costCentre={form.values.costCentre}
          bookable={form.values.bookable}
          examinationCapable={form.values.examinationCapable}
          onChange={(patch) => form.setValues(patch)}
          fieldProps={(field, hint) => form.fieldProps(field as never, hint)}
        />

      </div>
    </FormDialog>
  );
};

interface EditSpaceDialogProps {
  space: Space;
  onClose: () => void;
  onSubmit: (request: UpdateSpaceRequest) => Promise<void>;
}

/**
 * Editing a space.
 *
 * The floor is not offered: `UpdateRoom` has no field for it, because moving a room between floors
 * is not something a building does. The code is fixed for the same reason a site code is - assets,
 * devices, bookings and assessments all refer to it.
 */
export const EditSpaceDialog = ({ space, onClose, onSubmit }: EditSpaceDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      name: space.name,
      spaceType: space.spaceType,
      capacity: space.capacity === null ? '' : String(space.capacity),
      areaSqm: space.areaSqm === null ? '' : String(space.areaSqm),
      costCentre: space.costCentre ?? '',
      bookable: space.bookable,
      examinationCapable: space.examinationCapable,
    },
    schema: {
      name: compose(required('Name'), maxLength('Name', 160)),
      capacity: nonNegativeInteger('Capacity'),
      areaSqm: nonNegativeNumber('Floor area'),
      costCentre: maxLength('Cost centre', 60),
    },
    onSubmit: (values) =>
      onSubmit({
        name: values.name.trim(),
        spaceType: values.spaceType,
        capacity: values.capacity === '' ? null : Number(values.capacity),
        areaSqm: values.areaSqm === '' ? null : Number(values.areaSqm),
        costCentre: values.costCentre.trim() || null,
        bookable: values.bookable,
        examinationCapable: values.examinationCapable,
        expectedVersion: space.metadata.version,
      }),
  });

  return (
    <FormDialog
      open
      title={`Edit ${space.roomCode}`}
      description={`On ${space.siteCode}. Readiness and the lock are changed from the space itself.`}
      submitLabel="Save changes"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <TextInput
          label="Name"
          value={form.values.name}
          onChange={(value) => form.setValue('name', value)}
          required
          maxLength={160}
          {...form.fieldProps('name')}
        />

        <SpaceAttributes
          spaceType={form.values.spaceType}
          capacity={form.values.capacity}
          areaSqm={form.values.areaSqm}
          costCentre={form.values.costCentre}
          bookable={form.values.bookable}
          examinationCapable={form.values.examinationCapable}
          onChange={(patch) => form.setValues(patch)}
          fieldProps={(field, hint) => form.fieldProps(field as never, hint)}
        />

        <StaleWriteNotice error={form.formError} />
      </div>
    </FormDialog>
  );
};

/**
 * The attributes both dialogs share.
 *
 * One component rather than two copies, because a create form and an edit form that disagree about
 * what a space has is how a field ends up settable in one place and invisible in the other. Plain
 * props rather than the form object: threading a generic `useFleetForm` through would need a cast at
 * every field, and a cast on a field name is exactly the thing a compiler is here to catch.
 */
interface SpaceAttributeValues {
  spaceType: SpaceType;
  capacity: string;
  areaSqm: string;
  costCentre: string;
  bookable: boolean;
  examinationCapable: boolean;
}

interface SpaceAttributesProps extends SpaceAttributeValues {
  onChange: (patch: Partial<SpaceAttributeValues>) => void;
  fieldProps: (
    field: string,
    hint?: string,
  ) => { error: boolean; helperText: string | undefined; onBlur: () => void };
}

const SpaceAttributes = ({
  spaceType,
  capacity,
  areaSqm,
  costCentre,
  bookable,
  examinationCapable,
  onChange,
  fieldProps,
}: SpaceAttributesProps) => (
  <>
    <div className="grid gap-4 sm:grid-cols-2">
      <SelectInput
        label="Space type"
        value={spaceType}
        onChange={(value) => onChange({ spaceType: value as SpaceType })}
        required
        options={spaceTypes.map((value) => ({ value, label: humaniseCode(value) }))}
        helperText="Decides which readiness checklist an assessment resolves to."
      />
      <NumberInput
        label="Capacity"
        value={capacity}
        onChange={(value) => onChange({ capacity: value })}
        min={0}
        {...fieldProps('capacity', 'People, at examination spacing.')}
      />
    </div>

    <div className="grid gap-4 sm:grid-cols-2">
      <NumberInput
        label="Floor area"
        value={areaSqm}
        onChange={(value) => onChange({ areaSqm: value })}
        min={0}
        step={0.01}
        suffix="m²"
        {...fieldProps('areaSqm')}
      />
      <TextInput
        label="Cost centre"
        value={costCentre}
        onChange={(value) => onChange({ costCentre: value })}
        maxLength={60}
        {...fieldProps('costCentre')}
      />
    </div>

    <div className="space-y-3 rounded-lg border border-border bg-surface-muted/20 px-4 py-3">
      <Checkbox
        checked={bookable}
        onChange={(checked) => onChange({ bookable: checked })}
        label="Can be booked"
        hint="Offered in the booking diary, subject to its readiness on the day."
      />
      <Checkbox
        checked={examinationCapable}
        onChange={(checked) => onChange({ examinationCapable: checked })}
        label="Can hold an examination"
        hint="Assessed against the stricter examination checklist while the centre is in that mode."
      />
    </div>
  </>
);

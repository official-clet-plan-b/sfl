import SiteSelect from 'shared/components/SiteSelect';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import type {
  DeviceReference,
  RegisterDeviceReferenceRequest,
  UpdateDeviceReferenceRequest,
} from '../api/dto';
import { deviceReferenceTypes } from '../api/enums';
import type { DeviceReferenceType } from '../api/enums';
import { SpacePicker } from '../components/estatePickers';
import { humaniseCode } from '../components/facilitiesFormat';
import { StaleWriteNotice } from './common';
import { FormDialog, SelectInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * Registering a device reference.
 *
 * <h2>What is being registered, and what is not</h2>
 *
 * <p>The facilities register does not run cameras, readers or panels. It owns *where each one is*, so
 * that a CCTV event, an access denial or a fire alarm can be placed in a space and a zone without
 * every consuming system inventing its own device inventory. So this form has no status field: the
 * vendor feed supplies that, and a record created here starts at `UNKNOWN` until the feed says
 * otherwise. Offering a status would let an operator assert something only the vendor system knows.
 *
 * <p>`externalReference` is the vendor's own identifier for the same device, carried as a value
 * rather than a link. It is how somebody reconciles the two inventories by hand today, and it is the
 * seam the vendor integration plugs into later.
 */

interface RegisterDeviceDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: RegisterDeviceReferenceRequest) => Promise<void>;
}

export const RegisterDeviceDialog = ({
  siteCode,
  onClose,
  onSubmit,
}: RegisterDeviceDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode,
      deviceCode: '',
      name: '',
      type: 'CCTV_CAMERA' as DeviceReferenceType,
      roomId: '',
      locationCode: '',
      vendor: '',
      externalReference: '',
    },
    schema: {
      siteCode: required('Site'),
      deviceCode: compose(required('Device code'), maxLength('Device code', 80)),
      name: compose(required('Name'), maxLength('Name', 160)),
      locationCode: maxLength('Location', 120),
      vendor: maxLength('Vendor', 160),
      externalReference: maxLength('Vendor reference', 160),
    },
    onSubmit: (values) =>
      onSubmit({
        siteCode: values.siteCode,
        deviceCode: values.deviceCode.trim(),
        name: values.name.trim(),
        type: values.type,
        roomId: values.roomId || null,
        locationCode: values.locationCode.trim() || null,
        vendor: values.vendor.trim() || null,
        externalReference: values.externalReference.trim() || null,
      }),
  });

  return (
    <FormDialog
      open
      title="Register a device reference"
      description="Where a vendor-operated device sits on this estate"
      submitLabel="Register it"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <SiteSelect
            value={form.values.siteCode}
            onChange={(value) => form.setValue('siteCode', value)}
            required
            {...form.fieldProps('siteCode')}
          />
          <TextInput
            label="Device code"
            value={form.values.deviceCode}
            onChange={(value) => form.setValue('deviceCode', value)}
            required
            maxLength={80}
            placeholder="CAM-014"
            {...form.fieldProps('deviceCode', 'Unique within the site.')}
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Name"
            value={form.values.name}
            onChange={(value) => form.setValue('name', value)}
            required
            maxLength={160}
            placeholder="Main entrance, external"
            {...form.fieldProps('name')}
          />
          <SelectInput
            label="Device type"
            value={form.values.type}
            onChange={(value) => form.setValue('type', value as DeviceReferenceType)}
            required
            options={deviceReferenceTypes.map((value) => ({ value, label: humaniseCode(value) }))}
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <SpacePicker
            siteCode={form.values.siteCode}
            value={form.values.roomId}
            onChange={(value) => form.setValue('roomId', value)}
            label="In which space"
            emptyLabel="Not in a space"
          />
          <TextInput
            label="Or where exactly"
            value={form.values.locationCode}
            onChange={(value) => form.setValue('locationCode', value)}
            maxLength={120}
            placeholder="Perimeter fence, east"
            {...form.fieldProps(
              'locationCode',
              'For a device on a fence, a roof or a corridor rather than in a room.',
            )}
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Vendor"
            value={form.values.vendor}
            onChange={(value) => form.setValue('vendor', value)}
            maxLength={160}
            placeholder="Who operates it"
            {...form.fieldProps('vendor')}
          />
          <TextInput
            label="Vendor reference"
            value={form.values.externalReference}
            onChange={(value) => form.setValue('externalReference', value)}
            maxLength={160}
            {...form.fieldProps(
              'externalReference',
              'Their identifier for the same device, if you have it.',
            )}
          />
        </div>

      </div>
    </FormDialog>
  );
};

interface EditDeviceDialogProps {
  device: DeviceReference;
  onClose: () => void;
  onSubmit: (request: UpdateDeviceReferenceRequest) => Promise<void>;
}

/**
 * Correcting a device reference.
 *
 * <p>The code and the site are not offered - other records refer to this one by them - and neither is
 * the status, which belongs to the vendor feed. What is left is the descriptive half this service
 * owns, which is exactly the half that gets typed wrong on registration and had no way to be fixed
 * until `PATCH /device-references/{deviceId}` existed.
 *
 * <p>The location is also absent: it is a relocation, with its own consequence, and it is reached
 * from the register rather than folded into a name correction.
 */
export const EditDeviceDialog = ({ device, onClose, onSubmit }: EditDeviceDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      name: device.name,
      type: device.type,
      vendor: device.vendor ?? '',
      externalReference: device.externalReference ?? '',
    },
    schema: {
      name: compose(required('Name'), maxLength('Name', 160)),
      vendor: maxLength('Vendor', 160),
      externalReference: maxLength('Vendor reference', 160),
    },
    onSubmit: (values) =>
      onSubmit({
        name: values.name.trim(),
        type: values.type,
        vendor: values.vendor.trim() || null,
        externalReference: values.externalReference.trim() || null,
        expectedVersion: device.metadata.version,
      }),
  });

  return (
    <FormDialog
      open
      title={`Edit ${device.deviceCode}`}
      description={`On ${device.siteCode}. Its reported status comes from the vendor feed.`}
      submitLabel="Save changes"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Name"
            value={form.values.name}
            onChange={(value) => form.setValue('name', value)}
            required
            maxLength={160}
            {...form.fieldProps('name')}
          />
          <SelectInput
            label="Device type"
            value={form.values.type}
            onChange={(value) => form.setValue('type', value as DeviceReferenceType)}
            required
            options={deviceReferenceTypes.map((value) => ({ value, label: humaniseCode(value) }))}
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <TextInput
            label="Vendor"
            value={form.values.vendor}
            onChange={(value) => form.setValue('vendor', value)}
            maxLength={160}
            {...form.fieldProps('vendor')}
          />
          <TextInput
            label="Vendor reference"
            value={form.values.externalReference}
            onChange={(value) => form.setValue('externalReference', value)}
            maxLength={160}
            {...form.fieldProps('externalReference')}
          />
        </div>

        <StaleWriteNotice error={form.formError} />
      </div>
    </FormDialog>
  );
};

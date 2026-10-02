import SiteSelect from 'shared/components/SiteSelect';
import { Notice } from '@rfdtech/components';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, integerAtLeast, maxLength, required } from 'shared/validation/validators';
import type { FacilityAsset, RegisterAssetRequest, RelocateAssetRequest, UpdateAssetRequest } from '../api/dto';
import { assetCategories, assetCriticalities } from '../api/enums';
import type { AssetCategory, AssetCriticality } from '../api/enums';
import { SpacePicker } from '../components/estatePickers';
import { humaniseCode } from '../components/facilitiesFormat';
import { StaleWriteNotice } from './common';
import { DateField, FormDialog, NumberInput, SelectInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * Registering, editing and moving a facility asset.
 *
 * <h2>Criticality is the field that decides what breaks</h2>
 *
 * <p>It sets the ceiling on the readiness blocker a failure raises: a critical asset out of service
 * raises a `CRITICAL` blocker, which is the one thing that forbids a space being `READY`, and a low
 * one raises an advisory that changes nothing. The dialog says so at the point of choosing, because
 * the consequence lands months later on somebody else - an examination hall closed on the morning of
 * an examination - and by then nobody remembers who typed which word into this form.
 *
 * <h2>Location is either a space or a description, and often has to be the second</h2>
 *
 * <p>`roomId` is nullable and `locationCode` exists beside it because a standby generator in a yard
 * and a chiller on a roof are both real assets that are in no room at all. Offering only the space
 * picker would force somebody to file them in the nearest room, which is worse than saying where
 * they actually are.
 */

interface RegisterAssetDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (request: RegisterAssetRequest) => Promise<void>;
}

export const RegisterAssetDialog = ({ siteCode, onClose, onSubmit }: RegisterAssetDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode,
      assetCode: '',
      name: '',
      category: 'HVAC' as AssetCategory,
      criticality: 'MEDIUM' as AssetCriticality,
      roomId: '',
      locationCode: '',
      manufacturer: '',
      modelNumber: '',
      serialNumber: '',
      installedOn: '',
      warrantyExpiresOn: '',
      serviceIntervalDays: '',
      custodian: '',
    },
    schema: {
      siteCode: required('Site'),
      assetCode: compose(required('Asset code'), maxLength('Asset code', 80)),
      name: compose(required('Name'), maxLength('Name', 200)),
      locationCode: maxLength('Location', 120),
      manufacturer: maxLength('Manufacturer', 160),
      modelNumber: maxLength('Model', 120),
      serialNumber: maxLength('Serial number', 120),
      serviceIntervalDays: integerAtLeast('Service interval', 1),
      custodian: maxLength('Custodian', 160),
    },
    onSubmit: (values) =>
      onSubmit({
        siteCode: values.siteCode,
        assetCode: values.assetCode.trim(),
        name: values.name.trim(),
        category: values.category,
        criticality: values.criticality,
        roomId: values.roomId || null,
        locationCode: values.locationCode.trim() || null,
        manufacturer: values.manufacturer.trim() || null,
        modelNumber: values.modelNumber.trim() || null,
        serialNumber: values.serialNumber.trim() || null,
        installedOn: values.installedOn || null,
        warrantyExpiresOn: values.warrantyExpiresOn || null,
        serviceIntervalDays:
          values.serviceIntervalDays === '' ? null : Number(values.serviceIntervalDays),
        custodian: values.custodian.trim() || null,
      }),
  });

  return (
    <FormDialog
      open
      title="Register an asset"
      description="Fixed plant, and what its condition does to the space it serves"
      submitLabel="Register it"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
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
            label="Asset code"
            value={form.values.assetCode}
            onChange={(value) => form.setValue('assetCode', value)}
            required
            maxLength={80}
            placeholder="GEN-01"
            {...form.fieldProps('assetCode', 'Unique within the site.')}
          />
        </div>

        <TextInput
          label="Name"
          value={form.values.name}
          onChange={(value) => form.setValue('name', value)}
          required
          maxLength={200}
          placeholder="Standby generator, north yard"
          {...form.fieldProps('name')}
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput
            label="Category"
            value={form.values.category}
            onChange={(value) => form.setValue('category', value as AssetCategory)}
            required
            options={assetCategories.map((value) => ({ value, label: humaniseCode(value) }))}
          />
          <SelectInput
            label="Criticality"
            value={form.values.criticality}
            onChange={(value) => form.setValue('criticality', value as AssetCriticality)}
            required
            options={assetCriticalities.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText={criticalityHint(form.values.criticality)}
          />
        </div>

        <AssetLocationFields
          siteCode={form.values.siteCode}
          roomId={form.values.roomId}
          locationCode={form.values.locationCode}
          onRoomChange={(value) => form.setValue('roomId', value)}
          onLocationChange={(value) => form.setValue('locationCode', value)}
        />

        <div className="grid gap-4 sm:grid-cols-3">
          <TextInput
            label="Manufacturer"
            value={form.values.manufacturer}
            onChange={(value) => form.setValue('manufacturer', value)}
            maxLength={160}
            {...form.fieldProps('manufacturer')}
          />
          <TextInput
            label="Model"
            value={form.values.modelNumber}
            onChange={(value) => form.setValue('modelNumber', value)}
            maxLength={120}
            {...form.fieldProps('modelNumber')}
          />
          <TextInput
            label="Serial number"
            value={form.values.serialNumber}
            onChange={(value) => form.setValue('serialNumber', value)}
            maxLength={120}
            {...form.fieldProps('serialNumber')}
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          <DateField
            label="Installed"
            value={form.values.installedOn}
            onChange={(value) => form.setValue('installedOn', value)}
            helperText="Where there is no service history, the first service is due from this."
          />
          <DateField
            label="Warranty expires"
            value={form.values.warrantyExpiresOn}
            onChange={(value) => form.setValue('warrantyExpiresOn', value)}
          />
          <NumberInput
            label="Service interval"
            value={form.values.serviceIntervalDays}
            onChange={(value) => form.setValue('serviceIntervalDays', value)}
            min={1}
            suffix="days"
            {...form.fieldProps('serviceIntervalDays')}
          />
        </div>

        <TextInput
          label="Custodian"
          value={form.values.custodian}
          onChange={(value) => form.setValue('custodian', value)}
          maxLength={160}
          {...form.fieldProps('custodian', 'Who is answerable for it day to day.')}
        />

      </div>
    </FormDialog>
  );
};

interface EditAssetDialogProps {
  asset: FacilityAsset;
  onClose: () => void;
  onSubmit: (request: UpdateAssetRequest) => Promise<void>;
}

/**
 * Editing an asset's attributes.
 *
 * Neither the location nor the operational status is here, and both omissions are the service's:
 * `UpdateAsset` carries neither, because each has its own endpoint and its own consequence.
 * Relocating recomputes the readiness of two spaces, and changing the status recomputes one - side
 * effects that should not ride along with a corrected serial number.
 */
export const EditAssetDialog = ({ asset, onClose, onSubmit }: EditAssetDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      name: asset.name,
      category: asset.category,
      criticality: asset.criticality,
      manufacturer: asset.manufacturer ?? '',
      modelNumber: asset.modelNumber ?? '',
      serialNumber: asset.serialNumber ?? '',
      warrantyExpiresOn: asset.warrantyExpiresOn ?? '',
      serviceIntervalDays:
        asset.serviceIntervalDays === null ? '' : String(asset.serviceIntervalDays),
      custodian: asset.custodian ?? '',
    },
    schema: {
      name: compose(required('Name'), maxLength('Name', 200)),
      manufacturer: maxLength('Manufacturer', 160),
      modelNumber: maxLength('Model', 120),
      serialNumber: maxLength('Serial number', 120),
      serviceIntervalDays: integerAtLeast('Service interval', 1),
      custodian: maxLength('Custodian', 160),
    },
    onSubmit: (values) =>
      onSubmit({
        name: values.name.trim(),
        category: values.category,
        criticality: values.criticality,
        manufacturer: values.manufacturer.trim() || null,
        modelNumber: values.modelNumber.trim() || null,
        serialNumber: values.serialNumber.trim() || null,
        warrantyExpiresOn: values.warrantyExpiresOn || null,
        serviceIntervalDays:
          values.serviceIntervalDays === '' ? null : Number(values.serviceIntervalDays),
        custodian: values.custodian.trim() || null,
        expectedVersion: asset.metadata.version,
      }),
  });

  const raisingCriticality =
    assetCriticalities.indexOf(form.values.criticality) < assetCriticalities.indexOf(asset.criticality);

  return (
    <FormDialog
      open
      title={`Edit ${asset.assetCode}`}
      description="Its condition and its location are changed from the asset itself."
      submitLabel="Save changes"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <TextInput
          label="Name"
          value={form.values.name}
          onChange={(value) => form.setValue('name', value)}
          required
          maxLength={200}
          {...form.fieldProps('name')}
        />

        <div className="grid gap-4 sm:grid-cols-2">
          <SelectInput
            label="Category"
            value={form.values.category}
            onChange={(value) => form.setValue('category', value as AssetCategory)}
            required
            options={assetCategories.map((value) => ({ value, label: humaniseCode(value) }))}
          />
          <SelectInput
            label="Criticality"
            value={form.values.criticality}
            onChange={(value) => form.setValue('criticality', value as AssetCriticality)}
            required
            options={assetCriticalities.map((value) => ({ value, label: humaniseCode(value) }))}
            helperText={criticalityHint(form.values.criticality)}
          />
        </div>

        {raisingCriticality && asset.impairsReadiness && (
          <Notice variant="warning" title="This asset is already impairing its space">
            <p className="text-sm">
              Raising its criticality raises the severity of the blocker it is holding open, and a
              critical one is what forbids the space being ready. The change takes effect the next
              time its readiness is evaluated.
            </p>
          </Notice>
        )}

        <div className="grid gap-4 sm:grid-cols-3">
          <TextInput
            label="Manufacturer"
            value={form.values.manufacturer}
            onChange={(value) => form.setValue('manufacturer', value)}
            maxLength={160}
            {...form.fieldProps('manufacturer')}
          />
          <TextInput
            label="Model"
            value={form.values.modelNumber}
            onChange={(value) => form.setValue('modelNumber', value)}
            maxLength={120}
            {...form.fieldProps('modelNumber')}
          />
          <TextInput
            label="Serial number"
            value={form.values.serialNumber}
            onChange={(value) => form.setValue('serialNumber', value)}
            maxLength={120}
            {...form.fieldProps('serialNumber')}
          />
        </div>

        <div className="grid gap-4 sm:grid-cols-3">
          <DateField
            label="Warranty expires"
            value={form.values.warrantyExpiresOn}
            onChange={(value) => form.setValue('warrantyExpiresOn', value)}
          />
          <NumberInput
            label="Service interval"
            value={form.values.serviceIntervalDays}
            onChange={(value) => form.setValue('serviceIntervalDays', value)}
            min={1}
            suffix="days"
            {...form.fieldProps('serviceIntervalDays')}
          />
          <TextInput
            label="Custodian"
            value={form.values.custodian}
            onChange={(value) => form.setValue('custodian', value)}
            maxLength={160}
            {...form.fieldProps('custodian')}
          />
        </div>

        <StaleWriteNotice error={form.formError} />
      </div>
    </FormDialog>
  );
};

interface RelocateAssetDialogProps {
  asset: FacilityAsset;
  onClose: () => void;
  onSubmit: (request: RelocateAssetRequest) => Promise<void>;
}

/**
 * Moving an asset.
 *
 * Its own dialog because it has its own consequence: the readiness of the space it leaves and the
 * space it arrives in are both recomputed, so a critical chiller moving out of an examination hall
 * can clear a blocker there and raise one somewhere else in the same call.
 */
export const RelocateAssetDialog = ({ asset, onClose, onSubmit }: RelocateAssetDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      roomId: asset.roomId ?? '',
      locationCode: asset.locationCode ?? '',
    },
    schema: { locationCode: maxLength('Location', 120) },
    onSubmit: (values) =>
      onSubmit({
        roomId: values.roomId || null,
        locationCode: values.locationCode.trim() || null,
        expectedVersion: asset.metadata.version,
      }),
  });

  return (
    <FormDialog
      open
      title={`Move ${asset.assetCode}`}
      description="Where it is, and therefore whose readiness it affects"
      submitLabel="Move it"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={() => void form.submit()}
    >
      <div className="space-y-4">
        <AssetLocationFields
          siteCode={asset.siteCode}
          roomId={form.values.roomId}
          locationCode={form.values.locationCode}
          onRoomChange={(value) => form.setValue('roomId', value)}
          onLocationChange={(value) => form.setValue('locationCode', value)}
        />

        {asset.impairsReadiness && (
          <Notice variant="warning" title="This asset is impairing its current space">
            <p className="text-sm">
              Moving it clears the blocker it is holding on {asset.roomId ? 'that space' : 'its site'}{' '}
              and raises the equivalent one wherever it lands. Both spaces are re-evaluated as part of
              this change.
            </p>
          </Notice>
        )}

        <StaleWriteNotice error={form.formError} />
      </div>
    </FormDialog>
  );
};

/** The space-or-description pair, identical in three dialogs. */
const AssetLocationFields = ({
  siteCode,
  roomId,
  locationCode,
  onRoomChange,
  onLocationChange,
}: {
  siteCode: string;
  roomId: string;
  locationCode: string;
  onRoomChange: (value: string) => void;
  onLocationChange: (value: string) => void;
}) => (
  <div className="grid gap-4 sm:grid-cols-2">
    <SpacePicker
      siteCode={siteCode}
      value={roomId}
      onChange={onRoomChange}
      label="In which space"
      emptyLabel="Not in a space"
    />
    <TextInput
      label="Or where exactly"
      value={locationCode}
      onChange={onLocationChange}
      maxLength={120}
      placeholder="North yard, plant compound"
      helperText="For plant that is in no room - a yard, a roof, a riser."
    />
  </div>
);

/**
 * What choosing this criticality will mean later.
 *
 * Mirrors `ReadinessApplicationService.severityFor`, which is also transcribed in
 * `assetBlockerSeverity`. Said here in words rather than as a severity name, because the person
 * filling in this form is deciding what happens to a hall, not naming an enum value.
 */
const criticalityHint = (criticality: AssetCriticality): string => {
  switch (criticality) {
    case 'CRITICAL':
      return 'Out of service, this blocks its space outright - no booking, no examination.';
    case 'HIGH':
      return 'Out of service, this degrades its space and warns on the dashboard.';
    case 'MEDIUM':
      return 'Out of service, this raises a minor blocker against its space.';
    default:
      return 'Noted against its space, and changes nothing about whether it can be used.';
  }
};

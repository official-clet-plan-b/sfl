import { useState } from 'react';
import { humanise } from 'modules/fleet/api/enums';
import { FormDialog, EnumSelect, TextInput } from 'modules/facilities/dialogs/dialogKit';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import {
  CODE_MAX,
  CUSTODIAN_MAX,
  LOCATION_MAX,
  NAME_MAX,
  TAG_MAX,
  validateRegister,
  type RegisterDraft,
} from '../api/assets';
import {
  ASSET_CATEGORIES,
  LOCATION_TYPES,
  type AssetReference,
  type RegisterAssetReferenceRequest,
} from '../api/phase2Api';

interface RegisterAssetDialogProps {
  siteCode: string;
  onClose: () => void;
  onSubmit: (body: RegisterAssetReferenceRequest) => Promise<AssetReference>;
}

/**
 * Register an asset, and tag it if the tag is to hand.
 *
 * The tag is optional on purpose: an asset is registered when it arrives and tagged when the tag does,
 * and the update dialog can add one later. A tag already on another asset is refused by the service
 * with the holder's code, which is shown as it is rather than paraphrased.
 */
const RegisterAssetDialog = ({ siteCode, onClose, onSubmit }: RegisterAssetDialogProps) => {
  const [draft, setDraft] = useState<RegisterDraft>({
    assetCode: '',
    name: '',
    category: 'EQUIPMENT',
    tag: '',
    locationType: 'SITE',
    locationReference: siteCode,
    custodian: '',
  });
  const [attempted, setAttempted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const errors = attempted ? validateRegister(draft) : {};
  const set = <K extends keyof RegisterDraft>(key: K) => (value: RegisterDraft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  const submit = async () => {
    setAttempted(true);
    if (Object.keys(validateRegister(draft)).length > 0 || !draft.category) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        assetCode: draft.assetCode.trim(),
        name: draft.name.trim(),
        category: draft.category,
        siteCode,
        locationType: draft.locationType,
        locationReference: draft.locationReference.trim(),
        custodianReference: draft.custodian.trim() || null,
        externalReference: draft.tag.trim() || null,
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
      title="Register an asset"
      description={`Added to ${siteCode}. Tag it now, or later from the asset’s own update.`}
      submitLabel="Register asset"
      submitting={submitting}
      formError={formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <TextInput
          label="Asset code"
          required
          value={draft.assetCode}
          onChange={set('assetCode')}
          maxLength={CODE_MAX}
          placeholder="e.g. AV-00042"
          error={!!errors.assetCode}
          helperText={errors.assetCode}
          autoFocus
        />
        <TextInput
          label="Description"
          required
          value={draft.name}
          onChange={set('name')}
          maxLength={NAME_MAX}
          placeholder="What is being tracked?"
          error={!!errors.name}
          helperText={errors.name}
        />
        <EnumSelect
          label="Category"
          required
          value={draft.category}
          options={ASSET_CATEGORIES}
          onChange={set('category')}
          renderOptionLabel={humanise}
          error={!!errors.category}
          helperText={errors.category}
        />
        <TextInput
          label="Tag ID"
          value={draft.tag}
          onChange={set('tag')}
          maxLength={TAG_MAX}
          placeholder="RFID, barcode or QR value"
          error={!!errors.tag}
          helperText={errors.tag ?? 'Optional. One tag identifies one asset.'}
        />
        <EnumSelect
          label="Location type"
          required
          value={draft.locationType}
          options={LOCATION_TYPES}
          onChange={(value) => value && set('locationType')(value)}
          renderOptionLabel={humanise}
        />
        <TextInput
          label="Location"
          required
          value={draft.locationReference}
          onChange={set('locationReference')}
          maxLength={LOCATION_MAX}
          placeholder="Site, room, vehicle or external reference"
          error={!!errors.locationReference}
          helperText={errors.locationReference}
        />
        <TextInput
          label="Custodian"
          className="sm:col-span-2"
          value={draft.custodian}
          onChange={set('custodian')}
          maxLength={CUSTODIAN_MAX}
          placeholder="Person or team responsible"
          error={!!errors.custodian}
          helperText={errors.custodian ?? 'Optional.'}
        />
      </div>
    </FormDialog>
  );
};

export default RegisterAssetDialog;

import { useState } from 'react';
import { humanise } from 'modules/fleet/api/enums';
import { FormDialog, EnumSelect, TextInput } from 'modules/facilities/dialogs/dialogKit';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import {
  CUSTODIAN_MAX,
  LOCATION_MAX,
  STEP_LABELS,
  TAG_MAX,
  draftFor,
  joinWords,
  planAssetUpdate,
  type AssetDraft,
} from '../api/assets';
import { LOCATION_TYPES, assetVisibilityApi, type AssetReference } from '../api/phase2Api';

interface UpdateAssetDialogProps {
  asset: AssetReference;
  onClose: () => void;
  /** Called after any call succeeds - even a partial update leaves the register changed. */
  onChanged: (applied: string[]) => void;
}

/**
 * Move an asset, hand it to somebody, or tag it.
 *
 * Only what differs from the asset is sent, in the order tag, location, custody. The calls are
 * separate endpoints, so an update can succeed in part: if the second fails the first is already
 * recorded. The dialog says which parts went through and stays open, and because the plan is worked
 * out against the asset as it now is, retrying sends only what is still outstanding.
 */
const UpdateAssetDialog = ({ asset, onClose, onChanged }: UpdateAssetDialogProps) => {
  const [draft, setDraft] = useState<AssetDraft>(draftFor(asset));
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();
  const [saved, setSaved] = useState<string[]>([]);

  const plan = planAssetUpdate(asset, draft);
  const locationMissing = draft.locationReference.trim().length === 0;
  const set = <K extends keyof AssetDraft>(key: K) => (value: AssetDraft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));

  const submit = async () => {
    if (plan.length === 0 || locationMissing) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    const applied: string[] = [];
    try {
      for (const step of plan) {
        if (step === 'tag') {
          await assetVisibilityApi.tag(asset.id, draft.tag.trim());
        } else if (step === 'location') {
          await assetVisibilityApi.move(asset.id, draft.locationType, draft.locationReference.trim());
        } else {
          await assetVisibilityApi.custody(asset.id, draft.custodian.trim() || null);
        }
        applied.push(STEP_LABELS[step]);
      }
      onChanged(applied);
      onClose();
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
      if (applied.length > 0) {
        setSaved((previous) => [...previous, ...applied]);
        onChanged(applied);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={`Update ${asset.assetCode}`}
      description={asset.name}
      submitLabel="Save changes"
      submitting={submitting}
      submitDisabled={plan.length === 0 || locationMissing}
      formError={formError}
      maxWidth="lg"
      summary={
        <p className="px-6 pb-3 text-sm text-muted-foreground" aria-live="polite">
          {saved.length > 0 && `Already saved: ${joinWords(saved)}. `}
          {plan.length === 0
            ? 'Nothing has changed yet.'
            : `This will update the ${joinWords(plan.map((step) => STEP_LABELS[step]))}.`}
        </p>
      }
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="grid gap-4 sm:grid-cols-2">
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
          error={locationMissing}
          helperText={locationMissing ? 'Say where it is.' : undefined}
        />
        <TextInput
          label="Custodian"
          value={draft.custodian}
          onChange={set('custodian')}
          maxLength={CUSTODIAN_MAX}
          helperText="Leave blank to record that nobody holds it."
        />
        <TextInput
          label="Tag ID"
          value={draft.tag}
          onChange={set('tag')}
          maxLength={TAG_MAX}
          placeholder="RFID, barcode or QR value"
          helperText={
            asset.externalReference
              ? 'Changing this replaces the current tag.'
              : 'This asset has no tag yet. One tag identifies one asset.'
          }
        />
      </div>
    </FormDialog>
  );
};

export default UpdateAssetDialog;

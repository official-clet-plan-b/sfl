import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import SiteSelect from 'shared/components/SiteSelect';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { ConfigurationValue, PutConfigurationRequest } from '../api/dto';
import { describeKey, valueTypeHint } from '../api/configurationCatalogue';
import { Checkbox, FormDialog, SelectInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface EditConfigurationDialogProps {
  value: ConfigurationValue;
  /** The site the register is currently filtered to, offered as the default override target. */
  siteCode: string;
  onClose: () => void;
  onSubmit: (key: string, request: PutConfigurationRequest) => Promise<void>;
}

/**
 * Changing a runtime threshold.
 *
 * <h2>Values supersede, they do not overwrite</h2>
 *
 * <p>`PUT /configuration/{key}` writes a new version and the previous one stays in the table. So the
 * dialog says which version is being replaced and which will be written, because "edit" normally
 * means the old value is gone and here it is not - the history is what an auditor reads when asked
 * why a hall was reported ready in March under a threshold nobody uses now.
 *
 * <h2>A default and an override are two different rows</h2>
 *
 * <p>The endpoint takes an optional `siteCode`, and supplying one writes a **site override** that
 * shadows the platform default rather than changing it. That is a different act with a different
 * blast radius - one centre, or every centre - so it is a deliberate choice on the form rather than
 * something inferred from whatever the register happened to be filtered to.
 *
 * <h2>The format is stated, not discovered</h2>
 *
 * <p>Durations are ISO-8601. `PT4H` is not guessable, and an operator typing `4h` and being refused
 * learns only that they were wrong. The hint names the format and gives examples; the service still
 * parses and still refuses, so a wrong guess is caught either way.
 */
export const EditConfigurationDialog = ({
  value,
  siteCode,
  onClose,
  onSubmit,
}: EditConfigurationDialogProps) => {
  const entry = describeKey(value.key, value.description);
  const editingAnOverride = value.siteCode !== null;

  const [next, setNext] = useState(value.value);
  const [asOverride, setAsOverride] = useState(editingAnOverride);
  const [overrideSite, setOverrideSite] = useState(value.siteCode ?? siteCode);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const unchanged = next.trim() === value.value && asOverride === editingAnOverride;
  const missingSite = asOverride && !overrideSite;

  const submit = async () => {
    if (!next.trim() || missingSite) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit(value.key, {
        value: next.trim(),
        valueType: value.valueType,
        description: value.description,
        siteCode: asOverride ? overrideSite : null,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  const booleanValue = value.valueType === 'BOOLEAN';

  return (
    <FormDialog
      open
      title={entry.label}
      description={entry.effect}
      submitLabel="Save this value"
      submitting={submitting}
      submitDisabled={!next.trim() || unchanged || missingSite}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        {booleanValue ? (
          <SelectInput
            label="Value"
            value={next}
            onChange={setNext}
            required
            options={[
              { value: 'true', label: 'Yes' },
              { value: 'false', label: 'No' },
            ]}
          />
        ) : (
          <TextInput
            label="Value"
            value={next}
            onChange={setNext}
            required
            maxLength={200}
            helperText={valueTypeHint(value.valueType)}
          />
        )}

        <div className="rounded-lg border border-border bg-surface-muted/20 px-4 py-3">
          <Checkbox
            checked={asOverride}
            onChange={setAsOverride}
            label="Apply to one site only"
            hint="An override shadows the platform default for that site and leaves every other centre alone."
          />
          {asOverride && (
            <div className="mt-3">
              <SiteSelect
                value={overrideSite}
                onChange={setOverrideSite}
                required
                label="Override for"
                error={missingSite}
                helperText={missingSite ? 'Choose the site this applies to.' : undefined}
              />
            </div>
          )}
        </div>

        <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-lg border border-border px-4 py-3 text-sm">
          <dt className="text-muted-foreground">In force now</dt>
          <dd className="font-medium text-foreground">
            {value.value}
            <span className="ml-2 font-normal text-muted-foreground">v{value.version}</span>
          </dd>
          <dt className="text-muted-foreground">Will be written as</dt>
          <dd className="font-medium text-foreground">
            {next.trim() || '—'}
            <span className="ml-2 font-normal text-muted-foreground">v{value.version + 1}</span>
          </dd>
          <dt className="text-muted-foreground">Key</dt>
          <dd className="font-mono text-xs text-foreground">{value.key}</dd>
        </dl>

        <Notice variant="info" title="The previous value is kept">
          <p className="text-sm">
            Saving writes a new version rather than replacing the old one, and the service reads the
            newest at evaluation time - so this applies to the next evaluation without a redeploy, and
            what was in force before this change stays on the record.
          </p>
        </Notice>
      </div>
    </FormDialog>
  );
};

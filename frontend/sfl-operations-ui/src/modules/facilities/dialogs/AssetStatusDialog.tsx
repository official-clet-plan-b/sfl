import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import type { FacilityAsset } from '../api/dto';
import { assetOperationalStatuses } from '../api/enums';
import type { AssetOperationalStatus } from '../api/enums';
import { assetBlockerSeverity } from '../api/workflow';
import { humaniseCode, severityTone } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';
import StatusBadge from 'modules/facilities/components/StatusBadge';

interface AssetStatusDialogProps {
  asset: FacilityAsset;
  onClose: () => void;
  onChanged: (status: AssetOperationalStatus, notes: string) => Promise<void>;
}

/**
 * Changing an asset's condition.
 *
 * The dialog's job is to show the *consequence* before the change, not just accept it. Putting a
 * critical generator out of service blocks the examination hall it powers - that is one fact with
 * two faces, and an operator who sees only the first will be surprised by the second on the
 * dashboard ten minutes later.
 *
 * The severity shown mirrors `ReadinessApplicationService.severityFor`: criticality sets the ceiling
 * and the status sets how much of it applies. It is a preview of what the service will do, computed
 * the same way, and the service remains the thing that actually decides.
 */
const AssetStatusDialog = ({ asset, onClose, onChanged }: AssetStatusDialogProps) => {
  const [status, setStatus] = useState<AssetOperationalStatus>(asset.operationalStatus);
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const severity = assetBlockerSeverity(asset, status);
  const unchanged = status === asset.operationalStatus;
  const attached = asset.roomId !== null;

  const submit = async () => {
    if (unchanged) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onChanged(status, notes.trim());
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Change asset condition"
      description={`${asset.assetCode} · ${asset.name}`}
      submitLabel="Change condition"
      submitting={submitting}
      submitDisabled={unchanged}
      formError={formError}
      destructive={severity === 'CRITICAL'}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Condition"
          value={status}
          onChange={(value) => setStatus(value as AssetOperationalStatus)}
          required
          options={assetOperationalStatuses.map((value) => ({
            value,
            label: humaniseCode(value),
          }))}
          helperText={
            unchanged ? 'This is the asset’s current condition.' : undefined
          }
        />

        {severity && attached && (
          <Notice
            variant={severity === 'CRITICAL' ? 'error' : 'warning'}
            title="What this does to the space"
          >
            <p className="text-sm">
              This asset is of {humaniseCode(asset.criticality).toLowerCase()} criticality, so marking
              it {humaniseCode(status).toLowerCase()} raises a{' '}
              <StatusBadge value={severity} tone={severityTone(severity)} /> blocker on the space it
              serves.
              {severity === 'CRITICAL'
                ? ' That space will be marked BLOCKED and cannot be used until this is resolved.'
                : ' That space will be marked DEGRADED.'}
            </p>
          </Notice>
        )}

        {severity && !attached && (
          <Notice variant="info">
            This asset is not attached to a space, so no readiness blocker will be raised. Attach it
            to a space if its condition should affect one.
          </Notice>
        )}

        {!severity && !unchanged && asset.impairsReadiness && (
          <Notice variant="success" title="This clears the blocker">
            Returning this asset to {humaniseCode(status).toLowerCase()} resolves the readiness
            blocker it raised, and the space it serves will be re-derived.
          </Notice>
        )}

        <TextAreaInput
          label="Notes"
          value={notes}
          onChange={setNotes}
          rows={3}
          placeholder="e.g. Starter motor failed; parts ordered."
          helperText="Recorded against the asset and in the audit trail."
        />
      </div>
    </FormDialog>
  );
};

export default AssetStatusDialog;

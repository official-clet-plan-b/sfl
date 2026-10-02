import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { recordLifecycleStatuses } from '../api/enums';
import type { RecordLifecycleStatus } from '../api/enums';
import { humaniseCode } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

/**
 * The pieces every estate edit dialog needs, in one place.
 *
 * Two of them, and both exist because getting them subtly different per aggregate is how a register
 * ends up with six ways of saying the same thing.
 */

/**
 * What to do when the record moved underneath you.
 *
 * <p>Every edit here sends `expectedVersion`, so a second operator's save turns this one into a 409
 * rather than letting the later write silently win - which is the whole reason the field exists. The
 * service's own wording is already shown by {@link FormDialog}; this adds the bit the wording cannot
 * carry, which is what to *do*. Reload, look at what changed, decide again. Re-submitting the form as
 * it stands would just overwrite the other person's edit with a stale copy, one round trip later.
 */
export const StaleWriteNotice = ({ error }: { error?: FleetApiError }) =>
  error?.isVersionConflict ? (
    <Notice variant="warning" title="Somebody else changed this record first">
      <p className="text-sm">
        Close this dialog and reopen it to see their version. Your entries here are measured against
        the record as it was when you opened it, so saving them now would replace their change rather
        than build on it.
      </p>
    </Notice>
  ) : null;

interface LifecycleDialogProps {
  /** What is being retired, in the operator's words - "site", "space", "checklist". */
  noun: string;
  /** How the record identifies itself, for the confirmation sentence. */
  label: string;
  current: RecordLifecycleStatus;
  expectedVersion: number;
  onClose: () => void;
  onSubmit: (status: RecordLifecycleStatus, expectedVersion: number) => Promise<void>;
}

/**
 * Moving a record along its lifecycle - which is what this estate has instead of deletion.
 *
 * <h2>Why there is no delete</h2>
 *
 * <p>Nothing here is hard-deleted, and that is a property of the domain rather than a caution. A site
 * holds buildings, spaces, assets and readiness history; a space holds assessments and blockers that
 * an examination board may have to account for later. Removing the row would orphan all of it and
 * break the audit chain that proves the remainder was not altered. So a record that is finished with
 * is moved to `ARCHIVED` and stays readable.
 *
 * <h2>Why archiving asks twice</h2>
 *
 * <p>`ARCHIVED` is terminal - `RecordLifecycleStatus` allows no move out of it, and the service
 * refuses one. `INACTIVE` and `SUSPENDED` are reversible and are the right answer for "not in use
 * this term". Offering the three side by side without saying which is which would let somebody pick
 * the irreversible one because it sounded the most decisive.
 */
export const LifecycleDialog = ({
  noun,
  label,
  current,
  expectedVersion,
  onClose,
  onSubmit,
}: LifecycleDialogProps) => {
  const targets = recordLifecycleStatuses.filter((status) => status !== current);
  const [status, setStatus] = useState<RecordLifecycleStatus>(targets[0] ?? 'INACTIVE');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const terminal = status === 'ARCHIVED';

  const submit = async () => {
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit(status, expectedVersion);
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title={`Change what ${label} is used for`}
      description={`This ${noun} is ${humaniseCode(current).toLowerCase()} today.`}
      submitLabel={terminal ? 'Archive it' : 'Apply'}
      submitting={submitting}
      destructive={terminal}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Move it to"
          value={status}
          onChange={(value) => setStatus(value as RecordLifecycleStatus)}
          required
          options={targets.map((value) => ({ value, label: humaniseCode(value) }))}
        />

        {terminal && (
          <Notice variant="warning" title="Archiving cannot be undone">
            <p className="text-sm">
              An archived {noun} cannot be brought back into use, edited, or moved to another state.
              Everything it holds stays readable and its audit trail is untouched - but this is the
              end of the line. If it is only out of use for a while, choose inactive or suspended.
            </p>
          </Notice>
        )}

        <StaleWriteNotice error={formError} />
      </div>
    </FormDialog>
  );
};

/**
 * A free-text note that accompanies a change, sized to the service's own column.
 *
 * Exported rather than repeated because three dialogs want the same 1000-character box with the same
 * counter behaviour, and a fourth will.
 */
export const NoteField = ({
  label,
  value,
  onChange,
  helperText,
  rows = 3,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  helperText?: string;
  rows?: number;
}) => (
  <TextAreaInput
    label={label}
    value={value}
    onChange={onChange}
    rows={rows}
    maxLength={1000}
    helperText={helperText}
  />
);

import { useState } from 'react';
import { Notice } from '@rfdtech/components';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import type { DismissFaultRequest, FacilitiesPage, FacilityFault } from '../api/dto';
import type { FaultDismissalOutcome } from '../api/enums';
import { faultDismissalOutcomes } from '../api/enums';
import { searchFaults } from '../api/facilitiesApi';
import { humaniseCode } from '../components/facilitiesFormat';
import { FormDialog, SelectInput, TextAreaInput } from 'modules/facilities/dialogs/dialogKit';

interface DismissFaultDialogProps {
  fault: FacilityFault;
  onClose: () => void;
  onSubmit: (request: DismissFaultRequest) => Promise<void>;
}

/**
 * Closing a fault without fixing it.
 *
 * Three outcomes, and the distinction between them is the whole point: **rejected** means somebody
 * looked and there is nothing to do, **duplicate** means the work is already tracked somewhere else,
 * **cancelled** means the reporter withdrew it. A register that collapsed all three into "closed"
 * would leave a reviewer unable to tell a judgement from an administrative tidy-up.
 *
 * All three are terminal - a dismissed fault cannot be reopened, and the same problem is reported
 * again as a new fault with its own number. Reopening would leave an audit trail claiming one report
 * was made when two were.
 *
 * The reason is mandatory. The service refuses without one, and this says why rather than presenting
 * an unexplained required field.
 */
const DismissFaultDialog = ({ fault, onClose, onSubmit }: DismissFaultDialogProps) => {
  const [outcome, setOutcome] = useState<FaultDismissalOutcome>('REJECTED');
  const [reason, setReason] = useState('');
  const [duplicateOf, setDuplicateOf] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  /** Only loaded for the duplicate case, and only open faults can be duplicated onto. */
  const candidates = useApiQuery(
    (signal) =>
      outcome === 'DUPLICATE'
        ? searchFaults({ siteCode: fault.siteCode, openOnly: true, limit: 100 }, signal)
        : Promise.resolve<FacilitiesPage<FacilityFault>>({
            items: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: 0,
          }),
    [outcome, fault.siteCode],
  );

  const missingReason = reason.trim().length === 0;
  const missingDuplicate = outcome === 'DUPLICATE' && !duplicateOf;
  const invalid = missingReason || missingDuplicate;

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        outcome,
        reason: reason.trim(),
        duplicateOfFaultId: outcome === 'DUPLICATE' ? duplicateOf : null,
        expectedVersion: fault.metadata.version,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  const explanation: Record<FaultDismissalOutcome, string> = {
    REJECTED: 'Somebody assessed it and there is nothing to do.',
    DUPLICATE: 'The same problem is already tracked on another fault.',
    CANCELLED: 'Withdrawn before any work was done.',
  };

  return (
    <FormDialog
      open
      title="Dismiss fault"
      description={`${fault.faultNumber} - ${fault.title}`}
      submitLabel="Dismiss"
      submitting={submitting}
      submitDisabled={invalid}
      formError={formError}
      destructive
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="Outcome"
          value={outcome}
          onChange={(value) => setOutcome(value as FaultDismissalOutcome)}
          required
          options={faultDismissalOutcomes.map((value) => ({
            value,
            label: humaniseCode(value),
          }))}
          helperText={explanation[outcome]}
        />

        {outcome === 'DUPLICATE' && (
          <SelectInput
            label="Duplicate of"
            value={duplicateOf}
            onChange={setDuplicateOf}
            required
            allowEmpty
            emptyLabel="Choose the fault it duplicates"
            error={touched && missingDuplicate}
            options={(candidates.data?.items ?? [])
              .filter((candidate) => candidate.id !== fault.id)
              .map((candidate) => ({
                value: candidate.id,
                label: `${candidate.faultNumber} - ${candidate.title}`,
              }))}
            helperText={
              touched && missingDuplicate
                ? 'A duplicate must name the fault it duplicates.'
                : 'Only open faults at this site can be duplicated onto.'
            }
          />
        )}

        <TextAreaInput
          label="Why"
          value={reason}
          onChange={setReason}
          onBlur={() => setTouched(true)}
          required
          rows={3}
          placeholder="e.g. Attended and found nothing wrong; the door closes correctly."
          error={touched && missingReason}
          helperText={
            touched && missingReason
              ? 'A reason is required.'
              : 'Recorded on the fault and in the audit trail. A dismissal with no reason cannot be reviewed.'
          }
        />

        {fault.blockerRaised && (
          <Notice variant="info" title="This will unblock the space">
            <p className="text-sm">
              Dismissing the fault resolves the readiness blocker it currently holds, and the space
              returns to whatever its other blockers allow.
            </p>
          </Notice>
        )}

        <Notice variant="warning" title="This cannot be undone">
          <p className="text-sm">
            All three outcomes are terminal. If the problem turns out to be real, it is reported
            again as a new fault with its own number.
          </p>
        </Notice>
      </div>
    </FormDialog>
  );
};

export default DismissFaultDialog;

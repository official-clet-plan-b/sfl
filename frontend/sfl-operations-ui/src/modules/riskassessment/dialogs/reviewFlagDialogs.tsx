import { useState } from 'react';
import { useNotifier } from 'shared/components/Notifier';
import { todayIsoDate } from 'shared/components/format';
import type { ReviewFlag } from '../api/dto';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { deferralProblem } from '../api/workflow';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextAreaField, DateField } from 'modules/emergency/components/FormFields';

interface FlagDialogProps {
  flag: ReviewFlag;
  onClose: () => void;
  onDone: () => void;
}

/**
 * SRS-SFL-S165-04: "The flag is cleared only by a completed review with recorded findings." There is no
 * dismiss in this dialog or anywhere else - the service offers none.
 */
export const CompleteReviewDialog = ({ flag, onClose, onDone }: FlagDialogProps) => {
  const notify = useNotifier();
  const [findings, setFindings] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.completeFlag(flag.id, { findings: findings.trim(), expectedVersion: flag.metadata.version });
      notify.notifySuccess('Review completed', `${flag.assessmentReference} - flag cleared`);
      onDone();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <ActionDialog
      open
      title={`Complete the review of ${flag.assessmentReference}`}
      description={`Raised by ${flag.sourceReference ?? 'an incident'} against version ${flag.versionNumber}.`}
      submitLabel="Record findings and clear"
      submitting={submitting}
      submitDisabled={!findings.trim()}
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <Banner variant="info" heading="Findings are required"
  subtext={<>Say what the review found about the controls that were in place. If they need to change, revise the assessment as well -
        clearing the flag does not change it.</>} />
      <TextAreaField label="Review findings" value={findings} maxLength={4000} rows={6} required onChange={setFindings} />
    </ActionDialog>
  );
};

/** S165-04: "it can be deferred with a named reason and date". It comes back when the date passes. */
export const DeferFlagDialog = ({ flag, onClose, onDone }: FlagDialogProps) => {
  const notify = useNotifier();
  const [reason, setReason] = useState('');
  const [until, setUntil] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const problem = deferralProblem(reason, until, todayIsoDate());
  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.deferFlag(flag.id, { reason: reason.trim(), until, expectedVersion: flag.metadata.version });
      notify.notifySuccess('Review deferred', `${flag.assessmentReference} returns to the queue on ${until}`);
      onDone();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <ActionDialog
      open
      title={`Defer the review of ${flag.assessmentReference}`}
      description="A deferral postpones the review; it does not clear the flag."
      submitLabel="Defer"
      submitting={submitting}
      submitDisabled={problem !== null}
      summary={problem ?? undefined}
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <TextAreaField label="Reason for deferring" value={reason} maxLength={1000} rows={3} required onChange={setReason} />
      <DateField label="Back on the queue on" value={until} required onChange={setUntil} />
    </ActionDialog>
  );
};

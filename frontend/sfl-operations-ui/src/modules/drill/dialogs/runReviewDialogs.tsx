import { useState } from 'react';
import { Banner } from '@rfdtech/components';
import { useNotifier } from 'shared/components/Notifier';
import { formatDate, todayIsoDate } from 'shared/components/format';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { DateField, EnumField, NumberField, SelectField, TextAreaField, TextField } from 'modules/emergency/components/FormFields';
import { ConsequenceLine, ConsequencePanel } from 'modules/emergency/components/EmergencyFields';
import SiteSelect from 'shared/components/SiteSelect';
import { drillApi } from '../api/drillApi';
import type { DrillCorrectiveAction, DrillDetail, DrillFinding, Judgement, RollCallGap } from '../api/dto';
import {
  drillModuleLabel,
  drillTypeLabel,
  drillTypes,
  expectationOutcomes,
  gapFollowUpLabel,
  gapFollowUps,
  outcomeLabel,
  type DrillType,
  type ExpectationOutcome,
  type GapFollowUp,
} from '../api/enums';

/** Runs a call, reports success or the service's refusal, and tells the caller when it is done. */
const useSubmit = () => {
  const notify = useNotifier();
  const [submitting, setSubmitting] = useState(false);
  const run = async (call: () => Promise<unknown>, title: string, detail: string | undefined, onDone: () => void) => {
    setSubmitting(true);
    try {
      await call();
      notify.notifySuccess(title, detail);
      onDone();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };
  return { submitting, run };
};

interface DrillDialogProps {
  detail: DrillDetail;
  onClose: () => void;
  onDone: () => void;
}

// ---- the day ---------------------------------------------------------------------------------------

/** SRS-SFL-S175-01/-02: what starting does, read back before it is done - it sends a real notification. */
export const StartDrillDialog = ({ detail, onClose, onDone }: DrillDialogProps) => {
  const { submitting, run } = useSubmit();
  const { drill } = detail;
  return (
    <ActionDialog
      open
      title={`Start ${drill.reference}`}
      description="Starting is one step: nothing is recorded if any part is refused, and the drill stays scheduled."
      submitLabel="Start drill"
      submitting={submitting}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.start(drill.id, drill.metadata.version), 'Drill started', drill.reference, onDone)}
    >
      <ConsequencePanel title="Starting this drill will">
        <ConsequenceLine label="Baseline" value="Take who is on site now from S160 visitors and S160a access occupancy" />
        <ConsequenceLine label="Roll-call" value={`Open an S162a muster at ${drill.plan.assemblyZone}`} />
        <ConsequenceLine label="Notification" value="Send the S174 drill template to the chosen audiences, marked as a drill" />
      </ConsequencePanel>
      <div className="mt-4">
        <Banner variant="info" heading="Recipients will receive a real message" subtext="It goes down the real notification path, so it tests that path. Every message opens with the drill marker." />
      </div>
    </ActionDialog>
  );
};

export const CloseRollCallDialog = ({ detail, outstanding, onClose, onDone }: DrillDialogProps & { outstanding: number }) => {
  const { submitting, run } = useSubmit();
  const { drill } = detail;
  return (
    <ActionDialog
      open
      title={`Close roll-call for ${drill.reference}`}
      description="Closes the S162a muster and the S174 drill notification, and completes the drill."
      submitLabel="Close roll-call"
      submitting={submitting}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.closeRollCall(drill.id, drill.metadata.version), 'Roll-call closed', `${outstanding} gap(s) recorded`, onDone)}
    >
      <p className="text-theme-sm text-gray-800">
        {outstanding === 0
          ? 'Everyone in the baseline has checked in.'
          : `${outstanding} ${outstanding === 1 ? 'person' : 'people'} in the baseline ${outstanding === 1 ? 'has' : 'have'} not checked in. Each goes on the gap list for follow-up.`}
      </p>
    </ActionDialog>
  );
};

export const GapFollowUpDialog = ({ detail, gap, onClose, onDone }: DrillDialogProps & { gap: RollCallGap }) => {
  const { submitting, run } = useSubmit();
  const [followUp, setFollowUp] = useState<GapFollowUp | ''>('');
  const [notes, setNotes] = useState('');
  return (
    <ActionDialog
      open
      title={`Follow up ${gap.displayName ?? gap.personRef}`}
      description="Every gap needs a follow-up outcome before the review is submitted."
      submitLabel="Record follow-up"
      submitting={submitting}
      submitDisabled={!followUp}
      onClose={onClose}
      onSubmit={() => followUp && void run(() => drillApi.followUpGap(detail.drill.id, gap.id, followUp, notes.trim() || undefined), 'Follow-up recorded', gap.personRef, onDone)}
    >
      <div className="space-y-4">
        <EnumField label="Outcome" value={followUp} options={gapFollowUps} renderOptionLabel={(v) => gapFollowUpLabel[v]} required onChange={setFollowUp} />
        <TextAreaField label="Notes" value={notes} maxLength={2000} onChange={setNotes} helperText={followUp === 'NOT_ON_SITE' ? 'How they left without badging out - that is often a finding.' : undefined} />
      </div>
    </ActionDialog>
  );
};

// ---- after-action review -------------------------------------------------------------------------------

export const ReviewNarrativeDialog = ({ detail, onClose, onDone }: DrillDialogProps) => {
  const { submitting, run } = useSubmit();
  const [summary, setSummary] = useState(detail.review?.summary ?? '');
  const [timingNotes, setTimingNotes] = useState(detail.review?.timingNotes ?? '');
  return (
    <ActionDialog
      open
      title="After-action review"
      description="Timing and participation are measured and already on the drill. Record what people concluded."
      submitLabel="Save review"
      submitting={submitting}
      submitDisabled={!summary.trim()}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.review(detail.drill.id, summary.trim(), timingNotes.trim() || undefined), 'Review saved', undefined, onDone)}
    >
      <div className="space-y-4">
        <TextAreaField label="Summary" value={summary} maxLength={4000} required onChange={setSummary} />
        <TextAreaField label="Timing notes" value={timingNotes} maxLength={2000} onChange={setTimingNotes} helperText="Anything the measured notification-to-muster time does not show." />
      </div>
    </ActionDialog>
  );
};

export const FindingDialog = ({ detail, onClose, onDone }: DrillDialogProps) => {
  const { submitting, run } = useSubmit();
  const [description, setDescription] = useState('');
  return (
    <ActionDialog
      open
      title="Record a finding"
      description="Each finding then needs a corrective action or a reason none is required."
      submitLabel="Record finding"
      submitting={submitting}
      submitDisabled={!description.trim()}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.addFinding(detail.drill.id, description.trim()), 'Finding recorded', undefined, onDone)}
    >
      <TextAreaField label="What the drill revealed" value={description} maxLength={2000} required onChange={setDescription} />
    </ActionDialog>
  );
};

export const NoActionDialog = ({ detail, finding, onClose, onDone }: DrillDialogProps & { finding: DrillFinding }) => {
  const { submitting, run } = useSubmit();
  const [justification, setJustification] = useState('');
  return (
    <ActionDialog
      open
      title={`No action for finding #${finding.sequenceNo}`}
      description="The only alternative to a corrective action, and it stays on the record."
      submitLabel="Record justification"
      submitting={submitting}
      submitDisabled={!justification.trim()}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.noAction(detail.drill.id, finding.id, justification.trim()), 'Justification recorded', undefined, onDone)}
    >
      <p className="mb-3 text-theme-sm text-gray-800">{finding.description}</p>
      <TextAreaField label="Why no corrective action is needed" value={justification} maxLength={2000} required onChange={setJustification} />
    </ActionDialog>
  );
};

/** SRS-SFL-S175-05: a defined answer per module, against the success measure written before the drill ran. */
export const JudgeExpectationsDialog = ({ detail, onClose, onDone }: DrillDialogProps) => {
  const { submitting, run } = useSubmit();
  const expectations = detail.drill.plan.expectations;
  const [rows, setRows] = useState(expectations.map((e) => ({ outcome: (e.outcome ?? '') as ExpectationOutcome | '', notes: e.outcomeNotes ?? '' })));
  const judgements: Judgement[] = rows.flatMap((row, index) => (row.outcome ? [{ index, outcome: row.outcome, notes: row.notes.trim() || undefined }] : []));
  return (
    <ActionDialog
      open
      title="Did each module meet its expectation?"
      submitLabel="Save outcomes"
      submitting={submitting}
      submitDisabled={judgements.length === 0}
      maxWidth="xl"
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.judge(detail.drill.id, judgements, detail.drill.metadata.version), 'Outcomes recorded', undefined, onDone)}
    >
      <div className="space-y-4">
        {expectations.map((e, index) => (
          <div key={index} className="rounded-md border border-gray-200 p-3">
            <p className="font-semibold text-gray-900">{drillModuleLabel[e.module]}</p>
            <p className="text-theme-sm text-gray-800">{e.expectation}</p>
            <p className="text-theme-xs text-gray-600">Success measured by: {e.successCriterion}</p>
            <div className="mt-3 grid gap-3 sm:grid-cols-[12rem_1fr]">
              <EnumField label="Outcome" value={rows[index].outcome} options={expectationOutcomes} renderOptionLabel={(v) => outcomeLabel[v]} allowEmpty emptyLabel="Not judged yet" onChange={(value) => setRows(rows.map((r, i) => (i === index ? { ...r, outcome: value } : r)))} />
              <TextField label="Notes" value={rows[index].notes} maxLength={2000} onChange={(value) => setRows(rows.map((r, i) => (i === index ? { ...r, notes: value } : r)))} />
            </div>
          </div>
        ))}
      </div>
    </ActionDialog>
  );
};

export const SubmitReviewDialog = ({ detail, onClose, onDone }: DrillDialogProps) => {
  const { submitting, run } = useSubmit();
  return (
    <ActionDialog
      open
      title={`Submit the review of ${detail.drill.reference}`}
      description="Marks the drill reviewed. From here it counts toward frequency compliance."
      submitLabel="Submit review"
      submitting={submitting}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.submitReview(detail.drill.id, detail.drill.metadata.version), 'Review submitted', detail.drill.reference, onDone)}
    >
      <p className="text-theme-sm text-gray-800">{detail.findings.length} finding(s), {detail.correctiveActions.length} corrective action(s), {detail.gaps.length} gap(s) followed up.</p>
    </ActionDialog>
  );
};

export const CloseDrillDialog = ({ detail, onClose, onDone }: DrillDialogProps) => {
  const { submitting, run } = useSubmit();
  const [reason, setReason] = useState('');
  const overdue = detail.overdueActionIds.length;
  return (
    <ActionDialog
      open
      title={`Close ${detail.drill.reference}`}
      description="Open actions that are not yet overdue stay tracked on the HSE dashboard after closure."
      submitLabel="Close drill"
      submitting={submitting}
      submitDisabled={overdue > 0 && !reason.trim()}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.close(detail.drill.id, detail.drill.metadata.version, reason.trim() || undefined), 'Drill closed', detail.drill.reference, onDone)}
    >
      {overdue > 0 ? (
        <div className="space-y-4">
          <Banner variant="warning" heading={`${overdue} corrective action${overdue === 1 ? ' is' : 's are'} open and overdue`} subtext="Close them first, or give an explicit deferral reason - it is kept on the drill." />
          <TextAreaField label="Deferral reason" value={reason} maxLength={1000} required onChange={setReason} />
        </div>
      ) : (
        <p className="text-theme-sm text-gray-800">No corrective action is overdue.</p>
      )}
    </ActionDialog>
  );
};

// ---- corrective actions ----------------------------------------------------------------------------------

export const OpenActionDialog = ({ detail, finding, onClose, onDone }: DrillDialogProps & { finding: DrillFinding }) => {
  const { submitting, run } = useSubmit();
  const [description, setDescription] = useState('');
  const [ownerId, setOwnerId] = useState('');
  const [dueDate, setDueDate] = useState('');
  return (
    <ActionDialog
      open
      title={`Corrective action for finding #${finding.sequenceNo}`}
      description="S163's corrective-action pattern: an owner and a due date, tracked to verified closure."
      submitLabel="Raise action"
      submitting={submitting}
      submitDisabled={!description.trim() || !ownerId.trim() || !dueDate}
      onClose={onClose}
      onSubmit={() => void run(() => drillApi.openAction(detail.drill.id, { findingId: finding.id, description: description.trim(), ownerId: ownerId.trim(), dueDate }), 'Corrective action raised', undefined, onDone)}
    >
      <p className="mb-3 text-theme-sm text-gray-800">{finding.description}</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <TextAreaField label="Action" className="sm:col-span-2" value={description} maxLength={2000} required onChange={setDescription} />
        <TextField label="Owner" value={ownerId} maxLength={160} required onChange={setOwnerId} helperText="The user id of whoever will do it." />
        <DateField label="Due" value={dueDate} minDate={todayIsoDate()} required onChange={setDueDate} />
      </div>
    </ActionDialog>
  );
};

export const ActionTransitionDialog = ({ detail, action, kind, onClose, onDone }: DrillDialogProps & { action: DrillCorrectiveAction; kind: 'verify' | 'cancel' }) => {
  const { submitting, run } = useSubmit();
  const [text, setText] = useState('');
  const verify = kind === 'verify';
  return (
    <ActionDialog
      open
      title={verify ? 'Verify effectiveness' : 'Cancel corrective action'}
      description={`${action.description} · owner ${action.ownerId} · due ${formatDate(action.dueDate)}`}
      submitLabel={verify ? 'Verify' : 'Cancel action'}
      destructive={!verify}
      submitting={submitting}
      submitDisabled={!text.trim()}
      onClose={onClose}
      onSubmit={() => void run(() => (verify ? drillApi.verifyAction(detail.drill.id, action.id, text.trim()) : drillApi.cancelAction(detail.drill.id, action.id, text.trim())), verify ? 'Action verified' : 'Action cancelled', undefined, onDone)}
    >
      <TextAreaField label={verify ? 'How effectiveness was verified' : 'Reason'} value={text} maxLength={2000} required onChange={setText} />
    </ActionDialog>
  );
};

// ---- frequency requirement ---------------------------------------------------------------------------------

export const RequirementDialog = ({ siteCode: initialSite, drillType: initialType, intervalDays, warningDays, onClose, onDone }: {
  siteCode: string;
  drillType?: DrillType;
  intervalDays?: number;
  warningDays?: number;
  onClose: () => void;
  onDone: () => void;
}) => {
  const { submitting, run } = useSubmit();
  const [siteCode, setSiteCode] = useState(initialSite);
  const [drillType, setDrillType] = useState<DrillType | ''>(initialType ?? '');
  const [interval, setInterval] = useState(intervalDays ? String(intervalDays) : '');
  const [warning, setWarning] = useState(warningDays !== undefined ? String(warningDays) : '14');
  const days = Number(interval);
  const lead = Number(warning);
  const problem = !Number.isInteger(days) || days < 1 ? 'The interval must be at least one day.'
    : !Number.isInteger(lead) || lead < 0 || lead >= days ? 'The warning must fall inside the interval.' : null;
  const revising = Boolean(initialType);
  return (
    <ActionDialog
      open
      title={revising ? 'Revise required frequency' : 'Set a required frequency'}
      description="CLET's statutory or insurance figure for this site and drill type. A revision keeps the original effective date."
      submitLabel="Save"
      submitting={submitting}
      submitDisabled={!siteCode || !drillType || Boolean(problem)}
      onClose={onClose}
      onSubmit={() => drillType && void run(() => drillApi.setRequirement({ siteCode, drillType, intervalDays: days, warningDays: lead }), 'Requirement saved', undefined, onDone)}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        {revising ? <TextField label="Site" value={siteCode} onChange={() => undefined} disabled /> : <SiteSelect value={siteCode} onChange={setSiteCode} required />}
        {revising
          ? <TextField label="Drill type" value={drillTypeLabel[initialType as DrillType]} onChange={() => undefined} disabled />
          : <SelectField label="Drill type" value={drillType} allowEmpty emptyLabel="Choose a type" required options={drillTypes.map((t) => ({ value: t, label: drillTypeLabel[t] }))} onChange={(value) => setDrillType(value as DrillType | '')} />}
        <NumberField label="Required every" value={interval} min={1} suffix="days" required onChange={setInterval} error={Boolean(problem) && interval !== ''} helperText={problem ?? undefined} />
        <NumberField label="Warn this far ahead" value={warning} min={0} suffix="days" required onChange={setWarning} />
      </div>
    </ActionDialog>
  );
};

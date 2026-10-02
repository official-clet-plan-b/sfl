import { useMemo, useState } from 'react';
import { sflActor } from 'shared/api/config';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect from 'shared/components/SiteSelect';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import type { AssessmentDetail, HazardInput, ReviewInterval, VersionView } from '../api/dto';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { needsIndependentReviewer, previewAssessmentLevel, publishBlockers, signOffRefusedFor } from '../api/workflow';
import HazardEditor from '../components/HazardEditor';
import { hazardsIncomplete, toHazardInput, toRequestHazards } from '../components/hazardForm';
import { RiskLevelChip } from '../components/riskChips';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField, TextAreaField, SelectField } from 'modules/emergency/components/FormFields';

const NEW_ACTIVITY = '__new__';
const DAY_MS = 24 * 60 * 60 * 1000;

const dueDateAfter = (days: number | undefined) =>
  days === undefined ? null : formatDate(new Date(Date.now() + days * DAY_MS).toISOString());

const intervalFor = (intervals: ReviewInterval[] | undefined, level: string | null) =>
  intervals?.find((interval) => interval.riskLevel === level);

// ---- create ---------------------------------------------------------------------------------------

interface CreateAssessmentDialogProps {
  siteCode: string;
  /** Prefills the activity type - the coverage screen opens this for a gap. */
  activityType?: string;
  onClose: () => void;
  onCreated: (detail: AssessmentDetail) => void;
}

/**
 * SRS-SFL-S165-01: create an assessment and its first draft, against a template or from nothing.
 *
 * The activity type is offered from what the site actually does (S165-03's observed activity types,
 * plus any already assessed), with a way to name a new one - a new kind of work is exactly what an
 * assessment is written ahead of. The S152 location is a code typed by hand; see the UI gap report.
 */
export const CreateAssessmentDialog = ({ siteCode: initialSite, activityType: preset, onClose, onCreated }: CreateAssessmentDialogProps) => {
  const notify = useNotifier();
  const [siteCode, setSiteCode] = useState(initialSite);
  const [templateId, setTemplateId] = useState('');
  const [activityChoice, setActivityChoice] = useState(preset ?? '');
  const [newActivity, setNewActivity] = useState('');
  const [locationCode, setLocationCode] = useState('');
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [hazards, setHazards] = useState<HazardInput[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const templates = useApiQuery((signal) => riskAssessmentApi.templates(true, signal), []);
  const activityTypes = useApiQuery(
    (signal) => (siteCode ? riskAssessmentApi.activityTypes(siteCode, signal) : Promise.resolve<string[]>([])),
    [siteCode],
  );
  const template = templates.data?.find((candidate) => candidate.id === templateId);
  const activityOptions = useMemo(() => {
    const known = new Set(activityTypes.data ?? []);
    if (preset) known.add(preset);
    return [...[...known].sort().map((value) => ({ value, label: value })), { value: NEW_ACTIVITY, label: 'A new activity type…' }];
  }, [activityTypes.data, preset]);

  const activityType = activityChoice === NEW_ACTIVITY ? newActivity.trim() : activityChoice;
  const effectiveTitle = title.trim() || template?.name || '';
  const scoped = Boolean(activityType || template?.activityType || locationCode.trim());
  const invalid = !siteCode || !effectiveTitle || !scoped || hazardsIncomplete(hazards);

  const submit = async () => {
    if (invalid) return;
    setSubmitting(true);
    try {
      const created = await riskAssessmentApi.create({
        siteCode,
        activityType: activityType || undefined,
        locationCode: locationCode.trim() || undefined,
        title: title.trim() || undefined,
        summary: summary.trim() || undefined,
        hazards: toRequestHazards(hazards),
        templateId: templateId || undefined,
      });
      notify.notifySuccess('Assessment created', `${created.assessment.reference} - draft version 1 is open`);
      onCreated(created);
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ActionDialog
      open
      title="New risk assessment"
      description="Creates the assessment and its first draft. Nothing can link to it until a version is published."
      submitLabel="Create draft"
      submitting={submitting}
      submitDisabled={invalid}
      maxWidth="xl"
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <SiteSelect value={siteCode} onChange={setSiteCode} required />
        <SelectField
          label="Template"
          value={templateId}
          allowEmpty
          emptyLabel="No template - start blank"
          options={(templates.data ?? []).map((candidate) => ({ value: candidate.id, label: candidate.name }))}
          helperText={template ? `${template.hazards.length} hazards and their controls will be copied into the draft.` : undefined}
          onChange={setTemplateId}
        />
        <SelectField
          label="Activity type"
          value={activityChoice}
          allowEmpty
          emptyLabel={template?.activityType ? `From template: ${template.activityType}` : 'None - scope by location'}
          options={activityOptions}
          helperText="Activity types this site actually uses, so coverage can match them."
          onChange={setActivityChoice}
        />
        {activityChoice === NEW_ACTIVITY ? (
          <TextField label="New activity type" value={newActivity} maxLength={80} required onChange={setNewActivity} helperText="e.g. Roof access. Stored as ROOF_ACCESS." />
        ) : (
          <TextField label="S152 location code" value={locationCode} maxLength={80} onChange={setLocationCode} helperText="Optional room, space or zone code." />
        )}
        {activityChoice === NEW_ACTIVITY && (
          <TextField label="S152 location code" value={locationCode} maxLength={80} onChange={setLocationCode} helperText="Optional room, space or zone code." />
        )}
        <TextField
          label="Title"
          className="sm:col-span-2"
          value={title}
          maxLength={200}
          required={!template}
          placeholder={template?.name}
          onChange={setTitle}
        />
        <TextAreaField label="Summary" className="sm:col-span-2" value={summary} maxLength={4000} rows={3} onChange={setSummary} />
      </div>
      {!scoped && siteCode && (
        <Banner variant="info" heading="Scope the assessment"
  subtext={<>Choose an activity type, a location, or both. An assessment scoped to neither covers nothing.</>} />
      )}
      <div>
        <p className="mb-3 text-theme-sm font-semibold text-gray-900">Hazards</p>
        {template && hazards.length === 0 ? (
          <Banner variant="info" heading="The template's hazards will be used"
  subtext={<>Leave this empty to copy the {template.hazards.length} hazards from {template.name}. Add hazards here to use yours instead.</>} />
        ) : null}
        <div className="mt-3">
          <HazardEditor hazards={hazards} onChange={setHazards} />
        </div>
      </div>
    </ActionDialog>
  );
};

// ---- edit the draft -------------------------------------------------------------------------------

interface DraftDialogProps {
  assessmentId: string;
  draft: VersionView;
  onClose: () => void;
  onSaved: () => void;
}

/** The draft's whole body, replaced on save - a draft is edited in place; a published version never is. */
export const EditDraftDialog = ({ assessmentId, draft, onClose, onSaved }: DraftDialogProps) => {
  const notify = useNotifier();
  const [title, setTitle] = useState(draft.version.content.title);
  const [summary, setSummary] = useState(draft.version.content.summary ?? '');
  const [hazards, setHazards] = useState<HazardInput[]>(draft.version.content.hazards.map(toHazardInput));
  const [submitting, setSubmitting] = useState(false);
  const blockers = publishBlockers(hazards);
  const level = previewAssessmentLevel(hazards);

  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.editDraft(assessmentId, {
        title: title.trim(),
        summary: summary.trim() || undefined,
        hazards: toRequestHazards(hazards),
        expectedVersion: draft.version.metadata.version,
      });
      notify.notifySuccess('Draft saved', `Version ${draft.version.versionNumber}`);
      onSaved();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ActionDialog
      open
      title={`Edit draft version ${draft.version.versionNumber}`}
      submitLabel="Save draft"
      submitting={submitting}
      submitDisabled={!title.trim() || hazardsIncomplete(hazards)}
      maxWidth="xl"
      summary={
        <span className="flex flex-wrap items-center gap-2">
          Previewed level <RiskLevelChip level={level} />
          {blockers.length > 0 ? `· ${blockers.length} to resolve before publishing` : '· ready to publish'}
        </span>
      }
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <TextField label="Title" value={title} maxLength={200} required onChange={setTitle} />
      <TextAreaField label="Summary" value={summary} maxLength={4000} rows={3} onChange={setSummary} />
      <HazardEditor hazards={hazards} onChange={setHazards} />
    </ActionDialog>
  );
};

// ---- publish --------------------------------------------------------------------------------------

interface PublishDialogProps {
  assessmentId: string;
  draft: VersionView;
  currentVersion: number | null;
  onClose: () => void;
  onPublished: () => void;
}

/**
 * Publishes the draft as the current version. States beforehand everything the service will do: the
 * level it computes, the review date it sets, the version it supersedes - and refuses to submit while
 * "Hazard Without Control" would be the answer.
 */
export const PublishDialog = ({ assessmentId, draft, currentVersion, onClose, onPublished }: PublishDialogProps) => {
  const notify = useNotifier();
  const [submitting, setSubmitting] = useState(false);
  const intervals = useApiQuery((signal) => riskAssessmentApi.reviewIntervals(signal), []);
  const blockers = publishBlockers(draft.version.content.hazards.map(toHazardInput));
  const interval = intervalFor(intervals.data, draft.riskLevel);

  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.publish(assessmentId, draft.version.metadata.version);
      notify.notifySuccess('Version published', `Version ${draft.version.versionNumber} is now the assessment of record`);
      onPublished();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ActionDialog
      open
      title={`Publish version ${draft.version.versionNumber}`}
      submitLabel="Publish"
      submitting={submitting}
      submitDisabled={blockers.length > 0}
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      {blockers.length > 0 ? (
        <Banner variant="danger" heading="Hazard Without Control"
  subtext={<>Every identified hazard needs at least one control measure. Resolve these first:
          <ul className="mt-2 list-disc pl-5">
            {blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul></>} />
      ) : (
        <>
          <dl className="grid gap-3 text-theme-sm sm:grid-cols-2">
            <div>
              <dt className="text-gray-600">Risk level, from the highest residual rating</dt>
              <dd className="mt-1">
                <RiskLevelChip level={draft.riskLevel} />
              </dd>
            </div>
            <div>
              <dt className="text-gray-600">Due for review</dt>
              <dd className="mt-1 font-medium text-gray-900">
                {interval ? `${dueDateAfter(interval.intervalDays)} (every ${interval.intervalDays} days at this level)` : 'Loading the review cycle…'}
              </dd>
            </div>
          </dl>
          {currentVersion !== null && (
            <Banner variant="info" heading={`Version ${currentVersion} will be superseded`}
  subtext={<>It stays readable in the version history, marked not current. Nothing that links to this assessment will use it
              again.</>} />
          )}
          {needsIndependentReviewer(draft.riskLevel) && (
            <Banner variant="warning" heading="Not current until independently signed off"
  subtext={<>At {draft.riskLevel} the assessment cannot be linked until someone other than its author signs it off.</>} />
          )}
        </>
      )}
    </ActionDialog>
  );
};

// ---- revise ---------------------------------------------------------------------------------------

interface RevisionDialogProps {
  assessmentId: string;
  currentVersion: number;
  onClose: () => void;
  onOpened: () => void;
}

/** The only way to change a published assessment: a new draft copied from the current version. */
export const OpenRevisionDialog = ({ assessmentId, currentVersion, onClose, onOpened }: RevisionDialogProps) => {
  const notify = useNotifier();
  const [submitting, setSubmitting] = useState(false);
  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.openRevision(assessmentId);
      notify.notifySuccess('Revision opened', 'A new draft has been copied from the current version');
      onOpened();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };
  return (
    <ActionDialog open title="Revise the assessment" submitLabel="Open draft" submitting={submitting} onClose={onClose} onSubmit={() => void submit()}>
      <p className="text-theme-sm text-gray-700">
        Version {currentVersion} stays current - and linkable - until the new draft is published. The draft starts as a copy of
        it, and you become its author.
      </p>
    </ActionDialog>
  );
};

// ---- sign off -------------------------------------------------------------------------------------

interface SignOffDialogProps {
  assessmentId: string;
  current: VersionView;
  onClose: () => void;
  onSignedOff: () => void;
}

/**
 * SRS-SFL-S165-02: review sign-off, renewing the review date. Says before submission when the service
 * will refuse - the author signing off a HIGH or CRITICAL assessment alone - rather than after.
 */
export const SignOffDialog = ({ assessmentId, current, onClose, onSignedOff }: SignOffDialogProps) => {
  const notify = useNotifier();
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const intervals = useApiQuery((signal) => riskAssessmentApi.reviewIntervals(signal), []);
  const interval = intervalFor(intervals.data, current.riskLevel);
  const refused = signOffRefusedFor(current.riskLevel, current.version.authorId, sflActor.user);

  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.signOff(assessmentId, notes.trim() || undefined, current.version.metadata.version);
      notify.notifySuccess('Signed off', `Review renewed to ${dueDateAfter(interval?.intervalDays) ?? 'the next cycle'}`);
      onSignedOff();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ActionDialog
      open
      title={`Sign off version ${current.version.versionNumber}`}
      submitLabel="Sign off"
      submitting={submitting}
      submitDisabled={refused}
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      {refused ? (
        <Banner variant="danger" heading="A named competent reviewer is required"
  subtext={<>You wrote this version. At {current.riskLevel} it must be signed off by someone other than its author.</>} />
      ) : (
        <p className="text-theme-sm text-gray-700">
          Signing off renews the review date to <strong>{dueDateAfter(interval?.intervalDays) ?? '…'}</strong>
          {current.version.reviewDueAt ? ` (currently ${formatDate(current.version.reviewDueAt)})` : ''}. A lapsed assessment
          becomes current again.
        </p>
      )}
      <TextAreaField label="Review notes" value={notes} maxLength={2000} rows={4} onChange={setNotes} disabled={refused} />
    </ActionDialog>
  );
};

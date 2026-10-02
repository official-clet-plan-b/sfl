import { useState } from 'react';
import { useNotifier } from 'shared/components/Notifier';
import type { AssessmentTemplate, HazardInput, ReviewInterval } from '../api/dto';
import type { RiskLevel } from '../api/enums';
import { riskAssessmentApi } from '../api/riskAssessmentApi';
import { intervalProblem } from '../api/workflow';
import HazardEditor from '../components/HazardEditor';
import { hazardsIncomplete, toHazardInput, toRequestHazards } from '../components/hazardForm';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField, TextAreaField, NumberField, CheckboxField } from 'modules/emergency/components/FormFields';

interface ReviewIntervalsDialogProps {
  intervals: ReviewInterval[];
  onClose: () => void;
  onSaved: () => void;
}

/**
 * SRS-SFL-S165-02's review cycle. The whole set is edited together because the rule is about the set -
 * a higher level reviewed at least as often as a lower one - and checked here as the service checks it.
 */
export const ReviewIntervalsDialog = ({ intervals, onClose, onSaved }: ReviewIntervalsDialogProps) => {
  const notify = useNotifier();
  const [rows, setRows] = useState(intervals.map((interval) => ({
    riskLevel: interval.riskLevel,
    intervalDays: String(interval.intervalDays),
    reminderLeadDays: String(interval.reminderLeadDays),
  })));
  const [submitting, setSubmitting] = useState(false);
  const proposed = rows.map((row) => ({ riskLevel: row.riskLevel, intervalDays: Number(row.intervalDays), reminderLeadDays: Number(row.reminderLeadDays) }));
  const problem = intervalProblem(proposed);
  const update = (level: RiskLevel, patch: Partial<(typeof rows)[number]>) =>
    setRows((current) => current.map((row) => (row.riskLevel === level ? { ...row, ...patch } : row)));

  const submit = async () => {
    setSubmitting(true);
    try {
      await riskAssessmentApi.updateReviewIntervals(proposed);
      notify.notifySuccess('Review cycle updated', 'Applies from the next publish or sign-off');
      onSaved();
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ActionDialog open title="Review cycle" submitLabel="Save" submitting={submitting} submitDisabled={problem !== null} summary={problem ?? undefined} onClose={onClose} onSubmit={() => void submit()}>
      <Banner variant="info" heading="Applies going forward"
  subtext={<>An assessment keeps the review date it was published or signed off against. A change here applies from the next
        publish or sign-off.</>} />
      {rows.map((row) => (
        <div key={row.riskLevel} className="grid items-end gap-4 sm:grid-cols-[120px_1fr_1fr]">
          <p className="pb-2 text-theme-sm font-semibold text-gray-900">{row.riskLevel}</p>
          <NumberField label="Review every" suffix="days" min={1} step={1} value={row.intervalDays} onChange={(value) => update(row.riskLevel, { intervalDays: value })} />
          <NumberField label="Remind" suffix="days ahead" min={0} step={1} value={row.reminderLeadDays} onChange={(value) => update(row.riskLevel, { reminderLeadDays: value })} />
        </div>
      ))}
    </ActionDialog>
  );
};

interface TemplateDialogProps {
  /** Absent to create one. */
  template?: AssessmentTemplate;
  onClose: () => void;
  onSaved: () => void;
}

/**
 * A template in the library - S165-01's "author against a hazard/control-measure template". Editing one
 * never reaches an assessment already copied from it.
 */
export const TemplateDialog = ({ template, onClose, onSaved }: TemplateDialogProps) => {
  const notify = useNotifier();
  const [name, setName] = useState(template?.name ?? '');
  const [activityType, setActivityType] = useState(template?.activityType ?? '');
  const [description, setDescription] = useState(template?.description ?? '');
  const [active, setActive] = useState(template?.active ?? true);
  const [hazards, setHazards] = useState<HazardInput[]>(template ? template.hazards.map(toHazardInput) : []);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setSubmitting(true);
    const body = {
      name: name.trim(),
      activityType: activityType.trim() || undefined,
      description: description.trim() || undefined,
      active,
      hazards: toRequestHazards(hazards),
      expectedVersion: template?.metadata.version,
    };
    try {
      if (template) {
        await riskAssessmentApi.updateTemplate(template.id, body);
      } else {
        await riskAssessmentApi.createTemplate(body);
      }
      notify.notifySuccess(template ? 'Template updated' : 'Template added', name.trim());
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
      title={template ? `Edit template - ${template.name}` : 'New template'}
      submitLabel={template ? 'Save template' : 'Add template'}
      submitting={submitting}
      submitDisabled={!name.trim() || hazardsIncomplete(hazards)}
      maxWidth="xl"
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Name" value={name} maxLength={200} required onChange={setName} />
        <TextField label="Activity type" value={activityType} maxLength={80} onChange={setActivityType} helperText="Optional. Becomes the assessment's activity type." />
        <TextAreaField label="Description" className="sm:col-span-2" value={description} maxLength={2000} rows={2} onChange={setDescription} />
      </div>
      {template && (
        <CheckboxField checked={active} onChange={setActive} label="Offered when creating an assessment" hint="Untick to retire the template. Assessments already copied from it are unaffected." />
      )}
      <HazardEditor hazards={hazards} onChange={setHazards} />
    </ActionDialog>
  );
};

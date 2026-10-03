import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Banner, Button } from '@rfdtech/components';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect from 'shared/components/SiteSelect';
import { fromLocalInputValue, toLocalInputValue } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { CheckboxGroup } from 'modules/emergency/components/EmergencyFields';
import { DateTimeField, EnumField, SelectField, TextAreaField, TextField } from 'modules/emergency/components/FormFields';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import { drillApi } from '../api/drillApi';
import type { DrillDetail, ExpectationInput, PlanRequest } from '../api/dto';
import { drillModuleLabel, drillModules, drillTypeLabel, drillTypes, type DrillType } from '../api/enums';
import { planToRequest, readinessProblems } from '../api/workflow';

// ---- plan (create and revise) ------------------------------------------------------------------

const emptyPlan = (): PlanRequest => ({
  drillType: 'FIRE',
  title: '',
  scenario: '',
  expectedParticipants: '',
  assemblyZone: '',
  scheduledFor: undefined,
  notificationTemplateId: undefined,
  audienceGroupIds: [],
  recipientZoneIds: [],
  expectations: [],
});

interface PlanDialogProps {
  siteCode: string;
  /** Revising: the drill being revised. Omitted when planning a new one. */
  existing?: DrillDetail;
  onClose: () => void;
  onSaved: (detail: DrillDetail) => void;
}

/**
 * SRS-SFL-S175-01 and -05: the drill plan - site, type, scenario, expected participants - and what the day
 * needs: the assembly point, the S174 drill template and who it goes to, and for each participating module
 * what it is expected to do and how success is measured.
 *
 * A plan can be saved incomplete; it is scheduling that requires it ready, so the readiness list here is
 * advice while planning and a refusal only when a scheduled drill is revised.
 */
export const PlanDialog = ({ siteCode: initialSite, existing, onClose, onSaved }: PlanDialogProps) => {
  const notify = useNotifier();
  const [siteCode, setSiteCode] = useState(existing?.drill.siteCode ?? initialSite);
  const [plan, setPlan] = useState<PlanRequest>(existing ? planToRequest(existing.drill.plan) : emptyPlan());
  const [submitting, setSubmitting] = useState(false);
  const records = useSiteRecords(siteCode);
  const templates = useApiQuery(
    (signal) => (siteCode ? drillApi.templates(siteCode, signal) : Promise.resolve([])),
    [siteCode],
  );
  const set = <K extends keyof PlanRequest>(key: K, value: PlanRequest[K]) => setPlan((prev) => ({ ...prev, [key]: value }));
  const setExpectation = (index: number, next: Partial<ExpectationInput>) =>
    set('expectations', plan.expectations.map((e, i) => (i === index ? { ...e, ...next } : e)));

  const problems = readinessProblems(plan);
  const mustBeReady = existing?.drill.status === 'SCHEDULED';
  const invalid = !siteCode || !plan.title.trim() || plan.expectations.some((e) => !e.expectation.trim()) || (mustBeReady && problems.length > 0);

  const submit = async () => {
    if (invalid) return;
    setSubmitting(true);
    const body: PlanRequest = {
      ...plan,
      title: plan.title.trim(),
      scenario: plan.scenario?.trim() || undefined,
      expectedParticipants: plan.expectedParticipants?.trim() || undefined,
      assemblyZone: plan.assemblyZone?.trim() || undefined,
    };
    try {
      const saved = existing
        ? await drillApi.revise(existing.drill.id, body, existing.drill.metadata.version)
        : await drillApi.create(siteCode, body);
      notify.notifySuccess(existing ? 'Plan revised' : 'Drill planned', saved.drill.reference);
      onSaved(saved);
    } catch (error) {
      notify.notifyError(error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ActionDialog
      open
      title={existing ? `Revise ${existing.drill.reference}` : 'Plan a drill'}
      description="Saved as planned. Scheduling puts it on the calendar once the plan is ready."
      submitLabel={existing ? 'Save plan' : 'Save as planned'}
      submitting={submitting}
      submitDisabled={invalid}
      maxWidth="xl"
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        {existing ? (
          <TextField label="Site" value={siteCode} onChange={() => undefined} disabled />
        ) : (
          <SiteSelect value={siteCode} onChange={(value) => { setSiteCode(value); set('notificationTemplateId', undefined); set('audienceGroupIds', []); set('recipientZoneIds', []); }} required />
        )}
        <EnumField label="Drill type" value={plan.drillType} options={drillTypes} renderOptionLabel={(t) => drillTypeLabel[t]} required onChange={(value) => value && set('drillType', value as DrillType)} />
        <TextField label="Title" className="sm:col-span-2" value={plan.title} maxLength={200} required onChange={(value) => set('title', value)} />
        <TextAreaField label="Scenario" className="sm:col-span-2" value={plan.scenario ?? ''} maxLength={4000} onChange={(value) => set('scenario', value)} helperText="What participants are told is happening." />
        <TextField label="Expected participants" className="sm:col-span-2" value={plan.expectedParticipants ?? ''} maxLength={1000} onChange={(value) => set('expectedParticipants', value)} helperText="In words. The roll-call baseline is taken from live S160/S160a occupancy when the drill starts." />
        <DateTimeField label="Scheduled for" value={toLocalInputValue(plan.scheduledFor)} onChange={(value) => set('scheduledFor', value ? fromLocalInputValue(value) : undefined)} />
        <TextField label="Assembly point (S162a zone)" value={plan.assemblyZone ?? ''} maxLength={80} onChange={(value) => set('assemblyZone', value)} helperText="Where the roll-call is taken, e.g. BLOCK-A." />
        <SelectField
          label="S174 drill template"
          className="sm:col-span-2"
          value={plan.notificationTemplateId ?? ''}
          allowEmpty
          emptyLabel={templates.data?.length ? 'Choose a drill template' : 'No drill templates at this site'}
          options={(templates.data ?? []).map((t) => ({ value: t.templateId, label: `${t.templateCode} · ${t.title}` }))}
          helperText="Only S174 templates carrying the drill marker are offered - a drill can never go out on a real alert template."
          onChange={(value) => set('notificationTemplateId', value || undefined)}
        />
        <div className="sm:col-span-2">
          <CheckboxGroup
            label="Notify audience groups"
            columns={2}
            options={records.audiences.map((a) => ({ value: a.id, label: a.name, hint: `${a.recipientCount} recipients` }))}
            values={plan.audienceGroupIds}
            onChange={(values) => set('audienceGroupIds', values)}
            emptyMessage="No S174 audience groups at this site."
          />
        </div>
        <div className="sm:col-span-2">
          <CheckboxGroup
            label="Notify recipient zones"
            columns={2}
            options={records.zones.map((z) => ({ value: z.id, label: z.name }))}
            values={plan.recipientZoneIds}
            onChange={(values) => set('recipientZoneIds', values)}
            emptyMessage="No S174 recipient zones at this site."
          />
        </div>
      </div>

      <div className="mt-6 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <p className="font-semibold text-gray-900">Module expectations</p>
            <p className="text-theme-xs text-gray-600">
              What each participating module is expected to do, and how success is measured - so whether it worked has a defined answer.
              {plan.drillType === 'COMBINED' && ' A combined drill names at least two modules.'}
            </p>
          </div>
          <Button size="sm" variant="outline" onClick={() => set('expectations', [...plan.expectations, { module: 'S160A_ACCESS_CONTROL', expectation: '', successCriterion: '' }])}>
            <Plus size={14} strokeWidth={1.5} aria-hidden /> Add module
          </Button>
        </div>
        {plan.expectations.map((e, index) => (
          <div key={index} className="grid gap-3 rounded-md border border-gray-200 p-3 sm:grid-cols-[14rem_1fr_1fr_auto]">
            <SelectField label="Module" value={e.module} options={drillModules.map((m) => ({ value: m, label: drillModuleLabel[m] }))} onChange={(value) => setExpectation(index, { module: value as ExpectationInput['module'] })} />
            <TextField label="Expected to" value={e.expectation} maxLength={1000} required onChange={(value) => setExpectation(index, { expectation: value })} placeholder="Simulate lockdown of Block A" />
            <TextField label="Success measured by" value={e.successCriterion} maxLength={1000} onChange={(value) => setExpectation(index, { successCriterion: value })} placeholder="All doors report locked in 60 s" />
            <Button size="sm" variant="ghost" className="self-end" aria-label="Remove module" onClick={() => set('expectations', plan.expectations.filter((_, i) => i !== index))}>
              <Trash2 size={14} strokeWidth={1.5} aria-hidden />
            </Button>
          </div>
        ))}
      </div>

      {problems.length > 0 && (
        <div className="mt-5">
          <Banner
            variant={mustBeReady ? 'danger' : 'info'}
            heading={mustBeReady ? 'A scheduled drill must stay ready' : 'Before this can be scheduled'}
            subtext={<ul className="list-disc pl-5">{problems.map((p) => <li key={p}>{p}</li>)}</ul>}
          />
        </div>
      )}
    </ActionDialog>
  );
};

// ---- schedule ------------------------------------------------------------------------------------

export const ScheduleDialog = ({ detail, onClose, onDone }: { detail: DrillDetail; onClose: () => void; onDone: () => void }) => {
  const notify = useNotifier();
  const [when, setWhen] = useState(toLocalInputValue(detail.drill.plan.scheduledFor));
  const [submitting, setSubmitting] = useState(false);
  const problems = readinessProblems({ ...planToRequest(detail.drill.plan), scheduledFor: when ? fromLocalInputValue(when) : undefined });

  const submit = async () => {
    setSubmitting(true);
    try {
      await drillApi.schedule(detail.drill.id, detail.drill.metadata.version, when ? fromLocalInputValue(when) : undefined);
      notify.notifySuccess('Drill scheduled', detail.drill.reference);
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
      title={detail.drill.status === 'POSTPONED' ? `Reschedule ${detail.drill.reference}` : `Schedule ${detail.drill.reference}`}
      description="Puts the drill on the calendar. S174 confirms the template is a drill template before it is accepted."
      submitLabel="Schedule"
      submitting={submitting}
      submitDisabled={problems.length > 0}
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <div className="space-y-4">
        <DateTimeField label="Runs at" value={when} required onChange={setWhen} />
        {problems.length > 0 && (
          <Banner variant="warning" heading="Not ready to schedule" subtext={<ul className="list-disc pl-5">{problems.map((p) => <li key={p}>{p}</li>)}</ul>} />
        )}
      </div>
    </ActionDialog>
  );
};

// ---- postpone / cancel ------------------------------------------------------------------------------

export const ReasonDialog = ({ detail, kind, onClose, onDone }: { detail: DrillDetail; kind: 'postpone' | 'cancel'; onClose: () => void; onDone: () => void }) => {
  const notify = useNotifier();
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const cancel = kind === 'cancel';

  const submit = async () => {
    setSubmitting(true);
    try {
      await (cancel
        ? drillApi.cancel(detail.drill.id, reason.trim(), detail.drill.metadata.version)
        : drillApi.postpone(detail.drill.id, reason.trim(), detail.drill.metadata.version));
      notify.notifySuccess(cancel ? 'Drill cancelled' : 'Drill postponed', detail.drill.reference);
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
      title={`${cancel ? 'Cancel' : 'Postpone'} ${detail.drill.reference}`}
      description={cancel
        ? 'A cancelled drill keeps its plan and the reason, and never counts toward frequency compliance.'
        : 'The plan is kept so the drill can be rescheduled.'}
      submitLabel={cancel ? 'Cancel drill' : 'Postpone'}
      destructive={cancel}
      submitting={submitting}
      submitDisabled={!reason.trim()}
      onClose={onClose}
      onSubmit={() => void submit()}
    >
      <TextAreaField label="Reason" value={reason} maxLength={1000} required onChange={setReason} />
    </ActionDialog>
  );
};

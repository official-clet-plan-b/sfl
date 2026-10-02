import type { DrillRun } from 'modules/emergency/api/dto';
import { drillsApi } from 'modules/emergency/api/emergencyApi';
import {
  ConsequenceLine,
  ConsequencePanel,
} from 'modules/emergency/components/EmergencyFields';
import { formatElapsed, percentOf } from 'modules/emergency/components/emergencyFormat';
import type { SiteRecords } from 'modules/emergency/components/useSiteRecords';
import SiteSelect from 'shared/components/SiteSelect';
import { formatNumber } from 'shared/components/format';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { integerAtLeast, maxLength, required } from 'shared/validation/validators';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextAreaField, NumberField, SelectField } from 'modules/emergency/components/FormFields';

const twoColumn = 'grid gap-4 sm:grid-cols-2';

interface StartDrillDialogProps {
  open: boolean;
  defaultSiteCode: string;
  records: SiteRecords;
  onClose: () => void;
  onSaved: (drill: DrillRun) => void;
}

/**
 * Start a drill - `POST /drills`.
 *
 * A drill exercises the activation path and records how it performed. It sends nothing: there is no
 * fan-out, no channel record and no provider call, which is why the target is a number an operator
 * states rather than one derived from an audience group.
 */
export const StartDrillDialog = ({
  open,
  defaultSiteCode,
  records,
  onClose,
  onSaved,
}: StartDrillDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      scenarioId: '',
      targetRecipients: '',
      notes: '',
    },
    schema: {
      siteCode: required('Site'),
      targetRecipients: integerAtLeast('Target recipients', 0),
      notes: maxLength('Notes', 2000),
    },
    onSubmit: async (values) => {
      const saved = await drillsApi.start({
        siteCode: values.siteCode.trim().toUpperCase(),
        scenarioId: values.scenarioId || null,
        targetRecipients: Number(values.targetRecipients || 0),
        notes: values.notes.trim() || null,
      });
      onSaved(saved);
      onClose();
    },
  });

  const suggestedReach = records.audiences.reduce(
    (total, audience) => total + audience.recipientCount,
    0,
  );

  return (
    <ActionDialog
      open={open}
      title="Start a drill"
      description="Exercises the activation path and records how it performed. Nothing is broadcast."
      submitLabel="Start drill"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => form.setValues({ siteCode: value, scenarioId: '' })}
          {...form.fieldProps('siteCode')}
        />
        <SelectField
          label="Scenario"
          value={form.values.scenarioId}
          onChange={(value) => form.setValue('scenarioId', value)}
          allowEmpty
          emptyLabel="None"
          options={records.scenarios.map((scenario) => ({
            value: scenario.id,
            label: `${scenario.scenarioCode} · ${scenario.name}`,
          }))}
          {...form.fieldProps('scenarioId', 'The situation being rehearsed.')}
        />
      </div>

      <NumberField
        label="Target recipients"
        required
        value={form.values.targetRecipients}
        onChange={(value) => form.setValue('targetRecipients', value)}
        {...form.fieldProps(
          'targetRecipients',
          suggestedReach > 0
            ? `Every audience group at this site totals ${formatNumber(suggestedReach)} recipients.`
            : 'How many people the drill is meant to reach.',
        )}
      />

      <TextAreaField
        label="Notes"
        rows={3}
        value={form.values.notes}
        onChange={(value) => form.setValue('notes', value)}
        {...form.fieldProps('notes', 'What is being tested, and under what conditions.')}
      />

      <Banner variant="info" heading="The drill stays running until it is completed"
  subtext={<>Completion is where the figures are recorded - reached, acknowledged and elapsed time. A
        drill that is never completed contributes nothing to the performance record.</>} />
    </ActionDialog>
  );
};

interface CompleteDrillDialogProps {
  open: boolean;
  drill: DrillRun;
  onClose: () => void;
  onSaved: (drill: DrillRun) => void;
}

/**
 * Complete a drill - `POST /drills/{id}/complete`.
 *
 * The three figures entered here are the performance record. The acknowledgement rate the service
 * computes is against the **target**, not against how many were reached - so a drill that reached
 * half the site and had every one of them acknowledge still reports fifty per cent, which is the
 * honest reading. The dialog previews both so the difference is visible before it is filed.
 */
export const CompleteDrillDialog = ({
  open,
  drill,
  onClose,
  onSaved,
}: CompleteDrillDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      reachedRecipients: '',
      acknowledgedRecipients: '',
      activationSeconds: '',
      notes: drill.notes ?? '',
    },
    schema: {
      reachedRecipients: integerAtLeast('Reached', 0),
      acknowledgedRecipients: integerAtLeast('Acknowledged', 0),
      activationSeconds: integerAtLeast('Elapsed time', 0),
      notes: maxLength('Notes', 2000),
    },
    crossFieldValidate: (values) => {
      const reached = Number(values.reachedRecipients || 0);
      const acknowledged = Number(values.acknowledgedRecipients || 0);
      // Not a service rule - the domain accepts any non-negative pair. It is caught here because an
      // acknowledgement from somebody the drill never reached is a transcription error every time.
      return acknowledged > reached
        ? { acknowledgedRecipients: 'More acknowledgements than recipients reached.' }
        : {};
    },
    onSubmit: async (values) => {
      const saved = await drillsApi.complete(drill.id, {
        reachedRecipients: Number(values.reachedRecipients || 0),
        acknowledgedRecipients: Number(values.acknowledgedRecipients || 0),
        activationMillis: Number(values.activationSeconds || 0) * 1000,
        notes: values.notes.trim() || null,
      });
      onSaved(saved);
      onClose();
    },
  });

  const reached = Number(form.values.reachedRecipients || 0);
  const acknowledged = Number(form.values.acknowledgedRecipients || 0);

  return (
    <ActionDialog
      open={open}
      title={`Complete ${drill.drillNumber}`}
      description="Records what the drill achieved. A completed drill cannot be amended."
      submitLabel="Complete drill"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <NumberField
          label="Recipients reached"
          required
          value={form.values.reachedRecipients}
          onChange={(value) => form.setValue('reachedRecipients', value)}
          {...form.fieldProps(
            'reachedRecipients',
            `Out of a target of ${formatNumber(drill.targetRecipients)}.`,
          )}
        />
        <NumberField
          label="Recipients who acknowledged"
          required
          value={form.values.acknowledgedRecipients}
          onChange={(value) => form.setValue('acknowledgedRecipients', value)}
          {...form.fieldProps('acknowledgedRecipients')}
        />
      </div>

      <NumberField
        label="Elapsed time"
        required
        suffix="s"
        value={form.values.activationSeconds}
        onChange={(value) => form.setValue('activationSeconds', value)}
        {...form.fieldProps(
          'activationSeconds',
          'From starting the drill to the last recipient being reached.',
        )}
      />

      <TextAreaField
        label="Notes"
        rows={3}
        value={form.values.notes}
        onChange={(value) => form.setValue('notes', value)}
        {...form.fieldProps('notes', 'What failed, and what it points at.')}
      />

      <ConsequencePanel title="What will be recorded">
        <ConsequenceLine label="Target" value={formatNumber(drill.targetRecipients)} />
        <ConsequenceLine
          label="Reach against target"
          value={`${formatNumber(reached)} · ${percentOf(reached, drill.targetRecipients)}`}
        />
        <ConsequenceLine
          label="Acknowledgement rate against target"
          value={percentOf(acknowledged, drill.targetRecipients)}
        />
        <ConsequenceLine
          label="Acknowledgement rate among those reached"
          value={percentOf(acknowledged, reached)}
        />
        <ConsequenceLine
          label="Elapsed"
          value={formatElapsed(Number(form.values.activationSeconds || 0) * 1000)}
        />
      </ConsequencePanel>
    </ActionDialog>
  );
};

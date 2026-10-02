import { useMemo } from 'react';
import type { NotificationActivation } from 'modules/emergency/api/dto';
import {
  CHANNEL_DESCRIPTIONS,
  CHANNEL_TYPES,
  PRIORITIES,
  PRIORITY_DESCRIPTIONS,
  RETENTION_CLASSES,
  RETENTION_DESCRIPTIONS,
} from 'modules/emergency/api/enums';
import type { ChannelType, Priority, RetentionClass } from 'modules/emergency/api/enums';
import { activationsApi } from 'modules/emergency/api/emergencyApi';
import { emergencyNotificationProvider } from 'modules/emergency/api/notificationProvider';
import { closureBlockers } from 'modules/emergency/api/workflow';
import {
  CheckboxGroup,
  ConsequenceLine,
  ConsequencePanel,
  listChannels,
} from 'modules/emergency/components/EmergencyFields';
import { formatElapsed, totalsFor } from 'modules/emergency/components/emergencyFormat';
import type { SiteRecords } from 'modules/emergency/components/useSiteRecords';
import { humanise } from 'modules/fleet/api/enums';
import Icon from 'shared/components/Icon';
import SiteSelect from 'shared/components/SiteSelect';
import { formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField, TextAreaField, SelectField, EnumField } from 'modules/emergency/components/FormFields';

const twoColumn = 'grid gap-4 sm:grid-cols-2';

interface ComposeDialogProps {
  open: boolean;
  defaultSiteCode: string;
  records: SiteRecords;
  onClose: () => void;
  onSaved: (activation: NotificationActivation) => void;
}

/**
 * Compose an activation - `POST /activations`.
 *
 * This creates a **draft**. Nothing is sent: the draft goes on to submission, approval and only
 * then a send, which is the entire point of the routine path. The dialog says so twice - in the
 * description and in the submit label - because the operator using it may have reached for it in an
 * emergency and needs to know it is not the fast path.
 *
 * Choosing a scenario fills the template, priority and channels from it. That is a convenience, not
 * a constraint: the service reads only what is submitted, and all three stay editable.
 */
export const ComposeActivationDialog = ({
  open,
  defaultSiteCode,
  records,
  onClose,
  onSaved,
}: ComposeDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      scenarioId: '',
      templateId: '',
      priority: 'HIGH' as Priority,
      channels: [] as ChannelType[],
      audienceGroupIds: [] as string[],
      recipientZoneIds: [] as string[],
      incidentReference: '',
    },
    schema: {
      siteCode: required('Site'),
      templateId: required('Template'),
      channels: required('At least one channel'),
      audienceGroupIds: required('At least one audience group'),
      incidentReference: maxLength('Incident reference', 120),
    },
    onSubmit: async (values) => {
      const saved = await activationsApi.create({
        siteCode: values.siteCode.trim().toUpperCase(),
        scenarioId: values.scenarioId || null,
        templateId: values.templateId || null,
        audienceGroupIds: values.audienceGroupIds,
        recipientZoneIds: values.recipientZoneIds,
        channels: values.channels,
        priority: values.priority,
        incidentReference: values.incidentReference.trim() || null,
      });
      onSaved(saved);
      onClose();
    },
  });

  const chosenScenario = records.scenario(form.values.scenarioId);
  const chosenTemplate = records.template(form.values.templateId);
  const reach = records.audienceReach(form.values.audienceGroupIds);

  /** Applying a scenario fills what it implies, and leaves everything else as it was. */
  const applyScenario = (scenarioId: string) => {
    const scenario = records.scenario(scenarioId);
    if (!scenario) {
      form.setValue('scenarioId', scenarioId);
      return;
    }
    const template = records.template(scenario.defaultTemplateId);
    form.setValues({
      scenarioId,
      priority: scenario.priority,
      templateId: scenario.defaultTemplateId ?? form.values.templateId,
      channels: template ? template.channels : form.values.channels,
    });
  };

  return (
    <ActionDialog
      open={open}
      title="Compose an activation"
      description="Creates a draft. Nothing is sent until it has been submitted, approved and activated."
      submitLabel="Create draft"
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
          onChange={(value) =>
            form.setValues({
              siteCode: value,
              scenarioId: '',
              templateId: '',
              audienceGroupIds: [],
              recipientZoneIds: [],
            })
          }
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Incident reference"
          value={form.values.incidentReference}
          onChange={(value) => form.setValue('incidentReference', value)}
          {...form.fieldProps(
            'incidentReference',
            'Ties this broadcast to the incident record it serves.',
          )}
        />
      </div>

      <div className={twoColumn}>
        <SelectField
          label="Scenario"
          value={form.values.scenarioId}
          onChange={applyScenario}
          allowEmpty
          emptyLabel="None"
          options={records.scenarios.map((scenario) => ({
            value: scenario.id,
            label: `${scenario.scenarioCode} · ${scenario.name}`,
          }))}
          {...form.fieldProps('scenarioId', 'Fills the template, priority and channels below.')}
        />
        <SelectField
          label="Template"
          required
          value={form.values.templateId}
          onChange={(value) => {
            const template = records.template(value);
            form.setValues({
              templateId: value,
              channels: template ? template.channels : form.values.channels,
            });
          }}
          options={records.templates.map((template) => ({
            value: template.id,
            label: `${template.templateCode} · ${template.title}`,
          }))}
          {...form.fieldProps('templateId', 'The message recipients will receive.')}
        />
      </div>

      {chosenTemplate && (
        <ConsequencePanel title={`What "${chosenTemplate.title}" says`}>
          <p className="whitespace-pre-wrap text-gray-800">{chosenTemplate.body}</p>
        </ConsequencePanel>
      )}

      <EnumField
        label="Priority"
        required
        value={form.values.priority}
        options={PRIORITIES}
        onChange={(value) => form.setValue('priority', (value || 'HIGH') as Priority)}
        {...form.fieldProps('priority', PRIORITY_DESCRIPTIONS[form.values.priority])}
      />

      <CheckboxGroup
        label="Channels"
        required
        columns={2}
        values={form.values.channels}
        onChange={(values) => form.setValue('channels', values as ChannelType[])}
        options={CHANNEL_TYPES.map((channel) => ({
          value: channel,
          label: humanise(channel),
          hint: CHANNEL_DESCRIPTIONS[channel],
        }))}
        {...form.fieldProps(
          'channels',
          chosenTemplate
            ? `The template declares ${listChannels(chosenTemplate.channels)}. Any channel may still be chosen.`
            : 'An activation with no channel cannot be submitted.',
        )}
      />

      <CheckboxGroup
        label="Audience groups"
        required
        columns={2}
        values={form.values.audienceGroupIds}
        onChange={(values) => form.setValue('audienceGroupIds', values)}
        emptyMessage="No audience group is registered for this site. Create one before composing an activation."
        options={records.audiences.map((audience) => ({
          value: audience.id,
          label: audience.name,
          hint: `${formatNumber(audience.recipientCount)} recipients`,
        }))}
        {...form.fieldProps('audienceGroupIds')}
      />

      <CheckboxGroup
        label="Recipient zones"
        columns={2}
        values={form.values.recipientZoneIds}
        onChange={(values) => form.setValue('recipientZoneIds', values)}
        emptyMessage="No recipient zone is registered for this site."
        options={records.zones.map((zone) => ({
          value: zone.id,
          label: zone.name,
          hint: zone.locationReference ?? undefined,
        }))}
        {...form.fieldProps(
          'recipientZoneIds',
          'Optional. Naming zones records lockdown and CCTV preservation context against them.',
        )}
      />

      <ConsequencePanel title="What this draft would do once sent">
        <ConsequenceLine
          label="Recipients"
          value={`${formatNumber(reach)} across ${form.values.audienceGroupIds.length} group${form.values.audienceGroupIds.length === 1 ? '' : 's'}`}
        />
        <ConsequenceLine label="Channels" value={listChannels(form.values.channels)} />
        <ConsequenceLine
          label="Messages"
          value={formatNumber(reach * form.values.channels.length)}
        />
        {chosenScenario && (
          <ConsequenceLine label="Scenario" value={chosenScenario.name} />
        )}
      </ConsequencePanel>
    </ActionDialog>
  );
};

interface ActivationActionProps {
  open: boolean;
  activation: NotificationActivation;
  onClose: () => void;
  onDone: (activation: NotificationActivation) => void;
}

/**
 * Send an approved activation - `POST /activations/{id}/activate`.
 *
 * A confirmation with no fields, which is deliberate: everything that decides what goes out was
 * settled at composition and approved by somebody else. What this dialog adds is the last honest
 * statement of scale - how many people, over how many channels - because that is the number nobody
 * can take back once the button is pressed.
 */
export const SendActivationDialog = ({
  open,
  activation,
  records,
  onClose,
  onDone,
}: ActivationActionProps & { records: SiteRecords }) => {
  const reach = records.audienceReach(activation.audienceGroupIds);
  const form = useFleetForm({
    initialValues: {},
    onSubmit: async () => {
      const sent = await activationsApi.activate(activation.id);
      await emergencyNotificationProvider.dispatch({
        activationId: activation.id,
        channels: activation.channels,
        recipientCount: reach,
      });
      onDone(sent);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Send ${activation.activationNumber}`}
      description="Approved and cleared to send. This broadcast cannot be recalled."
      submitLabel="Send now"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      <ConsequencePanel title="What is about to be sent" tone="warning">
        <ConsequenceLine label="Message" value={records.templateName(activation.templateId)} />
        <ConsequenceLine label="Recipients" value={formatNumber(reach)} />
        <ConsequenceLine label="Channels" value={listChannels(activation.channels)} />
        <ConsequenceLine label="Messages" value={formatNumber(reach * activation.channels.length)} />
        <ConsequenceLine label="Priority" value={humanise(activation.priority)} />
      </ConsequencePanel>

      <Banner variant="info" heading="What happens next"
  subtext={<>The service hands each channel to its gateway and records the elapsed time. The activation
        stays live until an all-clear is sent, and it cannot be closed without a stated reason and
        closure evidence.</>} />
    </ActionDialog>
  );
};

/** Reject a submitted activation - `POST /activations/{id}/reject`. Terminal; there is no re-submit. */
export const RejectActivationDialog = ({
  open,
  activation,
  onClose,
  onDone,
}: ActivationActionProps) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Reason'), maxLength('Reason', 500)) },
    onSubmit: async (values) => {
      const rejected = await activationsApi.reject(activation.id, values.reason.trim());
      onDone(rejected);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Reject ${activation.activationNumber}`}
      description="The reason is kept on the record and is the only account of why this was not sent."
      submitLabel="Reject activation"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextAreaField
        label="Reason"
        required
        rows={4}
        autoFocus
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
      <Banner variant="warning" heading="Rejection is final"
  subtext={<>A rejected activation cannot be re-submitted. If the broadcast is still wanted, it has to be
        composed again - which is what puts a corrected version in front of an approver rather than
        an edited one.</>} />
    </ActionDialog>
  );
};

/** Cancel a not-yet-sent activation with an auditable reason. */
export const CancelActivationDialog = ({
  open,
  activation,
  onClose,
  onDone,
}: ActivationActionProps) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Cancellation reason'), maxLength('Cancellation reason', 500)) },
    onSubmit: async (values) => {
      const cancelled = await activationsApi.cancel(activation.id, { reason: values.reason.trim() });
      onDone(cancelled);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Cancel ${activation.activationNumber}`}
      description="Cancels an activation before any broadcast is sent."
      submitLabel="Cancel activation"
      submitting={form.submitting}
      formError={form.formError}
      destructive
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextAreaField
        label="Cancellation reason"
        required
        rows={4}
        autoFocus
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
      <Banner variant="warning" heading="Only pre-send activations can be cancelled"
  subtext={<>Once a broadcast is active, the operator path is all-clear, closure or degraded fallback -
        not cancellation. That keeps the record honest about whether a message actually went out.</>} />
    </ActionDialog>
  );
};

export const DegradedFallbackDialog = ({
  open,
  activation,
  onClose,
  onDone,
}: ActivationActionProps) => {
  const form = useFleetForm({
    initialValues: { fallbackPath: 'RECORDED_DIRECT_HANDOFF' },
    schema: {
      fallbackPath: compose(required('Fallback path'), maxLength('Fallback path', 120)),
    },
    onSubmit: async (values) => {
      const degraded = await activationsApi.degradedFallback(activation.id, {
        fallbackPath: values.fallbackPath.trim().toUpperCase(),
      });
      onDone(degraded);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Record degraded fallback for ${activation.activationNumber}`}
      description="Records that the live broadcast fell back to a degraded/direct path using the recorded adapter."
      submitLabel="Record fallback"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextField
        label="Fallback path"
        required
        autoFocus
        value={form.values.fallbackPath}
        onChange={(value) => form.setValue('fallbackPath', value)}
        {...form.fieldProps(
          'fallbackPath',
          'Example: RECORDED_DIRECT_HANDOFF, MANUAL_CALL_TREE, or SECURITY_RADIO_NET.',
        )}
      />
      <Banner variant="info" heading="No real vendor gateway is configured in Release 1"
  subtext={<>This calls the recorded gateway with degraded mode enabled. Real outbound delivery remains
        deferred for the later CLET Comms integration, but the activation, audit trail and channel
        records still show the fallback decision.</>} />
    </ActionDialog>
  );
};

export const AllClearDialog = ({ open, activation, onClose, onDone }: ActivationActionProps) => {
  const form = useFleetForm({
    initialValues: {},
    onSubmit: async () => {
      const cleared = await activationsApi.allClear(activation.id);
      onDone(cleared);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Send all-clear for ${activation.activationNumber}`}
      description="Stands the emergency down. The activation stays open until it is closed with evidence."
      submitLabel="Send all-clear"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Banner variant="info" heading="An all-clear is not a closure"
  subtext={<>It records that the emergency is over and moves the activation to all-clear pending. The
        record then still needs a closure reason and closure evidence
        {activation.mode === 'BREAK_GLASS' ? ', and after-the-fact approval' : ''} before it can be
        closed.</>} />
    </ActionDialog>
  );
};

/**
 * Record after-the-fact approval - `POST /activations/{id}/after-action-approval`.
 *
 * The counterweight to break-glass. The broadcast has already gone out with nobody approving it;
 * this is where somebody with the authority accounts for that, and until they do the activation
 * cannot be closed.
 */
export const AfterActionApprovalDialog = ({
  open,
  activation,
  onClose,
  onDone,
}: ActivationActionProps) => {
  const form = useFleetForm({
    initialValues: { justification: '' },
    schema: {
      justification: compose(required('Justification'), maxLength('Justification', 2000)),
    },
    onSubmit: async (values) => {
      const approved = await activationsApi.afterActionApproval(
        activation.id,
        values.justification.trim(),
      );
      onDone(approved);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Record after-the-fact approval for ${activation.activationNumber}`}
      description="Accounts for a broadcast that went out without prior approval."
      submitLabel="Record approval"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <ConsequencePanel title="What was sent without approval">
        <ConsequenceLine label="Activation" value={activation.activationNumber} />
        <ConsequenceLine label="Channels" value={listChannels(activation.channels)} />
        <ConsequenceLine label="Priority" value={humanise(activation.priority)} />
        <ConsequenceLine label="Time to send" value={formatElapsed(activation.fastLaneMillis)} />
      </ConsequencePanel>

      <TextAreaField
        label="Justification"
        required
        rows={5}
        autoFocus
        value={form.values.justification}
        onChange={(value) => form.setValue('justification', value)}
        {...form.fieldProps(
          'justification',
          'Why the emergency warranted bypassing approval. This is the audit answer.',
        )}
      />

      <Banner variant="info" heading="Your identity is the approval"
  subtext={<>The service records the approver from your credentials, not from anything typed here - the
        justification is the account, and the name on it is yours.</>} />
    </ActionDialog>
  );
};

/**
 * Close an activation - `POST /activations/{id}/close`.
 *
 * The service composes the delivery and acknowledgement summaries itself from the channel counters,
 * so there is nothing to enter for them. What it needs is a reason, an evidence reference and a
 * retention class - and, for a break-glass broadcast, after-the-fact approval already recorded.
 * `closureBlockers` lists all four separately, because the domain raises one message for the first
 * three together and an operator cannot tell from it which one is missing.
 */
export const ReopenActivationDialog = ({
  open,
  activation,
  onClose,
  onDone,
}: ActivationActionProps) => {
  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: { reason: compose(required('Reopen reason'), maxLength('Reopen reason', 500)) },
    onSubmit: async (values) => {
      const reopened = await activationsApi.reopen(activation.id, { reason: values.reason.trim() });
      onDone(reopened);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title={`Reopen ${activation.activationNumber}`}
      description="Reopens a closed activation so a follow-up correction or investigation can be recorded."
      submitLabel="Reopen activation"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextAreaField
        label="Reopen reason"
        required
        rows={4}
        autoFocus
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
      <Banner variant="info" heading="Reopened records must be closed again"
  subtext={<>The service puts the activation back into a reopened state. When the follow-up is complete,
        close it again with a closure reason and evidence.</>} />
    </ActionDialog>
  );
};

export const CloseActivationDialog = ({
  open,
  activation,
  onClose,
  onDone,
}: ActivationActionProps) => {
  const status = useApiQuery(
    (signal) => activationsApi.status(activation.id, signal),
    [activation.id],
  );

  const form = useFleetForm({
    initialValues: {
      reason: '',
      evidenceFileName: '',
      evidenceContentType: 'application/pdf',
      evidenceStorageReference: '',
      evidenceSha256: '',
      retentionClass: 'INCIDENT_10_YEARS' as RetentionClass,
    },
    schema: {
      reason: compose(required('Closure reason'), maxLength('Closure reason', 2000)),
      evidenceStorageReference: compose(
        required('Evidence storage reference'),
        maxLength('Evidence storage reference', 500),
      ),
      evidenceFileName: maxLength('File name', 255),
      evidenceContentType: maxLength('Content type', 120),
      evidenceSha256: maxLength('SHA-256 hash', 128),
      retentionClass: required('Retention class'),
    },
    onSubmit: async (values) => {
      const closed = await activationsApi.close(activation.id, {
        reason: values.reason.trim(),
        evidenceFileName: values.evidenceFileName.trim() || null,
        evidenceContentType: values.evidenceContentType.trim() || null,
        evidenceStorageReference: values.evidenceStorageReference.trim(),
        evidenceSha256: values.evidenceSha256.trim() || null,
        retentionClass: values.retentionClass,
      });
      onDone(closed);
      onClose();
    },
  });

  const blockers = useMemo(
    () => closureBlockers(activation, form.values),
    [activation, form.values],
  );
  const outstanding = blockers.filter((blocker) => !blocker.cleared);
  const totals = totalsFor(status.data?.channels ?? []);

  return (
    <ActionDialog
      open={open}
      title={`Close ${activation.activationNumber}`}
      description="Files the closure record. The delivery and acknowledgement summaries are written by the service."
      submitLabel="Close activation"
      submitting={form.submitting}
      submitDisabled={outstanding.some((blocker) => blocker.label === 'After-the-fact approval')}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div>
        <p className="mb-2 text-theme-sm font-medium text-gray-800">Path to closure</p>
        <ul className="space-y-2">
          {blockers.map((blocker) => (
            <li key={blocker.label} className="flex items-start gap-2">
              <Icon
                name={blocker.cleared ? 'check-circle' : 'alert-circle'}
                size={16}
                className={
                  blocker.cleared ? 'mt-0.5 shrink-0 text-success-700' : 'mt-0.5 shrink-0 text-error-800'
                }
              />
              <span className="min-w-0 text-theme-sm">
                <span className="font-medium text-gray-900">{blocker.label}</span>
                <span className="text-gray-600"> - {blocker.detail}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>

      {activation.mode === 'BREAK_GLASS' && !activation.afterActionApprovedBy && (
        <Banner variant="danger" heading="After-the-fact approval has not been recorded"
  subtext={<>This broadcast went out without prior approval and the service will refuse closure until
          somebody with the after-action approval permission accounts for it. Record that first.</>} />
      )}

      <TextAreaField
        label="Closure reason"
        required
        rows={4}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason', 'What happened, and on what basis the record is being closed.')}
      />

      <div className={twoColumn}>
        <TextField
          label="Evidence storage reference"
          required
          value={form.values.evidenceStorageReference}
          onChange={(value) => form.setValue('evidenceStorageReference', value)}
          {...form.fieldProps(
            'evidenceStorageReference',
            'Where the closure summary is filed. The service registers it as evidence.',
          )}
        />
        <EnumField
          label="Retention class"
          required
          value={form.values.retentionClass}
          options={RETENTION_CLASSES}
          onChange={(value) =>
            form.setValue('retentionClass', (value || 'INCIDENT_10_YEARS') as RetentionClass)
          }
          {...form.fieldProps(
            'retentionClass',
            RETENTION_DESCRIPTIONS[form.values.retentionClass],
          )}
        />
        <TextField
          label="File name"
          value={form.values.evidenceFileName}
          onChange={(value) => form.setValue('evidenceFileName', value)}
          {...form.fieldProps('evidenceFileName')}
        />
        <TextField
          label="Content type"
          value={form.values.evidenceContentType}
          onChange={(value) => form.setValue('evidenceContentType', value)}
          {...form.fieldProps('evidenceContentType')}
        />
      </div>

      <TextField
        label="SHA-256 hash"
        value={form.values.evidenceSha256}
        onChange={(value) => form.setValue('evidenceSha256', value)}
        {...form.fieldProps(
          'evidenceSha256',
          'Optional, and worth supplying - it is what proves the filed document was not altered later.',
        )}
      />

      <ConsequencePanel title="What the service will write into the closure record">
        <ConsequenceLine label="Channels" value={formatNumber(status.data?.channels.length ?? 0)} />
        <ConsequenceLine label="Sent" value={formatNumber(totals.sent)} />
        <ConsequenceLine label="Delivered" value={formatNumber(totals.delivered)} />
        <ConsequenceLine label="Failed" value={formatNumber(totals.failed)} />
        <ConsequenceLine
          label="Acknowledged"
          value={formatNumber(status.data?.acknowledgements ?? 0)}
        />
      </ConsequencePanel>
    </ActionDialog>
  );
};

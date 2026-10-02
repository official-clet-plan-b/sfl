import { Banner } from '@rfdtech/components';
import { WorkflowItemResponse } from 'modules/fleet/api/dto';
import {
  FLEET_WORKFLOW_TYPES,
  FleetWorkflowType,
  OPERATING_MODES,
  OperatingMode,
  WORKFLOW_PRIORITIES,
  WORKFLOW_SEVERITIES,
  WorkflowPriority,
  WorkflowSeverity,
} from 'modules/fleet/api/enums';
import { workflowApi } from 'modules/fleet/api/fleetApi';
import { EvidenceSelect } from 'shared/components/EvidenceSelect';
import FleetFormDialog from 'modules/fleet/components/FleetFormDialog';
import SiteSelect from 'shared/components/SiteSelect';
import { EnumSelect, TextAreaInput, TextInput } from 'modules/fleet/components/formFields';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import { searchEvidenceChoices } from 'modules/fleet/api/fleetApi';

const twoColumn = 'grid gap-4 sm:grid-cols-2';

interface BaseProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}

/* Raise - POST /api/v1/fleet/workflow-items */
export const RaiseWorkflowItemDialog = ({
  open,
  onClose,
  onSaved,
  defaultSiteCode,
  relatedRecordType,
  relatedRecordId,
}: BaseProps & {
  defaultSiteCode: string;
  relatedRecordType?: string;
  relatedRecordId?: string;
}) => {
  const form = useFleetForm({
    initialValues: {
      workflowType: '' as FleetWorkflowType | '',
      siteCode: defaultSiteCode,
      title: '',
      description: '',
      priority: 'MEDIUM' as WorkflowPriority,
      severity: 'MODERATE' as WorkflowSeverity,
      operatingMode: 'ROUTINE' as OperatingMode,
      assignee: '',
    },
    schema: {
      workflowType: required('Workflow type'),
      siteCode: compose(required('Site code'), maxLength('Site code', 40)),
      title: compose(required('Title'), maxLength('Title', 200)),
      description: compose(required('Description'), maxLength('Description', 2000)),
      priority: required('Priority'),
      severity: required('Severity'),
      assignee: maxLength('Assignee', 160),
    },
    onSubmit: async (values) => {
      await workflowApi.raise({
        workflowType: values.workflowType as FleetWorkflowType,
        relatedRecordType: relatedRecordType ?? null,
        relatedRecordId: relatedRecordId ?? null,
        siteCode: values.siteCode.trim().toUpperCase(),
        title: values.title.trim(),
        description: values.description.trim(),
        priority: values.priority,
        severity: values.severity,
        operatingMode: values.operatingMode,
        assignee: values.assignee.trim() || null,
      });
      onSaved();
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Raise a workflow item"
      description="Priority and severity drive the SLA target the service applies."
      submitLabel="Raise item"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="md"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className={twoColumn}>
        <EnumSelect
          label="Workflow type"
          required
          value={form.values.workflowType}
          options={FLEET_WORKFLOW_TYPES}
          onChange={(value) => form.setValue('workflowType', value)}
          {...form.fieldProps('workflowType')}
        />
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <EnumSelect
          label="Priority"
          required
          value={form.values.priority}
          options={WORKFLOW_PRIORITIES}
          onChange={(value) => form.setValue('priority', (value || 'MEDIUM') as WorkflowPriority)}
          {...form.fieldProps('priority')}
        />
        <EnumSelect
          label="Severity"
          required
          value={form.values.severity}
          options={WORKFLOW_SEVERITIES}
          onChange={(value) => form.setValue('severity', (value || 'MODERATE') as WorkflowSeverity)}
          {...form.fieldProps('severity')}
        />
        <EnumSelect
          label="Operating mode"
          value={form.values.operatingMode}
          options={OPERATING_MODES}
          onChange={(value) =>
            form.setValue('operatingMode', (value || 'ROUTINE') as OperatingMode)
          }
          {...form.fieldProps('operatingMode')}
        />
        <TextInput
          label="Assignee"
          value={form.values.assignee}
          onChange={(value) => form.setValue('assignee', value)}
          {...form.fieldProps('assignee', 'Optional - leave blank to raise unassigned.')}
        />
      </div>
      <TextInput
        label="Title"
        required
        value={form.values.title}
        onChange={(value) => form.setValue('title', value)}
        {...form.fieldProps('title')}
      />
      <TextAreaInput
        label="Description"
        required
        rows={3}
        value={form.values.description}
        onChange={(value) => form.setValue('description', value)}
        {...form.fieldProps('description')}
      />
    </FleetFormDialog>
  );
};

/* Assign - PATCH /api/v1/fleet/workflow-items/{id}/assignment */
export const AssignWorkflowItemDialog = ({
  open,
  onClose,
  onSaved,
  item,
}: BaseProps & { item: WorkflowItemResponse }) => {
  const form = useFleetForm({
    initialValues: { assignee: item.assignee ?? '', reason: '' },
    schema: {
      assignee: compose(required('Assignee'), maxLength('Assignee', 160)),
      reason: maxLength('Reason', 1000),
    },
    onSubmit: async (values) => {
      await workflowApi.assign(item.id, {
        assignee: values.assignee.trim(),
        reason: values.reason.trim() || undefined,
        expectedVersion: item.version,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title={item.assignee ? 'Reassign item' : 'Assign item'}
      description={`${item.workflowNumber} · ${item.title}`}
      submitLabel="Save assignment"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextInput
        label="Assignee"
        required
        value={form.values.assignee}
        onChange={(value) => form.setValue('assignee', value)}
        {...form.fieldProps('assignee')}
      />
      <TextInput
        label="Reason"
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
    </FleetFormDialog>
  );
};

/* Close - PATCH /api/v1/fleet/workflow-items/{id}/closure */
export const CloseWorkflowItemDialog = ({
  open,
  onClose,
  onSaved,
  item,
}: BaseProps & { item: WorkflowItemResponse }) => {
  const form = useFleetForm({
    initialValues: { closureReason: '', closureEvidenceId: '' },
    schema: {
      closureReason: compose(required('Closure reason'), maxLength('Closure reason', 1000)),
      closureEvidenceId: required('Closure evidence'),
    },
    onSubmit: async (values) => {
      await workflowApi.close(item.id, {
        closureReason: values.closureReason.trim(),
        closureEvidenceId: values.closureEvidenceId.trim(),
        expectedVersion: item.version,
      });
      onSaved();
      onClose();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Close workflow item"
      description="Closure reason and evidence are both mandatory."
      submitLabel="Close item"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <Banner
        variant="info"
        heading={
          <>
            Evidence is required to close this item. Register one under Evidence &amp; audit first
            if none is listed below.
          </>
        }
      />
      <EvidenceSelect
        label="Closure evidence"
        required
        search={searchEvidenceChoices}
        relatedRecordType={item.relatedRecordType}
        relatedRecordId={item.relatedRecordId}
        value={form.values.closureEvidenceId}
        onChange={(value) => form.setValue('closureEvidenceId', value)}
        {...form.fieldProps('closureEvidenceId')}
      />
      <TextAreaInput
        label="Closure reason"
        required
        rows={3}
        value={form.values.closureReason}
        onChange={(value) => form.setValue('closureReason', value)}
        {...form.fieldProps('closureReason')}
      />
    </FleetFormDialog>
  );
};

/**
 * Reason-only transitions: escalate, cancel, reopen, hold and resume.
 *
 * One dialog because the shape is identical; the caller supplies the verb and the request. Hold
 * and resume are the only two where the reason is optional.
 */
export const ReasonTransitionDialog = ({
  open,
  onClose,
  onSaved,
  item,
  transition,
}: BaseProps & {
  item: WorkflowItemResponse;
  transition: 'escalate' | 'cancel' | 'reopen' | 'hold' | 'resume';
}) => {
  const optionalReason = transition === 'hold' || transition === 'resume';

  const labels: Record<typeof transition, { title: string; submit: string; note?: string }> = {
    escalate: {
      title: 'Escalate item',
      submit: 'Escalate',
      note: 'Manual escalation is privileged - the service refuses it without the approval permission.',
    },
    cancel: {
      title: 'Cancel item',
      submit: 'Cancel item',
      note: 'Privileged. The record stays in history.',
    },
    reopen: { title: 'Reopen item', submit: 'Reopen', note: 'Privileged.' },
    hold: { title: 'Place item on hold', submit: 'Place on hold' },
    resume: { title: 'Resume item', submit: 'Resume' },
  };

  const form = useFleetForm({
    initialValues: { reason: '' },
    schema: {
      reason: optionalReason
        ? maxLength('Reason', 1000)
        : compose(required('Reason'), maxLength('Reason', 1000)),
    },
    onSubmit: async (values) => {
      const reason = values.reason.trim();
      const expectedVersion = item.version;
      if (transition === 'escalate') {
        await workflowApi.escalate(item.id, { reason, expectedVersion });
      } else if (transition === 'cancel') {
        await workflowApi.cancel(item.id, { reason, expectedVersion });
      } else if (transition === 'reopen') {
        await workflowApi.reopen(item.id, { reason, expectedVersion });
      } else {
        await workflowApi.holdOrResume(item.id, {
          resume: transition === 'resume',
          reason: reason || undefined,
          expectedVersion,
        });
      }
      onSaved();
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title={labels[transition].title}
      description={`${item.workflowNumber} · ${item.title}`}
      submitLabel={labels[transition].submit}
      submitting={form.submitting}
      formError={form.formError}
      destructive={transition === 'cancel'}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {labels[transition].note && (
        <Banner variant="info" heading={<>{labels[transition].note}</>} />
      )}
      <TextAreaInput
        label="Reason"
        required={!optionalReason}
        rows={3}
        value={form.values.reason}
        onChange={(value) => form.setValue('reason', value)}
        {...form.fieldProps('reason')}
      />
    </FleetFormDialog>
  );
};

/* Comment - POST /api/v1/fleet/workflow-items/{id}/comments */
export const AddCommentDialog = ({
  open,
  onClose,
  onSaved,
  item,
}: BaseProps & { item: WorkflowItemResponse }) => {
  const form = useFleetForm({
    initialValues: { body: '' },
    schema: { body: compose(required('Comment'), maxLength('Comment', 4000)) },
    onSubmit: async (values) => {
      await workflowApi.comment(item.id, { body: values.body.trim() });
      onSaved();
      onClose();
      form.reset();
    },
  });

  return (
    <FleetFormDialog
      open={open}
      title="Add a comment"
      description="Comments are immutable once recorded."
      submitLabel="Add comment"
      submitting={form.submitting}
      formError={form.formError}
      onClose={onClose}
      onSubmit={form.submit}
    >
      <TextAreaInput
        label="Comment"
        required
        rows={4}
        value={form.values.body}
        onChange={(value) => form.setValue('body', value)}
        {...form.fieldProps('body')}
      />
    </FleetFormDialog>
  );
};

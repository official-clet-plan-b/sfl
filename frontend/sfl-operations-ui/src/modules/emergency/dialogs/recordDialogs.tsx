import { CHANNEL_DESCRIPTIONS, CHANNEL_TYPES, PRIORITIES } from 'modules/emergency/api/enums';
import type { ChannelType, Priority } from 'modules/emergency/api/enums';
import type {
  AudienceGroup,
  EmergencyScenario,
  NotificationTemplate,
  RecipientZone,
} from 'modules/emergency/api/dto';
import { emergencyRecordsApi } from 'modules/emergency/api/emergencyApi';
import { CheckboxGroup } from 'modules/emergency/components/EmergencyFields';
import { humanise } from 'modules/fleet/api/enums';
import SiteSelect from 'shared/components/SiteSelect';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, integerAtLeast, maxLength, required } from 'shared/validation/validators';
import { Banner } from '@rfdtech/components';
import ActionDialog from 'modules/emergency/components/ActionDialog';
import { TextField, TextAreaField, NumberField, SelectField, EnumField, CheckboxField } from 'modules/emergency/components/FormFields';

const twoColumn = 'grid gap-4 sm:grid-cols-2';

/** Every record identifier on this service is optional - blank means the service allocates one. */
const codeHint = 'Leave blank and the service allocates one.';

interface TemplateDialogProps {
  open: boolean;
  defaultSiteCode: string;
  onClose: () => void;
  onSaved: (template: NotificationTemplate) => void;
}

/**
 * Create a notification template - `POST /templates`.
 *
 * A template must declare at least one channel; the domain refuses one with none. The break-glass
 * flag is the consequential field on this form, and it is the reason the dialog explains rather
 * than merely labels it: marking a template break-glass eligible is what later lets a broadcast go
 * out to the whole site without anybody approving it first.
 */
export const CreateTemplateDialog = ({
  open,
  defaultSiteCode,
  onClose,
  onSaved,
}: TemplateDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      templateCode: '',
      title: '',
      body: '',
      channels: [] as ChannelType[],
      breakGlassEligible: false,
    },
    schema: {
      siteCode: required('Site'),
      templateCode: maxLength('Template code', 60),
      title: compose(required('Title'), maxLength('Title', 200)),
      body: compose(required('Message body'), maxLength('Message body', 4000)),
      channels: required('At least one channel'),
    },
    onSubmit: async (values) => {
      const saved = await emergencyRecordsApi.createTemplate({
        siteCode: values.siteCode.trim().toUpperCase(),
        templateCode: values.templateCode.trim() || null,
        title: values.title.trim(),
        body: values.body.trim(),
        channels: values.channels,
        breakGlassEligible: values.breakGlassEligible,
      });
      onSaved(saved);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title="Create a notification template"
      description="Reusable message text, bound to the channels it may be sent over."
      submitLabel="Create template"
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
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Template code"
          value={form.values.templateCode}
          onChange={(value) => form.setValue('templateCode', value)}
          {...form.fieldProps('templateCode', codeHint)}
        />
      </div>

      <TextField
        label="Title"
        required
        value={form.values.title}
        onChange={(value) => form.setValue('title', value)}
        {...form.fieldProps('title', 'What this template is for - "Building evacuation".')}
      />

      <TextAreaField
        label="Message body"
        required
        rows={5}
        value={form.values.body}
        onChange={(value) => form.setValue('body', value)}
        {...form.fieldProps(
          'body',
          'The text recipients receive. Write it to be read once, under pressure.',
        )}
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
        {...form.fieldProps('channels', 'A template with no channel cannot be saved.')}
      />

      <CheckboxField
        checked={form.values.breakGlassEligible}
        onChange={(checked) => form.setValue('breakGlassEligible', checked)}
        label="Break-glass eligible"
        hint="An authorised role may send this without anybody approving it first."
      />

      {form.values.breakGlassEligible && (
        <Banner variant="warning" heading="This template can bypass approval"
  subtext={<>Marking it eligible is what lets a break-glass broadcast go out immediately during a
          declared emergency. Closure of any such activation is then blocked until after-the-fact
          approval is recorded against it. Reserve the flag for templates whose content is correct
          for a real emergency with no review.</>} />
      )}
    </ActionDialog>
  );
};

interface ScenarioDialogProps {
  open: boolean;
  defaultSiteCode: string;
  templates: NotificationTemplate[];
  onClose: () => void;
  onSaved: (scenario: EmergencyScenario) => void;
}

/**
 * Create an emergency scenario - `POST /scenarios`.
 *
 * A scenario is the named situation an activation cites, carrying a default template and the
 * priority that drives the acknowledgement SLA. Its break-glass flag is independent of the
 * template's: `BreakGlassPolicy` accepts either one, so a scenario marked eligible makes every
 * template usable without approval when that scenario is cited.
 */
export const CreateScenarioDialog = ({
  open,
  defaultSiteCode,
  templates,
  onClose,
  onSaved,
}: ScenarioDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      scenarioCode: '',
      name: '',
      priority: 'HIGH' as Priority,
      defaultTemplateId: '',
      breakGlassEligible: false,
    },
    schema: {
      siteCode: required('Site'),
      scenarioCode: maxLength('Scenario code', 60),
      name: compose(required('Name'), maxLength('Name', 200)),
      priority: required('Priority'),
    },
    onSubmit: async (values) => {
      const saved = await emergencyRecordsApi.createScenario({
        siteCode: values.siteCode.trim().toUpperCase(),
        scenarioCode: values.scenarioCode.trim() || null,
        name: values.name.trim(),
        priority: values.priority,
        defaultTemplateId: values.defaultTemplateId || null,
        breakGlassEligible: values.breakGlassEligible,
      });
      onSaved(saved);
      onClose();
    },
  });

  const chosenTemplate = templates.find(
    (template) => template.id === form.values.defaultTemplateId,
  );

  return (
    <ActionDialog
      open={open}
      title="Create an emergency scenario"
      description="The named situation an activation cites, with its default template and priority."
      submitLabel="Create scenario"
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
          onChange={(value) => form.setValues({ siteCode: value, defaultTemplateId: '' })}
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Scenario code"
          value={form.values.scenarioCode}
          onChange={(value) => form.setValue('scenarioCode', value)}
          {...form.fieldProps('scenarioCode', codeHint)}
        />
      </div>

      <TextField
        label="Name"
        required
        value={form.values.name}
        onChange={(value) => form.setValue('name', value)}
        {...form.fieldProps('name', 'How responders refer to it - "Fire - full evacuation".')}
      />

      <div className={twoColumn}>
        <EnumField
          label="Priority"
          required
          value={form.values.priority}
          options={PRIORITIES}
          onChange={(value) => form.setValue('priority', (value || 'HIGH') as Priority)}
          {...form.fieldProps('priority', 'Drives the acknowledgement SLA.')}
        />
        <SelectField
          label="Default template"
          value={form.values.defaultTemplateId}
          onChange={(value) => form.setValue('defaultTemplateId', value)}
          allowEmpty
          emptyLabel="None"
          options={templates.map((template) => ({
            value: template.id,
            label: `${template.templateCode} · ${template.title}`,
          }))}
          {...form.fieldProps(
            'defaultTemplateId',
            'Offered first when this scenario is activated.',
          )}
        />
      </div>

      <CheckboxField
        checked={form.values.breakGlassEligible}
        onChange={(checked) => form.setValue('breakGlassEligible', checked)}
        label="Break-glass eligible"
        hint="Any template may be sent without approval when this scenario is cited."
      />

      {form.values.breakGlassEligible && (
        <Banner variant="warning" heading="Eligibility is decided by either record"
  subtext={<>A break-glass send is allowed when the template <strong>or</strong> the scenario is
          eligible - not only when both are. Marking this scenario eligible therefore makes every
          template usable without approval whenever it is cited, including
          {chosenTemplate && !chosenTemplate.breakGlassEligible
            ? ` "${chosenTemplate.title}", which is not itself eligible.`
            : ' templates that are not themselves eligible.'}</>} />
      )}
    </ActionDialog>
  );
};

interface AudienceDialogProps {
  open: boolean;
  defaultSiteCode: string;
  onClose: () => void;
  onSaved: (audience: AudienceGroup) => void;
}

/**
 * Create an audience group - `POST /audience-groups`.
 *
 * `recipientCount` is not decoration: it is the number the service fans out to and the denominator
 * every delivery and acknowledgement figure is read against. A group whose count is stale makes an
 * activation look under-delivered, so the dialog says what the number is for.
 */
export const CreateAudienceDialog = ({
  open,
  defaultSiteCode,
  onClose,
  onSaved,
}: AudienceDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      groupCode: '',
      name: '',
      directoryReference: '',
      recipientCount: '0',
    },
    schema: {
      siteCode: required('Site'),
      groupCode: maxLength('Group code', 60),
      name: compose(required('Name'), maxLength('Name', 200)),
      directoryReference: maxLength('Directory reference', 200),
      recipientCount: integerAtLeast('Recipient count', 0),
    },
    onSubmit: async (values) => {
      const saved = await emergencyRecordsApi.createAudienceGroup({
        siteCode: values.siteCode.trim().toUpperCase(),
        groupCode: values.groupCode.trim() || null,
        name: values.name.trim(),
        directoryReference: values.directoryReference.trim() || null,
        recipientCount: Number(values.recipientCount || 0),
      });
      onSaved(saved);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title="Create an audience group"
      description="A named set of recipients, held by reference into the directory."
      submitLabel="Create group"
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
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Group code"
          value={form.values.groupCode}
          onChange={(value) => form.setValue('groupCode', value)}
          {...form.fieldProps('groupCode', codeHint)}
        />
      </div>

      <TextField
        label="Name"
        required
        value={form.values.name}
        onChange={(value) => form.setValue('name', value)}
        {...form.fieldProps('name', 'As responders would name it - "All staff", "Ward wardens".')}
      />

      <div className={twoColumn}>
        <TextField
          label="Directory reference"
          value={form.values.directoryReference}
          onChange={(value) => form.setValue('directoryReference', value)}
          {...form.fieldProps(
            'directoryReference',
            'Where the contact detail lives. It never reaches this dashboard.',
          )}
        />
        <NumberField
          label="Recipient count"
          value={form.values.recipientCount}
          onChange={(value) => form.setValue('recipientCount', value)}
          {...form.fieldProps(
            'recipientCount',
            'What the service fans out to, and what reach is measured against.',
          )}
        />
      </div>

      <Banner variant="info" heading="Contact detail stays in the directory"
  subtext={<>This record holds a pointer and a size, never a phone number or an address. Keeping the
        count current is what makes a delivery figure mean anything - a group sized at zero sends to
        nobody and still reports success.</>} />
    </ActionDialog>
  );
};

interface ZoneDialogProps {
  open: boolean;
  defaultSiteCode: string;
  onClose: () => void;
  onSaved: (zone: RecipientZone) => void;
}

/**
 * Create a recipient zone - `POST /recipient-zones`.
 *
 * A zone narrows a broadcast to a place. It also carries an integration consequence worth stating:
 * naming zones on an activation is what makes the service record lockdown and CCTV preservation
 * context against them. SFL never actuates that hardware - it records the context (Arch §0E).
 */
export const CreateZoneDialog = ({
  open,
  defaultSiteCode,
  onClose,
  onSaved,
}: ZoneDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      zoneCode: '',
      name: '',
      locationReference: '',
    },
    schema: {
      siteCode: required('Site'),
      zoneCode: maxLength('Zone code', 60),
      name: compose(required('Name'), maxLength('Name', 200)),
      locationReference: maxLength('Location reference', 200),
    },
    onSubmit: async (values) => {
      const saved = await emergencyRecordsApi.createRecipientZone({
        siteCode: values.siteCode.trim().toUpperCase(),
        zoneCode: values.zoneCode.trim() || null,
        name: values.name.trim(),
        locationReference: values.locationReference.trim() || null,
      });
      onSaved(saved);
      onClose();
    },
  });

  return (
    <ActionDialog
      open={open}
      title="Create a recipient zone"
      description="A building, floor or room a broadcast can be narrowed to."
      submitLabel="Create zone"
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
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <TextField
          label="Zone code"
          value={form.values.zoneCode}
          onChange={(value) => form.setValue('zoneCode', value)}
          {...form.fieldProps('zoneCode', codeHint)}
        />
      </div>

      <TextField
        label="Name"
        required
        value={form.values.name}
        onChange={(value) => form.setValue('name', value)}
        {...form.fieldProps('name', 'As it is signposted on site - "Block B", "Laboratory wing".')}
      />

      <TextField
        label="Location reference"
        value={form.values.locationReference}
        onChange={(value) => form.setValue('locationReference', value)}
        {...form.fieldProps('locationReference', 'The facilities location this zone maps to.')}
      />

      <Banner variant="info" heading="Naming a zone records context, it does not actuate anything"
  subtext={<>When an activation names zones, the service records access-control lockdown and CCTV
        preservation context against each of them. SFL governs and evidences; certified life-safety
        hardware is never driven from here.</>} />
    </ActionDialog>
  );
};

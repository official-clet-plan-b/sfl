import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import type { BreakGlassRequest } from 'modules/emergency/api/dto';
import {
  CHANNEL_DESCRIPTIONS,
  CHANNEL_TYPES,
  PRIORITIES,
  PRIORITY_DESCRIPTIONS,
} from 'modules/emergency/api/enums';
import type { ChannelType, Priority } from 'modules/emergency/api/enums';
import { activationsApi } from 'modules/emergency/api/emergencyApi';
import { afterActionOutstanding, breakGlassEligible, canBreakGlass } from 'modules/emergency/api/workflow';
import {
  ActivationStatusBadge,
  CheckboxGroup,
  ConsequenceLine,
  ConsequencePanel,
  listChannels,
} from 'modules/emergency/components/EmergencyFields';
import { formatElapsed } from 'modules/emergency/components/emergencyFormat';
import { useSiteRecords } from 'modules/emergency/components/useSiteRecords';
import { ConfirmBreakGlassDialog } from 'modules/emergency/dialogs/breakGlassDialogs';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { emergencyPaths } from 'shared/layout/navigation';
import type { NotificationActivation } from 'modules/emergency/api/dto';
import { Button, Banner, type TableColumn } from '@rfdtech/components';
import RegisterTable from 'modules/emergency/components/RegisterTable';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import { TextField, SelectField, EnumField } from 'modules/emergency/components/FormFields';
import { CellStack } from 'modules/emergency/components/RegisterTable';

/**
 * Break-glass activation - the declared-emergency path that sends without approval (Arch §0E).
 *
 * A destination of its own rather than a mode on the compose dialog, for two reasons that pull the
 * same way. It must never be reached by accident from a routine flow, and in a real emergency it
 * must be reachable in one click from anywhere - a screen that is both a warning and the fastest
 * path is exactly what this needs to be.
 *
 * The eligibility rule is stated on the form because `BreakGlassPolicy` is an OR: the template
 * **or** the scenario being marked eligible is enough. An operator who reads "not break-glass
 * eligible" beside a template and gives up would be wrong when the scenario carries it.
 */
const BreakGlassPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [scenarioId, setScenarioId] = useState('');
  const [templateId, setTemplateId] = useState('');
  const [priority, setPriority] = useState<Priority>('CRITICAL');
  const [channels, setChannels] = useState<ChannelType[]>([]);
  const [audienceGroupIds, setAudienceGroupIds] = useState<string[]>([]);
  const [recipientZoneIds, setRecipientZoneIds] = useState<string[]>([]);
  const [incidentReference, setIncidentReference] = useState('');
  const [confirming, setConfirming] = useState(false);

  const records = useSiteRecords(siteCode);

  /** Every break-glass send at this site - a server-side filter now, not a sieve over a window. */
  const history = useApiQuery(
    (signal) => activationsApi.search({ siteCode, mode: 'BREAK_GLASS', size: 100 }, signal),
    [siteCode],
  );

  const chosenTemplate = records.template(templateId);
  const chosenScenario = records.scenario(scenarioId);
  const reach = records.audienceReach(audienceGroupIds);

  const templateEligible = Boolean(chosenTemplate?.breakGlassEligible);
  const scenarioEligible = Boolean(chosenScenario?.breakGlassEligible);
  const eligible = breakGlassEligible(templateEligible, scenarioEligible);

  const eligibleVia = templateEligible
    ? scenarioEligible
      ? 'Both the template and the scenario'
      : 'The template'
    : scenarioEligible
      ? 'The scenario'
      : 'Neither record';

  /** Applying a scenario fills what it implies, exactly as the routine composer does. */
  const applyScenario = (nextScenarioId: string) => {
    setScenarioId(nextScenarioId);
    const scenario = records.scenario(nextScenarioId);
    if (!scenario) {
      return;
    }
    setPriority(scenario.priority);
    if (scenario.defaultTemplateId) {
      setTemplateId(scenario.defaultTemplateId);
      const template = records.template(scenario.defaultTemplateId);
      if (template) {
        setChannels(template.channels);
      }
    }
  };

  const ready =
    Boolean(templateId) && channels.length > 0 && audienceGroupIds.length > 0 && eligible;

  const request: BreakGlassRequest = {
    siteCode: siteCode.trim().toUpperCase(),
    scenarioId: scenarioId || null,
    templateId,
    audienceGroupIds,
    recipientZoneIds,
    channels,
    priority,
    incidentReference: incidentReference.trim() || null,
  };

  const breakGlassHistory = useMemo(() => history.data?.content ?? [], [history.data]);

  const historyColumns = useMemo<TableColumn<NotificationActivation>[]>(
    () => [
      {
        id: 'activation',
        header: 'Activation',
        width: 260,
        cell: ({ row }) => (
          <CellStack
            primary={row.activationNumber}
            secondary={records.templateName(row.templateId)}
          />
        ),
      },
      {
        id: 'sent',
        header: 'Sent in',
        width: 110,
        align: 'right',
        cell: ({ row }) => formatElapsed(row.fastLaneMillis),
      },
      {
        id: 'accounted',
        header: 'Accounted for',
        width: 230,
        cell: ({ row }) =>
          row.afterActionApprovedBy ? (
            <CellStack
              primary={row.afterActionApprovedBy}
              secondary={formatDateTime(row.afterActionApprovedAt)}
            />
          ) : (
            <span className="font-medium text-error-800">Not yet - closure is blocked</span>
          ),
      },
      {
        id: 'when',
        header: 'Sent',
        width: 170,
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
      {
        id: 'status',
        header: 'Status',
        width: 170,
        align: 'right',
        cell: ({ row }) => <ActivationStatusBadge status={row.status} />,
      },
    ],
    [records],
  );

  const outstanding = breakGlassHistory.filter(afterActionOutstanding);

  return (
    <div>
      <PageHeading
        title="Break glass"
        subtitle="A declared-emergency broadcast that sends immediately, with nobody approving it first."
        crumbs={[{ label: 'Emergency', to: emergencyPaths.dashboard }, { label: 'Break glass' }]}
        actions={
          <Button
            variant="outline"
           
            onClick={() => navigate(emergencyPaths.activations)}
          ><Icon name="megaphone" size={14} aria-hidden="true" />
            Routine activation instead
          </Button>
        }
      />

      <div className="space-y-5">
        <Banner variant="danger" heading="This is not the routine path"
  subtext={<>A break-glass broadcast goes out the moment it is confirmed. There is no draft, no
          approver and no recall. Use it when a declared emergency makes waiting for approval the
          greater risk - and use the routine path for everything else, including anything urgent
          that can still wait for one other person to read it.</>} />

        {outstanding.length > 0 && (
          <Banner
            variant="warning"
            heading={`${outstanding.length} earlier break-glass broadcast${outstanding.length === 1 ? ' has' : 's have'} not been accounted for`}
  subtext={<>Each is waiting on after-the-fact approval and cannot be closed until it has one. That
            backlog is the record of how this authority has been used.</>} />
        )}

        <Panel
          title="Compose the broadcast"
          subtitle="Everything here is what an operator would otherwise have had approved"
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <SiteSelect
              required
              value={siteCode}
              onChange={(value) => {
                setSiteCode(value);
                setScenarioId('');
                setTemplateId('');
                setAudienceGroupIds([]);
                setRecipientZoneIds([]);
              }}
            />
            <TextField
              label="Incident reference"
              value={incidentReference}
              onChange={setIncidentReference}
              helperText="Ties this broadcast to the incident record it serves."
            />
            <SelectField
              label="Scenario"
              value={scenarioId}
              onChange={applyScenario}
              allowEmpty
              emptyLabel="None"
              options={records.scenarios.map((scenario) => ({
                value: scenario.id,
                label: `${scenario.scenarioCode} · ${scenario.name}${scenario.breakGlassEligible ? ' - eligible' : ''}`,
              }))}
              helperText="Fills the template, priority and channels below."
            />
            <SelectField
              label="Template"
              required
              value={templateId}
              onChange={(value) => {
                setTemplateId(value);
                const template = records.template(value);
                if (template) {
                  setChannels(template.channels);
                }
              }}
              options={records.templates.map((template) => ({
                value: template.id,
                label: `${template.templateCode} · ${template.title}${template.breakGlassEligible ? ' - eligible' : ''}`,
              }))}
              helperText="The message recipients will receive. Required - break-glass has no default."
            />
          </div>

          <div className="mt-5 space-y-5">
            {templateId && !eligible && (
              <Banner variant="danger" heading="Neither record is break-glass eligible"
  subtext={<>The service will refuse this send. Break-glass needs the template{' '}
                <strong>or</strong> the scenario to be marked eligible - choose a different one, or
                have the record marked eligible by somebody who can justify it being sendable with
                no approval.</>} />
            )}

            {chosenTemplate && (
              <ConsequencePanel title={`What "${chosenTemplate.title}" says`}>
                <p className="whitespace-pre-wrap text-gray-800">{chosenTemplate.body}</p>
              </ConsequencePanel>
            )}

            <div className="grid gap-4 sm:grid-cols-2">
              <EnumField
                label="Priority"
                required
                value={priority}
                options={PRIORITIES}
                onChange={(value) => setPriority((value || 'CRITICAL') as Priority)}
                helperText={PRIORITY_DESCRIPTIONS[priority]}
              />
            </div>

            <CheckboxGroup
              label="Channels"
              required
              columns={2}
              values={channels}
              onChange={(values) => setChannels(values as ChannelType[])}
              options={CHANNEL_TYPES.map((channel) => ({
                value: channel,
                label: humanise(channel),
                hint: CHANNEL_DESCRIPTIONS[channel],
              }))}
              helperText="Break-glass requires at least one. Every channel chosen is a message per recipient."
            />

            <CheckboxGroup
              label="Audience groups"
              required
              columns={2}
              values={audienceGroupIds}
              onChange={setAudienceGroupIds}
              emptyMessage="No audience group is registered for this site. A break-glass send with no audience reaches nobody."
              options={records.audiences.map((audience) => ({
                value: audience.id,
                label: audience.name,
                hint: `${formatNumber(audience.recipientCount)} recipients`,
              }))}
            />

            <CheckboxGroup
              label="Recipient zones"
              columns={2}
              values={recipientZoneIds}
              onChange={setRecipientZoneIds}
              emptyMessage="No recipient zone is registered for this site."
              options={records.zones.map((zone) => ({
                value: zone.id,
                label: zone.name,
                hint: zone.locationReference ?? undefined,
              }))}
              helperText="Optional. Naming zones records lockdown and CCTV preservation context against them."
            />

            <ConsequencePanel title="What confirming would do" tone={ready ? 'warning' : 'neutral'}>
              <ConsequenceLine
                label="Recipients"
                value={`${formatNumber(reach)} across ${audienceGroupIds.length} group${audienceGroupIds.length === 1 ? '' : 's'}`}
              />
              <ConsequenceLine label="Channels" value={listChannels(channels)} />
              <ConsequenceLine label="Messages" value={formatNumber(reach * channels.length)} />
              <ConsequenceLine label="Approval" value="None - sent on your authority alone" />
              <ConsequenceLine label="Eligible via" value={eligibleVia} />
            </ConsequencePanel>

            <div className="flex flex-wrap items-center gap-3">
              {/*
                Hidden, not disabled, and this is the one control where that matters most.

                Break-glass sends to a whole site on one person's authority with no approval step -
                the narrowest grant in S174. Everything else on this page is a state check answered
                by `ready`, which correctly *disables* with a reason because the operator can fix it.
                Not holding EMERGENCY_BREAK_GLASS_SEND is not fixable, and a greyed "Break glass and
                send" sitting in front of somebody who may never press it is an invitation to try.
              */}
              {canBreakGlass() && (
                <Button
                  variant="primary-destructive"
                  size="md"
                 
                  disabled={!ready}
                  onClick={() => setConfirming(true)}
                ><Icon name="zap" size={14} aria-hidden="true" />
                  Break glass and send
                </Button>
              )}
              {!ready && (
                <span className="flex items-center gap-1.5 text-theme-sm text-gray-600">
                  <Icon name="info" size={14} className="shrink-0 text-teal-700" />
                  {!templateId
                    ? 'Choose a template.'
                    : !eligible
                      ? 'Neither the template nor the scenario is break-glass eligible.'
                      : channels.length === 0
                        ? 'Choose at least one channel.'
                        : 'Choose at least one audience group.'}
                </span>
              )}
            </div>
          </div>
        </Panel>

        <Panel
          title="How this authority has been used at this site"
          subtitle="Every break-glass broadcast, and whether it has been accounted for"
         
        >
          <DataState
            loading={history.initialising}
            error={history.error}
            onRetry={history.refetch}
            empty={breakGlassHistory.length === 0}
            emptyTitle="Break glass has never been used here"
            emptyHint="No activation at this site has been sent without approval."
            minHeight={180}
          >
            <RegisterTable
              paramPrefix="emergency-break-glass"
              rows={breakGlassHistory}
              columns={historyColumns}
              rowKey={(row) => row.id}
              onRowClick={(row) => navigate(emergencyPaths.activationDetail(row.id))}
              emptyTitle="No break-glass activations"
              framed={false}
            />
          </DataState>
        </Panel>
      </div>

      {confirming && (
        <ConfirmBreakGlassDialog
          open
          request={request}
          summary={{
            scenarioName: chosenScenario?.name ?? 'None cited',
            templateTitle: chosenTemplate?.title ?? '',
            templateBody: chosenTemplate?.body ?? '',
            reach,
            zoneNames: recipientZoneIds.map(records.zoneName),
            eligibleVia,
          }}
          onClose={() => setConfirming(false)}
          onSent={(activation) => {
            notifySuccess(
              `${activation.activationNumber} is live.`,
              'Record after-the-fact approval against it - closure is blocked until you do.',
            );
            history.refetch();
            navigate(emergencyPaths.activationDetail(activation.id));
          }}
        />
      )}
    </div>
  );
};

export default BreakGlassPage;

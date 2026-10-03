import { useCallback, useMemo, useState } from 'react';
import { InboxMessageResponse } from 'modules/fleet/api/dto';
import {
  INTEGRATION_MESSAGE_STATUSES,
  IntegrationMessageStatus,
  humanise,
} from 'modules/fleet/api/enums';
import { integrationsApi } from 'modules/fleet/api/fleetApi';
import {
  Banner,
  Button,
  Card,
  Input,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
} from '@rfdtech/components';
import FleetTable, {
  CellStack,
  FilterDropdown,
  FleetColumn,
  useRegisterState,
} from 'modules/fleet/components/FleetTable';
import StatusBadge from 'modules/fleet/components/StatusBadge';
import { TextInput } from 'modules/fleet/components/formFields';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { canReplayIntegration } from '../api/access';

/** How many messages the inbox search asks for. The service clamps at 500. */
const SEARCH_LIMIT = 100;

/**
 * Telematics and integration intake health.
 *
 * The counters come from the health projection; the messages come from `GET /integrations/messages`.
 * That search is why this page changed: replay takes a message identifier, and the only messages the
 * dashboard could see were the handful the health projection happened to carry - so replaying a dead
 * letter meant knowing its id from somewhere else entirely. Dead-letter replay was a documented
 * capability that could not be reached from here at all.
 *
 * The rows are typed now rather than read out of a loosely-shaped projection summary, so a missing
 * field is a compile error instead of an empty cell.
 *
 * Replay is offered on any message that is not already `PROCESSED`. That mirrors the service, which
 * treats replaying a processed message as a no-op rather than an error - the operation is
 * idempotent, and the audit entry is written either way.
 */
const IntegrationHealthPage = () => {
  const { notifyError, notifySuccess } = useNotifier();
  const canReplay = canReplayIntegration();
  const state = useRegisterState('integration-messages');
  const { filters, setFilter } = state;
  const sourceSystem = filters.source ?? '';
  const status = (filters.status ?? '') as IntegrationMessageStatus | '';
  const eventType = filters.event ?? '';
  const [replayId, setReplayId] = useState('');
  const [replaying, setReplaying] = useState<string | null>(null);
  const filtered = Boolean(sourceSystem || status || eventType);

  const health = useApiQuery((signal) => integrationsApi.health(signal), []);

  const messages = useApiQuery(
    (signal) =>
      canReplay
        ? integrationsApi.messages(
            {
              sourceSystem: sourceSystem.trim() || undefined,
              status: status || undefined,
              eventType: eventType.trim() || undefined,
              size: SEARCH_LIMIT,
            },
            signal,
          )
        : Promise.resolve(undefined),
    [canReplay, sourceSystem, status, eventType],
  );

  // Pulled out as locals because they are the stable half of the query objects, and the memoised
  // `replay` below depends on the refetch and not on the data.
  const { refetch: refetchHealth } = health;
  const { refetch: refetchMessages } = messages;

  const refreshAll = useCallback(() => {
    refetchHealth();
    if (canReplay) {
      refetchMessages();
    }
  }, [canReplay, refetchHealth, refetchMessages]);

  /**
   * Shared by the row action and the replay-by-id card, so both report the same way.
   *
   * Memoised because the column definitions close over it, and `useApiQuery` hands back a stable
   * `refetch`, so this identity only changes when the notifier does.
   */
  const replay = useCallback(
    async (messageId: string, onDone?: () => void) => {
      setReplaying(messageId);
      try {
        await integrationsApi.replay(messageId);
        notifySuccess('Replay accepted.');
        onDone?.();
        refreshAll();
      } catch (error) {
        notifyError(error);
      } finally {
        setReplaying(null);
      }
    },
    [refreshAll, notifyError, notifySuccess],
  );

  const columns = useMemo<FleetColumn<InboxMessageResponse>[]>(
    () => [
      {
        key: 'message',
        header: 'Message',
        width: 300,
        cell: (row) => (
          <div className="min-w-0">
            <CellStack
              primary={`${row.sourceSystem} · ${row.eventType ?? 'event'}`}
              secondary={`Received ${formatDateTime(row.receivedAt)}`}
            />
            {row.failureReason && (
              <div className="mt-1 text-theme-xs text-error-700">{row.failureReason}</div>
            )}
          </div>
        ),
      },
      {
        key: 'status',
        header: 'Status',
        width: 140,
        cell: (row) => (
          <div>
            <StatusBadge value={row.status} />
            {/* Attempts only tell a story once there has been more than one. */}
            {row.attempts > 1 && (
              <div className="mt-1 text-theme-xs opacity-70">{row.attempts} attempts</div>
            )}
          </div>
        ),
      },
      {
        key: 'site',
        header: 'Site',
        width: 100,
        cell: (row) => <span className="text-theme-xs opacity-70">{row.siteCode ?? '-'}</span>,
      },
      {
        key: 'processedAt',
        header: 'Processed',
        width: 170,
        cell: (row) =>
          row.processedAt ? (
            <span className="text-theme-xs opacity-70">{formatDateTime(row.processedAt)}</span>
          ) : (
            <span className="text-theme-xs opacity-70">Not processed</span>
          ),
      },
      {
        key: 'correlation',
        header: 'Correlation',
        width: 130,
        cell: (row) => (
          <span className="font-mono text-theme-xs opacity-70">
            {row.correlationId ? row.correlationId.slice(0, 8) : '-'}
          </span>
        ),
      },
      {
        key: 'actions',
        header: 'Actions',
        width: 110,
        align: 'right',
        cell: (row) =>
          row.status === 'PROCESSED' ? null : (
            <Button
              size="sm"
              variant="ghost"
              loading={replaying === row.id}
              onClick={(event) => {
                event.stopPropagation();
                void replay(row.id);
              }}
            >
              <Icon name="refresh" size={14} aria-hidden="true" />
              Replay
            </Button>
          ),
      },
    ],
    // `replaying` decides which row shows a spinner, so the columns depend on it.
    [replaying, replay],
  );

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Integration health</SectionTitle>
          {health.data && (
            <SectionDescription>Checked {formatDateTime(health.data.checkedAt)}</SectionDescription>
          )}
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <Button variant="outline" onClick={refreshAll}>
              <Icon name="refresh" size={14} aria-hidden="true" />
              Refresh
            </Button>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <DataState loading={false} error={health.error} onRetry={health.refetch} minHeight={280}>
        {health.data && health.data.deadLetterMessages > 0 && (
          <PageSection>
            <Banner
              variant="danger"
              heading={`${health.data.deadLetterMessages} message${
                health.data.deadLetterMessages === 1 ? '' : 's'
              } require replay or operator review.`}
              subtext="Until they are cleared, vehicle movement data may be stale."
              action={
                canReplay ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setFilter('status', 'DEAD_LETTER')}
                    disabled={status === 'DEAD_LETTER'}
                  >
                    Show them
                  </Button>
                ) : undefined
              }
            />
          </PageSection>
        )}

        <PageSection>
          <MetricCards>
            <MetricCard
              variant="soft"
              loading={health.initialising}
              label="Processed"
              value={health.data?.processedMessages ?? 0}
              description="Accepted and applied"
            />
            <MetricCard
              variant="soft"
              loading={health.initialising}
              label="Rejected"
              value={health.data?.rejectedMessages ?? 0}
              description="Signature, allowlist or schema"
            />
            <MetricCard
              variant="soft"
              loading={health.initialising}
              label="Dead letters"
              value={health.data?.deadLetterMessages ?? 0}
              description="Awaiting replay"
            />
          </MetricCards>
        </PageSection>

        {/*
          Nothing stands in for the inbox and replay controls when the role cannot use them.
          A card explaining the absence was more prominent than the counters the page is for,
          and it told the reader about permissions rather than about the integration.
        */}
        {canReplay && (
          <>
            <PageSection>
              <SectionHeader>
                <SectionTitle>Inbound messages</SectionTitle>
                <SectionDescription>Newest first</SectionDescription>
              </SectionHeader>
              <FleetTable
                paramPrefix="integration-messages"
                rows={messages.data ?? []}
                columns={columns}
                getRowId={(row) => row.id}
                loading={messages.loading}
                error={messages.error}
                onRetry={messages.refetch}
                caption="Inbound integration messages"
                filters={
                  <>
                    <Input
                      name="source"
                      aria-label="Source system"
                      placeholder="Source system"
                      defaultValue={sourceSystem}
                    />
                    <FilterDropdown
                      name="status"
                      label="Status"
                      value={status}
                      onChange={(value) => setFilter('status', value)}
                      options={INTEGRATION_MESSAGE_STATUSES.map((value) => ({
                        value,
                        label: humanise(value),
                      }))}
                    />
                    <Input
                      name="event"
                      aria-label="Event type"
                      placeholder="Event type, for example vehicle.location"
                      defaultValue={eventType}
                    />
                  </>
                }
                emptyTitle={filtered ? 'No messages match these filters' : 'No inbound messages'}
                emptyDescription={
                  filtered
                    ? 'Adjust the filters, or reset them to see the whole inbox.'
                    : 'Nothing has arrived through the signed intake endpoint yet.'
                }
              />
              {(messages.data?.length ?? 0) >= SEARCH_LIMIT && (
                <p className="mt-3 text-theme-xs opacity-70">
                  The most recent {SEARCH_LIMIT} messages. Filter by status or source system to see
                  further back.
                </p>
              )}
            </PageSection>

            <PageSection>
              <SectionHeader>
                <SectionTitle>Replay by message identifier</SectionTitle>
                <SectionDescription>
                  For an identifier that came from a log or an incident note rather than the list
                  above
                </SectionDescription>
              </SectionHeader>
              <Card bordered>
                {/*
                  Top-aligned, because the field carries a helper line and the button does not: under
                  `items-end` that line pushed the input up and left the two on different rows.
                */}
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
                  <TextInput
                    label="Integration message ID"
                    value={replayId}
                    onChange={setReplayId}
                    className="sm:max-w-[420px] sm:flex-1"
                    helperText="Privileged and idempotent - replaying the same message twice is safe."
                  />
                  <Button
                    variant="primary"
                    className="sm:mt-6"
                    loading={replaying === replayId.trim()}
                    disabled={!replayId.trim()}
                    onClick={() => void replay(replayId.trim(), () => setReplayId(''))}
                  >
                    <Icon name="refresh" size={14} aria-hidden="true" />
                    Replay
                  </Button>
                </div>
              </Card>
            </PageSection>
          </>
        )}
      </DataState>
    </>
  );
};

export default IntegrationHealthPage;

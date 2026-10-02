import { useMemo, useState } from 'react';
import {
  Button,
  EmptyState,
  MetricCard,
  MetricCards,
  PageSection,
  Table,
  TableContent,
  type TableColumn,
} from '@rfdtech/components';
import { RefreshCw } from 'lucide-react';
import { DispatchIntegrationHealth } from 'modules/dispatch/api/dto';
import { dispatchIntegrationsApi } from 'modules/dispatch/api/dispatchApi';
import { shortId } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import CellStack from 'modules/dispatch/components/CellStack';
import { Callout } from 'modules/dispatch/components/formKit';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';

type InboxMessage = DispatchIntegrationHealth['inbox']['recentMessages'][number];
type OutboxEntry = DispatchIntegrationHealth['outbox']['recentDeadLetters'][number];

/**
 * Scanner and carrier integration health.
 *
 * The dispatch service returns inbound and outbound health in **one** payload - unlike fuel, which
 * splits them across two endpoints - so this screen is a single fetch. Both halves are service-wide
 * rather than site-scoped: the inbox is shared, and a message here may belong to another module.
 *
 * Replay is offered for dead-lettered outbound messages only. Inbound scanner events are idempotent
 * on their own signature, so a provider that re-sends is safe without an operator doing anything.
 */
const DispatchIntegrationPage = () => {
  const { notifySuccess, notifyError } = useNotifier();
  const [replaying, setReplaying] = useState<string | null>(null);

  const health = useApiQuery((signal) => dispatchIntegrationsApi.health(signal), []);

  const replay = async (messageId: string) => {
    setReplaying(messageId);
    try {
      await dispatchIntegrationsApi.replay(messageId);
      notifySuccess('Message requeued for publication.');
      health.refetch();
    } catch (error) {
      notifyError(error);
    } finally {
      setReplaying(null);
    }
  };

  const inbox = health.data?.inbox;
  const outbox = health.data?.outbox;

  const messageColumns = useMemo<TableColumn<InboxMessage>[]>(
    () => [
      {
        id: 'message',
        header: 'Message',
        width: 260,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.sourceSystem} · ${row.eventType}`}
            secondary={row.idempotencyKey ? `key ${shortId(row.idempotencyKey)}` : 'no key'}
          />
        ),
      },
      {
        id: 'site',
        header: 'Site',
        width: 110,
        cell: ({ row }) => row.siteCode ?? '-',
      },
      {
        id: 'received',
        header: 'Received',
        width: 170,
        cell: ({ row }) => formatDateTime(row.receivedAt),
      },
      {
        id: 'attempts',
        header: 'Attempts',
        width: 100,
        align: 'right',
        cell: ({ row }) => row.attempts,
      },
      {
        id: 'status',
        header: 'Status',
        width: 140,
        align: 'right',
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
    ],
    [],
  );

  const outboxColumns = useMemo<TableColumn<OutboxEntry>[]>(
    () => [
      {
        id: 'event',
        header: 'Event',
        width: 260,
        cell: ({ row }) => (
          <CellStack
            primary={row.eventType}
            secondary={`${row.aggregateType} ${shortId(row.aggregateId)}`}
          />
        ),
      },
      {
        id: 'failure',
        header: 'Failure',
        width: 280,
        cell: ({ row }) =>
          row.failureReason ?? <span className="text-muted-foreground">Not recorded</span>,
      },
      {
        id: 'attempts',
        header: 'Attempts',
        width: 100,
        align: 'right',
        cell: ({ row }) => row.attemptCount,
      },
      {
        id: 'created',
        header: 'Created',
        width: 170,
        cell: ({ row }) => formatDateTime(row.createdAt),
      },
      {
        id: 'replay',
        header: '',
        width: 120,
        align: 'right',
        cell: ({ row }) => (
          <Button
            size="sm"
            variant="outline"
            loading={replaying === row.id}
            disabled={replaying !== null}
            onClick={() => replay(row.id)}
          >
            <RefreshCw size={14} strokeWidth={1.5} aria-hidden="true" />
            Replay
          </Button>
        ),
      },
    ],
    // Rebuilt when a replay starts or finishes so the row's own button reflects it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [replaying],
  );

  return (
    <>
      <PageHeading
        title="Scanner integration"
        subtitle="Inbound scanner and carrier feeds, and the events this module publishes."
        crumbs={[
          { label: 'Dispatch', to: dispatchPaths.dashboard },
          { label: 'Scanner integration' },
        ]}
        actions={
          <Button variant="outline" onClick={health.refetch}>
            <RefreshCw size={14} strokeWidth={1.5} aria-hidden="true" />
            Refresh
          </Button>
        }
      />

      <DataState
        loading={health.initialising}
        error={health.error}
        onRetry={health.refetch}
        minHeight={300}
      >
        {health.data && (
          <>
            {((outbox && outbox.deadLettered > 0) || (inbox && inbox.rejectedMessages > 0)) && (
              <PageSection className="space-y-3">
                {outbox && outbox.deadLettered > 0 && (
                  <Callout
                    tone="danger"
                    title={`${outbox.deadLettered} outbound messages are dead lettered`}
                  >
                    Downstream systems have not received these dispatch events. Replay them below
                    once the cause has been dealt with; a replay returns the message to the pending
                    queue.
                  </Callout>
                )}
                {inbox && inbox.rejectedMessages > 0 && (
                  <Callout
                    tone="warning"
                    title={`${inbox.rejectedMessages} inbound messages were rejected`}
                  >
                    A rejected message failed signature verification or schema validation and was
                    not applied. The sending system has to correct and re-send it - there is no
                    replay for inbound.
                  </Callout>
                )}
              </PageSection>
            )}

            <PageSection>
              <MetricCards>
                <MetricCard
                  variant="soft"
                  label="Inbound processed"
                  value={formatNumber(inbox?.processedMessages ?? 0)}
                  description="Accepted and applied"
                />
                <MetricCard
                  variant="soft"
                  label="Inbound rejected"
                  value={formatNumber(inbox?.rejectedMessages ?? 0)}
                  description="Signature or payload refused"
                />
                <MetricCard
                  variant="soft"
                  label="Outbound pending"
                  value={formatNumber(outbox?.pending ?? 0)}
                  description={`${formatNumber(outbox?.published ?? 0)} published`}
                />
                <MetricCard
                  variant="soft"
                  label="Dead lettered"
                  value={formatNumber(outbox?.deadLettered ?? 0)}
                  description="Awaiting replay"
                />
              </MetricCards>
            </PageSection>

            <Panel
              title="Outbound dead letters"
              description="Dispatch events downstream systems did not receive"
            >
              <Table paramPrefix="deadletters" variant="soft">
                <TableContent
                  variant="soft"
                  columns={outboxColumns}
                  data={outbox?.recentDeadLetters ?? []}
                  rowKey={(row) => row.id}
                  aria-label="Dispatch outbound messages that could not be published, with the recorded failure, attempt count and a control to replay each."
                  emptyContent={
                    <EmptyState
                      title="Nothing dead lettered"
                      description="Every dispatch event has been published."
                    />
                  }
                />
              </Table>
            </Panel>

            <Panel
              title="Recent inbound messages"
              description="Signed scanner and carrier callbacks across the service"
            >
              <Table paramPrefix="inbound" variant="soft">
                <TableContent
                  variant="soft"
                  columns={messageColumns}
                  data={inbox?.recentMessages ?? []}
                  rowKey={(row) => row.id}
                  aria-label="Recent inbound integration messages, with their source, site, receipt time, attempt count and status."
                  emptyContent={
                    <EmptyState
                      title="No inbound messages"
                      description="No scanner or carrier has posted to this service."
                    />
                  }
                />
              </Table>
              <p className="mt-3 text-xs text-muted-foreground">
                Checked {formatDateTime(inbox?.checkedAt)}. The inbox is shared across the service
                and is not filtered to dispatch or to a site - a message here may belong to another
                module.
              </p>
            </Panel>

            <Panel title="How the feeds behave">
              <ul className="space-y-3 text-sm text-foreground">
                <li>
                  <strong>Scanner events</strong> arrive signed, at{' '}
                  <span className="font-mono text-xs">
                    /integrations/scanners/{'{provider}'}/events
                  </span>
                  , and are idempotent on their own signature - a provider that re-sends the same
                  event is safe without anyone intervening.
                </li>
                <li>
                  <strong>Carrier status</strong> callbacks arrive at{' '}
                  <span className="font-mono text-xs">
                    /integrations/carriers/{'{carrier}'}/status
                  </span>{' '}
                  and update the consignment they name.
                </li>
                <li>
                  <strong>Outbound</strong> publication is what tells the rest of the platform a
                  consignment moved. A dead letter means a downstream system is out of step with what
                  this module recorded - {humanise('REPLAY').toLowerCase()} is the fix, once the cause
                  is dealt with.
                </li>
              </ul>
            </Panel>
          </>
        )}
      </DataState>
    </>
  );
};

export default DispatchIntegrationPage;

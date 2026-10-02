import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import { IntegrationMessageSummary, OutboxEntry } from 'modules/fuel/api/dto';
import { MAX_PAGE_SIZE, fuelIntegrationsApi, fuelTransactionsApi } from 'modules/fuel/api/fuelApi';
import { DerivedNote } from 'modules/fuel/components/Provenance';
import { shortId } from 'modules/fuel/components/fuelFormat';
import { humanise } from 'modules/fleet/api/enums';
import { Alert, Button, CellStack, DataTable, PageHeader, SectionCard, StatCard, StatusChip } from 'modules/fuel/components/fuelUi';
import DataState from 'shared/components/DataState';
import type { FuelColumn as Column } from 'modules/fuel/components/fuelUi';
import { FilterBar } from 'modules/fuel/components/fuelUi';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';

/**
 * How long a site can go without a provider transaction before the ingest is treated as stale.
 *
 * The fuel dashboard uses a fifteen-minute threshold, but that measures *any* transaction change,
 * including manual capture. A provider feed is a different thing: a quiet forecourt overnight is
 * normal and a six-hour silence during the day is not. Six hours is a dashboard judgement, stated here
 * because the service publishes no provider-level freshness of its own.
 */
const PROVIDER_STALE_HOURS = 6;

/**
 * Fuel provider integration health.
 *
 * Three things the service really exposes, and one it does not. Inbound message health and outbound
 * publication health both have endpoints, and a dead-lettered outbound message can be replayed.
 * What has no endpoint is **per-provider ingest freshness** - so the provider table below is built
 * from the transactions themselves, grouped by `sourceSystem`, and says so.
 *
 * Note that the two health endpoints are not site-scoped: `integrationHealth` requires the
 * `FUEL_INTEGRATION_REPLAY` permission and reports across the whole service. Only the provider
 * ingest panel narrows to a site.
 */
const FuelIntegrationPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [replaying, setReplaying] = useState<string | null>(null);

  const inbound = useApiQuery((signal) => fuelIntegrationsApi.inboundHealth(signal), []);
  const outbox = useApiQuery((signal) => fuelIntegrationsApi.outboxHealth(signal), []);

  const transactions = useApiQuery(
    (signal) => fuelTransactionsApi.search({ siteCode, size: MAX_PAGE_SIZE }, signal),
    [siteCode],
  );

  /** Per-source ingest standing, grouped from the transactions the register returned. */
  const providers = useMemo(() => {
    const grouped = new Map<
      string,
      { source: string; count: number; latest: string; withReference: number }
    >();
    (transactions.data?.content ?? []).forEach((transaction) => {
      const key = transaction.sourceSystem;
      const entry = grouped.get(key) ?? {
        source: key,
        count: 0,
        latest: transaction.ingestionTimestamp,
        withReference: 0,
      };
      entry.count += 1;
      if (transaction.ingestionTimestamp > entry.latest) {
        entry.latest = transaction.ingestionTimestamp;
      }
      if (transaction.providerTransactionId) {
        entry.withReference += 1;
      }
      grouped.set(key, entry);
    });
    return [...grouped.values()].sort((left, right) => right.latest.localeCompare(left.latest));
  }, [transactions.data]);

  const staleProviders = useMemo(
    () =>
      providers.filter(
        (provider) =>
          provider.source.toUpperCase() !== 'MANUAL' &&
          dayjs(provider.latest).isBefore(dayjs().subtract(PROVIDER_STALE_HOURS, 'hour')),
      ),
    [providers],
  );

  const replay = async (messageId: string) => {
    setReplaying(messageId);
    try {
      const result = await fuelIntegrationsApi.replay(messageId);
      if (result.requeued) {
        notifySuccess('Message requeued for publication.');
      } else {
        notifySuccess(
          'The service did not requeue this message.',
          'It may already have been published or removed.',
        );
      }
      outbox.refetch();
    } catch (error) {
      notifyError(error);
    } finally {
      setReplaying(null);
    }
  };

  const messageColumns = useMemo<Column<IntegrationMessageSummary>[]>(
    () => [
      {
        key: 'message',
        header: 'Message',
        width: 260,
        cell: (row) => (
          <CellStack
            primary={`${row.sourceSystem} · ${row.eventType}`}
            secondary={row.idempotencyKey ? `key ${shortId(row.idempotencyKey)}` : 'no key'}
          />
        ),
      },
      {
        key: 'site',
        header: 'Site',
        width: 110,
        hideBelowLg: true,
        cell: (row) => row.siteCode ?? '-',
      },
      {
        key: 'received',
        header: 'Received',
        width: 170,
        cell: (row) => formatDateTime(row.receivedAt),
      },
      {
        key: 'attempts',
        header: 'Attempts',
        width: 100,
        align: 'right',
        hideBelowLg: true,
        cell: (row) => row.attempts,
      },
      {
        key: 'status',
        header: 'Status',
        width: 140,
        align: 'right',
        cell: (row) => <StatusChip value={row.status} />,
      },
    ],
    [],
  );

  const outboxColumns = useMemo<Column<OutboxEntry>[]>(
    () => [
      {
        key: 'event',
        header: 'Event',
        width: 260,
        cell: (row) => (
          <CellStack
            primary={row.eventType}
            secondary={`${row.aggregateType} ${shortId(row.aggregateId)}`}
          />
        ),
      },
      {
        key: 'failure',
        header: 'Failure',
        width: 280,
        cell: (row) => row.failureReason ?? <span className="text-gray-500">Not recorded</span>,
      },
      {
        key: 'attempts',
        header: 'Attempts',
        width: 100,
        align: 'right',
        hideBelowLg: true,
        cell: (row) => row.attemptCount,
      },
      {
        key: 'created',
        header: 'Created',
        width: 170,
        hideBelowLg: true,
        cell: (row) => formatDateTime(row.createdAt),
      },
      {
        key: 'replay',
        header: '',
        width: 120,
        align: 'right',
        cell: (row) => (
          <Button
            size="sm"
            variant="outline"
            startIcon="refresh"
            loading={replaying === row.id}
            disabled={replaying !== null}
            onClick={() => replay(row.id)}
          >
            Replay
          </Button>
        ),
      },
    ],
    // Rebuilt when a replay starts or finishes so the row's own button reflects it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [replaying],
  );

  const providerColumns = useMemo<
    Column<{ source: string; count: number; latest: string; withReference: number }>[]
  >(
    () => [
      {
        key: 'source',
        header: 'Source system',
        width: 200,
        cell: (row) => (
          <CellStack
            primary={row.source}
            secondary={
              row.source.toUpperCase() === 'MANUAL' ? 'Captured in this dashboard' : 'External feed'
            }
          />
        ),
      },
      {
        key: 'count',
        header: 'Transactions',
        width: 130,
        align: 'right',
        cell: (row) => formatNumber(row.count),
      },
      {
        key: 'referenced',
        header: 'With provider reference',
        width: 180,
        align: 'right',
        hideBelowLg: true,
        cell: (row) => `${formatNumber(row.withReference)} of ${formatNumber(row.count)}`,
      },
      {
        key: 'latest',
        header: 'Most recent ingest',
        width: 190,
        cell: (row) => formatDateTime(row.latest),
      },
      {
        key: 'standing',
        header: 'Standing',
        width: 130,
        align: 'right',
        cell: (row) => {
          if (row.source.toUpperCase() === 'MANUAL') {
            return <StatusChip value="ACTIVE" label="Manual" tone="neutral" />;
          }
          const stale = dayjs(row.latest).isBefore(dayjs().subtract(PROVIDER_STALE_HOURS, 'hour'));
          return stale ? (
            <StatusChip value="WARNING" label="Stale" tone="caution" />
          ) : (
            <StatusChip value="ACTIVE" label="Current" tone="ready" />
          );
        },
      },
    ],
    [],
  );

  const refreshAll = () => {
    inbound.refetch();
    outbox.refetch();
    transactions.refetch();
  };

  return (
    <div>
      <PageHeader
        title="Provider integration"
        subtitle="Inbound provider transactions, outbound publication and the messages waiting to be replayed."
        crumbs={[{ label: 'Fuel', to: fuelPaths.dashboard }, { label: 'Provider integration' }]}
        actions={
          <Button variant="outline" startIcon="refresh" onClick={refreshAll}>
            Refresh
          </Button>
        }
      />

      <div className="space-y-5">
        {outbox.data && outbox.data.deadLettered > 0 && (
          <Alert
            variant="error"
            title={`${outbox.data.deadLettered} outbound messages are dead lettered`}
          >
            Downstream systems have not received these fuel events. Replay them below once the cause
            has been dealt with; a replay returns the message to the pending queue.
          </Alert>
        )}

        {staleProviders.length > 0 && (
          <Alert
            variant="warning"
            title={`${staleProviders.length} provider feed${staleProviders.length === 1 ? ' has' : 's have'} gone quiet`}
          >
            {staleProviders.map((provider) => provider.source).join(', ')} - no transaction ingested
            in the last {PROVIDER_STALE_HOURS} hours at {siteCode}.
            <DerivedNote>
              A dashboard threshold, not a service one. The fuel service publishes no per-provider
              freshness, so this is measured from the ingestion timestamps on the transactions
              themselves.
            </DerivedNote>
          </Alert>
        )}

        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard
            label="Inbound processed"
            value={formatNumber(inbound.data?.processedMessages ?? 0)}
            icon="cloud"
            caption="Accepted and applied"
          />
          <StatCard
            label="Inbound rejected"
            value={formatNumber(inbound.data?.rejectedMessages ?? 0)}
            icon="alert-circle"
            tone={(inbound.data?.rejectedMessages ?? 0) > 0 ? 'caution' : 'neutral'}
            caption="Signature or payload refused"
          />
          <StatCard
            label="Outbound pending"
            value={formatNumber(outbox.data?.pending ?? 0)}
            icon="inbox"
            caption="Waiting to publish"
          />
          <StatCard
            label="Dead lettered"
            value={formatNumber(outbox.data?.deadLettered ?? 0)}
            icon="alert-triangle"
            tone={(outbox.data?.deadLettered ?? 0) > 0 ? 'critical' : 'neutral'}
            caption="Awaiting replay"
          />
        </div>

        <SectionCard
          title="Provider ingest"
          subtitle="Which sources this site’s transactions arrived from"
          flush
        >
          <FilterBar>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
          </FilterBar>
          <DataState
            loading={transactions.initialising}
            error={transactions.error}
            empty={providers.length === 0}
            emptyTitle="No transactions at this site"
            emptyHint="Nothing has been captured, imported or ingested here."
            onRetry={transactions.refetch}
            minHeight={200}
          >
            <DataTable
              rows={providers}
              columns={providerColumns}
              getRowId={(row) => row.source}
              loading={transactions.loading}
              onRowClick={() => navigate(fuelPaths.transactions)}
              caption="Fuel transaction sources at this site, with volume, how many carry a provider reference, the most recent ingest and whether the feed is current."
              dense
            />
          </DataState>
          <div className="px-5 pt-2 pb-4">
            <DerivedNote>
              Grouped by source system from the {transactions.data?.content.length ?? 0} transactions this
              dashboard fetched for {siteCode}. The service exposes no per-provider ingest endpoint, so
              a provider that has never sent anything does not appear here at all.
            </DerivedNote>
          </div>
        </SectionCard>

        <SectionCard
          title="Outbound dead letters"
          subtitle="Fuel events downstream systems did not receive"
          flush
        >
          <DataState
            loading={outbox.initialising}
            error={outbox.error}
            empty={(outbox.data?.recentDeadLetters.length ?? 0) === 0}
            emptyTitle="Nothing dead lettered"
            emptyHint="Every fuel event has been published."
            onRetry={outbox.refetch}
            minHeight={180}
          >
            <DataTable
              rows={outbox.data?.recentDeadLetters ?? []}
              columns={outboxColumns}
              getRowId={(row) => row.id}
              loading={outbox.loading}
              caption="Fuel outbound messages that could not be published, with the recorded failure, attempt count and a control to replay each."
              dense
            />
          </DataState>
        </SectionCard>

        <SectionCard
          title="Recent inbound messages"
          subtitle="Signed provider webhooks across the service"
          flush
        >
          <DataState
            loading={inbound.initialising}
            error={inbound.error}
            empty={(inbound.data?.recentMessages.length ?? 0) === 0}
            emptyTitle="No inbound messages"
            emptyHint="No provider has posted to this service."
            onRetry={inbound.refetch}
            minHeight={180}
          >
            <DataTable
              rows={inbound.data?.recentMessages ?? []}
              columns={messageColumns}
              getRowId={(row) => row.id}
              loading={inbound.loading}
              caption="Recent inbound integration messages, with their source, site, receipt time, attempt count and status."
              dense
            />
          </DataState>
          <div className="px-5 pt-2 pb-4">
            <p className="text-theme-xs text-gray-600">
              Checked {formatDateTime(inbound.data?.checkedAt)}. The inbound inbox is shared across
              the service and is not filtered to fuel or to a site - a message here may belong to
              another module.
            </p>
          </div>
        </SectionCard>

        <SectionCard title="Replaying inbound messages">
          <Alert variant="info" title="Only outbound messages can be replayed from here">
            The fuel module exposes a replay for the outbound outbox
            (<span className="font-mono text-theme-xs">/integrations/outbox/{'{id}'}/replay</span>).
            Replaying an *inbound* provider message is a fleet-module operation and lives on the
            fleet integration health screen. {humanise('PROVIDER')} ingest itself is idempotent: a
            re-delivered transaction with the same reference returns the original record rather than
            creating a second.
          </Alert>
        </SectionCard>
      </div>
    </div>
  );
};

export default FuelIntegrationPage;

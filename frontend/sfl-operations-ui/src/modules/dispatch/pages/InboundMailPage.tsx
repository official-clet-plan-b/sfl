import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Button,
  DateRangeSelector,
  Input,
  MetricCard,
  MetricCards,
  PageSection,
  type TableColumn,
} from '@rfdtech/components';
import { Plus } from 'lucide-react';
import { CourierItem } from 'modules/dispatch/api/dto';
import { ITEM_STATUSES } from 'modules/dispatch/api/enums';
import { inboundMailApi } from 'modules/dispatch/api/dispatchApi';
import { itemDistributable } from 'modules/dispatch/api/workflow';
import CellStack from 'modules/dispatch/components/CellStack';
import { Callout } from 'modules/dispatch/components/formKit';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import {
  FilterDropdown,
  RegisterTable,
  emptyRange,
  rangeToInstants,
  useClampRegisterPage,
  useRegisterQuery,
} from 'modules/dispatch/components/registerTable';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import {
  DistributeInboundDialog,
  RegisterItemDialog,
} from 'modules/dispatch/dialogs/itemDialogs';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { canRegisterInbound } from 'modules/fleet/api/access';

const PREFIX = 'inbound';

/**
 * The mailroom: inbound registration and acknowledged distribution.
 *
 * The same courier item register underneath, with direction fixed to inbound by the endpoint. What
 * makes it a distinct screen is the one thing inbound mail is *for* - getting the item to its
 * recipient and recording that they took it. Distribution is offered directly from the row, because
 * an operator working through the morning's post should not have to open each item to acknowledge
 * it.
 */
const InboundMailPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [range, setRange] = useState(emptyRange);
  const [registering, setRegistering] = useState(false);
  const [distributing, setDistributing] = useState<CourierItem | null>(null);

  const table = useRegisterQuery(PREFIX);
  const { filters } = table;

  const query = useApiQuery(
    (signal) =>
      inboundMailApi.search(
        {
          siteCode,
          status: (filters.status as CourierItem['status']) || undefined,
          handler: filters.handler?.trim() || undefined,
          reference: table.search || undefined,
          ...rangeToInstants(range),
          page: table.page,
          size: table.size,
        },
        signal,
      ),
    [siteCode, filters.status, filters.handler, table.search, range, table.page, table.size],
  );

  useClampRegisterPage(table, query.data?.totalPages);

  const rows = useMemo(() => query.data?.content ?? [], [query.data]);
  const awaitingDistribution = useMemo(() => rows.filter(itemDistributable), [rows]);
  const acknowledged = useMemo(() => rows.filter((item) => Boolean(item.acknowledgedBy)), [rows]);

  const columns = useMemo<TableColumn<CourierItem>[]>(
    () => [
      {
        id: 'item',
        header: 'Item',
        minWidth: 220,
        cell: ({ row }) => (
          <CellStack
            primary={row.itemNumber}
            secondary={`From ${row.sender ?? row.origin} · ${humanise(row.itemType)}`}
          />
        ),
      },
      {
        id: 'recipient',
        header: 'For',
        cell: ({ row }) =>
          row.recipient ?? <span className="text-muted-foreground">Unaddressed</span>,
      },
      {
        id: 'sensitivity',
        header: 'Sensitivity',
        cell: ({ row }) => <StatusBadge value={row.sensitivity} />,
      },
      {
        id: 'received',
        header: 'Received',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
      {
        id: 'acknowledged',
        header: 'Acknowledgement',
        cell: ({ row }) =>
          row.acknowledgedBy ? (
            <CellStack primary={row.acknowledgedBy} secondary={formatDateTime(row.acknowledgedAt)} />
          ) : (
            <span className="text-muted-foreground">Not acknowledged</span>
          ),
      },
      {
        id: 'status',
        header: 'Status',
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
      {
        id: 'action',
        header: 'Action',
        align: 'right',
        cell: ({ row }) =>
          itemDistributable(row) ? (
            <Button
              size="sm"
              variant="outline"
              onClick={(event) => {
                event.stopPropagation();
                setDistributing(row);
              }}
            >
              Distribute
            </Button>
          ) : null,
      },
    ],
    [],
  );

  return (
    <>
      <PageHeading
        title="Inbound mail"
        subtitle="Registration, and the acknowledgement that closes each item."
        crumbs={[{ label: 'Dispatch', to: dispatchPaths.dashboard }, { label: 'Inbound mail' }]}
        actions={
          <>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {/* DISPATCH_INBOUND_REGISTER, which is the mailroom officer's grant. */}
            {canRegisterInbound() && (
              <Button variant="primary" onClick={() => setRegistering(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Register inbound item
              </Button>
            )}
          </>
        }
      />

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={query.initialising}
            label="Awaiting distribution"
            value={awaitingDistribution.length}
            description="Received, not yet handed over"
          />
          <MetricCard
            variant="soft"
            loading={query.initialising}
            label="Acknowledged"
            value={acknowledged.length}
            description="Handed over with a name"
          />
          <MetricCard
            variant="soft"
            loading={query.initialising}
            label="Registered in this window"
            value={query.data?.totalElements ?? 0}
            description="Inbound items matching the filters, site-wide"
          />
        </MetricCards>
      </PageSection>

      <PageSection>
        <Callout tone="info" title="Distribution is the record that matters">
          An acknowledgement names who physically took the item. Without it there is nothing to show
          that the mail reached its recipient, so the signature reference is worth capturing even
          though the service treats it as optional.
        </Callout>
      </PageSection>

      <Panel
        title="Inbound items"
        description={`Registered at the ${siteCode} mailroom and closed by the recipient acknowledgement`}
      >
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix={PREFIX}
            caption="Inbound mail at this site, with the recipient, sensitivity, whether distribution has been acknowledged, and status."
            columns={columns}
            rows={rows}
            rowKey={(row) => row.id}
            loading={query.loading}
            totalPages={query.data?.totalPages ?? 1}
            totalItems={query.data?.totalElements ?? 0}
            onRowClick={(row) => navigate(dispatchPaths.itemDetail(row.id))}
            searchPlaceholder="Search item, sender or recipient"
            filters={
              <>
                <FilterDropdown
                  paramPrefix={PREFIX}
                  name="status"
                  label="Status"
                  options={ITEM_STATUSES.map((value) => ({ value, label: humanise(value) }))}
                />
                <Input
                  name="handler"
                  aria-label="Handler"
                  placeholder="Handler: exact name"
                  defaultValue={filters.handler ?? ''}
                />
              </>
            }
            actions={
              <DateRangeSelector
                aria-label="Received between"
                placeholder="Received: any time"
                value={range}
                onChange={setRange}
              />
            }
            emptyTitle="Nothing waiting"
            emptyDescription="No inbound item matches these filters."
          />
        </DataState>
      </Panel>

      {registering && (
        <RegisterItemDialog
          open
          inboundOnly
          defaultSiteCode={siteCode}
          onClose={() => setRegistering(false)}
          onSaved={(item) => {
            notifySuccess(`${item.itemNumber} registered as inbound.`);
            query.refetch();
          }}
        />
      )}

      {distributing && (
        <DistributeInboundDialog
          open
          item={distributing}
          onClose={() => setDistributing(null)}
          onSaved={() => {
            notifySuccess('Distribution recorded with its acknowledgement.');
            query.refetch();
          }}
        />
      )}
    </>
  );
};

export default InboundMailPage;

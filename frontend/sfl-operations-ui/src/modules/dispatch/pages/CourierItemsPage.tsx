import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button, DateRangeSelector, Input, type TableColumn } from '@rfdtech/components';
import { Download, Plus, ShieldAlert } from 'lucide-react';
import { CourierItem } from 'modules/dispatch/api/dto';
import {
  ITEM_DIRECTIONS,
  ITEM_STATUSES,
  ITEM_TYPES,
  SENSITIVITIES,
} from 'modules/dispatch/api/enums';
import { courierItemsApi, dispatchReportsApi } from 'modules/dispatch/api/dispatchApi';
import CellStack from 'modules/dispatch/components/CellStack';
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
import { RegisterItemDialog } from 'modules/dispatch/dialogs/itemDialogs';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { canRegisterItems } from 'modules/fleet/api/access';

const PREFIX = 'items';

const options = (values: readonly string[]) =>
  values.map((value) => ({ value, label: humanise(value) }));

/**
 * The courier item register.
 *
 * Site, direction, status, sensitivity, handler, the search over item number, sender and recipient,
 * item type and the date range all reach the service. Every filter does, so the footer counts the
 * register rather than a window of it.
 *
 * Page, size, search and the dropdown filters live in the URL under `items.`, so a filtered view
 * survives a reload and can be sent on.
 */
const CourierItemsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [range, setRange] = useState(emptyRange);
  const [registering, setRegistering] = useState(false);
  const [exporting, setExporting] = useState(false);

  const table = useRegisterQuery(PREFIX);
  const { filters } = table;

  const query = useApiQuery(
    (signal) =>
      courierItemsApi.search(
        {
          siteCode,
          direction: (filters.direction as CourierItem['direction']) || undefined,
          status: (filters.status as CourierItem['status']) || undefined,
          sensitivity: (filters.sensitivity as CourierItem['sensitivity']) || undefined,
          itemType: (filters.itemType as CourierItem['itemType']) || undefined,
          handler: filters.handler?.trim() || undefined,
          reference: table.search || undefined,
          ...rangeToInstants(range),
          page: table.page,
          size: table.size,
        },
        signal,
      ),
    [
      siteCode,
      filters.direction,
      filters.status,
      filters.sensitivity,
      filters.itemType,
      filters.handler,
      table.search,
      range,
      table.page,
      table.size,
    ],
  );

  useClampRegisterPage(table, query.data?.totalPages);

  const exportReport = async () => {
    setExporting(true);
    try {
      const fileName = await dispatchReportsApi.items(siteCode);
      notifySuccess(
        `Downloaded ${fileName}.`,
        'The service exports the site’s items, not the filtered view.',
      );
    } catch (error) {
      notifyError(error);
    } finally {
      setExporting(false);
    }
  };

  const columns = useMemo<TableColumn<CourierItem>[]>(
    () => [
      {
        id: 'item',
        header: 'Item',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={row.itemNumber}
            secondary={`${row.origin} → ${row.destination} · ${humanise(row.itemType)}`}
          />
        ),
      },
      {
        id: 'direction',
        header: 'Direction',
        cell: ({ row }) => humanise(row.direction),
      },
      {
        id: 'sensitivity',
        header: 'Sensitivity',
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            <StatusBadge value={row.sensitivity} />
            {row.chainOfCustodyRequired && (
              <ShieldAlert
                size={14}
                strokeWidth={1.75}
                className="shrink-0 text-muted-foreground"
                aria-label="Chain of custody required"
              />
            )}
          </div>
        ),
      },
      {
        id: 'handler',
        header: 'Handler',
        cell: ({ row }) =>
          row.assignedHandler ?? <span className="text-muted-foreground">Unassigned</span>,
      },
      {
        id: 'registered',
        header: 'Registered',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
      {
        id: 'status',
        header: 'Status',
        cell: ({ row }) => (
          <div className="flex items-center gap-1.5">
            {row.undelivered && <StatusBadge value="MISSING" label="Undelivered" tone="blocked" />}
            <StatusBadge value={row.status} />
          </div>
        ),
      },
    ],
    [],
  );

  return (
    <>
      <PageHeading
        title="Courier items"
        subtitle="Every tracked item at this site, inbound and outbound."
        crumbs={[{ label: 'Dispatch', to: dispatchPaths.dashboard }, { label: 'Courier items' }]}
        actions={
          <>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button variant="outline" loading={exporting} onClick={exportReport}>
              <Download size={14} strokeWidth={1.5} aria-hidden="true" />
              Export CSV
            </Button>
            {/* DISPATCH_ITEM_REGISTER - a mailroom officer's grant, not a reader's. */}
            {canRegisterItems() && (
              <Button variant="primary" onClick={() => setRegistering(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Register item
              </Button>
            )}
          </>
        }
      />

      <Panel
        title="Items"
        description={`Every tracked item at ${siteCode}, inbound and outbound. Confidential items move under chain of custody.`}
      >
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix={PREFIX}
            caption="Courier items matching the current filters, with direction, sensitivity, whether a chain of custody is required, handler and status."
            columns={columns}
            rows={query.data?.content ?? []}
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
                  name="direction"
                  label="Direction"
                  options={options(ITEM_DIRECTIONS)}
                />
                <FilterDropdown
                  paramPrefix={PREFIX}
                  name="status"
                  label="Status"
                  options={options(ITEM_STATUSES)}
                />
                <FilterDropdown
                  paramPrefix={PREFIX}
                  name="sensitivity"
                  label="Sensitivity"
                  options={options(SENSITIVITIES)}
                />
                <FilterDropdown
                  paramPrefix={PREFIX}
                  name="itemType"
                  label="Item type"
                  options={options(ITEM_TYPES)}
                />
                <Input
                  name="handler"
                  aria-label="Handler"
                  placeholder="Handler: part of a name"
                  defaultValue={filters.handler ?? ''}
                />
              </>
            }
            actions={
              <DateRangeSelector
                aria-label="Registered between"
                placeholder="Registered: any time"
                value={range}
                onChange={setRange}
              />
            }
            emptyTitle="No item matches these filters"
            emptyDescription="Try removing a filter or adjusting your search terms."
          />
        </DataState>
      </Panel>

      {registering && (
        <RegisterItemDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setRegistering(false)}
          onSaved={(item) => {
            notifySuccess(
              `${item.itemNumber} registered.`,
              item.chainOfCustodyRequired
                ? 'It requires a chain of custody - every handover must be recorded.'
                : 'No chain of custody is required for this item.',
            );
            query.refetch();
            navigate(dispatchPaths.itemDetail(item.id));
          }}
        />
      )}
    </>
  );
};

export default CourierItemsPage;

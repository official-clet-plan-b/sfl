import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button, DateRangeSelector, type TableColumn } from '@rfdtech/components';
import { Plus } from 'lucide-react';
import { DispatchManifest } from 'modules/dispatch/api/dto';
import { DISPATCH_STATUSES, DispatchStatus } from 'modules/dispatch/api/enums';
import { manifestsApi } from 'modules/dispatch/api/dispatchApi';
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
import { CreateManifestDialog } from 'modules/dispatch/dialogs/manifestDialogs';
import { humanise } from 'modules/fleet/api/enums';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { canCreateManifests } from 'modules/fleet/api/access';

export const MANIFESTS_PREFIX = 'manifests';

/**
 * The manifest register.
 *
 * Site, stage, destination centre and the date range all reach the service; the search box is the
 * destination centre, which is the one text field the manifest search accepts. The seal count is
 * shown beside the item count because the two disagreeing is the first sign that a consignment was
 * assembled wrongly - a sealed manifest with no seals recorded should not exist.
 *
 * The stage filter is read from `manifests.f_status`, which is what the dashboard's figures link to.
 */
const ManifestsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [range, setRange] = useState(emptyRange);
  const [creating, setCreating] = useState(false);

  const table = useRegisterQuery(MANIFESTS_PREFIX);

  const query = useApiQuery(
    (signal) =>
      manifestsApi.search(
        {
          siteCode,
          status: (table.filters.status as DispatchStatus) || undefined,
          destinationCentre: table.search || undefined,
          ...rangeToInstants(range),
          page: table.page,
          size: table.size,
        },
        signal,
      ),
    [siteCode, table.filters.status, table.search, range, table.page, table.size],
  );

  useClampRegisterPage(table, query.data?.totalPages);

  const columns = useMemo<TableColumn<DispatchManifest>[]>(
    () => [
      {
        id: 'manifest',
        header: 'Manifest',
        minWidth: 240,
        cell: ({ row }) => <CellStack primary={row.route} secondary={row.manifestNumber} />,
      },
      {
        id: 'destination',
        header: 'Destination',
        cell: ({ row }) =>
          row.destinationCentre ?? (
            <span className="text-muted-foreground">No destination centre recorded</span>
          ),
      },
      {
        id: 'handler',
        header: 'Carried by',
        cell: ({ row }) => (
          <CellStack
            primary={row.assignedHandler}
            secondary={row.tripId ? 'On a trip' : undefined}
          />
        ),
      },
      {
        id: 'seals',
        header: 'Seals and items',
        cell: ({ row }) => (
          <CellStack
            primary={`${formatNumber(row.itemCount)} item${row.itemCount === 1 ? '' : 's'}`}
            secondary={
              row.sealIds.length > 0
                ? `${formatNumber(row.sealIds.length)} seal${row.sealIds.length === 1 ? '' : 's'}`
                : 'No seals'
            }
          />
        ),
      },
      {
        id: 'dispatched',
        header: 'Dispatched',
        cell: ({ row }) => formatDateTime(row.dispatchedAt),
      },
      {
        id: 'status',
        header: 'Custody stage',
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
    ],
    [],
  );

  return (
    <>
      <PageHeading
        title="Manifests"
        subtitle="Consignments, their seals, custody chain, receipt and return leg."
        crumbs={[{ label: 'Dispatch', to: dispatchPaths.dashboard }, { label: 'Manifests' }]}
        actions={
          <>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {/* DISPATCH_MANIFEST_CREATE. A reporting viewer reads the register and creates nothing. */}
            {canCreateManifests() && (
              <Button variant="primary" onClick={() => setCreating(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Create a manifest
              </Button>
            )}
          </>
        }
      />

      <Panel title="Manifests" description={`Consignments assembled at ${siteCode}`}>
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix={MANIFESTS_PREFIX}
            caption="Dispatch manifests matching the current filters, with destination, who carries them, item and seal counts, dispatch time and custody stage."
            columns={columns}
            rows={query.data?.content ?? []}
            rowKey={(row) => row.id}
            loading={query.loading}
            totalPages={query.data?.totalPages ?? 1}
            totalItems={query.data?.totalElements ?? 0}
            onRowClick={(row) => navigate(dispatchPaths.manifestDetail(row.id))}
            searchPlaceholder="Search destination centre"
            spreadFilters
            filters={
              <FilterDropdown
                paramPrefix={MANIFESTS_PREFIX}
                name="status"
                label="Stage"
                options={DISPATCH_STATUSES.map((value) => ({ value, label: humanise(value) }))}
              />
            }
            actions={
              <DateRangeSelector
                aria-label="Dispatched between"
                placeholder="Dispatched: any time"
                value={range}
                onChange={setRange}
              />
            }
            emptyTitle="No manifests open"
            emptyDescription="No manifest matches these filters."
          />
        </DataState>
      </Panel>

      {creating && (
        <CreateManifestDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreating(false)}
          onSaved={(manifest) => {
            notifySuccess(
              `${manifest.manifestNumber} created as a draft.`,
              'Add its items before sealing - the contents freeze at that point.',
            );
            query.refetch();
            navigate(dispatchPaths.manifestDetail(manifest.id));
          }}
        />
      )}
    </>
  );
};

export default ManifestsPage;

import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import {
  Card,
  Dropdown,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  useBreadcrumbs,
  useTableState,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { FacilityAsset } from '../api/dto';
import { assetCategories, assetCriticalities, assetOperationalStatuses } from '../api/enums';
import type { AssetCategory, AssetCriticality, AssetOperationalStatus } from '../api/enums';
import {
  changeAssetLifecycle,
  registerAsset,
  relocateAsset,
  searchAssets,
  updateAsset,
} from '../api/facilitiesApi';
import {
  createAssetControl,
  editAssetControl,
  relocateAssetControl,
  retireAssetControl,
} from '../api/workflow';
import ControlButton from '../components/ControlButton';
import RowActions, { EditRowAction, MoveRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import { assetStatusTone, formatDate, humaniseCode } from '../components/facilitiesFormat';
import { LifecycleDialog } from '../dialogs/common';
import {
  EditAssetDialog,
  RegisterAssetDialog,
  RelocateAssetDialog,
} from '../dialogs/assetDialogs';

/**
 * The facility asset register.
 *
 * Fixed plant - the chillers, lifts, generators and panels S153 raises work orders against. Not the
 * asset references that carry cross-programme identity for movable things; the two are linked by
 * value and answer different questions.
 *
 * Criticality and condition sit next to each other because their combination is what matters: a low
 * asset out of service is a note, a critical one out of service has blocked a hall.
 */
const pageSizeOptions = [10, 25, 50, 100];

const AssetRegisterPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Assets' }]);

  /*
    Paging and the filters live in the URL, as the table keeps them. The search endpoint takes one
    value per axis, so each filter is a single choice: choosing replaces, and clearing the field
    removes the constraint. Changing a filter returns the table to its first page.
  */
  const { filters, page, pageSize } = useTableState({
    paramPrefix: 'assets',
    defaultPageSize: 25,
    pageSizeOptions,
  });
  const category = filters.category ?? '';
  const criticality = filters.criticality ?? '';
  const status = filters.condition ?? '';

  // The fields hold the operator's choice until the filter is applied; the URL holds what applied.
  const [categoryValue, setCategoryValue] = useState(category);
  const [criticalityValue, setCriticalityValue] = useState(criticality);
  const [statusValue, setStatusValue] = useState(status);

  const [siteCode, setSiteCode] = useState<string>(defaultSite);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<FacilityAsset | null>(null);
  const [moving, setMoving] = useState<FacilityAsset | null>(null);
  const [retiring, setRetiring] = useState<FacilityAsset | null>(null);

  const { data, loading, error, refetch } = useApiQuery(
    (signal) =>
      searchAssets(
        {
          siteCode: siteCode || undefined,
          category: (category as AssetCategory) || undefined,
          criticality: (criticality as AssetCriticality) || undefined,
          operationalStatus: (status as AssetOperationalStatus) || undefined,
          // The table counts pages from one, the service from zero.
          page: page - 1,
          size: pageSize,
        },
        signal,
      ),
    [siteCode, category, criticality, status, page, pageSize],
  );

  const columns: TableColumn<FacilityAsset>[] = [
    {
      id: 'assetCode',
      header: 'Code',
      width: 140,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.assetCode}</span>,
    },
    { id: 'name', header: 'Asset', accessorKey: 'name' },
    {
      id: 'category',
      header: 'Category',
      cell: ({ row }) => humaniseCode(row.category),
    },
    {
      id: 'criticality',
      header: 'Criticality',
      width: 120,
      cell: ({ row }) => (
        <StatusBadge
          value={row.criticality}
          tone={
            row.criticality === 'CRITICAL'
              ? 'blocked'
              : row.criticality === 'HIGH'
                ? 'caution'
                : 'neutral'
          }
        />
      ),
    },
    {
      id: 'status',
      header: 'Condition',
      width: 160,
      cell: ({ row }) => (
        <StatusBadge value={row.operationalStatus} tone={assetStatusTone(row.operationalStatus)} />
      ),
    },
    {
      id: 'serviceDue',
      header: 'Service due',
      width: 140,
      align: 'right',
      cell: ({ row }) => <span className="text-muted-foreground">{formatDate(row.serviceDueOn)}</span>,
    },
    {
      id: 'impairs',
      header: '',
      width: 130,
      align: 'right',
      cell: ({ row }) =>
        row.impairsReadiness ? (
          <StatusBadge value="BLOCKING" label="Impairs space" tone="blocked" />
        ) : null,
    },
    {
      id: 'actions',
      header: '',
      width: 150,
      align: 'right',
      cell: ({ row: asset }) => (
        <RowActions>
          <EditRowAction
            state={editAssetControl(asset)}
            onClick={() => setEditing(asset)}
            label={`Edit ${asset.assetCode}`}
          />
          <MoveRowAction
            state={relocateAssetControl(asset)}
            onClick={() => setMoving(asset)}
            label={`Move ${asset.assetCode}`}
          />
          <RetireRowAction
            state={retireAssetControl(asset)}
            onClick={() => setRetiring(asset)}
            label={`Retire ${asset.assetCode}`}
          />
        </RowActions>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Facility assets</SectionTitle>
          <SectionDescription>
            Fixed plant and equipment, and what its condition does to the estate
          </SectionDescription>
          <SectionActions>
            <SiteSelect
              value={siteCode}
              onChange={setSiteCode}
              allowEmpty
              emptyLabel="All sites"
            />
            <ControlButton state={createAssetControl()} variant="primary" onClick={() => setAdding(true)}>
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Register an asset
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="assets" variant="soft">
            <Card bordered>
              <TableHeader>
                <TableFilter>
                  <Dropdown
                    name="category"
                    aria-label="Category"
                    placeholder="All categories"
                    clearable
                    value={categoryValue || null}
                    onValueChange={(next) => setCategoryValue(next ?? '')}
                    options={assetCategories.map((value) => ({ value, label: humaniseCode(value) }))}
                  />
                  <Dropdown
                    name="criticality"
                    aria-label="Criticality"
                    placeholder="All criticalities"
                    clearable
                    value={criticalityValue || null}
                    onValueChange={(next) => setCriticalityValue(next ?? '')}
                    options={assetCriticalities.map((value) => ({
                      value,
                      label: humaniseCode(value),
                    }))}
                  />
                  <Dropdown
                    name="condition"
                    aria-label="Condition"
                    placeholder="All conditions"
                    clearable
                    value={statusValue || null}
                    onValueChange={(next) => setStatusValue(next ?? '')}
                    options={assetOperationalStatuses.map((value) => ({
                      value,
                      label: humaniseCode(value),
                    }))}
                  />
                </TableFilter>
              </TableHeader>
              <TableContent
                variant="soft"
                columns={columns}
                data={data?.items ?? []}
                rowKey={(asset) => asset.id}
                loading={loading}
                onRowClick={(asset) => navigate(facilitiesPaths.assetDetail(asset.id))}
                aria-label="Facility assets"
                emptyContent={
                  <EmptyState
                    title="No assets match these filters"
                    description="Widen the site, or clear the category and condition filters."
                  />
                }
              />
            </Card>
            <TableFooter noBorder>
              <TablePagination
                totalPages={Math.max(1, data?.totalPages ?? 1)}
                totalItems={data?.totalElements ?? 0}
                pageSizeOptions={pageSizeOptions}
              />
            </TableFooter>
          </Table>
        </DataState>
      </PageSection>

      {adding && (
        <RegisterAssetDialog
          siteCode={siteCode || defaultSite}
          onClose={() => setAdding(false)}
          onSubmit={async (request) => {
            const created = await registerAsset(request);
            setAdding(false);
            notify.notifySuccess(`${created.assetCode} registered at ${created.siteCode}.`);
            refetch();
          }}
        />
      )}

      {editing && (
        <EditAssetDialog
          asset={editing}
          onClose={() => setEditing(null)}
          onSubmit={async (request) => {
            const saved = await updateAsset(editing.id, request);
            setEditing(null);
            notify.notifySuccess(`${saved.assetCode} updated.`);
            refetch();
          }}
        />
      )}

      {moving && (
        <RelocateAssetDialog
          asset={moving}
          onClose={() => setMoving(null)}
          onSubmit={async (request) => {
            const saved = await relocateAsset(moving.id, request);
            setMoving(null);
            notify.notifySuccess(`${saved.assetCode} moved.`);
            refetch();
          }}
        />
      )}

      {retiring && (
        <LifecycleDialog
          noun="asset"
          label={retiring.assetCode}
          current={retiring.lifecycleStatus}
          expectedVersion={retiring.metadata.version}
          onClose={() => setRetiring(null)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeAssetLifecycle(retiring.id, { status, expectedVersion });
            setRetiring(null);
            notify.notifySuccess(
              `${saved.assetCode} is now ${status.toLowerCase()}. Readiness has been re-derived.`,
            );
            refetch();
          }}
        />
      )}
    </>
  );
};

export default AssetRegisterPage;

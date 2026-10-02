import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { FuelImportBatch, FuelImportRow } from 'modules/fuel/api/dto';
import { CSV_OPTIONAL_HEADERS, CSV_REQUIRED_HEADERS } from 'modules/fuel/api/enums';
import { fuelImportsApi } from 'modules/fuel/api/fuelApi';
import { CsvImportDialog } from 'modules/fuel/dialogs/importDialogs';
import { useClampPage, useServerPage } from 'shared/hooks/useServerPage';
import { shortId } from 'modules/fuel/components/fuelFormat';
import Button from 'shared/components/Button';
import DataState from 'shared/components/DataState';
import DataTable, { CellStack, Column } from 'shared/components/DataTable';
import FilterBar from 'shared/components/FilterBar';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import PageHeader from 'shared/components/PageHeader';
import SectionCard from 'shared/components/SectionCard';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import StatCard from 'shared/components/StatCard';
import StatusChip from 'shared/components/StatusChip';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { canImportFuel } from 'modules/fleet/api/access';

/** `''` means every row, and is what the service reads as no filter. */
const ROW_FILTERS: { value: FuelImportRow['status'] | ''; label: string }[] = [
  { value: '', label: 'Every row' },
  { value: 'REJECTED', label: 'Rejected only' },
  { value: 'ACCEPTED', label: 'Accepted only' },
];

/**
 * CSV import, with the batch history the service now keeps.
 *
 * Every batch and every row outcome is readable. This screen used to hold the batches uploaded in
 * one browsing session and warn that leaving the page lost them - the rows were written and nothing
 * read them back - so a rejected row an operator did not deal with immediately was simply gone.
 *
 * The rows are paged and filtered by the service. They used to come from the batch detail read,
 * which returns all of them, and the rejected-only filter ran over that array - so a file of several
 * thousand rows arrived whole and was filtered in the browser. `GET /imports/{id}/rows?status=` does
 * both properly, which matters most for the filter: the rejected rows are the reason anyone opens
 * this screen.
 *
 * The one thing that has not changed is the most important: a batch is **never rejected as a whole
 * for a bad row**. Each row goes through the same capture command as a manual entry and is accepted
 * or rejected on its own, so the row table is the real result, not the totals above it.
 */
const FuelImportsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [importing, setImporting] = useState(false);
  const [selectedBatchId, setSelectedBatchId] = useState<string | null>(null);
  const [rowFilter, setRowFilter] = useState<FuelImportRow['status'] | ''>('');

  const paging = useServerPage(siteCode, 10);

  const batches = useApiQuery(
    (signal) =>
      fuelImportsApi.search({ siteCode, page: paging.page, size: paging.size }, signal),
    [siteCode, paging.page, paging.size],
  );

  useClampPage(paging.page, batches.data?.totalPages, paging.setPage);

  /** The most recent batch is the one an operator almost always wants, so it opens selected. */
  const activeBatchId = selectedBatchId ?? batches.data?.content[0]?.id ?? null;

  const batch = useApiQuery(
    (signal) =>
      activeBatchId ? fuelImportsApi.findById(activeBatchId, signal) : Promise.resolve(undefined),
    [activeBatchId],
  );

  /** Reset to the first page when the batch or the filter changes - page 4 of the old set is nothing. */
  const rowPaging = useServerPage(`${activeBatchId ?? ''}-${rowFilter}`, 50);

  const rowPage = useApiQuery(
    (signal) =>
      activeBatchId
        ? fuelImportsApi.rows(
            activeBatchId,
            { status: rowFilter || undefined, page: rowPaging.page, size: rowPaging.size },
            signal,
          )
        : Promise.resolve(undefined),
    [activeBatchId, rowFilter, rowPaging.page, rowPaging.size],
  );

  useClampPage(rowPaging.page, rowPage.data?.totalPages, rowPaging.setPage);

  const rows = rowPage.data?.content ?? [];

  const batchColumns = useMemo<Column<FuelImportBatch>[]>(
    () => [
      {
        key: 'file',
        header: 'File',
        width: 260,
        cell: (row) => (
          <CellStack
            primary={row.fileName}
            secondary={`${row.sourceSystem} · ${formatDateTime(row.submittedAt)}`}
          />
        ),
      },
      {
        key: 'rows',
        header: 'Rows',
        width: 100,
        align: 'right',
        cell: (row) => formatNumber(row.totalRows),
      },
      {
        key: 'accepted',
        header: 'Accepted',
        width: 110,
        align: 'right',
        cell: (row) => formatNumber(row.acceptedRows),
      },
      {
        key: 'rejected',
        header: 'Rejected',
        width: 110,
        align: 'right',
        cell: (row) =>
          row.rejectedRows > 0 ? (
            <span className="font-semibold text-error-800">{formatNumber(row.rejectedRows)}</span>
          ) : (
            <span className="text-gray-500">0</span>
          ),
      },
      {
        key: 'submittedBy',
        header: 'Imported by',
        width: 160,
        hideBelowLg: true,
        cell: (row) => row.submittedBy,
      },
      {
        key: 'status',
        header: 'Status',
        width: 190,
        align: 'right',
        cell: (row) => <StatusChip value={row.status} />,
      },
    ],
    [],
  );

  const rowColumns = useMemo<Column<FuelImportRow>[]>(
    () => [
      {
        key: 'row',
        header: 'Row',
        width: 90,
        cell: (row) => <span className="font-semibold text-gray-900">{row.rowNumber}</span>,
      },
      {
        key: 'status',
        header: 'Outcome',
        width: 120,
        cell: (row) => <StatusChip value={row.status} />,
      },
      {
        key: 'detail',
        header: 'Detail',
        width: 420,
        cell: (row) =>
          row.status === 'ACCEPTED' ? (
            <CellStack
              primary="Transaction created"
              secondary={row.transactionId ? shortId(row.transactionId) : undefined}
            />
          ) : (
            <CellStack
              primary={row.errorMessage ?? 'The service gave no message.'}
              secondary={row.errorCode ?? undefined}
            />
          ),
      },
      {
        key: 'link',
        header: '',
        width: 110,
        align: 'right',
        cell: (row) =>
          row.transactionId ? (
            <Button
              size="sm"
              variant="ghost"
              endIcon="chevron-right"
              onClick={() => navigate(fuelPaths.transactionDetail(row.transactionId as string))}
            >
              Open
            </Button>
          ) : null,
      },
    ],
    [navigate],
  );

  const selected = batch.data;

  return (
    <div>
      <PageHeader
        title="CSV imports"
        subtitle="Bulk capture, with an accepted or rejected outcome for every row."
        crumbs={[{ label: 'Fuel', to: fuelPaths.dashboard }, { label: 'CSV imports' }]}
        actions={
          // FUEL_TRANSACTION_IMPORT, which only the integration engineer and the fuel officer hold.
          canImportFuel() ? (
            <Button variant="primary" startIcon="upload" onClick={() => setImporting(true)}>
              Import a CSV
            </Button>
          ) : undefined
        }
      />

      <div className="space-y-5">
        <SectionCard title="Import history" subtitle="Every batch imported at this site" flush>
          <FilterBar>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
          </FilterBar>
          <DataState
            loading={batches.initialising}
            error={batches.error}
            empty={(batches.data?.totalElements ?? 0) === 0}
            emptyTitle="Nothing imported yet"
            emptyHint="Upload a CSV to see its row-by-row outcome here."
            onRetry={batches.refetch}
            minHeight={200}
          >
            <DataTable
              rows={batches.data?.content ?? []}
              columns={batchColumns}
              getRowId={(row) => row.id}
              loading={batches.loading}
              onRowClick={(row) => {
                setSelectedBatchId(row.id);
                setRowFilter(row.rejectedRows > 0 ? 'REJECTED' : '');
              }}
              caption="Fuel CSV import batches at this site, with row counts, who imported each and whether any rows were rejected."
              page={batches.data?.page ?? paging.page}
              pageSize={batches.data?.size ?? paging.size}
              totalElements={batches.data?.totalElements ?? 0}
              onPageChange={paging.setPage}
              onPageSizeChange={paging.setSize}
              dense
            />
          </DataState>
        </SectionCard>

        {selected && (
          <>
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <StatCard
                label="Rows in the file"
                value={formatNumber(selected.totalRows)}
                icon="document"
                caption={selected.fileName}
              />
              <StatCard
                label="Accepted"
                value={formatNumber(selected.acceptedRows)}
                icon="check-circle"
                tone={selected.acceptedRows > 0 ? 'good' : 'neutral'}
                caption="Transactions created"
              />
              <StatCard
                label="Rejected"
                value={formatNumber(selected.rejectedRows)}
                icon="alert-circle"
                tone={selected.rejectedRows > 0 ? 'critical' : 'neutral'}
                caption="Nothing was created for these"
              />
              <StatCard
                label="Imported"
                value={shortId(selected.id)}
                icon="inbox"
                caption={`${formatDateTime(selected.submittedAt)} by ${selected.submittedBy}`}
              />
            </div>

            <SectionCard
              title="Row outcomes"
              subtitle={`${selected.totalRows} rows · ${selected.fileName}`}
              actions={
                <div className="flex items-center gap-1.5">
                  {ROW_FILTERS.map((filter) => (
                    <Button
                      key={filter.value}
                      size="sm"
                      variant={rowFilter === filter.value ? 'primary' : 'outline'}
                      onClick={() => setRowFilter(filter.value)}
                    >
                      {filter.label}
                    </Button>
                  ))}
                </div>
              }
              flush
            >
              <DataState
                loading={rowPage.initialising}
                error={rowPage.error}
                empty={(rowPage.data?.totalElements ?? 0) === 0}
                emptyTitle="No rows match this filter"
                emptyHint={
                  rowFilter === 'REJECTED'
                    ? 'Every row in this batch was accepted.'
                    : rowFilter === 'ACCEPTED'
                      ? 'Every row in this batch was rejected.'
                      : 'Nothing to show.'
                }
                onRetry={rowPage.refetch}
                minHeight={200}
              >
                <DataTable
                  rows={rows}
                  columns={rowColumns}
                  getRowId={(row) => row.id}
                  loading={rowPage.loading}
                  caption="The outcome of every row in this import batch, with the validation error the service recorded for each rejected row."
                  page={rowPage.data?.page ?? rowPaging.page}
                  pageSize={rowPage.data?.size ?? rowPaging.size}
                  totalElements={rowPage.data?.totalElements ?? 0}
                  onPageChange={rowPaging.setPage}
                  onPageSizeChange={rowPaging.setSize}
                  dense
                />
              </DataState>
            </SectionCard>
          </>
        )}

        <div className="grid gap-5 xl:grid-cols-2">
          <SectionCard
            title="Required column headers"
            subtitle="The first row of the file must name the columns"
          >
            <p className="text-theme-sm text-gray-700">
              These ten must be present and populated on every row:
            </p>
            <ul className="mt-2 flex flex-wrap gap-1.5">
              {CSV_REQUIRED_HEADERS.map((header) => (
                <li
                  key={header}
                  className="rounded border border-gray-200 bg-gray-50 px-2 py-0.5 font-mono text-theme-xs text-gray-900"
                >
                  {header}
                </li>
              ))}
            </ul>
            <p className="mt-4 text-theme-sm text-gray-700">These are optional:</p>
            <ul className="mt-2 flex flex-wrap gap-1.5">
              {CSV_OPTIONAL_HEADERS.map((header) => (
                <li
                  key={header}
                  className="rounded border border-gray-200 px-2 py-0.5 font-mono text-theme-xs text-gray-700"
                >
                  {header}
                </li>
              ))}
            </ul>
            <p className="mt-4 text-theme-xs text-gray-600">
              Column names are case sensitive and every row must have the same number of columns as
              the header. `occurredAt` is an ISO-8601 instant; `vehicleId`, `driverId`, `tripId` and
              `receiptEvidenceId` are UUIDs. Omitting `totalCost` lets the service compute it.
            </p>
          </SectionCard>

          <SectionCard title="How a batch is judged" subtitle="Row by row, never as a whole">
            <ul className="space-y-3 text-theme-sm text-gray-700">
              <li className="flex items-start gap-2.5">
                <Icon name="check-circle" size={15} className="mt-0.5 shrink-0 text-success-700" />
                <span>
                  Each row is captured through the same command as a manual entry, so it fails for
                  the same reasons: an unknown vehicle or driver, a total that does not match
                  quantity × unit price, an unparseable timestamp.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <Icon name="info" size={15} className="mt-0.5 shrink-0 text-teal-700" />
                <span>
                  A rejected row does not stop the batch. Accepted rows are committed and appear in
                  the transaction register immediately.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <Icon name="lock" size={15} className="mt-0.5 shrink-0 text-gray-600" />
                <span>
                  The same file content cannot be imported twice for one site and source system. The
                  batch is keyed on its own hash and the second attempt is refused before any row is
                  captured, naming the batch that already holds it.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <Icon name="scale" size={15} className="mt-0.5 shrink-0 text-gray-600" />
                <span>
                  Imported rows arrive in the received state. They are not reconciled until a run
                  covers them.
                </span>
              </li>
            </ul>
          </SectionCard>
        </div>
      </div>

      {importing && (
        <CsvImportDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setImporting(false)}
          onImported={(result) => {
            setSelectedBatchId(result.batchId);
            setRowFilter(result.rejectedRows > 0 ? 'REJECTED' : '');
            notifySuccess(
              `Imported ${result.acceptedRows} of ${result.totalRows} rows.`,
              result.rejectedRows > 0
                ? `${result.rejectedRows} rows were rejected - their errors are listed below.`
                : 'Every row was accepted.',
            );
            batches.refetch();
          }}
        />
      )}
    </div>
  );
};

export default FuelImportsPage;

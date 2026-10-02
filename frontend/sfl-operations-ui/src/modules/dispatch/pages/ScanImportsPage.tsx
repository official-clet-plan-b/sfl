import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  MetricCard,
  MetricCards,
  PageSection,
  Table,
  TableContent,
  TableFooter,
  TableHeader,
  TablePagination,
  TableSearch,
  Tabs,
  TabsList,
  TabsTrigger,
  useTableState,
  type TableColumn,
} from '@rfdtech/components';
import { ChevronRight, CircleAlert, CircleCheck, Info, Search, TriangleAlert, Upload } from 'lucide-react';
import { ScanImportBatch, ScanImportRow } from 'modules/dispatch/api/dto';
import { SCAN_CSV_HEADERS } from 'modules/dispatch/api/enums';
import { scanImportsApi } from 'modules/dispatch/api/dispatchApi';
import CellStack from 'modules/dispatch/components/CellStack';
import { Callout } from 'modules/dispatch/components/formKit';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import { REGISTER_PAGE_SIZES, filterLink } from 'modules/dispatch/components/registerTable';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { ScanImportDialog } from 'modules/dispatch/dialogs/exceptionDialogs';
import { shortId } from 'modules/fuel/components/fuelFormat';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { canRegisterItems } from 'modules/fleet/api/access';

const BATCH_PREFIX = 'scan-batches';
const ROW_PREFIX = 'scan-rows';

const ROW_FILTERS = [
  { value: 'ALL', label: 'Every row' },
  { value: 'PROBLEM', label: 'Mismatched and unregistered' },
  { value: 'MATCHED', label: 'Matched only' },
];

/**
 * A page of an in-memory list, in the library table's own URL state.
 *
 * Both tables here are held in the browser - the service has no list of batches, and a batch's rows
 * arrive whole - so the paging the other registers get from the service is done over the array.
 */
const usePagedList = <T,>(prefix: string, items: T[]) => {
  const { page, pageSize } = useTableState({
    paramPrefix: prefix,
    defaultPageSize: REGISTER_PAGE_SIZES[0],
    pageSizeOptions: REGISTER_PAGE_SIZES,
  });
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  const current = Math.min(page, totalPages);
  return {
    totalPages,
    visible: items.slice((current - 1) * pageSize, current * pageSize),
  };
};

/**
 * Scanner batch import.
 *
 * Unlike the fuel CSV import, a batch here **is** readable afterwards - `GET /scans/imports/{id}`
 * and its `/rows` both exist. What does not exist is a way to *list* batches for a site, so this
 * screen holds the batches uploaded in this browsing session and says so. A batch identifier can be
 * pasted in to reopen one from an earlier session, which is the workaround the missing list forces.
 *
 * Recorded as gap 3.
 */
const ScanImportsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [importing, setImporting] = useState(false);
  const [sessionBatches, setSessionBatches] = useState<ScanImportBatch[]>([]);
  const [activeBatchId, setActiveBatchId] = useState<string | null>(null);
  const [lookupId, setLookupId] = useState('');
  const [rowFilter, setRowFilter] = useState('ALL');

  const batch = useApiQuery(
    (signal) =>
      activeBatchId ? scanImportsApi.batch(activeBatchId, signal) : Promise.resolve(undefined),
    [activeBatchId],
  );

  const rows = useApiQuery(
    (signal) =>
      activeBatchId ? scanImportsApi.rows(activeBatchId, signal) : Promise.resolve(undefined),
    [activeBatchId],
  );

  const filteredRows = useMemo(() => {
    const all = rows.data ?? [];
    if (rowFilter === 'PROBLEM') {
      return all.filter((row) => row.outcome !== 'MATCHED');
    }
    if (rowFilter === 'MATCHED') {
      return all.filter((row) => row.outcome === 'MATCHED');
    }
    return all;
  }, [rows.data, rowFilter]);

  const batchSearch = useTableState({ paramPrefix: BATCH_PREFIX }).search.trim().toLowerCase();
  const matchingBatches = useMemo(
    () =>
      batchSearch
        ? sessionBatches.filter((entry) =>
            `${entry.batchReference ?? ''} ${entry.sourceSystem} ${entry.id}`
              .toLowerCase()
              .includes(batchSearch),
          )
        : sessionBatches,
    [sessionBatches, batchSearch],
  );
  const batchPage = usePagedList(BATCH_PREFIX, matchingBatches);
  const rowPage = usePagedList(ROW_PREFIX, filteredRows);

  const batchColumns = useMemo<TableColumn<ScanImportBatch>[]>(
    () => [
      {
        id: 'batch',
        header: 'Batch',
        minWidth: 220,
        cell: ({ row }) => (
          <CellStack
            primary={row.batchReference ?? shortId(row.id)}
            secondary={row.dispatchId ? `Against ${shortId(row.dispatchId)}` : row.sourceSystem}
          />
        ),
      },
      { id: 'source', header: 'Source', accessorKey: 'sourceSystem' },
      {
        id: 'uploaded',
        header: 'Uploaded',
        cell: ({ row }) => formatDateTime(row.metadata.createdAt),
      },
      { id: 'rows', header: 'Rows', align: 'right', accessorKey: 'totalRows' },
      { id: 'matched', header: 'Matched', align: 'right', accessorKey: 'acceptedRows' },
      { id: 'mismatch', header: 'Mismatch', align: 'right', accessorKey: 'mismatchRows' },
      {
        id: 'outcome',
        header: 'Outcome',
        cell: ({ row }) => (
          <StatusBadge
            value={row.status}
            {...(row.mismatchRows === 0 && row.status === 'PROCESSED'
              ? { label: 'All matched', tone: 'ready' as const }
              : {})}
          />
        ),
      },
    ],
    [],
  );

  const rowColumns = useMemo<TableColumn<ScanImportRow>[]>(
    () => [
      {
        id: 'reference',
        header: 'Row',
        cell: ({ row }) => <span className="font-semibold text-foreground">{row.rowReference}</span>,
      },
      {
        id: 'code',
        header: 'Scanned code',
        cell: ({ row }) => <span className="font-mono text-xs">{row.scannedCode}</span>,
      },
      {
        id: 'outcome',
        header: 'Outcome',
        cell: ({ row }) => <StatusBadge value={row.outcome} />,
      },
      {
        id: 'detail',
        header: 'Detail',
        minWidth: 260,
        cell: ({ row }) =>
          row.courierItemId ? (
            <CellStack
              primary={row.message ?? 'Matched to an item'}
              secondary={shortId(row.courierItemId)}
            />
          ) : (
            (row.message ?? <span className="text-muted-foreground">No message recorded</span>)
          ),
      },
      {
        id: 'open',
        header: 'Item',
        align: 'right',
        cell: ({ row }) =>
          row.courierItemId ? (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => navigate(dispatchPaths.itemDetail(row.courierItemId as string))}
            >
              Open
              <ChevronRight size={14} strokeWidth={1.5} aria-hidden="true" />
            </Button>
          ) : null,
      },
    ],
    [navigate],
  );

  const openLookup = () => {
    const id = lookupId.trim();
    if (!id) {
      return;
    }
    setActiveBatchId(id);
    setRowFilter('ALL');
  };

  const selected = batch.data;

  return (
    <>
      <PageHeading
        title="Scan imports"
        subtitle="Scanner batches checked against the manifest, row by row."
        crumbs={[{ label: 'Dispatch', to: dispatchPaths.dashboard }, { label: 'Scan imports' }]}
        actions={
          <>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {/* A scan batch is a bulk custody record; registering items is what it amounts to. */}
            {canRegisterItems() && (
              <Button variant="primary" onClick={() => setImporting(true)}>
                <Upload size={14} strokeWidth={1.5} aria-hidden="true" />
                Import a batch
              </Button>
            )}
          </>
        }
      />

      <PageSection>
        <Callout tone="info" title="Batches are readable, but there is no list of them">
          The service can return any batch and its rows by identifier, and does not offer a way to
          list the batches for a site. The batches below are the ones uploaded from this screen since
          it was opened; paste an identifier to reopen one from an earlier session.
        </Callout>
      </PageSection>

      <Panel
        title="Scan batches"
        description={`Scanner files uploaded at ${siteCode}, each checked against a manifest row by row. Scans are checked against this site's manifests.`}
        actions={
          /*
            A real form, so Enter still opens the batch. The input it replaced did that with a key
            handler; a form gets it from the browser and keeps it working from the keyboard.
          */
          <form
            className="flex items-center gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              openLookup();
            }}
          >
            <Input
              aria-label="Open a batch by identifier"
              value={lookupId}
              onChange={(event) => setLookupId(event.target.value)}
              placeholder="Batch UUID"
            />
            <Button type="submit" variant="outline">
              <Search size={14} strokeWidth={1.5} aria-hidden="true" />
              Open
            </Button>
          </form>
        }
      >
        <Table paramPrefix={BATCH_PREFIX} variant="soft" aria-label="Batches imported in this session">
          <TableHeader>
            <TableSearch placeholder="Search batch or source" />
          </TableHeader>
          <TableContent
            variant="soft"
            columns={batchColumns}
            data={batchPage.visible}
            rowKey={(entry) => entry.id}
            onRowClick={(entry) => {
              setActiveBatchId(entry.id);
              setRowFilter(entry.mismatchRows > 0 ? 'PROBLEM' : 'ALL');
            }}
            emptyContent={
              <EmptyState
                title="None yet"
                description="Import a batch, or open one by its identifier."
              />
            }
          />
          <TableFooter noBorder>
            <TablePagination
              totalPages={batchPage.totalPages}
              totalItems={matchingBatches.length}
              pageSizeOptions={REGISTER_PAGE_SIZES}
            />
          </TableFooter>
        </Table>
      </Panel>

      {activeBatchId && (
        <DataState
          loading={batch.initialising}
          error={batch.error}
          onRetry={batch.refetch}
          minHeight={200}
        >
          {selected && (
            <>
              <PageSection>
                <MetricCards>
                  <MetricCard
                    variant="soft"
                    label="Rows scanned"
                    value={selected.totalRows}
                    description={selected.batchReference ?? shortId(selected.id)}
                  />
                  <MetricCard
                    variant="soft"
                    label="Matched"
                    value={selected.acceptedRows}
                    description="Found on the manifest"
                  />
                  <MetricCard
                    variant="soft"
                    label="Mismatched"
                    value={selected.mismatchRows}
                    description="Each raises an exception case"
                  />
                  <MetricCard
                    variant="soft"
                    label="Source"
                    value={selected.sourceSystem}
                    description={
                      selected.dispatchId
                        ? `Checked against ${shortId(selected.dispatchId)}`
                        : 'Checked against the site’s manifests'
                    }
                  />
                </MetricCards>
                {selected.mismatchRows > 0 && (
                  <div className="mt-3">
                    <Button
                      variant="outline"
                      onClick={() =>
                        navigate(
                          filterLink(dispatchPaths.exceptions, 'exceptions', 'type', 'SCAN_MISMATCH'),
                        )
                      }
                    >
                      View the cases raised
                    </Button>
                  </div>
                )}
              </PageSection>

              <Panel
                title="Row outcomes"
                description={`${selected.totalRows} rows · ${selected.sourceSystem}`}
              >
                <Tabs variant="pill" value={rowFilter} onValueChange={setRowFilter}>
                  <TabsList>
                    {ROW_FILTERS.map((filter) => (
                      <TabsTrigger key={filter.value} value={filter.value}>
                        {filter.label}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </Tabs>
                <DataState loading={false} error={rows.error} onRetry={rows.refetch}>
                  <Table paramPrefix={ROW_PREFIX} variant="soft" aria-label="Every scanned row in this batch, with the code, the outcome the service classified it as, and the item it matched.">
                    <TableContent
                      variant="soft"
                      columns={rowColumns}
                      data={rowPage.visible}
                      rowKey={(row) => row.id}
                      loading={rows.initialising}
                      emptyContent={
                        <EmptyState
                          title="No rows match this filter"
                          description={
                            rowFilter === 'PROBLEM'
                              ? 'Every scanned code in this batch matched the manifest.'
                              : 'Nothing to show.'
                          }
                        />
                      }
                    />
                    <TableFooter noBorder>
                      <TablePagination
                        totalPages={rowPage.totalPages}
                        totalItems={filteredRows.length}
                        pageSizeOptions={REGISTER_PAGE_SIZES}
                      />
                    </TableFooter>
                  </Table>
                </DataState>
              </Panel>
            </>
          )}
        </DataState>
      )}

      <PageSection>
        <div className="grid gap-6 xl:grid-cols-2">
          <Panel title="Before you import" description="Scans are checked against the manifest you choose at upload">
            <div className="space-y-3 text-sm text-foreground">
              <h3 className="font-semibold">File format</h3>
              <p>The header row is skipped. Columns are read by position rather than by name:</p>
              <ul className="flex flex-wrap gap-1.5">
                {SCAN_CSV_HEADERS.map((header, index) => (
                  <li key={header}>
                    <Badge variant="outline" className="font-mono">
                      {index + 1}. {header}
                    </Badge>
                  </li>
                ))}
              </ul>
              <p className="text-xs text-muted-foreground">
                A file with a single column is read as the scanned code, with a row reference
                generated for each line. Blank lines are skipped; a file with only a header is
                refused.
              </p>
            </div>
          </Panel>

          <Panel title="How a row is classified" description="Three outcomes, one of them fine">
            <ul className="space-y-3 text-sm text-foreground">
              <li className="flex items-start gap-2.5">
                <CircleCheck size={16} strokeWidth={1.75} className="mt-0.5 shrink-0 text-success" aria-hidden="true" />
                <span>
                  <strong>Matched</strong> - the code belongs to an item the manifest expects.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <CircleAlert size={16} strokeWidth={1.75} className="mt-0.5 shrink-0 text-error" aria-hidden="true" />
                <span>
                  <strong>Mismatch</strong> - the code belongs to a registered item, but not one this
                  manifest carries. An exception case is raised.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <TriangleAlert size={16} strokeWidth={1.75} className="mt-0.5 shrink-0 text-warning" aria-hidden="true" />
                <span>
                  <strong>Unregistered</strong> - the code belongs to no item on the register at all.
                  An exception case is raised.
                </span>
              </li>
              <li className="flex items-start gap-2.5">
                <Info size={16} strokeWidth={1.75} className="mt-0.5 shrink-0 text-info" aria-hidden="true" />
                <span>
                  Scanning a batch against the wrong consignment raises a case per row, so check the
                  manifest before uploading.
                </span>
              </li>
            </ul>
          </Panel>
        </div>
      </PageSection>

      {importing && (
        <ScanImportDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setImporting(false)}
          onImported={(imported) => {
            setSessionBatches((current) => [imported, ...current]);
            setActiveBatchId(imported.id);
            setRowFilter(imported.mismatchRows > 0 ? 'PROBLEM' : 'ALL');
            if (imported.mismatchRows > 0) {
              notifyError(
                undefined,
                `${imported.mismatchRows} of ${imported.totalRows} rows did not match. Each has raised an exception case.`,
              );
            } else {
              notifySuccess(`All ${imported.totalRows} scanned rows matched the manifest.`);
            }
          }}
        />
      )}
    </>
  );
};

export default ScanImportsPage;

import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
  Button,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Tabs,
  TabsList,
  TabsTrigger,
  type TableColumn,
} from '@rfdtech/components';
import { Download, Plus } from 'lucide-react';
import { FuelTransaction } from 'modules/fuel/api/dto';
import { FUEL_TRANSACTION_STATUSES, FuelTransactionStatus } from 'modules/fuel/api/enums';
import { fuelTransactionsApi } from 'modules/fuel/api/fuelApi';
import { CaptureTransactionDialog } from 'modules/fuel/dialogs/transactionDialogs';
import { DriverSelect, VehicleSelect } from 'modules/fleet/components/FleetReferenceSelect';
import { humanise } from 'modules/fleet/api/enums';
import { useClampPage, useRegisterPaging } from 'modules/fuel/components/useRegisterPaging';
import { formatMoney, formatQuantity, shortId } from 'modules/fuel/components/fuelFormat';
import { CellStack, ErrorBanner, FuelBadge, Panel, RegisterTable } from 'modules/fuel/components/fuelUi';
import { DateTimeField, SelectField } from 'modules/fuel/components/fuelFields';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { canCaptureFuel, canExportFuelReports } from 'modules/fleet/api/access';

/** `sourceSystem` is an exact match on the wire; these are the values this deployment writes. */
const SOURCE_FILTERS = [
  { value: 'MANUAL', label: 'Manual capture' },
  { value: 'CSV-IMPORT', label: 'CSV import' },
];

/**
 * The fuel transaction register.
 *
 * Every filter here goes to the service, and the table is server-paged with a real total. Source
 * and vendor used to be applied in the browser over a capped window - so "manual captures at this
 * site" really meant "manual captures among the first two hundred" - and are now query parameters.
 */
const FuelTransactionsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess, notifyError } = useNotifier();
  const [searchParams] = useSearchParams();

  const [siteCode, setSiteCode] = useState(defaultSite);
  // A dashboard card links here with a status already applied, so the register opens on what the
  // operator clicked rather than on everything.
  const [status, setStatus] = useState<FuelTransactionStatus | ''>(
    (searchParams.get('status') as FuelTransactionStatus | null) ?? '',
  );
  const [vehicleId, setVehicleId] = useState('');
  const [driverId, setDriverId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [source, setSource] = useState('');
  const [capturing, setCapturing] = useState(false);
  const [exporting, setExporting] = useState(false);

  const filterKey = `${siteCode}|${status}|${vehicleId}|${driverId}|${from}|${to}|${source}`;
  const paging = useRegisterPaging('fuel-transactions', filterKey);

  const query = useApiQuery(
    (signal) =>
      fuelTransactionsApi.search(
        {
          siteCode,
          status: status || undefined,
          vehicleId: vehicleId || undefined,
          driverId: driverId || undefined,
          sourceSystem: source || undefined,
          vendorReference: paging.search || undefined,
          from: from ? new Date(from).toISOString() : undefined,
          to: to ? new Date(to).toISOString() : undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [filterKey, paging.search, paging.page, paging.size],
  );

  useClampPage(paging.page, query.data?.totalPages, paging.setPage);

  /**
   * The service's own report, not the filtered table.
   *
   * `GET /reports/transactions.csv` takes a site and nothing else, so the download is described as
   * the site's report rather than "these results", which it is not.
   */
  const exportReport = async () => {
    setExporting(true);
    try {
      const fileName = await fuelTransactionsApi.downloadReport(siteCode);
      notifySuccess(
        `Downloaded ${fileName}.`,
        'The service exports the site’s most recent transactions, not the filtered view.',
      );
    } catch (error) {
      notifyError(error);
    } finally {
      setExporting(false);
    }
  };

  const columns = useMemo<TableColumn<FuelTransaction>[]>(
    () => [
      {
        id: 'transaction',
        header: 'Transaction',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.vendorReference}${row.stationReference ? ` · ${row.stationReference}` : ''}`}
            secondary={`${formatDateTime(row.occurredAt)} · ${row.fuelProduct}`}
          />
        ),
      },
      {
        id: 'quantity',
        header: 'Quantity',
        width: 120,
        align: 'right',
        cell: ({ row }) => formatQuantity(row.quantity, row.quantityUnit),
      },
      {
        id: 'cost',
        header: 'Total cost',
        width: 140,
        align: 'right',
        cell: ({ row }) => formatMoney(row.totalCost, row.currency),
      },
      {
        id: 'odometer',
        header: 'Odometer',
        width: 120,
        align: 'right',
        cell: ({ row }) => `${formatNumber(row.odometerReading)} km`,
      },
      {
        id: 'source',
        header: 'Source',
        width: 150,
        cell: ({ row }) => (
          <CellStack
            primary={row.sourceSystem}
            secondary={
              row.providerTransactionId ? `ref ${shortId(row.providerTransactionId)}` : 'no reference'
            }
          />
        ),
      },
      {
        id: 'receipt',
        header: 'Receipt',
        width: 100,
        align: 'center',
        cell: ({ row }) =>
          row.receiptEvidenceId ? (
            <FuelBadge value="ACTIVE" label="Held" tone="ready" />
          ) : (
            <FuelBadge value="MISSING" label="None" tone="caution" />
          ),
      },
      {
        id: 'status',
        header: 'Status',
        width: 130,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
    ],
    [],
  );

  const filtersApplied = Boolean(
    status || vehicleId || driverId || from || to || source || paging.search,
  );

  const resetFilters = () => {
    setStatus('');
    setVehicleId('');
    setDriverId('');
    setFrom('');
    setTo('');
    setSource('');
  };

  const totalPages = query.data?.totalPages ?? 0;
  const totalItems = query.data?.totalElements ?? 0;

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Fuel transactions</SectionTitle>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {/*
              Both controls are gated, and on different permissions, because they are different
              questions. A reporting viewer holds FUEL_TRANSACTION_READ and may open this register;
              they hold neither the export nor the capture grant, and were being offered both.
            */}
            {canExportFuelReports() && (
              <Button variant="outline" loading={exporting} onClick={exportReport}>
                <Download size={14} strokeWidth={1.5} aria-hidden />
                Export CSV
              </Button>
            )}
            {canCaptureFuel() && (
              <Button variant="primary" onClick={() => setCapturing(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden />
                Capture transaction
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <Panel
          title="Transactions"
          description="Every captured, imported and provider-ingested transaction at this site."
        >
          <Tabs
            variant="pill"
            value={status || 'all'}
            onValueChange={(value) => setStatus(value === 'all' ? '' : (value as FuelTransactionStatus))}
          >
            <TabsList>
              <TabsTrigger value="all">All</TabsTrigger>
              {FUEL_TRANSACTION_STATUSES.map((value) => (
                <TabsTrigger key={value} value={value}>
                  {humanise(value)}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          {query.error && <ErrorBanner error={query.error} onRetry={query.refetch} className="mt-4" />}

          <RegisterTable
            paramPrefix="fuel-transactions"
            columns={columns}
            rows={query.data?.content ?? []}
            rowKey={(row) => row.id}
            loading={query.loading}
            onRowClick={(row) => navigate(fuelPaths.transactionDetail(row.id))}
            empty={{
              title: 'No transactions yet',
              description: 'Captured, imported and provider-ingested transactions at this site appear here.',
              filteredTitle: 'No transaction matches these filters',
            }}
            filtersApplied={filtersApplied}
            totalPages={totalPages}
            totalItems={totalItems}
            pageSize={paging.size}
            searchPlaceholder="Search vendor or station"
            onResetFilters={resetFilters}
            filters={
              <>
                <VehicleSelect
                  siteCode={siteCode}
                  value={vehicleId}
                  onChange={setVehicleId}
                  allowEmpty
                  emptyLabel="Any vehicle"
                />
                <DriverSelect
                  siteCode={siteCode}
                  value={driverId}
                  onChange={setDriverId}
                  allowEmpty
                  emptyLabel="Any driver"
                />
                <DateTimeField label="From" value={from} onChange={setFrom} />
                <DateTimeField label="To" value={to} onChange={setTo} />
                <SelectField
                  label="Source"
                  value={source}
                  onChange={setSource}
                  options={SOURCE_FILTERS}
                  allowEmpty
                  emptyLabel="Any source"
                />
              </>
            }
          />
        </Panel>
      </PageSection>

      {capturing && (
        <CaptureTransactionDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCapturing(false)}
          onSaved={(transaction) => {
            notifySuccess(
              `Transaction captured against ${transaction.vendorReference}.`,
              'It is in the received state until reconciliation runs.',
            );
            query.refetch();
          }}
        />
      )}
    </>
  );
};

export default FuelTransactionsPage;

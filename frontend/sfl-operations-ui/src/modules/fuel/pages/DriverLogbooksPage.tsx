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
import { Plus } from 'lucide-react';
import { DriverLogbook } from 'modules/fuel/api/dto';
import {
  LOGBOOK_STATUSES,
  LOGBOOK_USE_CLASSIFICATIONS,
  LogbookStatus,
  LogbookUseClassification,
} from 'modules/fuel/api/enums';
import { driverLogbooksApi } from 'modules/fuel/api/fuelApi';
import { CreateLogbookDialog } from 'modules/fuel/dialogs/logbookDialogs';
import { DriverSelect, VehicleSelect } from 'modules/fleet/components/FleetReferenceSelect';
import { humanise } from 'modules/fleet/api/enums';
import { useClampPage, useRegisterPaging } from 'modules/fuel/components/useRegisterPaging';
import { CellStack, ErrorBanner, FuelBadge, Panel, RegisterTable } from 'modules/fuel/components/fuelUi';
import { DateField, EnumField } from 'modules/fuel/components/fuelFields';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate, formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fuelPaths } from 'shared/layout/navigation';
import { canCreateLogbooks } from 'modules/fleet/api/access';

/**
 * The driver logbook register.
 *
 * Site and status are the only two filters `GET /logbooks` accepts; use classification and the
 * route search are applied here over the returned window and are labelled as such.
 *
 * One service behaviour shapes what an operator sees and is worth knowing: a `FLEET_DRIVER`-only
 * actor gets **their own logbooks only** - `FuelApplicationService.logbooks` passes `ownOnly` from
 * `isDriverOnly(actor)`. A manager or logistics officer sees the site. The dashboard does not filter
 * this itself; it is simply what came back.
 */
const DriverLogbooksPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [searchParams] = useSearchParams();

  const [siteCode, setSiteCode] = useState(defaultSite);
  const [status, setStatus] = useState<LogbookStatus | ''>(
    (searchParams.get('status') as LogbookStatus | null) ?? '',
  );
  const [useClass, setUseClass] = useState<LogbookUseClassification | ''>('');
  const [driverId, setDriverId] = useState('');
  const [vehicleId, setVehicleId] = useState('');
  const [journeyFrom, setJourneyFrom] = useState('');
  const [journeyTo, setJourneyTo] = useState('');
  const [creating, setCreating] = useState(false);

  const filterKey = `${siteCode}|${status}|${useClass}|${driverId}|${vehicleId}|${journeyFrom}|${journeyTo}`;
  const paging = useRegisterPaging('fuel-logbooks', filterKey);

  const query = useApiQuery(
    (signal) =>
      driverLogbooksApi.search(
        {
          siteCode,
          status: status || undefined,
          useClassification: useClass || undefined,
          driverId: driverId || undefined,
          vehicleId: vehicleId || undefined,
          journeyFrom: journeyFrom || undefined,
          journeyTo: journeyTo || undefined,
          page: paging.page,
          size: paging.size,
        },
        signal,
      ),
    [filterKey, paging.page, paging.size],
  );

  useClampPage(paging.page, query.data?.totalPages, paging.setPage);

  const columns = useMemo<TableColumn<DriverLogbook>[]>(
    () => [
      {
        id: 'logbook',
        header: 'Logbook',
        minWidth: 240,
        cell: ({ row }) => (
          <CellStack
            primary={`${row.logbookNumber} · ${row.origin} → ${row.destination}`}
            secondary={row.purpose}
          />
        ),
      },
      {
        id: 'journey',
        header: 'Journey date',
        width: 130,
        cell: ({ row }) => formatDate(row.journeyDate),
      },
      {
        id: 'distance',
        header: 'Distance',
        width: 110,
        align: 'right',
        cell: ({ row }) =>
          row.endOdometer === null || row.endOdometer === undefined
            ? '-'
            : `${formatNumber(row.endOdometer - row.startOdometer)} km`,
      },
      {
        id: 'use',
        header: 'Use',
        width: 120,
        cell: ({ row }) => <FuelBadge value={row.useClassification} tone="neutral" />,
      },
      {
        id: 'submitted',
        header: 'Submitted',
        width: 160,
        cell: ({ row }) => formatDateTime(row.submittedAt),
      },
      {
        id: 'status',
        header: 'Status',
        width: 140,
        align: 'right',
        cell: ({ row }) => <FuelBadge value={row.status} />,
      },
    ],
    [],
  );

  const filtersApplied = Boolean(
    status || useClass || driverId || vehicleId || journeyFrom || journeyTo,
  );

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Driver logbooks</SectionTitle>
          <SectionActions>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            {/*
              A driver holds this - the logbook is their own journey record. A reporting viewer
              reads the register and creates nothing in it.
            */}
            {canCreateLogbooks() && (
              <Button variant="primary" onClick={() => setCreating(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden />
                Create logbook
              </Button>
            )}
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <Panel
          title="Logbooks"
          description="Journey records from draft through review to approval. Approved logbooks feed fuel reconciliation."
        >
          <Tabs
            variant="pill"
            value={status || 'all'}
            onValueChange={(value) => setStatus(value === 'all' ? '' : (value as LogbookStatus))}
          >
            <TabsList>
              <TabsTrigger value="all">All</TabsTrigger>
              {LOGBOOK_STATUSES.map((value) => (
                <TabsTrigger key={value} value={value}>
                  {humanise(value)}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          {query.error && <ErrorBanner error={query.error} onRetry={query.refetch} className="mt-4" />}

          <RegisterTable
            paramPrefix="fuel-logbooks"
            columns={columns}
            rows={query.data?.content ?? []}
            rowKey={(row) => row.id}
            loading={query.loading}
            onRowClick={(row) => navigate(fuelPaths.logbookDetail(row.id))}
            empty={{
              title: 'Nothing to review',
              description: 'Logbooks drivers create at this site appear here.',
              filteredTitle: 'No logbook matches these filters',
            }}
            filtersApplied={filtersApplied}
            totalPages={query.data?.totalPages ?? 0}
            totalItems={query.data?.totalElements ?? 0}
            pageSize={paging.size}
            onResetFilters={() => {
              setUseClass('');
              setDriverId('');
              setVehicleId('');
              setJourneyFrom('');
              setJourneyTo('');
            }}
            filters={
              <>
                <EnumField
                  label="Use classification"
                  value={useClass}
                  options={LOGBOOK_USE_CLASSIFICATIONS}
                  onChange={setUseClass}
                  allowEmpty
                  emptyLabel="Any use"
                />
                <DriverSelect
                  siteCode={siteCode}
                  value={driverId}
                  onChange={setDriverId}
                  allowEmpty
                  emptyLabel="Any driver"
                />
                <VehicleSelect
                  siteCode={siteCode}
                  value={vehicleId}
                  onChange={setVehicleId}
                  allowEmpty
                  emptyLabel="Any vehicle"
                />
                <DateField label="Journey from" value={journeyFrom} onChange={setJourneyFrom} />
                <DateField label="Journey to" value={journeyTo} onChange={setJourneyTo} />
              </>
            }
          />
        </Panel>
      </PageSection>

      {creating && (
        <CreateLogbookDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreating(false)}
          onSaved={(logbook) => {
            notifySuccess(
              `${logbook.logbookNumber} created as a draft.`,
              'Complete the journey and accept the declaration before submitting it.',
            );
            query.refetch();
            navigate(fuelPaths.logbookDetail(logbook.id));
          }}
        />
      )}
    </>
  );
};

export default DriverLogbooksPage;

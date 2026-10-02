import { useNavigate, useParams } from 'react-router';
import { Banner, Button, MetricCard } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import DataTable, { FacilitiesColumn as Column } from '../components/FacilitiesDataTable';
import KeyValueGrid from 'shared/components/KeyValueGrid';
import PageHeading from 'modules/emergency/components/PageHeading';
import TitledSection from '../components/TitledSection';
import StatusBadge from '../components/StatusBadge';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { FacilitiesPage, WorkOrder } from '../api/dto';
import { getAsset, getSchedule, searchWorkOrders } from '../api/facilitiesApi';
import {
  formatDate,
  formatDateTime,
  humaniseCode,
  orDash,
  workOrderStatusTone,
} from '../components/facilitiesFormat';

/**
 * One preventive schedule: what it covers, when it next fires, and what it has already raised.
 *
 * The generated history is the useful part. A schedule in isolation is a promise; the work orders it
 * has raised are whether the promise has been kept, and an inspection that has generated nothing in
 * a year is the thing somebody needs to notice.
 */
const ScheduleDetailPage = () => {
  const { scheduleId = '' } = useParams();
  const navigate = useNavigate();

  const schedule = useApiQuery((signal) => getSchedule(scheduleId, signal), [scheduleId]);

  const asset = useApiQuery(
    (signal) => (schedule.data ? getAsset(schedule.data.assetId, signal) : Promise.resolve(null)),
    [schedule.data?.assetId],
  );

  /** Everything this schedule has raised, newest first - the service already orders it that way. */
  const generated = useApiQuery(
    (signal) =>
      schedule.data
        ? searchWorkOrders(
            { siteCode: schedule.data.siteCode, assetId: schedule.data.assetId, limit: 50 },
            signal,
          )
        : Promise.resolve<FacilitiesPage<WorkOrder>>({
            items: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: 0,
          }),
    [schedule.data?.assetId, schedule.data?.siteCode],
  );

  const fromThisSchedule = (generated.data?.items ?? []).filter(
    (order) => order.scheduleId === scheduleId,
  );

  const columns: Column<WorkOrder>[] = [
    {
      key: 'workOrderNumber',
      header: 'Work order',
      width: 180,
      cell: (order) => <span className="font-medium text-gray-900">{order.workOrderNumber}</span>,
    },
    {
      key: 'createdAt',
      header: 'Raised',
      width: 190,
      cell: (order) => formatDateTime(order.metadata.createdAt),
    },
    {
      key: 'status',
      header: 'Status',
      width: 150,
      cell: (order) => (
        <StatusBadge value={humaniseCode(order.status)} tone={workOrderStatusTone(order.status)} />
      ),
    },
    {
      key: 'closedAt',
      header: 'Closed',
      cell: (order) =>
        order.closedAt ? formatDateTime(order.closedAt) : (
          <span className="text-theme-xs text-gray-500">Outstanding</span>
        ),
    },
  ];

  return (
    <DataState
      loading={schedule.loading}
      error={schedule.error}
      onRetry={schedule.refetch}
      minHeight={280}
    >
      {schedule.data && (
        <>
          <PageHeading
            title={schedule.data.name}
            subtitle={`${schedule.data.scheduleCode} · every ${schedule.data.intervalDays} days · ${schedule.data.siteCode}`}
            crumbs={[
              { label: 'Facilities', to: facilitiesPaths.dashboard },
              { label: 'Preventive schedules', to: facilitiesPaths.schedules },
              { label: schedule.data.scheduleCode },
            ]}
            actions={
              <Button
                variant="outline"
                onClick={() => navigate(facilitiesPaths.assetDetail(schedule.data!.assetId))}
              >
                Open the asset
              </Button>
            }
          />

          <div className="space-y-5">
            {schedule.data.dueForGeneration && (
              <Banner variant="info" heading="Due to raise work" subtext={`This schedule is inside its lead-time window and has not yet generated for ${formatDate(schedule.data.nextDueOn)}. The hourly job will raise it, or you can generate from the schedule register.`} />
            )}

            {schedule.data.lifecycleStatus !== 'ACTIVE' && (
              <Banner variant="warning" heading={`This schedule is ${humaniseCode(schedule.data.lifecycleStatus).toLowerCase()}`} subtext="It will not generate work while it is in this state." />
            )}

            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <MetricCard
                label="Next due"
                value={formatDate(schedule.data.nextDueOn)}
                description={`Raises on ${formatDate(schedule.data.generateOn)}`}
              />
              <MetricCard
                label="Interval"
                value={`${schedule.data.intervalDays} days`}
                description={`${schedule.data.leadTimeDays} days notice`}
              />
              <MetricCard
                label="Last generated"
                value={
                  schedule.data.lastGeneratedFor
                    ? formatDate(schedule.data.lastGeneratedFor)
                    : 'Never'
                }
                description={
                  schedule.data.lastGeneratedAt
                    ? formatDateTime(schedule.data.lastGeneratedAt)
                    : 'No work raised yet'
                }
              />
              <MetricCard
                label="Asset serviced"
                value={asset.data?.lastServicedOn ? formatDate(asset.data.lastServicedOn) : 'Never'}
                description={
                  asset.data?.serviceDueOn
                    ? `Next due ${formatDate(asset.data.serviceDueOn)}`
                    : 'Set when a preventive order closes'
                }
              />
            </div>

            <TitledSection title="Schedule">
              <KeyValueGrid
                items={[
                  { label: 'Code', value: schedule.data.scheduleCode },
                  { label: 'Type', value: humaniseCode(schedule.data.workOrderType) },
                  { label: 'Priority', value: humaniseCode(schedule.data.priority) },
                  { label: 'Asset', value: orDash(asset.data?.assetCode) },
                  {
                    label: 'Lifecycle',
                    value: (
                      <StatusBadge
                        value={humaniseCode(schedule.data.lifecycleStatus)}
                        tone="neutral"
                      />
                    ),
                  },
                  { label: 'Created by', value: schedule.data.metadata.createdBy },
                ]}
              />
              {schedule.data.description && (
                <p className="mt-3 whitespace-pre-line text-theme-sm text-gray-800">
                  {schedule.data.description}
                </p>
              )}
            </TitledSection>

            <TitledSection
              title="What it has raised"
              subtitle="Every work order generated from this schedule"
              flush
            >
              <DataState
                loading={generated.loading}
                error={generated.error}
                empty={fromThisSchedule.length === 0}
                emptyTitle="Nothing raised yet"
                emptyHint="Work appears here once the schedule reaches its lead-time window."
                onRetry={generated.refetch}
              >
                <DataTable
                  rows={fromThisSchedule}
                  columns={columns}
                  getRowId={(order) => order.id}
                  dense
                  onRowClick={(order) => navigate(facilitiesPaths.workOrderDetail(order.id))}
                />
              </DataState>
            </TitledSection>
          </div>
        </>
      )}
    </DataState>
  );
};

export default ScheduleDetailPage;

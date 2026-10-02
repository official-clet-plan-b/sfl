import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { DashboardDrilldownRow, TripResponse, WorkflowItemResponse } from 'modules/fleet/api/dto';
import { canManageTrips } from 'modules/fleet/api/access';
import { humanise } from 'modules/fleet/api/enums';
import {
  DRILLDOWN_INDICATORS,
  DrilldownIndicator,
  dashboardApi,
  driversApi,
  tripsApi,
  vehiclesApi,
  workflowApi,
} from 'modules/fleet/api/fleetApi';
import { CreateTripDialog } from 'modules/fleet/dialogs/tripDialogs';
import ActivityChart, { ActivityPoint } from 'modules/fleet/charts/ActivityChart';
import DrilldownDrawer from 'modules/fleet/components/DrilldownDrawer';
import Panel from 'modules/fleet/components/Panel';
import FleetTable, { CellStack, FleetColumn } from 'modules/fleet/components/FleetTable';
import StatusBadge, { tabLabel } from 'modules/fleet/components/StatusBadge';

import {
  Badge,
  Banner,
  Button,
  Card,
  Dropdown,
  HeroBanner,
  MetricCard,
  MetricCards,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@rfdtech/components';
import { sflActor } from 'shared/api/config';
import DataState from 'shared/components/DataState';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import { defaultSite, sflSites } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';

dayjs.extend(relativeTime);

const ACTIVITY_DAYS = 14;

/** Buckets records into one entry per day so the chart window is fixed even where data is sparse. */
const bucketByDay = (
  trips: TripResponse[],
  workflow: WorkflowItemResponse[],
  days: number,
): ActivityPoint[] => {
  const start = dayjs()
    .startOf('day')
    .subtract(days - 1, 'day');
  const buckets = new Map<string, ActivityPoint>();

  for (let offset = 0; offset < days; offset += 1) {
    const day = start.add(offset, 'day');
    buckets.set(day.format('YYYY-MM-DD'), {
      label: day.format('D MMM'),
      trips: 0,
      workflow: 0,
    });
  }

  const add = (iso: string | null | undefined, key: 'trips' | 'workflow') => {
    if (!iso) {
      return;
    }
    const bucket = buckets.get(dayjs(iso).format('YYYY-MM-DD'));
    if (bucket) {
      bucket[key] += 1;
    }
  };

  trips.forEach((trip) => add(trip.plannedStart, 'trips'));
  workflow.forEach((item) => add(item.createdAt, 'workflow'));

  return [...buckets.values()];
};

interface AvailabilitySlice {
  key: string;
  label: string;
  detail: string;
  value: number;
  /** Tailwind background for this slice's share of the bar. */
  bar: string;
}

/** The fleet split into usable, committed and blocked, as one proportional bar over a legend. */
const AvailabilityBar = ({
  slices,
  onSelect,
}: {
  slices: AvailabilitySlice[];
  onSelect: (key: string) => void;
}) => {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0);
  return (
    <div>
      <div
        className="flex h-2 w-full overflow-hidden rounded-full bg-gray-100"
        role="img"
        aria-label={slices.map((slice) => `${slice.value} ${slice.label.toLowerCase()}`).join(', ')}
      >
        {slices.map((slice) =>
          slice.value > 0 ? (
            <span
              key={slice.key}
              className={slice.bar}
              style={{ width: `${(slice.value / total) * 100}%` }}
            />
          ) : null,
        )}
      </div>
      <ul className="mt-4 divide-y divide-gray-200">
        {slices.map((slice) => (
          <li key={slice.key}>
            <button
              type="button"
              onClick={() => onSelect(slice.key)}
              className="flex w-full items-center justify-between gap-3 py-3 text-left"
            >
              <span className="min-w-0">
                <span className="block font-semibold">{slice.label}</span>
                <span className="block text-theme-xs opacity-70">{slice.detail}</span>
              </span>
              <span className="flex items-center gap-2 font-semibold">
                {slice.value}
                <Icon name="chevron-right" size={14} aria-hidden="true" />
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
};

interface ExceptionEntry {
  key: string;
  label: string;
  value: number;
  blocking?: boolean;
  onSelect: () => void;
}

/** One line per exception class, with its count; a class at zero says it is clear. */
const ExceptionList = ({ entries }: { entries: ExceptionEntry[] }) => (
  <ul className="divide-y divide-gray-200">
    {entries.map((entry) => (
      <li key={entry.key}>
        <button
          type="button"
          onClick={entry.onSelect}
          className="flex w-full items-center justify-between gap-3 py-3 text-left"
        >
          <span className="min-w-0">
            <span className="block font-semibold">{entry.label}</span>
            {entry.value === 0 ? (
              <Badge variant="success" size="sm">
                Clear
              </Badge>
            ) : (
              <Badge variant={entry.blocking ? 'error' : 'default'} size="sm">
                {entry.blocking ? 'Blocking' : 'Needs attention'}
              </Badge>
            )}
          </span>
          <span className="flex items-center gap-2 font-semibold">
            {entry.value}
            <Icon name="chevron-right" size={14} aria-hidden="true" />
          </span>
        </button>
      </li>
    ))}
  </ul>
);

/**
 * The Fleet operations workspace.
 *
 * Indicators come from the service's own dashboard snapshot; the trend is bucketed from the trip and
 * workflow records themselves, because the service exposes no time-series endpoint. Nothing on this
 * page is synthetic - where there is no history to show, no trend is drawn.
 */
const FleetDashboardPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  /*
    The site the summary covers. Empty is "all sites", which the services read as the actor's whole
    site scope - so a manager over two sites can see both, and one over a single site sees theirs
    either way. It opens on the actor's own site, the same default every register uses.
  */
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [planOpen, setPlanOpen] = useState(false);
  const operatingMode = '';
  const [drilldown, setDrilldown] = useState<DrilldownIndicator | null>(null);
  const [exceptionsTab, setExceptionsTab] = useState<'escalated' | 'compliance'>('escalated');

  const windowStart = useMemo(
    () =>
      dayjs()
        .startOf('day')
        .subtract(ACTIVITY_DAYS - 1, 'day')
        .toISOString(),
    [],
  );
  const windowEnd = useMemo(() => dayjs().endOf('day').toISOString(), []);

  const snapshot = useApiQuery(
    (signal) =>
      dashboardApi.operations(
        {
          siteCode: siteCode || undefined,
          operatingMode: operatingMode || undefined,
        },
        signal,
      ),
    [siteCode, operatingMode],
  );

  const recentTrips = useApiQuery(
    (signal) =>
      tripsApi.search(
        {
          siteCode: siteCode || undefined,
          operatingMode: operatingMode || undefined,
          from: windowStart,
          to: windowEnd,
          size: 200,
        },
        signal,
      ),
    [siteCode, operatingMode, windowStart, windowEnd],
  );

  const recentWorkflow = useApiQuery(
    (signal) =>
      workflowApi.search(
        {
          siteCode: siteCode || undefined,
          from: windowStart,
          to: windowEnd,
          size: 200,
        },
        signal,
      ),
    [siteCode, windowStart, windowEnd],
  );

  const activeTrips = useApiQuery(
    (signal) =>
      tripsApi.search({ siteCode: siteCode || undefined, status: 'IN_PROGRESS', size: 6 }, signal),
    [siteCode],
  );

  /**
   * Names for the active-trips table. A trip carries a vehicle and a driver id and nothing readable,
   * so the site's vehicles and drivers are fetched once and indexed rather than once per row.
   */
  const vehicleIndex = useApiQuery(
    (signal) =>
      vehiclesApi
        .search({ siteCode: siteCode || undefined, size: 200 }, signal)
        .then((page) => new Map(page.content.map((vehicle) => [vehicle.id, vehicle]))),
    [siteCode],
  );
  const driverIndex = useApiQuery(
    (signal) =>
      driversApi
        .search({ siteCode: siteCode || undefined, size: 200 }, signal)
        .then((page) => new Map(page.content.map((driver) => [driver.id, driver]))),
    [siteCode],
  );

  const escalated = useApiQuery(
    (signal) =>
      workflowApi.search({ siteCode: siteCode || undefined, escalatedOnly: true, size: 6 }, signal),
    [siteCode],
  );

  /**
   * The documents behind the expired-compliance indicator.
   *
   * There is no compliance-document search endpoint - documents are only readable per vehicle or
   * through this indicator drilldown - so the panel lists what the service itself counts, and never
   * an expiry horizon the service was not asked about.
   */
  const expiredCompliance = useApiQuery(
    (signal) =>
      dashboardApi.drilldown(
        DRILLDOWN_INDICATORS.EXPIRED_COMPLIANCE,
        { siteCode: siteCode || undefined },
        signal,
      ),
    [siteCode],
  );

  const indicators = snapshot.data?.indicators;

  const activity = useMemo(
    () =>
      bucketByDay(
        recentTrips.data?.content ?? [],
        recentWorkflow.data?.content ?? [],
        ACTIVITY_DAYS,
      ),
    [recentTrips.data, recentWorkflow.data],
  );

  const tripsPlanned = useMemo(
    () => activity.reduce((total, point) => total + point.trips, 0),
    [activity],
  );
  const exceptionsRaised = useMemo(
    () => activity.reduce((total, point) => total + point.workflow, 0),
    [activity],
  );

  const availability = useMemo<AvailabilitySlice[]>(() => {
    if (!indicators || !snapshot.data) {
      return [];
    }
    const total = snapshot.data.reconciliation.vehicles;
    const available = indicators.vehiclesAvailable;
    const blocked = indicators.readinessBlockers;
    const committed = Math.max(total - available - blocked, 0);
    const share = (value: number) => (total > 0 ? `${Math.round((value / total) * 100)}%` : '0%');
    return [
      {
        key: 'available',
        label: 'Available',
        detail: `${share(available)} of the fleet`,
        value: available,
        bar: 'bg-brand-800',
      },
      {
        key: 'committed',
        label: 'Committed to a trip',
        detail: `${share(committed)}, back when the trip closes`,
        value: committed,
        bar: 'bg-teal-600',
      },
      {
        key: 'blocked',
        label: 'Readiness blocked',
        detail: `${share(blocked)}, cleared by compliance or service`,
        value: blocked,
        bar: 'bg-error-500',
      },
    ];
  }, [indicators, snapshot.data]);

  const selectAvailability = (key: string) => {
    if (key === 'blocked') {
      setDrilldown('READINESS_BLOCKERS');
    } else {
      navigate(key === 'available' ? fleetPaths.vehicles : fleetPaths.trips);
    }
  };

  const exceptionEntries = useMemo<ExceptionEntry[]>(
    () =>
      indicators
        ? [
            {
              key: 'expired',
              label: 'Expired compliance',
              value: indicators.expiredCompliance,
              blocking: true,
              onSelect: () => setDrilldown('EXPIRED_COMPLIANCE'),
            },
            {
              key: 'service',
              label: 'Service due or overdue',
              value: indicators.serviceDue,
              blocking: true,
              onSelect: () => setDrilldown('SERVICE_DUE'),
            },
            {
              key: 'conflicts',
              label: 'Assignment conflicts',
              value: indicators.assignmentConflicts,
              onSelect: () => setDrilldown('ASSIGNMENT_CONFLICTS'),
            },
            {
              key: 'escalated',
              label: 'Escalated workflow',
              value: indicators.escalatedWorkflowItems,
              onSelect: () => navigate(fleetPaths.workflow),
            },
            {
              key: 'deadLetters',
              label: 'Integration dead letters',
              value: indicators.integrationDeadLetters,
              onSelect: () => navigate(fleetPaths.integrations),
            },
          ]
        : [],
    [indicators, navigate],
  );

  const tripColumns = useMemo<FleetColumn<TripResponse>[]>(() => {
    const vehicles = vehicleIndex.data;
    const drivers = driverIndex.data;
    return [
      {
        key: 'trip',
        header: 'Trip',
        width: 240,
        cell: (row) => (
          <CellStack primary={row.tripNumber} secondary={`${row.origin} → ${row.destination}`} />
        ),
      },
      {
        key: 'assignment',
        header: 'Vehicle and driver',
        width: 200,
        cell: (row) => (
          <CellStack
            primary={
              (row.vehicleId && vehicles?.get(row.vehicleId)?.registrationNumber) || 'Not assigned'
            }
            secondary={(row.driverId && drivers?.get(row.driverId)?.displayName) || '-'}
          />
        ),
      },
      {
        key: 'plannedEnd',
        header: 'Due back',
        width: 160,
        cell: (row) => {
          const overdue = dayjs(row.plannedEnd).isBefore(dayjs());
          return (
            <CellStack
              primary={dayjs(row.plannedEnd).format('HH:mm')}
              secondary={
                overdue
                  ? `Overdue by ${dayjs(row.plannedEnd).fromNow(true)}`
                  : dayjs(row.plannedEnd).isSame(dayjs(), 'day')
                    ? 'Today'
                    : formatDateTime(row.plannedEnd)
              }
            />
          );
        },
      },
      {
        key: 'status',
        header: 'Status',
        width: 130,
        cell: (row) =>
          dayjs(row.plannedEnd).isBefore(dayjs()) ? (
            <StatusBadge value={row.status} label="Late return" tone="blocked" />
          ) : (
            <StatusBadge value={row.status} label="On route" tone="active" />
          ),
      },
    ];
  }, [vehicleIndex.data, driverIndex.data]);

  const workflowColumns = useMemo<FleetColumn<WorkflowItemResponse>[]>(
    () => [
      {
        key: 'item',
        header: 'Item',
        width: 280,
        cell: (row) => (
          <CellStack
            primary={row.title}
            secondary={`${row.workflowNumber}${row.relatedRecordType ? `, ${row.relatedRecordType}` : ''}`}
          />
        ),
      },
      {
        key: 'waitingOn',
        header: 'Waiting on',
        width: 160,
        cell: (row) => row.assignee ?? 'Unassigned',
      },
      {
        key: 'openFor',
        header: 'Open for',
        width: 120,
        cell: (row) => dayjs(row.createdAt).fromNow(true),
      },
      {
        key: 'sla',
        header: 'SLA',
        width: 200,
        cell: (row) =>
          row.slaBreached ? (
            <Badge variant="error">
              Breached by {dayjs().diff(dayjs(row.slaDueAt), 'hour')} hours
            </Badge>
          ) : (
            <span>{formatDateTime(row.slaDueAt)}</span>
          ),
      },
      {
        key: 'open',
        header: 'Open',
        width: 90,
        cell: (row) => (
          <Button
            size="sm"
            variant="outline"
            onClick={(event) => {
              event.stopPropagation();
              navigate(fleetPaths.workflowDetail(row.id));
            }}
          >
            Open
          </Button>
        ),
      },
    ],
    [navigate],
  );

  const complianceColumns = useMemo<FleetColumn<DashboardDrilldownRow>[]>(
    () => [
      {
        key: 'record',
        header: 'Document',
        width: 260,
        cell: (row) => <CellStack primary={row.summary} secondary={row.resourceType} />,
      },
      {
        key: 'site',
        header: 'Site',
        width: 110,
        cell: (row) => row.siteCode,
      },
    ],
    [],
  );

  const refreshAll = () => {
    snapshot.refetch();
    recentTrips.refetch();
    recentWorkflow.refetch();
    activeTrips.refetch();
    vehicleIndex.refetch();
    driverIndex.refetch();
    escalated.refetch();
    expiredCompliance.refetch();
  };

  const role = sflActor.roles.split(',')[0]?.trim();
  const noActiveTrips = (activeTrips.data?.content.length ?? 0) === 0 && !activeTrips.initialising;
  const metricsLoading = snapshot.initialising;
  const siteOptions = sflSites().map((site) => ({ value: site, label: `Site: ${site}` }));

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Fleet dashboard</SectionTitle>
          <SectionActions>
            <Dropdown
              aria-label="Site"
              value={siteCode || null}
              onValueChange={(value) => setSiteCode(value ?? '')}
              options={siteOptions}
              placeholder="All sites"
              clearable
            />
            <Button variant="outline" aria-label="Refresh" title="Refresh" onClick={refreshAll}>
              <Icon name="refresh" size={14} aria-hidden="true" />
            </Button>
            {/* Planning is FLEET_TRIP_MANAGE: a driver reads the dashboard and plans nothing. */}
            {canManageTrips() && (
              <Button variant="primary" onClick={() => setPlanOpen(true)}>
                <Icon name="plus" size={14} aria-hidden="true" />
                <span className="whitespace-nowrap">Plan a trip</span>
              </Button>
            )}
          </SectionActions>
        </SectionHeader>

        <HeroBanner name={sflActor.displayName} role={role ? humanise(role) : undefined} />
      </PageSection>

      <DataState loading={false} error={snapshot.error} onRetry={snapshot.refetch} minHeight={360}>
        {snapshot.data?.warnings.length ? (
          <PageSection>
            <Banner
              variant={snapshot.data.stale ? 'warning' : 'info'}
              heading={snapshot.data.warnings[0]}
              subtext={
                snapshot.data.warnings.length > 1
                  ? snapshot.data.warnings.slice(1).join(' ')
                  : undefined
              }
            />
          </PageSection>
        ) : null}

        {/*
         * Four measures in the grey group the frame puts them in. The exception classes
         * (compliance, service, conflicts, dead letters) live in the exceptions list beside the
         * chart, where each states whether it blocks work, so a clean fleet reads as four quiet
         * cards and a list of "Clear" rather than eight cards competing for the eye.
         */}
        <PageSection>
          <Card className="bg-[var(--clet-surface-subtle)]">
            <div className="rounded-2xl border border-gray-200 bg-gray-50 p-3 sm:p-4">
            <MetricCards>
              <MetricCard
                variant="soft"
                loading={metricsLoading}
                label="Vehicles available"
                value={indicators?.vehiclesAvailable ?? 0}
                description={`of ${snapshot.data?.reconciliation.vehicles ?? 0} in the register`}
              />
              <MetricCard
                variant="soft"
                loading={metricsLoading}
                label="Readiness blocked"
                value={indicators?.readinessBlockers ?? 0}
                description="Cannot be assigned"
              />
              <MetricCard
                variant="soft"
                loading={metricsLoading || recentTrips.initialising}
                label="Trips planned"
                value={tripsPlanned}
                description={`Last ${ACTIVITY_DAYS} days`}
              />
              <MetricCard
                variant="soft"
                loading={metricsLoading || recentWorkflow.initialising}
                label="Exceptions raised"
                value={exceptionsRaised}
                description={`${indicators?.openWorkflowItems ?? 0} still open`}
              />
            </MetricCards>
            </div>
          </Card>
        </PageSection>

        <PageSection>
          <div className="grid gap-[var(--clet-app-layout-body-gap)] xl:grid-cols-3">
            <Panel
              section={false}
              className="xl:col-span-2"
              title="Operational activity"
              description={`Trips planned and exceptions raised each day, last ${ACTIVITY_DAYS} days`}
            >
              <DataState
                loading={recentTrips.initialising || recentWorkflow.initialising}
                error={recentTrips.error ?? recentWorkflow.error}
                onRetry={() => {
                  recentTrips.refetch();
                  recentWorkflow.refetch();
                }}
                minHeight={280}
              >
                <ActivityChart points={activity} />
              </DataState>
            </Panel>

            <Panel
              section={false}
              title="Fleet availability"
              description={`${snapshot.data?.reconciliation.vehicles ?? 0} vehicles ${
                siteCode ? `at ${siteCode}` : 'in scope'
              }`}
            >
              <AvailabilityBar slices={availability} onSelect={selectAvailability} />
            </Panel>
          </div>
        </PageSection>

        <PageSection>
          <div className="grid gap-[var(--clet-app-layout-body-gap)] xl:grid-cols-3">
            <Panel
              section={false}
              className="xl:col-span-2"
              title="Active trips"
              description="Trips that have started and are not yet closed"
              actions={
                <Button variant="outline" onClick={() => navigate(fleetPaths.trips)}>
                  View all trips
                </Button>
              }
            >
              {/*
                An empty road is the normal state outside working hours, and this panel used to
                spend a third of the dashboard saying so. When nothing is in progress it now
                shows the week's movements instead - the same chart the activity panel uses,
                over the trips already fetched - so the space answers "what has been happening"
                rather than repeating "nothing, right now".
              */}
              {noActiveTrips ? (
                <div>
                  <p className="mb-3 text-theme-sm opacity-70">
                    Nothing is on the road at the moment. Movements over the last seven days:
                  </p>
                  <ActivityChart points={activity} height={200} />
                </div>
              ) : (
                <FleetTable
                  paramPrefix="active-trips"
                  rows={activeTrips.data?.content ?? []}
                  columns={tripColumns}
                  getRowId={(row) => row.id}
                  loading={activeTrips.initialising}
                  error={activeTrips.error}
                  onRetry={activeTrips.refetch}
                  onRowClick={(row) => navigate(fleetPaths.tripDetail(row.id))}
                  caption="Trips in progress in the current site scope, with their status."
                  emptyTitle="Nothing on the road"
                />
              )}
            </Panel>

            <Panel section={false} title="Open exceptions" description="What needs attention today">
              <ExceptionList entries={exceptionEntries} />
            </Panel>
          </div>
        </PageSection>

        <Panel
          title={exceptionsTab === 'escalated' ? 'Escalated workflow' : 'Compliance exceptions'}
          description={
            exceptionsTab === 'escalated'
              ? 'Past its SLA or escalated by hand'
              : 'Records behind the expired-compliance indicator'
          }
          actions={
            <Button
              variant="outline"
              onClick={() =>
                navigate(
                  exceptionsTab === 'escalated' ? fleetPaths.workflow : fleetPaths.compliance,
                )
              }
            >
              {exceptionsTab === 'escalated' ? 'View queue' : 'Compliance'}
            </Button>
          }
        >
          <Tabs
            variant="pill"
            value={exceptionsTab}
            onValueChange={(value) => setExceptionsTab(value as 'escalated' | 'compliance')}
          >
            <TabsList>
              <TabsTrigger value="escalated">
                {tabLabel('Escalated workflow', escalated.data?.content.length)}
              </TabsTrigger>
              <TabsTrigger value="compliance">
                {tabLabel('Compliance exceptions', expiredCompliance.data?.length)}
              </TabsTrigger>
            </TabsList>
            <TabsContent value="escalated">
              <FleetTable
                paramPrefix="escalated"
                rows={escalated.data?.content ?? []}
                columns={workflowColumns}
                getRowId={(row) => row.id}
                loading={escalated.initialising}
                error={escalated.error}
                onRetry={escalated.refetch}
                onRowClick={(row) => navigate(fleetPaths.workflowDetail(row.id))}
                caption="Escalated workflow items in the current site scope, with their SLA standing."
                emptyTitle="Nothing escalated"
                emptyDescription="No workflow item has breached its SLA in this scope."
              />
            </TabsContent>
            <TabsContent value="compliance">
              <FleetTable
                paramPrefix="expired"
                rows={expiredCompliance.data ?? []}
                columns={complianceColumns}
                getRowId={(row) => `${row.resourceType}-${row.resourceId}`}
                loading={expiredCompliance.initialising}
                error={expiredCompliance.error}
                onRetry={expiredCompliance.refetch}
                caption="Compliance documents past their expiry date, with the site that holds them."
                emptyTitle="No expired documents"
                emptyDescription="Nothing in this scope is past its expiry date."
              />
            </TabsContent>
          </Tabs>
        </Panel>
      </DataState>

      {/* Mounted only while open, so the dialog opens on the chosen site and never reopens holding a
          half-typed trip from a previous attempt. */}
      {planOpen && (
        <CreateTripDialog
          open
          defaultSiteCode={siteCode || defaultSite}
          onClose={() => setPlanOpen(false)}
          onSaved={() => {
            notifySuccess('Trip created.');
            refreshAll();
          }}
        />
      )}

      <DrilldownDrawer
        indicator={drilldown}
        siteCode={siteCode || undefined}
        onClose={() => setDrilldown(null)}
      />
    </>
  );
};

export default FleetDashboardPage;

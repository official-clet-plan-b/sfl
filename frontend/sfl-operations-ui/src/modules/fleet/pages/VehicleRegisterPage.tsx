import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button, Input, PageSection } from '@rfdtech/components';
import { VehicleResponse } from 'modules/fleet/api/dto';
import {
  VEHICLE_CATEGORIES,
  VEHICLE_LIFECYCLE_STATUSES,
  VEHICLE_SERVICE_STATUSES,
  VehicleAvailabilityStatus,
  VehicleCategory,
  VehicleLifecycleStatus,
  VehicleServiceStatus,
  humanise,
} from 'modules/fleet/api/enums';
import { vehiclesApi } from 'modules/fleet/api/fleetApi';
import FleetTable, {
  CellStack,
  FilterDropdown,
  FleetColumn,
  useRegisterState,
} from 'modules/fleet/components/FleetTable';
import RegisterHeader from 'modules/fleet/components/RegisterHeader';
import StatusBadge from 'modules/fleet/components/StatusBadge';
import { RegisterVehicleDialog } from 'modules/fleet/dialogs/vehicleDialogs';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import { defaultSite } from 'shared/components/SiteSelect';
import { formatOdometer } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';
import { canManageVehicles } from '../api/access';

/** The one-click views of the register; each is an availability status. */
const VIEWS: { value: string; label: string; availability?: VehicleAvailabilityStatus }[] = [
  { value: 'all', label: 'All' },
  { value: 'available', label: 'Available', availability: 'AVAILABLE' },
  { value: 'trip', label: 'On a trip', availability: 'IN_USE' },
  { value: 'withheld', label: 'Withheld', availability: 'UNAVAILABLE' },
];

/**
 * The vehicle register.
 *
 * Filtering, sorting and paging all run server-side - the service owns site scoping, so a
 * client-side filter over one page would quietly show the wrong denominator.
 */
const VehicleRegisterPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [registerOpen, setRegisterOpen] = useState(false);

  // The register's state lives in the URL, so the filters that explain a short list are on the
  // page's address as well as in the filter control. Every change returns to the first page -
  // leaving the page index behind asks the server for a page the narrowed result set no longer has.
  const state = useRegisterState('vehicles');
  const { filters, setFilter } = state;
  const view = VIEWS.find((entry) => entry.value === filters.view) ?? VIEWS[0];
  const status = (filters.status ?? '') as VehicleLifecycleStatus | '';
  const serviceStatus = (filters.service ?? '') as VehicleServiceStatus | '';
  const category = (filters.category ?? '') as VehicleCategory | '';
  const responsibleUnit = filters.unit ?? '';

  const query = useApiQuery(
    (signal) =>
      vehiclesApi.search(
        {
          siteCode: state.site || undefined,
          registrationNumber: state.search || undefined,
          status: status || undefined,
          serviceStatus: serviceStatus || undefined,
          availability: view.availability,
          category: category || undefined,
          responsibleUnit: responsibleUnit || undefined,
          page: state.apiPage,
          size: state.pageSize,
        },
        signal,
      ),
    [
      state.site,
      state.search,
      status,
      serviceStatus,
      view.value,
      category,
      responsibleUnit,
      state.apiPage,
      state.pageSize,
    ],
  );

  /** How many vehicles each view holds, so the tab says what is behind it before it is opened. */
  const counts = useApiQuery(
    async (signal) => {
      const totals = await Promise.all(
        VIEWS.map((entry) =>
          vehiclesApi
            .search(
              { siteCode: state.site || undefined, availability: entry.availability, size: 1 },
              signal,
            )
            .then((page) => [entry.value, page.totalElements] as const),
        ),
      );
      return Object.fromEntries(totals);
    },
    [state.site],
  );

  const columns = useMemo<FleetColumn<VehicleResponse>[]>(
    () => [
      {
        key: 'registrationNumber',
        header: 'Registration',
        width: 180,
        cell: (row) => (
          <CellStack
            primary={row.registrationNumber}
            secondary={`${row.make} ${row.model} · ${row.manufactureYear}`}
          />
        ),
      },
      {
        key: 'category',
        header: 'Category',
        width: 150,
        cell: (row) => humanise(row.category),
      },
      {
        key: 'lifecycleStatus',
        header: 'Lifecycle',
        width: 130,
        cell: (row) => <StatusBadge value={row.lifecycleStatus} />,
      },
      {
        key: 'serviceStatus',
        header: 'Service',
        width: 140,
        cell: (row) => <StatusBadge value={row.serviceStatus} />,
      },
      {
        key: 'availabilityStatus',
        header: 'Availability',
        width: 130,
        cell: (row) => <StatusBadge value={row.availabilityStatus} />,
      },
      {
        key: 'odometerValue',
        header: 'Odometer',
        width: 130,
        align: 'right',
        cell: (row) => formatOdometer(row.odometerValue, row.odometerUnit),
      },
      {
        key: 'responsibleUnit',
        header: 'Responsible unit',
        width: 180,
        cell: (row) => row.responsibleUnit,
      },
      {
        key: 'emergencyOnly',
        header: 'Restriction',
        width: 130,
        cell: (row) =>
          row.emergencyOnly ? (
            <StatusBadge value="EMERGENCY_ONLY" label="Emergency only" tone="accent" />
          ) : (
            <span className="text-theme-xs opacity-70">None</span>
          ),
      },
    ],
    [],
  );

  return (
    <>
      <RegisterHeader
        title="Vehicle register"
        siteCode={state.site}
        onSiteChange={state.setSite}
        actions={
          <>
            <Button
              variant="outline"
              aria-label="Refresh"
              title="Refresh"
              onClick={() => {
                query.refetch();
                counts.refetch();
              }}
            >
              <Icon name="refresh" size={14} aria-hidden="true" />
            </Button>
            {/* Hidden, not disabled: a driver will never hold FLEET_VEHICLE_MANAGE, and a
                permanently greyed control is a question they cannot answer. */}
            {canManageVehicles() && (
              <Button variant="primary" onClick={() => setRegisterOpen(true)}>
                <Icon name="plus" size={14} aria-hidden="true" />
                Register a vehicle
              </Button>
            )}
          </>
        }
      />

      <PageSection>
        <FleetTable
          paramPrefix="vehicles"
          rows={query.data?.content ?? []}
          columns={columns}
          getRowId={(row) => row.id}
          loading={query.loading}
          error={query.error}
          onRetry={query.refetch}
          onRowClick={(row) => navigate(fleetPaths.vehicleDetail(row.id))}
          totalElements={query.data?.totalElements ?? 0}
          pageSize={state.pageSize}
          searchPlaceholder="Search registration number"
          caption="Vehicle register"
          tabs={VIEWS.map((entry) => ({
            value: entry.value,
            label: entry.label,
            count: counts.data?.[entry.value],
          }))}
          tab={view.value}
          onTabChange={(value) => setFilter('view', value === 'all' ? '' : value)}
          filters={
            <>
              <FilterDropdown
                name="status"
                label="Lifecycle"
                value={status}
                onChange={(value) => setFilter('status', value)}
                options={VEHICLE_LIFECYCLE_STATUSES.map((value) => ({
                  value,
                  label: humanise(value),
                }))}
              />
              <FilterDropdown
                name="service"
                label="Service status"
                value={serviceStatus}
                onChange={(value) => setFilter('service', value)}
                options={VEHICLE_SERVICE_STATUSES.map((value) => ({
                  value,
                  label: humanise(value),
                }))}
              />
              <FilterDropdown
                name="category"
                label="Type"
                value={category}
                onChange={(value) => setFilter('category', value)}
                options={VEHICLE_CATEGORIES.map((value) => ({ value, label: humanise(value) }))}
              />
              <Input
                name="unit"
                aria-label="Responsible unit"
                placeholder="Responsible unit"
                defaultValue={responsibleUnit}
              />
            </>
          }
          emptyTitle="No vehicles match these filters"
          emptyDescription="Adjust the filters, or register the first vehicle for this site."
        />
      </PageSection>

      {/* Mounted only while open, so the dialog picks up the current site filter as its default
          and cannot reopen holding a half-typed registration from a previous attempt. */}
      {registerOpen && (
        <RegisterVehicleDialog
          open
          defaultSiteCode={state.site || defaultSite}
          onClose={() => setRegisterOpen(false)}
          onSaved={() => {
            notifySuccess('Vehicle registered.');
            query.refetch();
            counts.refetch();
          }}
        />
      )}
    </>
  );
};

export default VehicleRegisterPage;

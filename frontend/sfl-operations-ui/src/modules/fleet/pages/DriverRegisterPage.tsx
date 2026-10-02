import { useMemo, useState } from 'react';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router';
import { Button, Input, PageSection } from '@rfdtech/components';
import { DriverResponse } from 'modules/fleet/api/dto';
import {
  DRIVER_LIFECYCLE_STATUSES,
  DriverEligibilityStatus,
  DriverLifecycleStatus,
  humanise,
} from 'modules/fleet/api/enums';
import { describeDriverEligibility } from 'modules/fleet/api/driverEligibility';
import { driversApi } from 'modules/fleet/api/fleetApi';
import FleetTable, {
  CellStack,
  FilterDropdown,
  FilterField,
  FleetColumn,
  useRegisterState,
} from 'modules/fleet/components/FleetTable';
import RegisterHeader from 'modules/fleet/components/RegisterHeader';
import StatusBadge from 'modules/fleet/components/StatusBadge';
import { DateField } from 'modules/fleet/components/formFields';
import { RegisterDriverDialog } from 'modules/fleet/dialogs/driverDialogs';
import Icon from 'shared/components/Icon';
import { useNotifier } from 'shared/components/Notifier';
import { defaultSite } from 'shared/components/SiteSelect';
import { formatDate, formatDaysRemaining } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';
import { canManageDrivers } from '../api/access';

/** Licence expiry earns colour: an expired licence is a refusal at assignment time, not a note. */
const expiryTone = (days: number) =>
  days < 0 ? 'text-error-700' : days < 30 ? 'text-warning-700' : 'opacity-70';

/** The one-click views of the register. The last is a date window, the rest are eligibility. */
const VIEWS: { value: string; label: string; eligibility?: DriverEligibilityStatus }[] = [
  { value: 'all', label: 'All' },
  { value: 'eligible', label: 'Eligible', eligibility: 'ELIGIBLE' },
  { value: 'conditional', label: 'Conditional', eligibility: 'CONDITIONAL' },
  { value: 'ineligible', label: 'Not eligible', eligibility: 'INELIGIBLE' },
  { value: 'expiring', label: 'Licence expiring within 60 days' },
];

const expiryHorizon = () => dayjs().add(60, 'day').format('YYYY-MM-DD');

/** The driver register: licence standing, lifecycle and eligibility in one scan. */
const DriverRegisterPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [registerOpen, setRegisterOpen] = useState(false);

  // The register's state lives in the URL. Every filter change returns to the first page - leaving
  // the page index behind asks the server for a page the narrowed result set no longer has.
  const state = useRegisterState('drivers');
  const { filters, setFilter } = state;
  const view = VIEWS.find((entry) => entry.value === filters.view) ?? VIEWS[0];
  const status = (filters.status ?? '') as DriverLifecycleStatus | '';
  const responsibleUnit = filters.unit ?? '';
  const typedExpiry = filters.licenceExpiringBefore ?? '';
  const licenceExpiringBefore = view.value === 'expiring' ? expiryHorizon() : typedExpiry;

  const query = useApiQuery(
    (signal) =>
      driversApi.search(
        {
          siteCode: state.site || undefined,
          search: state.search || undefined,
          status: status || undefined,
          eligibility: view.eligibility,
          responsibleUnit: responsibleUnit || undefined,
          licenceExpiringBefore: licenceExpiringBefore || undefined,
          page: state.apiPage,
          size: state.pageSize,
        },
        signal,
      ),
    [
      state.site,
      state.search,
      status,
      view.value,
      responsibleUnit,
      licenceExpiringBefore,
      state.apiPage,
      state.pageSize,
    ],
  );

  /** How many drivers each view holds, so the tab says what is behind it before it is opened. */
  const counts = useApiQuery(
    async (signal) => {
      const site = state.site || undefined;
      const totals = await Promise.all(
        VIEWS.map((entry) =>
          driversApi
            .search(
              {
                siteCode: site,
                eligibility: entry.eligibility,
                licenceExpiringBefore: entry.value === 'expiring' ? expiryHorizon() : undefined,
                size: 1,
              },
              signal,
            )
            .then((page) => [entry.value, page.totalElements] as const),
        ),
      );
      return Object.fromEntries(totals);
    },
    [state.site],
  );

  const columns = useMemo<FleetColumn<DriverResponse>[]>(
    () => [
      {
        key: 'displayName',
        header: 'Driver',
        width: 200,
        cell: (row) => (
          <CellStack
            primary={row.displayName}
            secondary={`${row.staffReference} · ${row.siteCode}`}
          />
        ),
      },
      {
        key: 'licenceClass',
        header: 'Licence',
        width: 150,
        cell: (row) => (
          <CellStack
            primary={`Class ${row.licenceClass}`}
            secondary={row.licenceNumberMasked ? 'Number masked' : (row.licenceNumber ?? '-')}
          />
        ),
      },
      {
        key: 'licenceExpiresOn',
        header: 'Licence expiry',
        width: 170,
        cell: (row) => (
          <div className="min-w-0">
            <div className="truncate">{formatDate(row.licenceExpiresOn)}</div>
            <div className={`truncate text-theme-xs ${expiryTone(row.daysUntilLicenceExpiry)}`}>
              {formatDaysRemaining(row.daysUntilLicenceExpiry)}
            </div>
          </div>
        ),
      },
      {
        key: 'medicalClearanceExpiresOn',
        header: 'Medical clearance',
        width: 150,
        cell: (row) => formatDate(row.medicalClearanceExpiresOn),
      },
      {
        key: 'lifecycleStatus',
        header: 'Lifecycle',
        width: 130,
        cell: (row) => <StatusBadge value={row.lifecycleStatus} />,
      },
      {
        key: 'eligibilityStatus',
        header: 'Eligibility',
        width: 230,
        // The leading reason only: a scan of the register should explain itself without a click,
        // and the full list is one row-click away on the driver record.
        cell: (row) => {
          const [reason] = describeDriverEligibility(row);
          return (
            <div className="min-w-0">
              <StatusBadge value={row.eligibilityStatus} />
              {reason && <p className="mt-1 text-theme-xs opacity-70">{reason}</p>}
            </div>
          );
        },
      },
      {
        key: 'responsibleUnit',
        header: 'Responsible unit',
        width: 180,
        cell: (row) => row.responsibleUnit,
      },
    ],
    [],
  );

  return (
    <>
      <RegisterHeader
        title="Driver register"
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
            {/* FLEET_DRIVER_MANAGE is a personnel function, not "am I a driver". A driver
                registering themselves is precisely what this gate prevents. */}
            {canManageDrivers() && (
              <Button variant="primary" onClick={() => setRegisterOpen(true)}>
                <Icon name="user-plus" size={14} aria-hidden="true" />
                Register driver
              </Button>
            )}
          </>
        }
      />

      <PageSection>
        <FleetTable
          paramPrefix="drivers"
          rows={query.data?.content ?? []}
          columns={columns}
          getRowId={(row) => row.id}
          loading={query.loading}
          error={query.error}
          onRetry={query.refetch}
          onRowClick={(row) => navigate(fleetPaths.driverDetail(row.id))}
          totalElements={query.data?.totalElements ?? 0}
          pageSize={state.pageSize}
          searchPlaceholder="Search name or staff number"
          caption="Driver register"
          heading={{
            title: 'Drivers',
            description:
              'Licence standing, lifecycle and eligibility for every driver in your site scope. An ineligible driver cannot be assigned to a trip.',
          }}
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
                options={DRIVER_LIFECYCLE_STATUSES.map((value) => ({
                  value,
                  label: humanise(value),
                }))}
              />
              <Input
                name="unit"
                aria-label="Responsible unit"
                placeholder="Responsible unit"
                defaultValue={responsibleUnit}
              />
              <FilterField name="licenceExpiringBefore" value={typedExpiry}>
                <DateField
                  label="Licence expiring before"
                  value={typedExpiry}
                  onChange={(value) => setFilter('licenceExpiringBefore', value)}
                />
              </FilterField>
            </>
          }
          emptyTitle="No drivers match these filters"
          emptyDescription="Adjust the filters, or register the first driver for this site."
        />
      </PageSection>

      {/* Mounted only while open, so the dialog picks up the current site filter as its default
          and cannot reopen holding a half-typed profile from a previous attempt. */}
      {registerOpen && (
        <RegisterDriverDialog
          open
          defaultSiteCode={state.site || defaultSite}
          onClose={() => setRegisterOpen(false)}
          onSaved={() => {
            notifySuccess('Driver registered.');
            query.refetch();
            counts.refetch();
          }}
        />
      )}
    </>
  );
};

export default DriverRegisterPage;

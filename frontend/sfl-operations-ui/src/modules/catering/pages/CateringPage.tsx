import { useCallback, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus, RefreshCw } from 'lucide-react';
import { Banner, Button, Dropdown, MetricCards, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite, sflSites } from 'shared/components/SiteSelect';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import ReasonDialog from 'shared/components/ReasonDialog';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { facilitiesPaths } from 'shared/layout/navigation';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import { cateringApi, type CateringException, type CateringService, type Configuration } from '../api/cateringApi';
import { ExceptionFormDialog, ItemDialog, MenuDialog, ServiceFormDialog, SupplierCheckDialog, SupplierDialog, VenueDialog } from '../components/CateringDialogs';
import { CateringBadge, labelsOf, options, serviceStatuses } from '../components/cateringUi';
import ServiceDialog from '../components/ServiceDialog';

type View = 'overview' | 'services' | 'exceptions' | 'setup';
const views: Array<{ value: View; label: string }> = [
  { value: 'overview', label: 'Overview' },
  { value: 'services', label: 'Services' },
  { value: 'exceptions', label: 'Exceptions' },
  { value: 'setup', label: 'Setup' },
];
const cateringPath = (view: View) => (view === 'overview' ? '/facilities/catering' : `/facilities/catering/${view}`);
const emptyConfig: Configuration = { suppliers: [], venues: [], menus: [] };
const percent = (value: number | null | undefined) => (value == null ? 'No data' : `${value}%`);

/**
 * S172 Catering & Cafeteria Management, one site at a time: the figures, the services and what stands between each
 * and confirmation, the exceptions raised, and the suppliers, venues and menus they run on. Write controls
 * appear only for roles the service lets write; dietary detail only for roles that hold the dietary grant.
 */
const CateringPage = () => {
  const navigate = useNavigate();
  const { view: requested } = useParams<{ view?: string }>();
  const view = (views.find((candidate) => candidate.value === requested)?.value ?? 'overview') as View;
  const [siteCode, setSiteCode] = useState(defaultSite || sflSites()[0] || '');
  const [refresh, setRefresh] = useState(0);
  const [planning, setPlanning] = useState(false);
  const canManage = permits('FACILITIES_CATERING_MANAGE');
  const bump = () => setRefresh((value) => value + 1);
  const configuration = useApiQuery((signal) => (siteCode ? cateringApi.configuration(siteCode, signal) : Promise.resolve(emptyConfig)), [siteCode, refresh]);
  const config = configuration.data ?? emptyConfig;

  return (
    <>
      <PageHeading
        title="Catering & cafeteria"
        subtitle="Plan menus and services, record dietary needs, keep supplier and food-safety checks, and reconcile what was delivered."
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: 'Catering & cafeteria' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
            {canManage && <Button variant="primary" onClick={() => setPlanning(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Plan a service</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(next) => navigate(cateringPath(next as View))}>
          <TabsList aria-label="Catering views">
            {views.map((candidate) => <TabsTrigger key={candidate.value} value={candidate.value}>{candidate.label}</TabsTrigger>)}
          </TabsList>
        </Tabs>
      </PageSection>
      {!siteCode ? (
        <PageSection><Banner variant="info" heading="Choose a site" subtext="Catering is planned per site." /></PageSection>
      ) : (
        <>
          {view === 'overview' && <Overview siteCode={siteCode} refresh={refresh} onOpen={(next) => navigate(cateringPath(next))} />}
          {view === 'services' && <Services siteCode={siteCode} config={config} refresh={refresh} onChanged={bump} />}
          {view === 'exceptions' && <Exceptions siteCode={siteCode} refresh={refresh} onChanged={bump} />}
          {view === 'setup' && <Setup siteCode={siteCode} config={config} loading={configuration.initialising} error={configuration.error} onChanged={bump} />}
        </>
      )}
      {planning && <ServiceFormDialog siteCode={siteCode} config={config} onClose={() => setPlanning(false)} onDone={() => { setPlanning(false); bump(); }} />}
    </>
  );
};

const Overview = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (view: View) => void }) => {
  const query = useApiQuery((signal) => cateringApi.dashboard(siteCode, signal), [siteCode, refresh]);
  const d = query.data;
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Confirmed services delivered" value={percent(d?.deliveredPercent)} icon="check-circle" tone="good" caption={d ? `${d.deliveredServices} of ${d.confirmedServices} in ${d.periodDays} days` : undefined} loading={query.initialising} onClick={() => onOpen('services')} />
          <StatMetric label="Food-safety checks completed" value={percent(d?.checkedPercent)} icon="shield-check" tone="good" caption={d ? `${d.servicesWithPassingCheck} of ${d.servicesDelivered} delivered services had a passing check` : undefined} loading={query.initialising} />
          <StatMetric label="Dietary exception rate" value={percent(d?.dietaryExceptionPercent)} icon="alert-triangle" caption={d ? `${d.dietaryExceptions} of ${d.dietaryRequests} needed a substitution or waiver` : undefined} loading={query.initialising} />
          <StatMetric label="Open variances" value={d?.openVariances ?? 0} icon="clock" tone={d?.variancesOver14Days ? 'critical' : d?.openVariances ? 'caution' : 'good'}
            caption={d ? `${d.variancesUpTo7Days} this week · ${d.variances8To14Days} to 14 days · ${d.variancesOver14Days} older` : undefined} loading={query.initialising} onClick={() => onOpen('services')} />
          <StatMetric label="Net portion variance" value={d ? d.netQuantityVariance : 0} icon="gauge" caption="Delivered less planned, all recorded variances" loading={query.initialising} />
          <StatMetric label="Open exceptions" value={d?.openExceptions ?? 0} icon="bell" tone={d?.openExceptions ? 'caution' : 'good'} loading={query.initialising} onClick={() => onOpen('exceptions')} />
          <StatMetric label="Awaiting finance" value={d?.awaitingFinance ?? 0} icon="document" tone={d?.awaitingFinance ? 'caution' : 'good'} caption="Delivered, reconciliation pending finance" loading={query.initialising} />
        </MetricCards>
      </PageSection>
    </DataState>
  );
};

const Services = ({ siteCode, config, refresh, onChanged }: { siteCode: string; config: Configuration; refresh: number; onChanged: () => void }) => {
  const [openId, setOpenId] = useState<string>();
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'services', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => cateringApi.services({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const venues = useMemo(() => new Map(config.venues.map((v) => [v.id, v])), [config]);
  const columns = useMemo<TableColumn<CateringService>[]>(() => [
    { id: 'service', header: 'Service', width: 300, cell: ({ row }) => <CellStack primary={row.title} secondary={`${row.reference} · ${humanise(row.contextType)}${row.contextReference ? ` ${row.contextReference}` : ''}`} /> },
    { id: 'venue', header: 'Venue', width: 150, hideBelowLg: true, cell: ({ row }) => venues.get(row.venueId)?.name ?? '-' },
    { id: 'when', header: 'Starts', width: 170, hideBelowLg: true, cell: ({ row }) => formatDateTime(row.startsAt) },
    { id: 'portions', header: 'Portions', width: 120, hideBelowLg: true, cell: ({ row }) => (row.deliveredPortions == null ? String(row.plannedPortions) : `${row.deliveredPortions}/${row.plannedPortions}`) },
    { id: 'status', header: 'Status', width: 170, align: 'right', cell: ({ row }) => <CateringBadge value={row.status} /> },
  ], [venues]);
  return (
    <PageSection>
      <Panel title="Services" subtitle="Latest first. Open one for its allergen labels, controls, dietary needs, checks and variances.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="services" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => setOpenId(row.id)} emptyTitle="No services match these filters" emptyDescription="Widen the status, or plan a service."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(serviceStatuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
      {openId && <ServiceDialog serviceId={openId} onClose={() => setOpenId(undefined)} onChanged={onChanged} />}
    </PageSection>
  );
};

const Exceptions = ({ siteCode, refresh, onChanged }: { siteCode: string; refresh: number; onChanged: () => void }) => {
  const [resolving, setResolving] = useState<CateringException>();
  const [linking, setLinking] = useState<CateringException>();
  const [raising, setRaising] = useState(false);
  const canManage = permits('FACILITIES_CATERING_MANAGE');
  const canApprove = permits('FACILITIES_CATERING_APPROVE');
  const { page, pageSize } = useTableState({ paramPrefix: 'exceptions', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const query = useApiQuery((signal) => cateringApi.exceptions({ siteCode, page: page - 1, size: pageSize }, signal), [siteCode, page, pageSize, refresh]);
  const notifier = useNotifier();
  const orders = useApiQuery((signal) => cateringApi.exceptionWorkOrders(siteCode, signal), [siteCode, refresh]);
  const orderOf = useMemo(() => new Map((orders.data ?? []).map((o) => [o.exceptionId, o])), [orders.data]);
  const refetchOrders = orders.refetch;
  const retryOrder = useCallback(async (id: string) => {
    try {
      await cateringApi.retryWorkOrder(id);
    } catch (cause) {
      notifier.notifyError(cause);
    }
    refetchOrders();
  }, [notifier, refetchOrders]);
  const columns = useMemo<TableColumn<CateringException>[]>(() => [
    { id: 'exception', header: 'Exception', width: 320, cell: ({ row }) => <CellStack primary={humanise(row.exceptionType)} secondary={`${row.reference} · ${row.description}`} /> },
    { id: 'owner', header: 'Owner', width: 150, hideBelowLg: true, cell: ({ row }) => row.ownerReference },
    { id: 'incident', header: 'Incident (S163)', width: 200, hideBelowLg: true, cell: ({ row }) => (row.incidentState === 'NOT_REQUIRED' ? '-' : row.incidentState === 'LINKED' ? row.incidentReference : 'Pending - not confirmed by S163') },
    {
      id: 'work', header: 'Work order (S153)', width: 200, hideBelowLg: true,
      cell: ({ row }) => {
        const order = orderOf.get(row.id);
        if (row.exceptionType === 'SUBSTITUTION') return '-';
        if (!order) return 'Not requested yet';
        return order.state === 'RAISED' ? order.workOrderNumber : (
          <span className="inline-flex items-center gap-2">Pending - not confirmed by S153 {canManage && <Button size="sm" variant="outline" onClick={(event) => { event.stopPropagation(); void retryOrder(row.id); }}>Retry</Button>}</span>
        );
      },
    },
    {
      id: 'state', header: 'State', width: 260, align: 'right',
      cell: ({ row }) => (
        <span className="inline-flex flex-wrap items-center justify-end gap-2">
          {row.status === 'OPEN' && row.incidentState === 'PENDING_MANUAL' && canManage && <Button size="sm" variant="outline" onClick={() => setLinking(row)}>Link incident</Button>}
          {row.status === 'OPEN' && (row.exceptionType === 'FOOD_SAFETY_INCIDENT' ? canApprove : canManage) && <Button size="sm" variant="outline" onClick={() => setResolving(row)}>Resolve</Button>}
          <CateringBadge value={row.status} />
        </span>
      ),
    },
  ], [canManage, canApprove, orderOf, retryOrder]);
  return (
    <PageSection>
      <Panel title="Exceptions" subtitle="Shortages, substitutions, service exceptions and food-safety incidents. A food-safety incident blocks delivery until an approver resolves it."
        actions={canManage && <Button size="sm" variant="outline" onClick={() => setRaising(true)}>Raise exception</Button>}>
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="exceptions" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            emptyTitle="No exceptions" emptyDescription="Exceptions appear when a temperature check fails or someone raises one."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} />
        </DataState>
      </Panel>
      {resolving && <ReasonDialog title="Resolve the exception" description="Say what was done." label="Resolution" submitLabel="Resolve" write={(text) => cateringApi.resolveException(resolving, text)} onClose={() => setResolving(undefined)} onDone={() => { setResolving(undefined); query.refetch(); onChanged(); }} />}
      {linking && <ReasonDialog title="Link the incident" description="Enter the reference Incident Reporting (S163) gave this incident." label="Incident reference" submitLabel="Link incident" write={(text) => cateringApi.linkIncident(linking.id, text)} onClose={() => setLinking(undefined)} onDone={() => { setLinking(undefined); query.refetch(); onChanged(); }} />}
      {raising && <ExceptionFormDialog siteCode={siteCode} onClose={() => setRaising(false)} onDone={() => { setRaising(false); query.refetch(); onChanged(); }} />}
    </PageSection>
  );
};

type SetupDialog = 'supplier' | 'venue' | 'menu';

const Setup = ({ siteCode, config, loading, error, onChanged }: { siteCode: string; config: Configuration; loading: boolean; error: ReturnType<typeof useApiQuery>['error']; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [dialog, setDialog] = useState<SetupDialog>();
  const [adding, setAdding] = useState<string>();
  const [checking, setChecking] = useState<{ id: string; name: string }>();
  const canManage = permits('FACILITIES_CATERING_MANAGE');
  const canApprove = permits('FACILITIES_CATERING_APPROVE');
  const run = async (write: () => Promise<unknown>, success: string) => {
    try {
      await write();
      notifier.notifySuccess(success);
    } catch (cause) {
      notifier.notifyError(cause);
    }
    onChanged();
  };
  return (
    <DataState loading={loading} error={error} onRetry={onChanged}>
      <PageSection>
        <Panel title="Suppliers" subtitle="Organisation-wide. Certificate and a passing check in the last 30 days keep a supplier valid for approval." actions={canManage && <Button size="sm" variant="outline" onClick={() => setDialog('supplier')}>Add supplier</Button>}>
          {config.suppliers.length === 0 ? <p className="text-theme-sm text-gray-600">No suppliers yet.</p> : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {config.suppliers.map((s) => (
                <li key={s.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
                  <div><p className="font-medium">{s.name} ({s.code})</p><p className="text-theme-xs text-gray-600">Certificate {s.certificateReference}, expires {formatDate(s.certificateExpiresOn)}</p></div>
                  <div className="flex items-center gap-2">
                    <CateringBadge value={s.status} />
                    {canManage && <Button size="sm" variant="outline" onClick={() => setChecking({ id: s.id, name: s.name })}>Record check</Button>}
                    {canManage && <Button size="sm" variant="outline" onClick={() => void run(() => cateringApi.setSupplierStatus(s, s.status === 'APPROVED' ? 'SUSPENDED' : 'APPROVED'), 'Supplier updated')}>{s.status === 'APPROVED' ? 'Suspend' : 'Reinstate'}</Button>}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Venues" subtitle={`At ${siteCode}. Capacity is checked at approval.`} actions={canManage && <Button size="sm" variant="outline" onClick={() => setDialog('venue')}>Add venue</Button>}>
          {config.venues.length === 0 ? <p className="text-theme-sm text-gray-600">No venues at this site yet.</p> : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {config.venues.map((v) => <li key={v.id} className="flex items-center justify-between px-4 py-3 text-sm"><span>{v.name} ({v.code})</span><span className="text-theme-xs text-gray-600">Capacity {v.capacity}</span></li>)}
            </ul>
          )}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Menus" subtitle="Allergens and diets are declared per dish. Approval needs every dish declared; changing an approved menu sends its services back for approval." actions={canManage && <Button size="sm" variant="outline" onClick={() => setDialog('menu')}>Add menu</Button>}>
          {config.menus.length === 0 ? <p className="text-theme-sm text-gray-600">No menus yet.</p> : (
            <div className="space-y-3">
              {config.menus.map(({ menu, items }) => (
                <div key={menu.id} className="rounded-lg border border-border">
                  <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3">
                    <p className="text-sm font-medium">{menu.name} ({menu.code})</p>
                    <div className="flex items-center gap-2">
                      <CateringBadge value={menu.status} />
                      {canManage && menu.status !== 'RETIRED' && <Button size="sm" variant="outline" onClick={() => setAdding(menu.id)}>Add dish</Button>}
                      {canApprove && menu.status === 'DRAFT' && <Button size="sm" variant="primary" onClick={() => void run(() => cateringApi.approveMenu(menu), 'Menu approved')}>Approve menu</Button>}
                    </div>
                  </div>
                  <ul className="divide-y divide-border">
                    {items.length === 0 && <li className="px-4 py-3 text-theme-sm text-gray-600">No dishes yet.</li>}
                    {items.map((i) => <li key={i.id} className="flex flex-wrap items-baseline justify-between gap-2 px-4 py-2 text-sm"><span className="font-medium">{i.name}</span><span className="text-theme-xs text-gray-600">{labelsOf(i)}</span></li>)}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </Panel>
      </PageSection>
      {dialog === 'supplier' && <SupplierDialog onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {dialog === 'venue' && <VenueDialog siteCode={siteCode} onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {dialog === 'menu' && <MenuDialog siteCode={siteCode} onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {adding && <ItemDialog menuId={adding} onClose={() => setAdding(undefined)} onDone={() => { setAdding(undefined); onChanged(); }} />}
      {checking && <SupplierCheckDialog supplierId={checking.id} supplierName={checking.name} onClose={() => setChecking(undefined)} onDone={() => { setChecking(undefined); onChanged(); }} />}
    </DataState>
  );
};

export default CateringPage;

import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus, RefreshCw } from 'lucide-react';
import { Banner, Button, Dropdown, MetricCards, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import ExportButton from 'shared/components/ExportButton';
import SiteSelect, { defaultSite, sflSites } from 'shared/components/SiteSelect';
import { formatDate } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { facilitiesPaths } from 'shared/layout/navigation';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import { leaseApi, type Agreement, type Alert, type Amendment, type Calendar, type Obligation } from '../api/leaseApi';
import AgreementDialog from '../components/AgreementDialog';
import { CalendarDialog, HolidayDialog, RegisterDialog } from '../components/LeaseDialogs';
import { LeaseBadge, agreementStatuses, amendmentStatuses, describeAmendment, money, obligationStatuses, options } from '../components/leaseUi';

type View = 'overview' | 'agreements' | 'amendments' | 'obligations' | 'alerts' | 'setup';
const views: Array<{ value: View; label: string }> = [
  { value: 'overview', label: 'Overview' },
  { value: 'agreements', label: 'Agreements' },
  { value: 'amendments', label: 'Amendments' },
  { value: 'obligations', label: 'Obligations' },
  { value: 'alerts', label: 'Alerts' },
  { value: 'setup', label: 'Calendar' },
];
const leasePath = (view: View) => (view === 'overview' ? '/facilities/leases' : `/facilities/leases/${view}`);
const percent = (value: number | null | undefined) => (value == null ? 'No data' : `${value}%`);

/**
 * S177 Lease & Tenancy, one site at a time: the portfolio and what it is owed, each agreement and what stops it being
 * approved, the amendments waiting on someone, and the dates being watched. Rent is shown only to the financial grant.
 */
const LeasePage = () => {
  const navigate = useNavigate();
  const { view: requested } = useParams<{ view?: string }>();
  const view = (views.find((candidate) => candidate.value === requested)?.value ?? 'overview') as View;
  const [siteCode, setSiteCode] = useState(defaultSite || sflSites()[0] || '');
  const [refresh, setRefresh] = useState(0);
  const [registering, setRegistering] = useState(false);
  const [openId, setOpenId] = useState<string>();
  const canManage = permits('FACILITIES_LEASE_MANAGE');
  const bump = () => setRefresh((value) => value + 1);
  const open = (id: string) => setOpenId(id);

  return (
    <>
      <PageHeading
        title="Lease & tenancy"
        subtitle="Keep every agreement, amendment, obligation and notice date in one place, and be told before a deadline passes."
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: 'Lease & tenancy' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <ExportButton path="/api/v1/facilities/leases/exports/agreements" siteCode={siteCode} />
            <Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
            {canManage && <Button variant="primary" onClick={() => setRegistering(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Register agreement</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(next) => navigate(leasePath(next as View))}>
          <TabsList aria-label="Lease views">
            {views.map((candidate) => <TabsTrigger key={candidate.value} value={candidate.value}>{candidate.label}</TabsTrigger>)}
          </TabsList>
        </Tabs>
      </PageSection>
      {view === 'setup' ? (
        <Setup refresh={refresh} onChanged={bump} />
      ) : !siteCode ? (
        <PageSection><Banner variant="info" heading="Choose a site" subtext="Agreements are held per site." /></PageSection>
      ) : (
        <>
          {view === 'overview' && <Overview siteCode={siteCode} refresh={refresh} onOpen={(next) => navigate(leasePath(next))} />}
          {view === 'agreements' && <Agreements siteCode={siteCode} refresh={refresh} onOpen={open} />}
          {view === 'amendments' && <Amendments siteCode={siteCode} refresh={refresh} onOpen={open} />}
          {view === 'obligations' && <Obligations siteCode={siteCode} refresh={refresh} onOpen={open} />}
          {view === 'alerts' && <Alerts siteCode={siteCode} refresh={refresh} onOpen={open} onChanged={bump} />}
        </>
      )}
      {registering && <RegisterDialog siteCode={siteCode} canSeeMoney={permits('FACILITIES_LEASE_FINANCIAL_READ')} onClose={() => setRegistering(false)} onDone={() => { setRegistering(false); bump(); }} />}
      {openId && <AgreementDialog agreementId={openId} onClose={() => setOpenId(undefined)} onChanged={bump} />}
    </>
  );
};

const Overview = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (view: View) => void }) => {
  const query = useApiQuery((signal) => leaseApi.portfolio(siteCode, signal), [siteCode, refresh]);
  const d = query.data;
  const count = (map: Record<string, number> | undefined, key: string) => map?.[key] ?? 0;
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Active agreements" value={count(d?.agreementsByStatus, 'ACTIVE')} icon="document" caption={d ? `${count(d.agreementsByStatus, 'DRAFT') + count(d.agreementsByStatus, 'IN_REVIEW')} not yet approved` : undefined} loading={query.initialising} onClick={() => onOpen('agreements')} />
          <StatMetric label="Ending within 90 days" value={count(d?.expiringWithin, '90')} icon="calendar" tone={count(d?.expiringWithin, '30') ? 'caution' : 'good'} caption={d ? `${count(d.expiringWithin, '30')} within 30 days` : undefined} loading={query.initialising} onClick={() => onOpen('agreements')} />
          <StatMetric label="Obligations overdue" value={d?.overdueObligations ?? 0} icon="clock" tone={d?.overdueObligations ? 'critical' : 'good'} caption={d ? `${count(d.obligationsDueWithin, '30')} more due within 30 days` : undefined} loading={query.initialising} onClick={() => onOpen('obligations')} />
          <StatMetric label="Incomplete agreements" value={d?.incompleteAgreements ?? 0} icon="alert-triangle" tone={d?.incompleteAgreements ? 'caution' : 'good'} caption="Missing something approval needs" loading={query.initialising} />
          <StatMetric label="Counterparties unresolved" value={d?.unresolvedCounterparties ?? 0} icon="shield-check" tone={d?.unresolvedCounterparties ? 'caution' : 'good'} caption="Recorded, not verified" loading={query.initialising} />
          <StatMetric label="Past end date" value={d?.expiredAgreements ?? 0} icon="bell" tone={d?.expiredAgreements ? 'critical' : 'good'} loading={query.initialising} />
          <StatMetric label="Renewals handled on time" value={percent(d?.renewalsOnTimePercent)} icon="check-circle" tone="good" caption={d ? `Of ${d.renewalsDueOrDone} renewal dates due or done` : undefined} loading={query.initialising} />
          <StatMetric label="Mean amendment cycle" value={d?.meanAmendmentCycleHours == null ? 'No data' : `${d.meanAmendmentCycleHours} h`} icon="gauge" caption="Proposed to decided" loading={query.initialising} />
        </MetricCards>
      </PageSection>
      {d && (
        <PageSection>
          <Panel title="Rent still to run" subtitle={d.financialExposure ? 'By currency, from today to each agreement end. Payable is rent CLET owes; receivable is rent owed to CLET.' : 'Not shown to your role.'}>
            {d.financialExposure ? (
              Object.keys(d.financialExposure).length === 0 ? <p className="text-theme-sm text-gray-600">No active agreements with rent.</p> : (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {Object.entries(d.financialExposure).map(([currency, e]) => (
                    <li key={currency} className="grid grid-cols-2 gap-2 px-4 py-3 text-sm sm:grid-cols-4">
                      <span className="font-medium">{currency}</span>
                      <span>Payable {money(e.payableRemaining, currency)}</span>
                      <span>Receivable {money(e.receivableRemaining, currency)}</span>
                      <span>Deposits: paid {money(e.depositsHeldByLandlords, currency)}, held {money(e.depositsHeld, currency)}</span>
                    </li>
                  ))}
                </ul>
              )
            ) : <p className="text-theme-sm text-gray-600">Rent and deposit are shown only to roles holding the financial grant.</p>}
          </Panel>
        </PageSection>
      )}
      {d && d.byOwner.length > 0 && (
        <PageSection>
          <Panel title="By owner" subtitle="Who is carrying which agreements and dates.">
            <ul className="divide-y divide-border rounded-lg border border-border">
              {d.byOwner.map((o) => <li key={o.owner} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm"><span className="font-medium">{o.owner}</span><span className="text-theme-xs text-gray-600">{o.agreements} agreements · {o.openObligations} open · {o.overdueObligations} overdue</span></li>)}
            </ul>
          </Panel>
        </PageSection>
      )}
    </DataState>
  );
};

const Agreements = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (id: string) => void }) => {
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'agreements', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => leaseApi.agreements({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const canSeeMoney = permits('FACILITIES_LEASE_FINANCIAL_READ');
  const columns = useMemo<TableColumn<Agreement>[]>(() => [
    { id: 'agreement', header: 'Agreement', width: 300, cell: ({ row }) => <CellStack primary={row.title} secondary={`${row.reference} · ${humanise(row.kind)} · ${row.propertyReference}`} /> },
    { id: 'owner', header: 'Owner', width: 150, hideBelowLg: true, cell: ({ row }) => row.ownerReference ?? 'Not assigned' },
    { id: 'ends', header: 'Ends', width: 130, hideBelowLg: true, cell: ({ row }) => formatDate(row.endDate) },
    { id: 'notice', header: 'Notice by', width: 130, hideBelowLg: true, cell: ({ row }) => (row.noticeDate ? formatDate(row.noticeDate) : 'Not set') },
    { id: 'rent', header: 'Annual rent', width: 150, hideBelowLg: true, cell: ({ row }) => money(row.annualRent, row.currency, canSeeMoney) },
    { id: 'status', header: 'Status', width: 120, align: 'right', cell: ({ row }) => <LeaseBadge value={row.status} /> },
  ], [canSeeMoney]);
  return (
    <PageSection>
      <Panel title="Agreements" subtitle="Open one for what it still needs, its versions, amendments, obligations and documents.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="agreements" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => onOpen(row.id)} emptyTitle="No agreements match these filters" emptyDescription="Widen the status, or register an agreement."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(agreementStatuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
    </PageSection>
  );
};

const Amendments = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (id: string) => void }) => {
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'amendments', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => leaseApi.amendments({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const columns = useMemo<TableColumn<Amendment>[]>(() => [
    { id: 'amendment', header: 'Amendment', width: 360, cell: ({ row }) => <CellStack primary={describeAmendment(row)} secondary={`${row.reference} · ${row.reason}`} /> },
    { id: 'by', header: 'Proposed by', width: 170, hideBelowLg: true, cell: ({ row }) => row.proposedBy },
    { id: 'status', header: 'Status', width: 150, align: 'right', cell: ({ row }) => <LeaseBadge value={row.status} /> },
  ], []);
  return (
    <PageSection>
      <Panel title="Amendments" subtitle="Rent, term, renewal and termination change only through an approved amendment. Conflicting ones are held for legal review.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="amendments" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => onOpen(row.agreementId)} emptyTitle="No amendments" emptyDescription="Propose one from an active agreement."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(amendmentStatuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
    </PageSection>
  );
};

const Obligations = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (id: string) => void }) => {
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'obligations', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? 'OPEN';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => leaseApi.obligations({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const today = new Date().toISOString().slice(0, 10);
  const columns = useMemo<TableColumn<Obligation>[]>(() => [
    { id: 'obligation', header: 'Obligation', width: 320, cell: ({ row }) => <CellStack primary={row.title} secondary={`${humanise(row.kind)} · ${row.ownerReference ?? 'no owner'}`} /> },
    { id: 'due', header: 'Due', width: 150, hideBelowLg: true, cell: ({ row }) => <span className={row.status === 'OPEN' && row.dueOn < today ? 'text-[var(--clet-error-text)]' : undefined}>{formatDate(row.dueOn)}{row.status === 'OPEN' && row.dueOn < today ? ' · overdue' : ''}</span> },
    { id: 'status', header: 'Status', width: 120, align: 'right', cell: ({ row }) => <LeaseBadge value={row.status} /> },
  ], [today]);
  return (
    <PageSection>
      <Panel title="Obligations" subtitle="Soonest first. Open one to complete or waive it on the agreement.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="obligations" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => onOpen(row.agreementId)} emptyTitle="No obligations" emptyDescription="They are generated when an agreement is approved."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(obligationStatuses)} placeholder="Open" clearable />} />
        </DataState>
      </Panel>
    </PageSection>
  );
};

const Alerts = ({ siteCode, refresh, onOpen, onChanged }: { siteCode: string; refresh: number; onOpen: (id: string) => void; onChanged: () => void }) => {
  const notifier = useNotifier();
  const { page, pageSize } = useTableState({ paramPrefix: 'alerts', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const query = useApiQuery((signal) => leaseApi.alerts({ siteCode, openOnly: true, page: page - 1, size: pageSize }, signal), [siteCode, page, pageSize, refresh]);
  const canManage = permits('FACILITIES_LEASE_MANAGE');
  const acknowledge = async (id: string) => {
    try {
      await leaseApi.acknowledge(id);
      notifier.notifySuccess('Alert acknowledged');
    } catch (cause) {
      notifier.notifyError(cause);
    }
    query.refetch();
    onChanged();
  };
  const columns = useMemo<TableColumn<Alert>[]>(() => [
    { id: 'alert', header: 'Alert', width: 380, cell: ({ row }) => <CellStack primary={row.detail ?? humanise(row.reason)} secondary={`${humanise(row.reason)} · raised ${formatDate(row.raisedAt)}`} /> },
    { id: 'level', header: 'Told', width: 120, hideBelowLg: true, cell: ({ row }) => <LeaseBadge value={row.level} /> },
    { id: 'act', header: '', width: 150, align: 'right', cell: ({ row }) => (canManage ? <Button size="sm" variant="outline" onClick={(event) => { event.stopPropagation(); void acknowledge(row.id); }}>Acknowledge</Button> : null) },
  // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [canManage]);
  return (
    <PageSection>
      <Panel title="Alerts" subtitle="Raised as a date approaches and passed up if nobody acts: the owner first, then the manager, the director and legal. Acknowledging does not clear the date; completing the obligation does.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="alerts" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => onOpen(row.agreementId)} emptyTitle="No open alerts" emptyDescription="Nothing is close to a deadline that has not been acted on."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} />
        </DataState>
      </Panel>
    </PageSection>
  );
};

const Setup = ({ refresh, onChanged }: { refresh: number; onChanged: () => void }) => {
  const [dialog, setDialog] = useState<'settings' | 'holiday'>();
  const canApprove = permits('FACILITIES_LEASE_APPROVE');
  const query = useApiQuery((signal) => leaseApi.calendar(signal), [refresh]);
  const c: Calendar | undefined = query.data;
  return (
    <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <Panel title="Business calendar" subtitle="Notice and renewal dates move back to the last business day before the deadline. Organisation-wide."
          actions={canApprove && <Button size="sm" variant="outline" onClick={() => setDialog('settings')}>Change timezone and weekend</Button>}>
          {c && <p className="text-sm">Timezone <span className="font-medium">{c.timezone}</span> · weekend {c.weekend.map(humanise).join(', ') || 'none'}</p>}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Holidays" subtitle="Deadlines falling on these days move to the working day before."
          actions={canApprove && <Button size="sm" variant="outline" onClick={() => setDialog('holiday')}>Add holiday</Button>}>
          {c && c.holidays.length === 0 ? <p className="text-theme-sm text-gray-600">No holidays recorded.</p> : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {c?.holidays.map((h) => <li key={h.date} className="flex items-center justify-between px-4 py-3 text-sm"><span>{h.name}</span><span className="text-theme-xs text-gray-600">{formatDate(h.date)}</span></li>)}
            </ul>
          )}
        </Panel>
      </PageSection>
      {dialog === 'settings' && c && <CalendarDialog timezone={c.timezone} weekend={c.weekend} onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); query.refetch(); onChanged(); }} />}
      {dialog === 'holiday' && <HolidayDialog onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); query.refetch(); onChanged(); }} />}
    </DataState>
  );
};

export default LeasePage;

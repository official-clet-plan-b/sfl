import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus, RefreshCw } from 'lucide-react';
import { Banner, Button, Dropdown, MetricCards, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import ExportButton from 'shared/components/ExportButton';
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
import { lostFoundApi, type Claim, type Configuration, type Escalation, type FoundItem } from '../api/lostFoundApi';
import ItemDialog from '../components/ItemDialog';
import { LocationDialog, PolicyDialog, RegisterDialog } from '../components/LfDialogs';
import { categories, claimStatuses, itemStatuses, LfBadge, options } from '../components/lfUi';

type View = 'overview' | 'items' | 'claims' | 'escalations' | 'setup';
const views: Array<{ value: View; label: string }> = [
  { value: 'overview', label: 'Overview' },
  { value: 'items', label: 'Items' },
  { value: 'claims', label: 'Claims' },
  { value: 'escalations', label: 'Escalations' },
  { value: 'setup', label: 'Setup' },
];
const lostFoundPath = (view: View) => (view === 'overview' ? '/facilities/lost-found' : `/facilities/lost-found/${view}`);
const emptyConfig: Configuration = { locations: [], policies: [] };
const percent = (value: number | null | undefined) => (value == null ? 'No data' : `${value}%`);

/**
 * S179 Lost-and-Found Register, one site at a time. Without the private grant every list and every detail is
 * the masked view the service sends; this page does not hide fields, it shows what it is given and says when
 * that is the masked view.
 */
const LostFoundPage = () => {
  const navigate = useNavigate();
  const { view: requested } = useParams<{ view?: string }>();
  const view = (views.find((candidate) => candidate.value === requested)?.value ?? 'overview') as View;
  const [siteCode, setSiteCode] = useState(defaultSite || sflSites()[0] || '');
  const [refresh, setRefresh] = useState(0);
  const [registering, setRegistering] = useState(false);
  const canManage = permits('FACILITIES_LOSTFOUND_MANAGE');
  const bump = () => setRefresh((value) => value + 1);
  const configuration = useApiQuery((signal) => (siteCode ? lostFoundApi.configuration(siteCode, signal) : Promise.resolve(emptyConfig)), [siteCode, refresh]);
  const config = configuration.data ?? emptyConfig;

  return (
    <>
      <PageHeading
        title="Lost & found"
        subtitle="Found property, its chain of custody, and the claims against it - with private detail kept for the people who need it."
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: 'Lost & found' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <ExportButton path="/api/v1/facilities/lost-found/exports/items" siteCode={siteCode} />
            <Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
            {canManage && <Button variant="primary" onClick={() => setRegistering(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Register item</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(next) => navigate(lostFoundPath(next as View))}>
          <TabsList aria-label="Lost and found views">
            {views.map((candidate) => <TabsTrigger key={candidate.value} value={candidate.value}>{candidate.label}</TabsTrigger>)}
          </TabsList>
        </Tabs>
      </PageSection>
      {!siteCode ? (
        <PageSection><Banner variant="info" heading="Choose a site" subtext="Found property is kept per site." /></PageSection>
      ) : (
        <>
          {view === 'overview' && <Overview siteCode={siteCode} refresh={refresh} onOpen={(next) => navigate(lostFoundPath(next))} />}
          {view === 'items' && <Items siteCode={siteCode} config={config} refresh={refresh} onChanged={bump} />}
          {view === 'claims' && <Claims siteCode={siteCode} config={config} refresh={refresh} onChanged={bump} />}
          {view === 'escalations' && <Escalations siteCode={siteCode} refresh={refresh} onChanged={bump} />}
          {view === 'setup' && <Setup siteCode={siteCode} config={config} loading={configuration.initialising} error={configuration.error} onChanged={bump} />}
        </>
      )}
      {registering && <RegisterDialog siteCode={siteCode} config={config} onClose={() => setRegistering(false)} onDone={() => { setRegistering(false); bump(); }} />}
    </>
  );
};

const Overview = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (view: View) => void }) => {
  const query = useApiQuery((signal) => lostFoundApi.dashboard(siteCode, signal), [siteCode, refresh]);
  const d = query.data;
  const open = d ? d.openByAge.upTo7Days + d.openByAge.from8To30Days + d.openByAge.from31To90Days + d.openByAge.over90Days : 0;
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Open items" value={open} icon="inbox" caption={d ? `${d.openByAge.upTo7Days} this week · ${d.openByAge.from8To30Days} to 30 days · ${d.openByAge.from31To90Days} to 90 · ${d.openByAge.over90Days} older` : undefined} loading={query.initialising} onClick={() => onOpen('items')} />
          <StatMetric label="Verified claim to release" value={d?.meanHoursToVerifiedRelease == null ? 'No data' : `${Math.round(d.meanHoursToVerifiedRelease)} h`} icon="clock" caption="Mean, claim received to released" loading={query.initialising} onClick={() => onOpen('claims')} />
          <StatMetric label="Custody chains complete" value={percent(d?.custodyCompletenessPercent)} icon="link" tone="good" caption={d ? `${d.itemsWithChain} items with a chain` : undefined} loading={query.initialising} />
          <StatMetric label="Closed within policy" value={percent(d?.withinPolicyPercent)} icon="check-circle" tone="good" caption={d ? `${d.itemsClosed} items returned or disposed of` : undefined} loading={query.initialising} />
          <StatMetric label="Past retention" value={d?.retentionExpired ?? 0} icon="calendar" tone={d?.retentionExpired ? 'caution' : 'good'} caption="Unclaimed, awaiting an approved disposal" loading={query.initialising} onClick={() => onOpen('items')} />
          <StatMetric label="Open escalations" value={d?.openEscalations ?? 0} icon="bell" tone={d?.openEscalations ? 'critical' : 'good'} caption={`${d?.isolatedItems ?? 0} isolated as unsafe`} loading={query.initialising} onClick={() => onOpen('escalations')} />
        </MetricCards>
      </PageSection>
      <PageSection>
        <Panel title="Where open items are" subtitle="By storage location.">
          {(d?.openByLocation ?? []).length === 0 ? <p className="text-theme-sm text-gray-600">Nothing is being held.</p> : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {d?.openByLocation.map((row) => (
                <li key={row.location} className="flex items-center justify-between px-4 py-3 text-sm"><span>{row.location}</span><span className="font-semibold">{row.items}</span></li>
              ))}
            </ul>
          )}
        </Panel>
      </PageSection>
    </DataState>
  );
};

const Items = ({ siteCode, config, refresh, onChanged }: { siteCode: string; config: Configuration; refresh: number; onChanged: () => void }) => {
  const [openId, setOpenId] = useState<string>();
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'items', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const category = filters.category ?? '';
  const [statusField, setStatusField] = useState(status);
  const [categoryField, setCategoryField] = useState(category);
  const query = useApiQuery((signal) => lostFoundApi.items({ siteCode, status: status || undefined, category: category || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, category, page, pageSize, refresh]);
  const columns = useMemo<TableColumn<FoundItem>[]>(() => [
    { id: 'item', header: 'Item', width: 280, cell: ({ row }) => <CellStack primary={row.publicDescription} secondary={`${row.reference} · ${row.claimReference}`} /> },
    { id: 'category', header: 'Category', width: 140, hideBelowLg: true, cell: ({ row }) => humanise(row.category) },
    { id: 'found', header: 'Found', width: 160, hideBelowLg: true, cell: ({ row }) => formatDate(row.foundAt) },
    { id: 'keep', header: 'Keep until', width: 120, hideBelowLg: true, cell: ({ row }) => formatDate(row.retentionUntil) },
    { id: 'status', header: 'Status', width: 140, align: 'right', cell: ({ row }) => <LfBadge value={row.status} /> },
  ], []);
  return (
    <PageSection>
      <Panel title="Found items" subtitle="Newest first. Open one for its custody chain, claims and evidence.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="items" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => setOpenId(row.id)} emptyTitle="No items match these filters" emptyDescription="Widen the filters, or register found property."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={2}
            filters={<>
              <Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(itemStatuses)} placeholder="All statuses" clearable />
              <Dropdown name="category" aria-label="Filter by category" value={categoryField || null} onValueChange={(next) => setCategoryField(next ?? '')} options={options(categories)} placeholder="All categories" clearable />
            </>} />
        </DataState>
      </Panel>
      {openId && <ItemDialog itemId={openId} config={config} onClose={() => setOpenId(undefined)} onChanged={onChanged} />}
    </PageSection>
  );
};

const Claims = ({ siteCode, config, refresh, onChanged }: { siteCode: string; config: Configuration; refresh: number; onChanged: () => void }) => {
  const [openItem, setOpenItem] = useState<string>();
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'claims', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => lostFoundApi.claims({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const columns = useMemo<TableColumn<Claim>[]>(() => [
    { id: 'claim', header: 'Claim', width: 260, cell: ({ row }) => <CellStack primary={row.claimantName ?? row.reference} secondary={row.claimantName ? row.reference : 'Details not shown'} /> },
    { id: 'received', header: 'Received', width: 160, hideBelowLg: true, cell: ({ row }) => formatDateTime(row.createdAt) },
    { id: 'identity', header: 'Identity', width: 150, hideBelowLg: true, cell: ({ row }) => (row.identityVerified ? 'Verified' : 'Not verified') },
    { id: 'status', header: 'Status', width: 140, align: 'right', cell: ({ row }) => <LfBadge value={row.status} /> },
  ], []);
  return (
    <PageSection>
      <Panel title="Claims" subtitle="Open one to verify, approve, release or refuse.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="claims" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => setOpenItem(row.itemId)} emptyTitle="No claims match these filters" emptyDescription="Claims are received against a found item."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(claimStatuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
      {openItem && <ItemDialog itemId={openItem} config={config} onClose={() => setOpenItem(undefined)} onChanged={onChanged} />}
    </PageSection>
  );
};

const Escalations = ({ siteCode, refresh, onChanged }: { siteCode: string; refresh: number; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [linking, setLinking] = useState<Escalation>();
  const canManage = permits('FACILITIES_LOSTFOUND_MANAGE');
  const { page, pageSize } = useTableState({ paramPrefix: 'escalations', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const query = useApiQuery((signal) => lostFoundApi.escalations({ siteCode, openOnly: false, page: page - 1, size: pageSize }, signal), [siteCode, page, pageSize, refresh]);
  const acknowledge = async (id: string) => {
    try {
      await lostFoundApi.acknowledge(id);
      notifier.notifySuccess('Escalation acknowledged');
      query.refetch();
      onChanged();
    } catch (cause) {
      notifier.notifyError(cause);
    }
  };
  const columns = useMemo<TableColumn<Escalation>[]>(() => [
    { id: 'reason', header: 'Reason', width: 260, cell: ({ row }) => <CellStack primary={humanise(row.reason)} secondary={row.detail ?? undefined} /> },
    { id: 'to', header: 'Escalated to', width: 170, hideBelowLg: true, cell: ({ row }) => humanise(row.escalatedTo) },
    { id: 'incident', header: 'Incident (S163)', width: 190, hideBelowLg: true, cell: ({ row }) => (row.incidentState === 'NOT_REQUIRED' ? '-' : row.incidentState === 'LINKED' ? row.incidentReference : 'Pending - not confirmed by S163') },
    { id: 'raised', header: 'Raised', width: 160, hideBelowLg: true, cell: ({ row }) => formatDateTime(row.raisedAt) },
    {
      id: 'state', header: 'State', width: 260, align: 'right',
      cell: ({ row }) => (
        <span className="inline-flex flex-wrap items-center justify-end gap-2">
          {row.incidentState === 'PENDING_MANUAL' && canManage && <Button size="sm" variant="outline" onClick={() => setLinking(row)}>Link incident</Button>}
          {row.acknowledgedAt ? <span className="text-theme-xs text-gray-600">Acknowledged by {row.acknowledgedBy}</span>
            : canManage ? <Button size="sm" variant="outline" onClick={() => void acknowledge(row.id)}>Acknowledge</Button> : <LfBadge value="PENDING_MANUAL" label="Awaiting acknowledgement" />}
        </span>
      ),
    },
  // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [canManage]);
  return (
    <PageSection>
      <Panel title="Escalations" subtitle="Raised for an unsafe item, competing claims, or an unclaimed item past its retention period.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="escalations" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            emptyTitle="Nothing has been escalated" emptyDescription="Escalations appear when an item is unsafe, claims compete, or retention runs out."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} />
        </DataState>
      </Panel>
      {linking && (
        <ReasonDialog title="Link the incident" description="Enter the reference Incident Reporting (S163) gave this incident." label="Incident reference" submitLabel="Link incident"
          write={(text) => lostFoundApi.linkIncident(linking.id, text)} onClose={() => setLinking(undefined)} onDone={() => { setLinking(undefined); query.refetch(); onChanged(); }} />
      )}
    </PageSection>
  );
};

const Setup = ({ siteCode, config, loading, error, onChanged }: { siteCode: string; config: Configuration; loading: boolean; error: ReturnType<typeof useApiQuery>['error']; onChanged: () => void }) => {
  const [dialog, setDialog] = useState<'location' | 'policy'>();
  const canManage = permits('FACILITIES_LOSTFOUND_MANAGE');
  const canApprove = permits('FACILITIES_LOSTFOUND_APPROVE');
  return (
    <DataState loading={loading} error={error} onRetry={onChanged}>
      <PageSection>
        <Panel title="Storage locations" subtitle={`At ${siteCode}. Secure locations are required for documents, electronics, valuables and medical items.`}
          actions={canManage && <Button size="sm" variant="outline" onClick={() => setDialog('location')}>Add location</Button>}>
          {config.locations.length === 0 ? <p className="text-theme-sm text-gray-600">No storage locations at this site yet.</p> : (
            <ul className="divide-y divide-border rounded-lg border border-border">
              {config.locations.map((l) => (
                <li key={l.id} className="flex items-center justify-between gap-3 px-4 py-3 text-sm"><span>{l.name} ({l.code})</span>{l.secure && <LfBadge value="SECURE" label="Secure" />}</li>
              ))}
            </ul>
          )}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Retention" subtitle="How long unclaimed items and claimants' personal data are kept, by category."
          actions={canApprove && <Button size="sm" variant="outline" onClick={() => setDialog('policy')}>Change a period</Button>}>
          <ul className="divide-y divide-border rounded-lg border border-border">
            {config.policies.map((p) => (
              <li key={p.category} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
                <span>{humanise(p.category)}</span>
                <span className="text-theme-xs text-gray-600">Unclaimed {p.unclaimedDays} days · claimant data {p.personalDataDays} days after the claim closes</span>
              </li>
            ))}
          </ul>
        </Panel>
      </PageSection>
      {dialog === 'location' && <LocationDialog siteCode={siteCode} onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {dialog === 'policy' && <PolicyDialog siteCode={siteCode} onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
    </DataState>
  );
};

export default LostFoundPage;

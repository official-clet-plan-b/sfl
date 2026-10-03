import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus, RefreshCw } from 'lucide-react';
import { Banner, Button, Dropdown, MetricCards, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import ExportButton from 'shared/components/ExportButton';
import { DateField } from 'modules/facilities/dialogs/dialogKit';
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
import { wasteApi, type Configuration, type WasteCarrier, type WasteCollection, type WasteDestination, type WasteException } from '../api/wasteApi';
import CollectionDialog from '../components/CollectionDialog';
import ExceptionPanel from '../components/ExceptionPanel';
import { CarrierDialog, DestinationDialog, ExceptionDialog, PointDialog, ScheduleDialog, StreamDialog } from '../components/WasteDialogs';
import { collectionStatuses, exceptionStatuses, options, quantityText, WasteBadge } from '../components/wasteUi';

type View = 'overview' | 'collections' | 'exceptions' | 'setup' | 'report';
const views: Array<{ value: View; label: string }> = [
  { value: 'overview', label: 'Overview' },
  { value: 'collections', label: 'Collections' },
  { value: 'exceptions', label: 'Exceptions' },
  { value: 'setup', label: 'Setup' },
  { value: 'report', label: 'Report' },
];
const wastePath = (view: View) => (view === 'overview' ? '/facilities/waste' : `/facilities/waste/${view}`);
const kg = (value: number | null | undefined) => (value == null ? '-' : `${Number(value).toLocaleString(undefined, { maximumFractionDigits: 1 })} kg`);
const percent = (value: number | null | undefined) => (value == null ? 'No data' : `${value}%`);
const emptyConfig: Configuration = { streams: [], points: [], carriers: [], destinations: [], units: [] };

/**
 * S178 Waste Management & Recycling Tracking, one site at a time: the figures, the collections and their
 * chain of custody, the exceptions raised against them, the configuration they run on, and the sourced
 * sustainability report. Write controls appear only for roles the service lets write.
 */
const WastePage = () => {
  const navigate = useNavigate();
  const { view: requested } = useParams<{ view?: string }>();
  const view = (views.find((candidate) => candidate.value === requested)?.value ?? 'overview') as View;
  const [siteCode, setSiteCode] = useState(defaultSite || sflSites()[0] || '');
  const [refresh, setRefresh] = useState(0);
  const [scheduling, setScheduling] = useState(false);
  const canManage = permits('FACILITIES_WASTE_MANAGE');
  const bump = () => setRefresh((value) => value + 1);
  const configuration = useApiQuery((signal) => (siteCode ? wasteApi.configuration(siteCode, signal) : Promise.resolve(emptyConfig)), [siteCode, refresh]);
  const config = configuration.data ?? emptyConfig;

  return (
    <>
      <PageHeading
        title="Waste & recycling"
        subtitle="Streams, collections and the chain of custody behind them - with the exceptions raised, and the sustainability figures worked out from measured records."
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: 'Waste & recycling' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <ExportButton path="/api/v1/facilities/waste/exports/collections" siteCode={siteCode} />
            <Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
            {canManage && <Button variant="primary" onClick={() => setScheduling(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Schedule collection</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(next) => navigate(wastePath(next as View))}>
          <TabsList aria-label="Waste views">
            {views.map((candidate) => <TabsTrigger key={candidate.value} value={candidate.value}>{candidate.label}</TabsTrigger>)}
          </TabsList>
        </Tabs>
      </PageSection>
      {!siteCode ? (
        <PageSection><Banner variant="info" heading="Choose a site" subtext="Waste records are kept per site." /></PageSection>
      ) : (
        <>
          {view === 'overview' && <Overview siteCode={siteCode} refresh={refresh} onOpen={(next) => navigate(wastePath(next))} />}
          {view === 'collections' && <Collections siteCode={siteCode} config={config} refresh={refresh} onChanged={bump} />}
          {view === 'exceptions' && <Exceptions siteCode={siteCode} refresh={refresh} onChanged={bump} />}
          {view === 'setup' && <Setup siteCode={siteCode} config={config} loading={configuration.initialising} error={configuration.error} onChanged={bump} />}
          {view === 'report' && <ReportView siteCode={siteCode} refresh={refresh} />}
        </>
      )}
      {scheduling && <ScheduleDialog siteCode={siteCode} config={config} onClose={() => setScheduling(false)} onDone={() => { setScheduling(false); bump(); }} />}
    </>
  );
};

const Overview = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (view: View) => void }) => {
  const query = useApiQuery((signal) => wasteApi.dashboard(siteCode, signal), [siteCode, refresh]);
  const d = query.data;
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Diversion from landfill" value={percent(d?.diversionRatePercent)} icon="refresh" tone="good"
            caption={d ? `Measured only · ${kg(d.measuredKg)} in ${d.periodDays} days` : undefined} loading={query.initialising} onClick={() => onOpen('report')} />
          <StatMetric label="Estimated, not counted" value={kg(d?.estimatedKg)} icon="info" tone={d?.estimatedKg ? 'caution' : 'neutral'} caption="Excluded from every percentage" loading={query.initialising} />
          <StatMetric label="Certificate completion" value={percent(d?.certificateCompletionPercent)} icon="document"
            caption={d ? `${d.certifiedCollections} of ${d.handedOverCollections} handed over` : undefined} loading={query.initialising} onClick={() => onOpen('collections')} />
          <StatMetric label="Missed collections" value={d?.missedCollections.missedOpen ?? 0} icon="clock" tone={d?.missedCollections.missedOpen ? 'critical' : 'good'}
            caption={d?.missedCollections.oldestDays != null ? `Oldest ${d.missedCollections.oldestDays} days · ${d.missedCollections.over14Days} over 14` : 'None open'} loading={query.initialising} onClick={() => onOpen('collections')} />
          <StatMetric label="Hazardous chain exceptions" value={d?.openHazardousChainExceptions ?? 0} icon="shield-alert" tone={d?.openHazardousChainExceptions ? 'critical' : 'good'} loading={query.initialising} onClick={() => onOpen('exceptions')} />
          <StatMetric label="Open exceptions" value={d?.openExceptions ?? 0} icon="alert-triangle" tone={d?.overdueExceptions ? 'caution' : 'neutral'} caption={`${d?.overdueExceptions ?? 0} overdue`} loading={query.initialising} onClick={() => onOpen('exceptions')} />
        </MetricCards>
      </PageSection>
    </DataState>
  );
};

const Collections = ({ siteCode, config, refresh, onChanged }: { siteCode: string; config: Configuration; refresh: number; onChanged: () => void }) => {
  const [openId, setOpenId] = useState<string>();
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'collections', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => wasteApi.collections({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const streams = useMemo(() => new Map(config.streams.map((s) => [s.id, s])), [config]);
  const points = useMemo(() => new Map(config.points.map((p) => [p.id, p])), [config]);
  const columns = useMemo<TableColumn<WasteCollection>[]>(() => [
    { id: 'collection', header: 'Collection', width: 260, cell: ({ row }) => <CellStack primary={streams.get(row.streamId)?.name ?? row.reference} secondary={`${row.reference} · ${points.get(row.pointId)?.name ?? ''}`} /> },
    { id: 'scheduled', header: 'Scheduled', width: 120, cell: ({ row }) => formatDate(row.scheduledFor) },
    { id: 'quantity', header: 'Quantity', width: 190, hideBelowLg: true, cell: ({ row }) => (
      <span className="inline-flex flex-wrap items-center gap-1.5">{quantityText(row)}{row.quantityBasis === 'ESTIMATED' && <WasteBadge value="ESTIMATED" />}</span>
    ) },
    { id: 'hazard', header: 'Hazard', width: 110, hideBelowLg: true, cell: ({ row }) => (row.hazardous ? <WasteBadge value="HAZARDOUS" label="Hazardous" /> : '-') },
    { id: 'status', header: 'Status', width: 200, align: 'right', cell: ({ row }) => <WasteBadge value={row.status} /> },
  ], [streams, points]);
  return (
    <PageSection>
      <Panel title="Collections" subtitle="Newest scheduled first. Open one for its chain of custody, evidence and exceptions.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="collections" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => setOpenId(row.id)} emptyTitle="No collections match these filters" emptyDescription="Widen the status, or schedule a collection."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(collectionStatuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
      {openId && <CollectionDialog collectionId={openId} config={config} onClose={() => setOpenId(undefined)} onChanged={onChanged} />}
    </PageSection>
  );
};

const Exceptions = ({ siteCode, refresh, onChanged }: { siteCode: string; refresh: number; onChanged: () => void }) => {
  const [selected, setSelected] = useState<WasteException>();
  const [reporting, setReporting] = useState(false);
  const canManage = permits('FACILITIES_WASTE_MANAGE');
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'exceptions', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => wasteApi.exceptions({ siteCode, status: status || undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const today = new Date().toISOString().slice(0, 10);
  const columns = useMemo<TableColumn<WasteException>[]>(() => [
    { id: 'exception', header: 'Exception', width: 280, cell: ({ row }) => <CellStack primary={humanise(row.exceptionType)} secondary={`${row.reference} · ${row.description}`} /> },
    { id: 'owner', header: 'Owner', width: 150, hideBelowLg: true, cell: ({ row }) => row.ownerReference ?? 'Not assigned' },
    { id: 'due', header: 'Due', width: 120, cell: ({ row }) => formatDate(row.dueOn) },
    { id: 'links', header: 'S153 / S163', width: 170, hideBelowLg: true, cell: ({ row }) => `${row.workOrderNumber ?? (row.workOrderState === 'PENDING_MANUAL' ? 'WO pending' : '-')} / ${row.incidentState === 'NOT_REQUIRED' ? '-' : row.incidentState === 'LINKED' ? row.incidentReference : 'pending'}` },
    { id: 'status', header: 'Status', width: 140, align: 'right', cell: ({ row }) => <WasteBadge value={row.status} label={row.status !== 'RESOLVED' && row.dueOn < today ? 'Overdue' : undefined} /> },
  ], [today]);
  return (
    <PageSection>
      <Panel title="Exceptions" subtitle="Raised for missed collections, contamination, missing certificates or receiving evidence, spills and unapproved carriers or destinations." actions={canManage && <Button size="sm" variant="outline" onClick={() => setReporting(true)}>Report exception</Button>}>
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="exceptions" framed={false} columns={columns} rows={query.data?.items ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={setSelected} emptyTitle="No exceptions" emptyDescription="Exceptions appear here when a collection is missed, contaminated or blocked."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(exceptionStatuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
      {selected && <ExceptionPanel exception={selected} onClose={() => setSelected(undefined)} onChanged={onChanged} />}
      {reporting && <ExceptionDialog siteCode={siteCode} onClose={() => setReporting(false)} onDone={() => { setReporting(false); onChanged(); }} />}
    </PageSection>
  );
};

type SetupDialog = 'stream' | 'point' | 'carrier' | 'destination';

const Setup = ({ siteCode, config, loading, error, onChanged }: { siteCode: string; config: Configuration; loading: boolean; error: ReturnType<typeof useApiQuery>['error']; onChanged: () => void }) => {
  const notifier = useNotifier();
  const [dialog, setDialog] = useState<SetupDialog>();
  const canManage = permits('FACILITIES_WASTE_MANAGE');
  const toggle = async (write: () => Promise<unknown>) => {
    try {
      await write();
      notifier.notifySuccess('Approval changed');
      onChanged();
    } catch (cause) {
      notifier.notifyError(cause);
    }
  };
  const add = (kind: SetupDialog, label: string) => canManage && <Button size="sm" variant="outline" onClick={() => setDialog(kind)}>{label}</Button>;
  const list = (rows: Array<{ id: string; primary: string; secondary: string; status?: string; action?: React.ReactNode }>, empty: string) => (
    rows.length === 0 ? <p className="text-theme-sm text-gray-600">{empty}</p> : (
      <ul className="divide-y divide-border rounded-lg border border-border">
        {rows.map((row) => (
          <li key={row.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <div className="min-w-0"><p className="text-sm font-medium">{row.primary}</p><p className="text-theme-xs text-gray-600">{row.secondary}</p></div>
            <div className="flex items-center gap-2">{row.status && <WasteBadge value={row.status} />}{row.action}</div>
          </li>
        ))}
      </ul>
    )
  );
  const approval = (item: WasteCarrier | WasteDestination, set: (next: 'APPROVED' | 'SUSPENDED') => Promise<unknown>) => canManage && (
    <Button size="sm" variant="outline" onClick={() => void toggle(() => set(item.status === 'APPROVED' ? 'SUSPENDED' : 'APPROVED'))}>{item.status === 'APPROVED' ? 'Suspend' : 'Reinstate'}</Button>
  );
  return (
    <DataState loading={loading} error={error} onRetry={onChanged}>
      <PageSection>
        <Panel title="Waste streams" subtitle="Organisation-wide. Hazardous streams need an approved carrier and destination." actions={add('stream', 'Add stream')}>
          {list(config.streams.map((s) => ({ id: s.id, primary: `${s.name} (${s.code})`, secondary: `${humanise(s.category)}${s.hazardous ? ' · hazardous' : ''}${s.diverted ? ' · counts as diverted' : ''}`, status: s.active ? undefined : 'CANCELLED' })), 'No streams configured yet.')}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Collection points" subtitle={`At ${siteCode}.`} actions={add('point', 'Add point')}>
          {list(config.points.map((p) => ({ id: p.id, primary: `${p.name} (${p.code})`, secondary: p.containerDescription ?? 'No containers described' })), 'No collection points at this site yet.')}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Approved carriers" subtitle="Checked when waste is scheduled and again when it is handed over." actions={add('carrier', 'Add carrier')}>
          {list(config.carriers.map((c) => ({ id: c.id, primary: `${c.name} (${c.code})`, secondary: `Licence ${c.licenceReference}, expires ${formatDate(c.licenceExpiresOn)}${c.hazardousApproved ? ' · hazardous approved' : ''}`, status: c.status, action: approval(c, (next) => wasteApi.setCarrierStatus(c, next)) })), 'No carriers yet.')}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Approved destinations" subtitle="Recyclers, treatment plants, composters and landfill." actions={add('destination', 'Add destination')}>
          {list(config.destinations.map((d) => ({ id: d.id, primary: `${d.name} (${d.code})`, secondary: `${humanise(d.destinationType)} · permit ${d.permitReference}, expires ${formatDate(d.permitExpiresOn)}${d.acceptsHazardous ? ' · accepts hazardous' : ''}`, status: d.status, action: approval(d, (next) => wasteApi.setDestinationStatus(d, next)) })), 'No destinations yet.')}
        </Panel>
      </PageSection>
      <PageSection>
        <Panel title="Units" subtitle="Quantities are converted to kilograms with these factors; the original entry is always kept.">
          {list(config.units.map((u) => ({ id: u.code, primary: `${u.name} (${u.code})`, secondary: `${u.kilogramsPerUnit} kg per unit` })), 'No units.')}
        </Panel>
      </PageSection>
      {dialog === 'stream' && <StreamDialog onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {dialog === 'point' && <PointDialog siteCode={siteCode} onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {dialog === 'carrier' && <CarrierDialog onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
      {dialog === 'destination' && <DestinationDialog onClose={() => setDialog(undefined)} onDone={() => { setDialog(undefined); onChanged(); }} />}
    </DataState>
  );
};

const ReportView = ({ siteCode, refresh }: { siteCode: string; refresh: number }) => {
  const today = new Date().toISOString().slice(0, 10);
  const [from, setFrom] = useState(() => new Date(Date.now() - 89 * 86_400_000).toISOString().slice(0, 10));
  const [to, setTo] = useState(today);
  const query = useApiQuery((signal) => wasteApi.report(siteCode, from, to, signal), [siteCode, from, to, refresh]);
  const report = query.data;
  return (
    <PageSection>
      <Panel title="Sustainability report" subtitle="Worked out from collection records. Every line names the collections behind it, and the rules are stated.">
        <div className="mb-4 grid max-w-md grid-cols-2 gap-4">
          <DateField label="From" value={from} onChange={setFrom} />
          <DateField label="To" value={to} onChange={setTo} />
        </div>
        <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
          {report && (
            <div className="space-y-4">
              <MetricCards>
                <StatMetric label="Measured" value={kg(report.measuredKg)} icon="check-circle" tone="good" caption={`${report.collections - report.estimatedCollections} collections`} />
                <StatMetric label="Estimated" value={kg(report.estimatedKg)} icon="info" tone={report.estimatedKg ? 'caution' : 'neutral'} caption={`${report.estimatedCollections} collections · not in any percentage`} />
                <StatMetric label="Diversion" value={percent(report.diversionRatePercent)} icon="refresh" tone="good" caption="Measured kilograms only" />
              </MetricCards>
              <div className="overflow-x-auto rounded-lg border border-border">
                <table className="w-full text-left text-sm">
                  <thead className="bg-surface-muted/40 text-theme-xs uppercase text-gray-600">
                    <tr><th className="px-4 py-2">Stream</th><th className="px-4 py-2">Measured</th><th className="px-4 py-2">Estimated</th><th className="px-4 py-2">Diverted (measured)</th><th className="px-4 py-2">Source collections</th></tr>
                  </thead>
                  <tbody>
                    {report.lines.length === 0 && <tr><td className="px-4 py-6 text-gray-600" colSpan={5}>No collections were carried out in this period.</td></tr>}
                    {report.lines.map((line) => (
                      <tr key={line.streamCode} className="border-t border-border align-top">
                        <td className="px-4 py-2 font-medium">{line.streamName}{line.hazardous && <> <WasteBadge value="HAZARDOUS" label="Hazardous" /></>}</td>
                        <td className="px-4 py-2">{kg(line.measuredKg)}</td>
                        <td className="px-4 py-2">{kg(line.estimatedKg)}{line.estimatedCollections > 0 && <> <WasteBadge value="ESTIMATED" /></>}</td>
                        <td className="px-4 py-2">{kg(line.divertedMeasuredKg)}</td>
                        <td className="px-4 py-2 font-mono text-theme-xs">{line.sourceReferences.join(', ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Banner variant="info" heading="How estimates are handled" subtext={report.estimationRule} />
              <Banner variant="info" heading="How diversion is calculated" subtext={report.diversionRule} />
            </div>
          )}
        </DataState>
      </Panel>
    </PageSection>
  );
};

export default WastePage;

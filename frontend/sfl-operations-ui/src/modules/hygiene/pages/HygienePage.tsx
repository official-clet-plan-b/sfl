import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus, RefreshCw } from 'lucide-react';
import { Banner, Button, Dropdown, MetricCards, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import ExportButton from 'shared/components/ExportButton';
import SiteSelect, { defaultSite, sflSites } from 'shared/components/SiteSelect';
import { formatDate, formatDateTime } from 'shared/components/format';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { facilitiesPaths } from 'shared/layout/navigation';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import { hygieneApi, type ControlRow, type HygieneEscalation, type HygieneFinding } from '../api/hygieneApi';
import ControlDialog from '../components/ControlDialog';
import FindingDialog from '../components/FindingDialog';
import { ControlFormDialog } from '../components/HygieneDialogs';
import { controlStatuses, controlTypes, findingStatuses, HygieneBadge, options, severities } from '../components/hygieneUi';

type View = 'overview' | 'controls' | 'findings' | 'escalations';
const views: Array<{ value: View; label: string }> = [
  { value: 'overview', label: 'Overview' },
  { value: 'controls', label: 'Controls' },
  { value: 'findings', label: 'Findings' },
  { value: 'escalations', label: 'Escalations' },
];
const hygienePath = (view: View) => (view === 'overview' ? '/facilities/hygiene' : `/facilities/hygiene/${view}`);

/**
 * S170 Hygiene & Pest-Control Audit Tracker. One site at a time, in four views: the figures that say
 * whether the programme is being kept, the scheduled controls, the findings they produced, and the
 * escalations raised when something was overdue or critical.
 *
 * Write controls appear only for roles the service lets write; a role that can read still sees every
 * register, and the evidence behind a finding only if it holds the evidence permission.
 */
const HygienePage = () => {
  const navigate = useNavigate();
  const { view: requested } = useParams<{ view?: string }>();
  const view = (views.find((candidate) => candidate.value === requested)?.value ?? 'overview') as View;
  const [siteCode, setSiteCode] = useState(defaultSite || sflSites()[0] || '');
  const [refresh, setRefresh] = useState(0);
  const [scheduling, setScheduling] = useState(false);
  const canManage = permits('FACILITIES_HYGIENE_MANAGE');
  const bump = () => setRefresh((value) => value + 1);

  return (
    <>
      <PageHeading
        title="Hygiene & pest control"
        subtitle="Scheduled audits, pest-control visits and statutory checks - and what they find, who is fixing it and what proves it."
        crumbs={[{ label: 'Facilities', to: facilitiesPaths.dashboard }, { label: 'Hygiene & pest control' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <ExportButton path="/api/v1/facilities/hygiene/exports/findings" siteCode={siteCode} />
            <Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
            {canManage && <Button variant="primary" onClick={() => setScheduling(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Schedule control</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(next) => navigate(hygienePath(next as View))}>
          <TabsList aria-label="Hygiene views">
            {views.map((candidate) => <TabsTrigger key={candidate.value} value={candidate.value}>{candidate.label}</TabsTrigger>)}
          </TabsList>
        </Tabs>
      </PageSection>
      {!siteCode ? (
        <PageSection><Banner variant="info" heading="Choose a site" subtext="Hygiene records are kept per site." /></PageSection>
      ) : (
        <>
          {view === 'overview' && <Overview siteCode={siteCode} refresh={refresh} onOpen={(next) => navigate(hygienePath(next))} />}
          {view === 'controls' && <Controls siteCode={siteCode} refresh={refresh} onChanged={bump} />}
          {view === 'findings' && <Findings siteCode={siteCode} refresh={refresh} onChanged={bump} />}
          {view === 'escalations' && <Escalations siteCode={siteCode} refresh={refresh} onChanged={bump} />}
        </>
      )}
      {scheduling && <ControlFormDialog siteCode={siteCode} onClose={() => setScheduling(false)} onDone={() => { setScheduling(false); bump(); }} />}
    </>
  );
};

const Overview = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (view: View) => void }) => {
  const query = useApiQuery((signal) => hygieneApi.dashboard(siteCode, signal), [siteCode, refresh]);
  const kpis = query.data?.kpis;
  const rate = query.data?.completionRatePercent;
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Controls kept" value={rate == null ? 'No controls due' : `${rate}%`} icon="check-circle"
            caption={kpis ? `${kpis.controlsCompleted} of ${kpis.controlsDue} due in the last ${query.data?.periodDays} days` : undefined} loading={query.initialising} onClick={() => onOpen('controls')} />
          <StatMetric label="Overdue controls" value={kpis?.overdueControls ?? 0} icon="clock" tone={kpis?.overdueControls ? 'critical' : 'good'} loading={query.initialising} onClick={() => onOpen('controls')} />
          <StatMetric label="Open findings" value={kpis?.openFindings ?? 0} icon="alert-triangle" caption={`${kpis?.overdueFindings ?? 0} past their target date`} tone={kpis?.overdueFindings ? 'caution' : 'neutral'} loading={query.initialising} onClick={() => onOpen('findings')} />
          <StatMetric label="Open critical" value={kpis?.openCriticalFindings ?? 0} icon="shield-alert" tone={kpis?.openCriticalFindings ? 'critical' : 'good'} loading={query.initialising} onClick={() => onOpen('findings')} />
          <StatMetric label="Overdue actions" value={kpis?.overdueActions ?? 0} icon="clipboard-list" tone={kpis?.overdueActions ? 'caution' : 'good'} loading={query.initialising} />
          <StatMetric label="Time to close critical" value={kpis?.meanHoursToCloseCritical == null ? '-' : `${Math.round(kpis.meanHoursToCloseCritical)} h`} icon="gauge" caption="Mean, raised to closed" loading={query.initialising} />
          <StatMetric label="Open escalations" value={kpis?.openEscalations ?? 0} icon="bell" tone={kpis?.openEscalations ? 'caution' : 'good'} loading={query.initialising} onClick={() => onOpen('escalations')} />
        </MetricCards>
      </PageSection>
    </DataState>
  );
};

const Controls = ({ siteCode, refresh, onChanged }: { siteCode: string; refresh: number; onChanged: () => void }) => {
  const [openId, setOpenId] = useState<string>();
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'controls', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const type = filters.controlType ?? '';
  const [statusField, setStatusField] = useState(status);
  const [typeField, setTypeField] = useState(type);
  const overdueOnly = status === 'OVERDUE';
  const query = useApiQuery(
    (signal) => hygieneApi.controls({ siteCode, status: overdueOnly ? undefined : status || undefined, controlType: type || undefined, overdueOnly, page: page - 1, size: pageSize }, signal),
    [siteCode, status, type, page, pageSize, refresh],
  );
  const columns = useMemo<TableColumn<ControlRow>[]>(() => [
    { id: 'control', header: 'Control', width: 320, cell: ({ row }) => <CellStack primary={row.control.title} secondary={`${row.control.reference}${row.control.locationLabel ? ` · ${row.control.locationLabel}` : ''}`} /> },
    { id: 'type', header: 'Type', width: 150, hideBelowLg: true, cell: ({ row }) => humanise(row.control.controlType) },
    { id: 'owner', header: 'Owner', width: 150, hideBelowLg: true, cell: ({ row }) => row.control.ownerReference },
    { id: 'frequency', header: 'Frequency', width: 120, hideBelowLg: true, cell: ({ row }) => humanise(row.control.frequency) },
    { id: 'due', header: 'Due', width: 120, cell: ({ row }) => formatDate(row.control.dueOn) },
    { id: 'status', header: 'Status', width: 130, align: 'right', cell: ({ row }) => <HygieneBadge value={row.effectiveStatus} /> },
  ], []);
  return (
    <PageSection>
      <Panel title="Controls" subtitle="Soonest due first. Open one to start, complete, record a finding or confirm the provider.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix="controls"
            framed={false}
            columns={columns}
            rows={query.data?.items ?? []}
            rowKey={(row) => row.control.id}
            loading={query.initialising}
            onRowClick={(row) => setOpenId(row.control.id)}
            emptyTitle="No controls match these filters"
            emptyDescription="Widen the filters, or schedule a control."
            totalItems={query.data?.totalElements ?? 0}
            size={pageSize}
            filterCount={2}
            filters={<>
              <Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={[...options(controlStatuses), { value: 'OVERDUE', label: 'Overdue' }]} placeholder="All statuses" clearable />
              <Dropdown name="controlType" aria-label="Filter by type" value={typeField || null} onValueChange={(next) => setTypeField(next ?? '')} options={options(controlTypes)} placeholder="All types" clearable />
            </>}
          />
        </DataState>
      </Panel>
      {openId && <ControlDialog controlId={openId} onClose={() => setOpenId(undefined)} onChanged={onChanged} />}
    </PageSection>
  );
};

const Findings = ({ siteCode, refresh, onChanged }: { siteCode: string; refresh: number; onChanged: () => void }) => {
  const [openId, setOpenId] = useState<string>();
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'findings', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const severity = filters.severity ?? '';
  const [statusField, setStatusField] = useState(status);
  const [severityField, setSeverityField] = useState(severity);
  const overdueOnly = status === 'OVERDUE';
  const query = useApiQuery(
    (signal) => hygieneApi.findings({ siteCode, status: overdueOnly ? undefined : status || undefined, severity: severity || undefined, overdueOnly, page: page - 1, size: pageSize }, signal),
    [siteCode, status, severity, page, pageSize, refresh],
  );
  const columns = useMemo<TableColumn<HygieneFinding>[]>(() => [
    { id: 'finding', header: 'Finding', width: 320, cell: ({ row }) => <CellStack primary={row.title} secondary={`${row.reference} · ${humanise(row.category)}`} /> },
    { id: 'severity', header: 'Severity', width: 110, cell: ({ row }) => <HygieneBadge value={row.severity} /> },
    { id: 'owner', header: 'Owner', width: 150, hideBelowLg: true, cell: ({ row }) => row.ownerReference ?? 'Not assigned' },
    { id: 'target', header: 'Target', width: 120, hideBelowLg: true, cell: ({ row }) => (row.targetDate ? formatDate(row.targetDate) : '-') },
    { id: 'links', header: 'Work order', width: 130, hideBelowLg: true, cell: ({ row }) => (row.workOrderNumber ? row.workOrderNumber : row.workOrderState === 'PENDING_MANUAL' ? 'Pending' : '-') },
    { id: 'status', header: 'Status', width: 170, align: 'right', cell: ({ row }) => <HygieneBadge value={row.status !== 'CLOSED' && row.targetDate && row.targetDate < new Date().toISOString().slice(0, 10) ? 'OVERDUE' : row.status} /> },
  ], []);
  return (
    <PageSection>
      <Panel title="Findings" subtitle="Most severe first. Open one for its actions, evidence and closure.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix="findings"
            framed={false}
            columns={columns}
            rows={query.data?.items ?? []}
            rowKey={(row) => row.id}
            loading={query.initialising}
            onRowClick={(row) => setOpenId(row.id)}
            emptyTitle="No findings match these filters"
            emptyDescription="Findings are recorded from a control."
            totalItems={query.data?.totalElements ?? 0}
            size={pageSize}
            filterCount={2}
            filters={<>
              <Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={[...options(findingStatuses), { value: 'OVERDUE', label: 'Overdue' }]} placeholder="All statuses" clearable />
              <Dropdown name="severity" aria-label="Filter by severity" value={severityField || null} onValueChange={(next) => setSeverityField(next ?? '')} options={options(severities)} placeholder="All severities" clearable />
            </>}
          />
        </DataState>
      </Panel>
      {openId && <FindingDialog findingId={openId} onClose={() => setOpenId(undefined)} onChanged={onChanged} />}
    </PageSection>
  );
};

const Escalations = ({ siteCode, refresh, onChanged }: { siteCode: string; refresh: number; onChanged: () => void }) => {
  const notifier = useNotifier();
  const canManage = permits('FACILITIES_HYGIENE_MANAGE');
  const { page, pageSize } = useTableState({ paramPrefix: 'escalations', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const query = useApiQuery((signal) => hygieneApi.escalations(siteCode, false, page - 1, pageSize, signal), [siteCode, page, pageSize, refresh]);
  const acknowledge = async (id: string) => {
    try {
      await hygieneApi.acknowledge(id);
      notifier.notifySuccess('Escalation acknowledged');
      query.refetch();
      onChanged();
    } catch (cause) {
      notifier.notifyError(cause);
    }
  };
  const columns = useMemo<TableColumn<HygieneEscalation>[]>(() => [
    { id: 'subject', header: 'Raised for', width: 200, cell: ({ row }) => <CellStack primary={row.subjectReference} secondary={humanise(row.subjectType)} /> },
    { id: 'level', header: 'Escalated to', width: 130, cell: ({ row }) => <HygieneBadge value={row.level} /> },
    { id: 'reason', header: 'Reason', width: 220, cell: ({ row }) => <CellStack primary={humanise(row.reason)} secondary={row.detail ?? undefined} /> },
    { id: 'raised', header: 'Raised', width: 170, hideBelowLg: true, cell: ({ row }) => formatDateTime(row.raisedAt) },
    {
      id: 'state', header: 'State', width: 190, align: 'right',
      cell: ({ row }) => row.acknowledgedAt
        ? <span className="text-theme-xs text-gray-600">Acknowledged by {row.acknowledgedBy}</span>
        : canManage ? <Button size="sm" variant="outline" onClick={() => void acknowledge(row.id)}>Acknowledge</Button> : <HygieneBadge value="PENDING_MANUAL" label="Awaiting acknowledgement" />,
    },
  // eslint-disable-next-line react-hooks/exhaustive-deps
  ], [canManage]);
  return (
    <PageSection>
      <Panel title="Escalations" subtitle="Raised when a control is overdue or missed, a provider has not confirmed, or a finding is critical or repeats.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix="escalations"
            framed={false}
            columns={columns}
            rows={query.data?.items ?? []}
            rowKey={(row) => row.id}
            loading={query.initialising}
            emptyTitle="Nothing has been escalated"
            emptyDescription="Escalations appear here when a control is overdue or a finding is critical."
            totalItems={query.data?.totalElements ?? 0}
            size={pageSize}
          />
        </DataState>
      </Panel>
    </PageSection>
  );
};

export default HygienePage;

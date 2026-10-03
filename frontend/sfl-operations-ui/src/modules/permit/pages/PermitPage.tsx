import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { Plus, RefreshCw } from 'lucide-react';
import { Banner, Button, Dropdown, MetricCards, PageSection, Tabs, TabsList, TabsTrigger, useTableState, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import ExportButton from 'shared/components/ExportButton';
import SiteSelect, { defaultSite, sflSites } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { humanise } from 'modules/fleet/api/enums';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack, DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import { permitApi, type Permit, type PermitType } from '../api/permitApi';
import { RequestDialog, TypeDialog } from '../components/PermitDialogs';
import PermitDetailDialog from '../components/PermitDetailDialog';
import { PermitBadge, options, statuses } from '../components/permitUi';

type View = 'dashboard' | 'register' | 'types' | 'analytics';

const permitPath = (view: View) => (view === 'dashboard' ? '/safetysecurity/permits' : `/safetysecurity/permits/${view}`);

/**
 * S164 Permit-to-Work, one site at a time (or every site the role is scoped to): what is open and where, what is nearing expiry or overdue,
 * the isolation standing per zone, the competence exceptions, and the register from which any permit opens to its full record. Controls
 * appear only for roles the service lets act.
 */
const PermitPage = () => {
  const navigate = useNavigate();
  const { view: requested } = useParams<{ view?: string }>();
  const canAnalyse = permits('PERMIT_ANALYTICS_READ');
  const canConfigure = permits('PERMIT_CONFIGURE');
  const views = useMemo(() => ([
    { value: 'dashboard' as View, label: 'Dashboard' },
    { value: 'register' as View, label: 'Register' },
    ...(canConfigure ? [{ value: 'types' as View, label: 'Permit types' }] : []),
    ...(canAnalyse ? [{ value: 'analytics' as View, label: 'Analytics' }] : []),
  ]), [canAnalyse, canConfigure]);
  const view = (views.find((candidate) => candidate.value === requested)?.value ?? 'dashboard') as View;
  const [siteCode, setSiteCode] = useState(defaultSite || sflSites()[0] || '');
  const [refresh, setRefresh] = useState(0);
  const [requesting, setRequesting] = useState(false);
  const [openId, setOpenId] = useState<string>();
  const canRequest = permits('PERMIT_REQUEST');
  const bump = () => setRefresh((value) => value + 1);

  return (
    <>
      <PageHeading
        title="Permit to work"
        subtitle="Authorise high-risk work only against a current risk assessment, with isolations verified, and see it through to a closed, evidenced permit."
        crumbs={[{ label: 'Safety & security' }, { label: 'Permit to work' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
            <ExportButton path="/api/v1/permits/export" siteCode={siteCode} label="Export evidence" service="safetySecurity" />
            <Button variant="outline" onClick={bump}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
            {canRequest && <Button variant="primary" onClick={() => setRequesting(true)}><Plus size={14} strokeWidth={1.5} aria-hidden /> Request permit</Button>}
          </>
        }
      />
      <PageSection>
        <Tabs variant="pill" value={view} onValueChange={(next) => navigate(permitPath(next as View))}>
          <TabsList aria-label="Permit views">
            {views.map((candidate) => <TabsTrigger key={candidate.value} value={candidate.value}>{candidate.label}</TabsTrigger>)}
          </TabsList>
        </Tabs>
      </PageSection>
      {!siteCode && view !== 'types' ? (
        <PageSection><Banner variant="info" heading="Choose a site" subtext="Permits are issued per site." /></PageSection>
      ) : (
        <>
          {view === 'dashboard' && <DashboardView siteCode={siteCode} refresh={refresh} onOpen={setOpenId} onGo={(next) => navigate(permitPath(next))} />}
          {view === 'register' && <RegisterView siteCode={siteCode} refresh={refresh} onOpen={setOpenId} />}
          {view === 'types' && <TypesView refresh={refresh} onChanged={bump} />}
          {view === 'analytics' && <AnalyticsView siteCode={siteCode} refresh={refresh} />}
        </>
      )}
      {requesting && <RequestDialog siteCode={siteCode} onClose={() => setRequesting(false)} onDone={() => { setRequesting(false); bump(); }} />}
      {openId && <PermitDetailDialog permitId={openId} onClose={() => setOpenId(undefined)} onChanged={bump} />}
    </>
  );
};

const PermitList = ({ items, empty, onOpen }: { items: Permit[]; empty: string; onOpen: (id: string) => void }) =>
  items.length === 0 ? <p className="text-theme-sm text-gray-600">{empty}</p> : (
    <ul className="divide-y divide-border rounded-lg border border-border">
      {items.map((p) => (
        <li key={p.id}>
          <button type="button" className="flex w-full flex-wrap items-center justify-between gap-2 px-4 py-3 text-left text-sm hover:bg-gray-50" onClick={() => onOpen(p.id)}>
            <span><span className="font-medium">{p.title}</span> <span className="text-gray-600">· {p.reference} · {p.locationCode}</span></span>
            <span className="text-theme-xs text-gray-600">ends {formatDateTime(p.endsAt)}</span>
          </button>
        </li>
      ))}
    </ul>
  );

const DashboardView = ({ siteCode, refresh, onOpen, onGo }: { siteCode: string; refresh: number; onOpen: (id: string) => void; onGo: (view: View) => void }) => {
  const query = useApiQuery((signal) => permitApi.dashboard(siteCode, signal), [siteCode, refresh]);
  const d = query.data;
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Open permits" value={d?.open ?? 0} icon="document" caption="Issued and not yet closed" loading={query.initialising} onClick={() => onGo('register')} />
          <StatMetric label="Awaiting isolation verification" value={d?.awaitingVerification ?? 0} icon="shield-check" tone={d?.awaitingVerification ? 'caution' : 'good'} loading={query.initialising} />
          <StatMetric label="Awaiting approval" value={d?.awaitingApproval ?? 0} icon="clock" tone={d?.awaitingApproval ? 'caution' : 'good'} loading={query.initialising} />
          <StatMetric label="Nearing expiry" value={d?.nearingExpiry.length ?? 0} icon="calendar" tone={d?.nearingExpiry.length ? 'caution' : 'good'} caption={d ? `Within ${d.warnMinutes} minutes` : undefined} loading={query.initialising} />
          <StatMetric label="Overdue close-outs" value={d?.overdueCloseOuts.length ?? 0} icon="alert-triangle" tone={d?.overdueCloseOuts.length ? 'critical' : 'good'} caption="Past validity, not closed" loading={query.initialising} />
          <StatMetric label="Competence exceptions" value={d?.competencyExceptions.length ?? 0} icon="bell" tone={d?.competencyExceptions.length ? 'caution' : 'good'} caption="Workers without a current check" loading={query.initialising} />
          <StatMetric label="Flagged for review" value={d?.openFlags.length ?? 0} icon="bell" tone={d?.openFlags.length ? 'critical' : 'good'} caption="Incident or emergency" loading={query.initialising} />
        </MetricCards>
      </PageSection>
      {d && (
        <>
          <PageSection><Panel title="Overdue close-outs" subtitle="Issued permits past their validity window without a close-out. The authoriser has been told."><PermitList items={d.overdueCloseOuts} empty="No permit is overdue." onOpen={onOpen} /></Panel></PageSection>
          <PageSection><Panel title="Nearing expiry" subtitle="Still open, and ending soon."><PermitList items={d.nearingExpiry} empty="No permit is about to expire." onOpen={onOpen} /></Panel></PageSection>
          <PageSection>
            <Panel title="Open permits by type and risk level">
              {d.openByTypeAndRisk.length === 0 ? <p className="text-theme-sm text-gray-600">No open permits.</p> : (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {d.openByTypeAndRisk.map((c) => (
                    <li key={`${c.typeCode}-${c.riskLevel}`} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
                      <span className="flex items-center gap-2"><span className="font-medium">{c.typeName}</span><PermitBadge value={c.riskLevel} /></span>
                      <span className="text-theme-xs text-gray-600">{c.open} open · {c.active} active · {c.suspended} suspended</span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </PageSection>
          <PageSection>
            <Panel title="Isolation status per zone" subtitle="Across open permits: isolations still to verify, verified and in place, and removed.">
              {d.isolationByZone.length === 0 ? <p className="text-theme-sm text-gray-600">Nothing to show.</p> : (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {d.isolationByZone.map((z) => <li key={z.zone} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm"><span className="font-medium">{z.zone} <span className="font-normal text-gray-600">· {z.permits} permit(s)</span></span><span className="text-theme-xs text-gray-600">{z.required} to verify · {z.verified} in place · {z.removed} removed</span></li>)}
                </ul>
              )}
            </Panel>
          </PageSection>
          <PageSection>
            <Panel title="Competence exceptions" subtitle="A worker named on a permit who has no current, competent check for what the work needs. Approval is refused until it is recorded.">
              {d.competencyExceptions.length === 0 ? <p className="text-theme-sm text-gray-600">No exceptions.</p> : (
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {d.competencyExceptions.map((e, i) => (
                    <li key={`${e.permitId}-${i}`}><button type="button" className="flex w-full flex-wrap items-center justify-between gap-2 px-4 py-3 text-left text-sm hover:bg-gray-50" onClick={() => onOpen(e.permitId)}>
                      <span><span className="font-medium">{e.worker}</span> <span className="text-gray-600">· {e.permitReference}{e.contractor ? ` · ${e.contractor}` : ''}</span></span>
                      <span className="text-theme-xs text-gray-600">{humanise(e.competency)}: {humanise(e.reason)}</span>
                    </button></li>
                  ))}
                </ul>
              )}
            </Panel>
          </PageSection>
          {d.openFlags.length > 0 && (
            <PageSection>
              <Panel title="Flagged for review" subtitle="A permit touched by an incident or an emergency in its zone. Open it to review, and suspend if the work should stop.">
                <ul className="divide-y divide-border rounded-lg border border-border">
                  {d.openFlags.map((f) => <li key={f.id}><button type="button" className="flex w-full flex-wrap items-center justify-between gap-2 px-4 py-3 text-left text-sm hover:bg-gray-50" onClick={() => onOpen(f.permitId)}><span>{f.flagType === 'INCIDENT' ? 'Incident' : 'Emergency'} {f.reference}</span><span className="text-theme-xs text-gray-600">{formatDateTime(f.raisedAt)}</span></button></li>)}
                </ul>
              </Panel>
            </PageSection>
          )}
        </>
      )}
    </DataState>
  );
};

const RegisterView = ({ siteCode, refresh, onOpen }: { siteCode: string; refresh: number; onOpen: (id: string) => void }) => {
  const { page, pageSize, filters } = useTableState({ paramPrefix: 'permits', defaultPageSize: DEFAULT_PAGE_SIZE, pageSizeOptions: PAGE_SIZE_OPTIONS });
  const status = filters.status ?? '';
  const [statusField, setStatusField] = useState(status);
  const query = useApiQuery((signal) => permitApi.search({ siteCode, status: (status || undefined) as Permit['status'] | undefined, page: page - 1, size: pageSize }, signal), [siteCode, status, page, pageSize, refresh]);
  const columns = useMemo<TableColumn<Permit>[]>(() => [
    { id: 'permit', header: 'Permit', width: 300, cell: ({ row }) => <CellStack primary={row.title} secondary={`${row.reference} · ${humanise(row.workType)}`} /> },
    { id: 'location', header: 'Location', width: 170, hideBelowLg: true, cell: ({ row }) => row.locationCode },
    { id: 'contractor', header: 'Contractor', width: 160, hideBelowLg: true, cell: ({ row }) => row.contractorReference ?? '-' },
    { id: 'window', header: 'Valid', width: 220, hideBelowLg: true, cell: ({ row }) => `${formatDateTime(row.startsAt)} to ${formatDateTime(row.endsAt)}` },
    { id: 'status', header: 'Status', width: 180, align: 'right', cell: ({ row }) => <PermitBadge value={row.status} /> },
  ], []);
  return (
    <PageSection>
      <Panel title="Permits" subtitle="Latest first. Open one for its verification, approvals, close-out and history.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable paramPrefix="permits" framed={false} columns={columns} rows={query.data?.content ?? []} rowKey={(row) => row.id} loading={query.initialising}
            onRowClick={(row) => onOpen(row.id)} emptyTitle="No permits match these filters" emptyDescription="Widen the status, or request a permit."
            totalItems={query.data?.totalElements ?? 0} size={pageSize} filterCount={1}
            filters={<Dropdown name="status" aria-label="Filter by status" value={statusField || null} onValueChange={(next) => setStatusField(next ?? '')} options={options(statuses)} placeholder="All statuses" clearable />} />
        </DataState>
      </Panel>
    </PageSection>
  );
};

const TypesView = ({ refresh, onChanged }: { refresh: number; onChanged: () => void }) => {
  const [dialog, setDialog] = useState<{ existing?: PermitType } | null>(null);
  const query = useApiQuery((signal) => permitApi.types(false, signal), [refresh]);
  return (
    <PageSection>
      <Panel title="Permit types" subtitle="Each type has its own approvals and validity rules. High and critical risk types always need the independent safety sign-off." actions={<Button size="sm" variant="outline" onClick={() => setDialog({})}>Add type</Button>}>
        <DataState loading={query.initialising} error={query.error} onRetry={query.refetch}>
          <ul className="divide-y divide-border rounded-lg border border-border">
            {(query.data ?? []).map((t) => (
              <li key={t.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
                <div>
                  <p className="flex items-center gap-2 font-medium">{t.name} ({t.code}) <PermitBadge value={t.riskLevel} />{!t.active && <PermitBadge value="CANCELLED" label="Not in use" />}</p>
                  <p className="text-theme-xs text-gray-600">{t.twoStage ? 'Two-stage approval' : 'One approval'} · valid up to {t.maxValidityHours} h · {t.requiresIsolation ? 'isolations required' : 'no isolations required'} · {t.riskAssessmentRequired ? `needs a current assessment${t.activityType ? ` (${humanise(t.activityType)})` : ''}` : 'no assessment required'}{t.requiredCompetencies.length ? ` · checks ${t.requiredCompetencies.map(humanise).join(', ')}` : ''}</p>
                </div>
                <Button size="sm" variant="outline" onClick={() => setDialog({ existing: t })}>Edit</Button>
              </li>
            ))}
          </ul>
        </DataState>
      </Panel>
      {dialog && <TypeDialog existing={dialog.existing} onClose={() => setDialog(null)} onDone={() => { setDialog(null); query.refetch(); onChanged(); }} />}
    </PageSection>
  );
};

const AnalyticsView = ({ siteCode, refresh }: { siteCode: string; refresh: number }) => {
  const query = useApiQuery((signal) => permitApi.analytics(siteCode, signal), [siteCode, refresh]);
  const a = query.data;
  const rows = (title: string, data: Array<{ key: string; count: number }> | undefined) => (
    <Panel title={title}>
      {!data || data.length === 0 ? <p className="text-theme-sm text-gray-600">No permits yet.</p> : (
        <ul className="divide-y divide-border rounded-lg border border-border">{data.map((r) => <li key={r.key} className="flex items-center justify-between px-4 py-3 text-sm"><span>{humanise(r.key)}</span><span className="font-medium">{r.count}</span></li>)}</ul>
      )}
    </Panel>
  );
  return (
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Permits" value={a?.permits ?? 0} icon="document" loading={query.initialising} />
          <StatMetric label="Mean time open" value={a?.meanOpenHours == null ? 'No data' : `${a.meanOpenHours.toFixed(1)} h`} icon="clock" caption="Issued to closed" loading={query.initialising} />
          <StatMetric label="Flagged for an incident" value={a?.flaggedForIncident ?? 0} icon="alert-triangle" tone={a?.flaggedForIncident ? 'caution' : 'good'} caption={a ? `${a.incidentCorrelationPercent}% of permits` : undefined} loading={query.initialising} />
        </MetricCards>
      </PageSection>
      <PageSection>{rows('By type', a?.byType)}</PageSection>
      <PageSection>{rows('By contractor', a?.byContractor)}</PageSection>
      <PageSection>{rows('By outcome', a?.byOutcome)}</PageSection>
    </DataState>
  );
};

export default PermitPage;

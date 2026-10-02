import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { RefreshCw } from 'lucide-react';
import { Button, MetricCards, PageSection, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { drillPaths } from 'shared/layout/navigation';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import { SelectField } from 'modules/emergency/components/FormFields';
import { drillApi } from '../api/drillApi';
import type { SiteDrillStats } from '../api/dto';
import { drillTypeLabel } from '../api/enums';
import { participation } from '../api/workflow';
import { ComplianceChip } from '../components/drillChips';

const WINDOWS = [
  { value: '90', label: 'Last 90 days' },
  { value: '365', label: 'Last 12 months' },
  { value: '730', label: 'Last 2 years' },
];

const AGE_LABELS = ['0-30 days', '31-60 days', '61-90 days', 'Over 90 days'];

/**
 * The S175 landing - SRS-SFL-S175-04/-05's oversight view: per-site completion and participation, where each site
 * stands against its required frequencies, and the drill corrective actions still open. Every figure is the
 * service's, from `GET /drills/dashboard`.
 */
const DrillDashboardPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [days, setDays] = useState('365');
  const query = useApiQuery((signal) => drillApi.dashboard(siteCode || undefined, Number(days), signal), [siteCode, days]);
  const data = query.data;
  const totals = (data?.sites ?? []).reduce(
    (sum, s) => ({ executed: sum.executed + s.executed, reviewed: sum.reviewed + s.reviewed, baseline: sum.baseline + s.baselineTotal, checkedIn: sum.checkedIn + s.checkedInTotal, gaps: sum.gaps + s.gapsTotal }),
    { executed: 0, reviewed: 0, baseline: 0, checkedIn: 0, gaps: 0 },
  );
  const gaps = (data?.compliance ?? []).filter((row) => row.standing === 'COMPLIANCE_GAP').length;
  const capa = data?.correctiveActions;

  const columns = useMemo<TableColumn<SiteDrillStats>[]>(() => [
    { id: 'site', header: 'Site', width: 110, cell: ({ row }) => row.siteCode },
    { id: 'planned', header: 'Planned', width: 90, align: 'right', cell: ({ row }) => row.planned },
    { id: 'executed', header: 'Run', width: 80, align: 'right', cell: ({ row }) => row.executed },
    { id: 'reviewed', header: 'Reviewed', width: 90, align: 'right', cell: ({ row }) => row.reviewed },
    { id: 'cancelled', header: 'Cancelled', width: 90, align: 'right', hideBelowLg: true, cell: ({ row }) => row.cancelled },
    { id: 'participation', header: 'Participation', width: 120, align: 'right', cell: ({ row }) => participation(row.checkedInTotal, row.baselineTotal) },
    { id: 'gaps', header: 'Roll-call gaps', width: 120, align: 'right', cell: ({ row }) => row.gapsTotal },
    { id: 'findings', header: 'Findings open / closed', width: 170, align: 'right', hideBelowLg: true, cell: ({ row }) => `${row.findingsOpen} / ${row.findingsClosed}` },
  ], []);

  return (
    <>
      <PageHeading
        title="Crisis & evacuation drills"
        subtitle="Whether drills are run as often as required, whether people turn up, and whether what they reveal gets fixed."
        crumbs={[{ label: 'Safety & security' }, { label: 'Drills' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} className="w-44" />
            <SelectField label="Window" value={days} options={WINDOWS} onChange={setDays} className="w-40" />
            <Button variant="outline" onClick={query.refetch}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
          </>
        }
      />
      <DataState loading={false} error={query.error} onRetry={query.refetch}>
        <PageSection>
          <MetricCards>
            <StatMetric label="Drills run" value={totals.executed} icon="activity" caption={`${totals.reviewed} reviewed`} loading={query.initialising} onClick={() => navigate(drillPaths.register)} />
            <StatMetric label="Participation" value={participation(totals.checkedIn, totals.baseline)} icon="users" caption={`${totals.checkedIn} of ${totals.baseline} on site checked in`} loading={query.initialising} />
            <StatMetric label="Roll-call gaps" value={totals.gaps} icon="alert-triangle" tone={totals.gaps ? 'caution' : 'good'} loading={query.initialising} />
            {permits('DRILL_COMPLIANCE_READ') && <StatMetric label="Compliance gaps" value={gaps} icon="shield-check" tone={gaps ? 'critical' : 'good'} caption="Sites past their required interval" loading={query.initialising} onClick={() => navigate(drillPaths.compliance)} />}
          </MetricCards>
        </PageSection>
        <PageSection>
          <Panel title="By site" subtitle={data ? `Since ${formatDate(data.since)}` : undefined}>
            <RegisterTable paramPrefix="drill-sites" framed={false} columns={columns} rows={data?.sites ?? []} rowKey={(row) => row.siteCode} loading={query.initialising} emptyTitle="No drills in this window" />
          </Panel>
        </PageSection>
        <PageSection>
          <div className="grid gap-5 lg:grid-cols-2">
            <Panel title="Drill corrective actions" subtitle="Also on the HSE dashboard, beside incident actions.">
              <div className="space-y-3">
                {[['Open', capa?.open], ['Overdue', capa?.overdue], ['Verified', capa?.verified], ['Cancelled', capa?.cancelled]].map(([label, value]) => (
                  <div key={label as string} className="flex justify-between border-b border-[var(--clet-border-subtle)] pb-3 last:border-0">
                    <span className={label === 'Overdue' && value ? 'text-[var(--clet-error-text)]' : ''}>{label}</span>
                    <strong className="tabular-nums">{value ?? 0}</strong>
                  </div>
                ))}
                <p className="pt-1 text-theme-xs text-gray-600">Open by age: {AGE_LABELS.map((label, i) => `${label} ${capa?.ageing[i] ?? 0}`).join(' · ')}</p>
              </div>
            </Panel>
            {permits('DRILL_COMPLIANCE_READ') && (
              <Panel title="Most urgent requirements" actions={<Button size="sm" variant="ghost" onClick={() => navigate(drillPaths.compliance)}>All</Button>}>
                {data?.compliance.length ? (
                  <ul className="space-y-3">
                    {data.compliance.slice(0, 6).map((row) => (
                      <li key={row.requirement.id} className="flex items-center justify-between border-b border-[var(--clet-border-subtle)] pb-3 last:border-0">
                        <span>{row.requirement.siteCode} · {drillTypeLabel[row.requirement.drillType]} <span className="text-theme-xs text-gray-600">due {formatDate(row.dueAt)}</span></span>
                        <ComplianceChip standing={row.standing} />
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="text-theme-sm text-gray-600">No frequency requirements are set. Until CLET sets them, no site can show a compliance gap.</p>
                )}
              </Panel>
            )}
          </div>
        </PageSection>
      </DataState>
    </>
  );
};

export default DrillDashboardPage;

import { useState } from 'react';
import { useNavigate } from 'react-router';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { incidentPaths } from 'shared/layout/navigation';
import { incidentApi } from '../api/incidentApi';
import type { IncidentStatus, Severity } from '../api/dto';
import { RefreshCw } from 'lucide-react';
import { Button, MetricCards, PageSection } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import StatMetric from 'modules/emergency/components/StatMetric';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import CorrectiveActionPanel from '../components/CorrectiveActionPanel';

const IncidentDashboardPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const query = useApiQuery((signal) => siteCode ? incidentApi.dashboard(siteCode, signal) : Promise.resolve({ byStatus: {}, bySeverity: {} } as import('../api/dto').IncidentDashboard), [siteCode]);
  const countStatus = (value: IncidentStatus) => query.data?.byStatus[value] ?? 0;
  const countSeverity = (value: Severity) => query.data?.bySeverity[value] ?? 0;
  const open = countStatus('TRIAGE') + countStatus('INVESTIGATING');
  return <>
    <PageHeading
      title="Incident assurance"
      subtitle="Open cases, risk concentration and emergency-rated events for the selected site."
      crumbs={[{ label: 'Safety & security' }, { label: 'Incident dashboard' }]}
      actions={<>
        <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
        <Button variant="outline" onClick={query.refetch}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
      </>}
    />
    <DataState loading={false} error={query.error} onRetry={query.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Open cases" value={open} icon="clipboard-list" tone={open ? 'caution' : 'good'} loading={query.initialising} onClick={() => navigate(incidentPaths.cases)} />
          <StatMetric label="Awaiting triage" value={countStatus('TRIAGE')} icon="clock" tone={countStatus('TRIAGE') ? 'caution' : 'neutral'} loading={query.initialising} onClick={() => navigate(`${incidentPaths.cases}?cases.f_status=TRIAGE`)} />
          <StatMetric label="Under investigation" value={countStatus('INVESTIGATING')} icon="search" loading={query.initialising} onClick={() => navigate(`${incidentPaths.cases}?cases.f_status=INVESTIGATING`)} />
          <StatMetric label="Emergency rated" value={countSeverity('EMERGENCY')} icon="siren" tone={countSeverity('EMERGENCY') ? 'critical' : 'good'} loading={query.initialising} onClick={() => navigate(`${incidentPaths.cases}?cases.f_severity=EMERGENCY`)} />
        </MetricCards>
      </PageSection>
      <PageSection>
        <div className="grid gap-5 lg:grid-cols-2">
          <Panel title="Cases by lifecycle"><div className="space-y-3">{(['TRIAGE', 'INVESTIGATING', 'CLOSED'] as IncidentStatus[]).map((status) => <div key={status} className="flex items-center justify-between border-b border-[var(--clet-border-subtle)] pb-3 last:border-0"><StatusBadge value={status} /><strong className="tabular-nums">{countStatus(status)}</strong></div>)}</div></Panel>
          <Panel title="Cases by severity"><div className="space-y-3">{(['EMERGENCY', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as Severity[]).map((severity) => <div key={severity} className="flex items-center justify-between border-b border-[var(--clet-border-subtle)] pb-3 last:border-0"><StatusBadge value={severity} /><strong className="tabular-nums">{countSeverity(severity)}</strong></div>)}</div></Panel>
        </div>
      </PageSection>
      {siteCode && <PageSection><CorrectiveActionPanel siteCode={siteCode} /></PageSection>}
    </DataState>
  </>;
};
export default IncidentDashboardPage;

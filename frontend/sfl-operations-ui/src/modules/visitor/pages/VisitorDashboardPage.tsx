import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { RefreshCw } from 'lucide-react';
import { Button, MetricCards, PageSection, type TableColumn } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack } from 'modules/emergency/components/RegisterTable';
import StatMetric from 'modules/emergency/components/StatMetric';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { visitorPaths } from 'shared/layout/navigation';
import { visitorApi } from '../api/visitorApi';
import type { VisitorVisit } from '../api/dto';

const VisitorDashboardPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const visits = useApiQuery((signal) => visitorApi.search({ siteCode: siteCode || undefined, page: 0, size: 100, sort: 'expectedArrival,desc' }, signal), [siteCode]);
  const rows = useMemo(() => visits.data?.content ?? [], [visits.data]);
  const count = (status: string) => rows.filter((visit) => visit.status === status).length;
  const active = rows.filter((visit) => ['PRE_REGISTERED', 'CONFIRMED', 'CHECKED_IN'].includes(visit.status));
  const columns = useMemo<TableColumn<VisitorVisit>[]>(() => [
    { id: 'visitor', header: 'Visitor', width: 230, cell: ({ row }) => <CellStack primary={row.visitorName} secondary={row.visitorOrganization ?? row.purpose} /> },
    { id: 'host', header: 'Host', width: 180, cell: ({ row }) => row.hostName ?? row.hostId },
    { id: 'arrival', header: 'Expected arrival', width: 180, cell: ({ row }) => formatDateTime(row.expectedArrival) },
    { id: 'status', header: 'Status', align: 'right', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ], []);
  return <>
    <PageHeading
      title="Visitor operations"
      subtitle="Expected arrivals, approvals and the people currently inside each site."
      crumbs={[{ label: 'Safety & security' }, { label: 'Visitor dashboard' }]}
      actions={<>
        <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} allowEmpty className="w-44" />
        <Button variant="outline" onClick={visits.refetch}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
      </>}
    />
    <DataState loading={false} error={visits.error} onRetry={visits.refetch}>
      <PageSection>
        <MetricCards>
          <StatMetric label="Awaiting decision" value={count('PRE_REGISTERED')} icon="clock" tone={count('PRE_REGISTERED') ? 'caution' : 'neutral'} loading={visits.initialising} onClick={() => navigate(`${visitorPaths.visits}?visits.f_status=PRE_REGISTERED`)} />
          <StatMetric label="Confirmed" value={count('CONFIRMED')} icon="calendar" loading={visits.initialising} onClick={() => navigate(`${visitorPaths.visits}?visits.f_status=CONFIRMED`)} />
          <StatMetric label="On site now" value={count('CHECKED_IN')} icon="users" tone={count('CHECKED_IN') ? 'accent' : 'neutral'} loading={visits.initialising} onClick={() => navigate(visitorPaths.rollCall)} />
          <StatMetric label="Watchlist flags" value={active.filter((visit) => visit.watchlistFlagged).length} icon="alert-triangle" tone={active.some((visit) => visit.watchlistFlagged) ? 'critical' : 'good'} loading={visits.initialising} />
        </MetricCards>
      </PageSection>
      <PageSection>
        <Panel title="Active and upcoming visits" subtitle="The newest 100 records are used for this operational snapshot.">
          <RegisterTable
            paramPrefix="active-visits"
            framed={false}
            columns={columns}
            rows={active.slice(0, 10)}
            rowKey={(row) => row.id}
            loading={visits.initialising}
            onRowClick={(row) => navigate(visitorPaths.visitDetail(row.id))}
            emptyTitle="No active or upcoming visits"
            emptyDescription="Pre-registered, confirmed and checked-in visits at this site will appear here."
          />
        </Panel>
      </PageSection>
    </DataState>
  </>;
};
export default VisitorDashboardPage;

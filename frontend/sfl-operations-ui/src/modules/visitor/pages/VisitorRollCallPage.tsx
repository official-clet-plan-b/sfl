import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { RefreshCw } from 'lucide-react';
import { Button, PageSection, type TableColumn } from '@rfdtech/components';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable, { CellStack } from 'modules/emergency/components/RegisterTable';
import StatusBadge from 'modules/emergency/components/StatusBadge';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { visitorPaths } from 'shared/layout/navigation';
import { visitorApi } from '../api/visitorApi';
import type { VisitorVisit } from '../api/dto';

const VisitorRollCallPage = () => {
  const navigate = useNavigate();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const query = useApiQuery((signal) => siteCode ? visitorApi.rollCall(siteCode, signal) : Promise.resolve([]), [siteCode]);
  const columns = useMemo<TableColumn<VisitorVisit>[]>(() => [
    { id: 'visitor', header: 'Visitor', width: 240, cell: ({ row }) => <CellStack primary={row.visitorName} secondary={row.visitorOrganization ?? row.visitorContact ?? 'No organisation'} /> },
    { id: 'host', header: 'Host', width: 190, cell: ({ row }) => row.hostName ?? row.hostId },
    { id: 'badge', header: 'Badge', width: 120, cell: ({ row }) => row.badgeNumber ?? '-' },
    { id: 'zones', header: 'Access zones', width: 220, cell: ({ row }) => row.accessZones.join(', ') || '-' },
    { id: 'arrival', header: 'Checked in', width: 180, cell: ({ row }) => formatDateTime(row.checkedInAt) },
    { id: 'status', header: 'Status', align: 'right', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ], []);
  return <>
    <PageHeading
      title="Visitor roll call"
      subtitle="The live list of visitors currently checked in at a site."
      crumbs={[{ label: 'Safety & security' }, { label: 'Visitors', to: visitorPaths.visits }, { label: 'Roll call' }]}
      actions={<>
        <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} required className="w-44" />
        <Button variant="outline" onClick={query.refetch}><RefreshCw size={14} strokeWidth={1.5} aria-hidden /> Refresh</Button>
      </>}
    />
    <PageSection>
      <Panel title="People currently on site" subtitle="Use this list during an evacuation or site accountability check.">
        <DataState loading={false} error={query.error} onRetry={query.refetch}>
          <RegisterTable
            paramPrefix="roll-call"
            framed={false}
            columns={columns}
            rows={query.data ?? []}
            rowKey={(row) => row.id}
            loading={query.initialising}
            onRowClick={(row) => navigate(visitorPaths.visitDetail(row.id))}
            emptyTitle="No visitors are checked in"
            emptyDescription="The roll call will populate as reception checks visitors in."
          />
        </DataState>
      </Panel>
    </PageSection>
  </>;
};
export default VisitorRollCallPage;

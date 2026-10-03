import { useMemo, useState } from 'react';
import { Plus } from 'lucide-react';
import { Banner, Button, PageSection, type TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDate } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { permits } from 'shared/layout/actorPermissions';
import { drillPaths } from 'shared/layout/navigation';
import PageHeading from 'modules/emergency/components/PageHeading';
import Panel from 'modules/emergency/components/Panel';
import RegisterTable from 'modules/emergency/components/RegisterTable';
import { drillApi } from '../api/drillApi';
import type { ComplianceRow } from '../api/dto';
import { drillTypeLabel } from '../api/enums';
import { ComplianceChip } from '../components/drillChips';
import { RequirementDialog } from '../dialogs/runReviewDialogs';

type Editing = { row?: ComplianceRow } | null;

/**
 * SRS-SFL-S175-04: each site against the drill frequency CLET requires of it, most urgent first. Only a completed,
 * reviewed drill counts; a cancelled or postponed one never does. A lapse raises "Compliance Gap" once, from the
 * hourly sweep.
 */
const DrillCompliancePage = () => {
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [editing, setEditing] = useState<Editing>(null);
  const query = useApiQuery((signal) => drillApi.compliance(siteCode || undefined, signal), [siteCode]);
  const canConfigure = permits('DRILL_CONFIGURE');
  const lapsed = (query.data ?? []).filter((row) => row.standing === 'COMPLIANCE_GAP');

  const columns = useMemo<TableColumn<ComplianceRow>[]>(() => [
    { id: 'site', header: 'Site', width: 100, cell: ({ row }) => row.requirement.siteCode },
    { id: 'type', header: 'Drill type', width: 170, cell: ({ row }) => drillTypeLabel[row.requirement.drillType] },
    { id: 'interval', header: 'Required every', width: 130, cell: ({ row }) => `${row.requirement.intervalDays} days` },
    { id: 'last', header: 'Last reviewed drill', width: 160, hideBelowLg: true, cell: ({ row }) => (row.lastCountedDrillAt ? formatDate(row.lastCountedDrillAt) : 'Never') },
    { id: 'due', header: 'Next due', width: 130, cell: ({ row }) => formatDate(row.dueAt) },
    { id: 'standing', header: 'Standing', width: 150, align: 'right', cell: ({ row }) => <ComplianceChip standing={row.standing} /> },
  ], []);

  return (
    <>
      <PageHeading
        title="Frequency compliance"
        subtitle="Required drill intervals per site and type, and where each site stands."
        crumbs={[{ label: 'Drills', to: drillPaths.dashboard }, { label: 'Compliance' }]}
        actions={
          <>
            <SiteSelect label="Site" value={siteCode} onChange={setSiteCode} className="w-44" />
            {canConfigure && <Button variant="primary" onClick={() => setEditing({})}><Plus size={14} strokeWidth={1.5} aria-hidden /> Set requirement</Button>}
          </>
        }
      />
      <DataState loading={false} error={query.error} onRetry={query.refetch}>
        {lapsed.length > 0 && (
          <PageSection>
            <Banner variant="danger" heading={`Compliance Gap at ${lapsed.length} requirement${lapsed.length === 1 ? '' : 's'}`} subtext={lapsed.map((row) => `${row.requirement.siteCode} ${drillTypeLabel[row.requirement.drillType]} (due ${formatDate(row.dueAt)})`).join(' · ')} />
          </PageSection>
        )}
        <PageSection>
          <Panel title="Requirements" subtitle={canConfigure ? 'Select a row to revise its interval.' : undefined}>
            <RegisterTable
              paramPrefix="drill-compliance"
              framed={false}
              columns={columns}
              rows={query.data ?? []}
              rowKey={(row) => row.requirement.id}
              loading={query.initialising}
              onRowClick={canConfigure ? (row) => setEditing({ row }) : undefined}
              emptyTitle="No frequency requirements set"
              emptyDescription="The statutory and insurance intervals are CLET's to set. Until they are, no site can show a compliance gap."
            />
          </Panel>
        </PageSection>
      </DataState>
      {editing && (
        <RequirementDialog
          siteCode={editing.row?.requirement.siteCode ?? siteCode}
          drillType={editing.row?.requirement.drillType}
          intervalDays={editing.row?.requirement.intervalDays}
          warningDays={editing.row?.requirement.warningDays}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); query.refetch(); }}
        />
      )}
    </>
  );
};

export default DrillCompliancePage;

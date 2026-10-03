import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus } from 'lucide-react';
import {
  Card,
  EmptyState,
  PageSection,
  SectionActions,
  SectionDescription,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  useBreadcrumbs,
} from '@rfdtech/components';
import type { TableColumn } from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { facilitiesPaths } from 'shared/layout/navigation';
import type { Site } from '../api/dto';
import { changeSiteLifecycle, createSite, listSites, updateSite } from '../api/facilitiesApi';
import {
  changeSiteLifecycleControl,
  createSiteControl,
  editSiteControl,
} from '../api/workflow';
import ControlButton from '../components/ControlButton';
import RowActions, { EditRowAction, RetireRowAction } from '../components/RowActions';
import StatusBadge from '../components/StatusBadge';
import { formatDateTime, orDash } from '../components/facilitiesFormat';
import { LifecycleDialog } from '../dialogs/common';
import { EditSiteDialog, RegisterSiteDialog } from '../dialogs/siteDialogs';

/**
 * The sites this actor is scoped to.
 *
 * The service filters rather than refuses here - asking for "all sites" is a legitimate request that
 * should answer with the actor's own - so an operator scoped to one centre sees one row and no
 * error. Operating mode is the column that matters most: a centre in examination mode is running
 * under different rules, and that has to be visible without opening anything.
 *
 * Adding and editing live here rather than only on the detail screen because a register an operator
 * cannot write to is a report. The operating mode stays on the detail screen: it is a centre-level
 * operational declaration with its own permission, not an attribute of the record.
 */
const SiteRegisterPage = () => {
  const navigate = useNavigate();
  const notify = useNotifier();
  useBreadcrumbs([{ label: 'Facilities', href: facilitiesPaths.dashboard }, { label: 'Sites' }]);
  const { data, loading, error, refetch } = useApiQuery((signal) => listSites(signal), []);

  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Site | null>(null);
  const [retiring, setRetiring] = useState<Site | null>(null);

  const columns: TableColumn<Site>[] = [
    {
      id: 'siteCode',
      header: 'Code',
      width: 140,
      cell: ({ row }) => <span className="font-medium text-foreground">{row.siteCode}</span>,
    },
    { id: 'name', header: 'Site', accessorKey: 'name' },
    {
      id: 'description',
      header: 'Description',
      cell: ({ row }) => <span className="text-muted-foreground">{orDash(row.description)}</span>,
    },
    {
      id: 'mode',
      header: 'Operating mode',
      width: 170,
      cell: ({ row }) => (
        <StatusBadge
          value={row.operatingMode}
          tone={row.operatingMode === 'EXAMINATION' ? 'accent' : 'neutral'}
        />
      ),
    },
    {
      id: 'lifecycle',
      header: 'Lifecycle',
      width: 120,
      cell: ({ row }) => <StatusBadge value={row.lifecycleStatus} />,
    },
    {
      id: 'changed',
      header: 'Last changed',
      width: 190,
      align: 'right',
      cell: ({ row }) => (
        <span className="text-muted-foreground">{formatDateTime(row.metadata.lastModifiedAt)}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      width: 150,
      align: 'right',
      cell: ({ row: site }) => (
        <RowActions>
          <EditRowAction
            state={editSiteControl(site)}
            onClick={() => setEditing(site)}
            label={`Edit ${site.siteCode}`}
          />
          <RetireRowAction
            state={changeSiteLifecycleControl(site)}
            onClick={() => setRetiring(site)}
            label={`Retire ${site.siteCode}`}
          />
        </RowActions>
      ),
    },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Sites</SectionTitle>
          <SectionDescription>CLET centres, and the operating mode each is running under</SectionDescription>
          <SectionActions className="items-end [&_button]:whitespace-nowrap">
            <ControlButton state={createSiteControl()} variant="primary" onClick={() => setAdding(true)}>
              <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
              Add a site
            </ControlButton>
          </SectionActions>
        </SectionHeader>
      </PageSection>

      <PageSection>
        <DataState loading={false} error={error} onRetry={refetch}>
          <Table paramPrefix="sites" variant="soft">
            <Card bordered>
              <TableContent
                variant="soft"
                columns={columns}
                data={data ?? []}
                rowKey={(site) => site.id}
                loading={loading}
                onRowClick={(site) => navigate(facilitiesPaths.siteDetail(site.id))}
                aria-label="Sites"
                emptyContent={
                  <EmptyState
                    title="No sites in your scope"
                    description="Your profile is scoped to sites that do not exist yet, or to none at all."
                  />
                }
              />
            </Card>
          </Table>
        </DataState>
      </PageSection>

      {adding && (
        <RegisterSiteDialog
          onClose={() => setAdding(false)}
          onSubmit={async (request) => {
            const created = await createSite(request);
            setAdding(false);
            notify.notifySuccess(`${created.siteCode} added.`);
            refetch();
          }}
        />
      )}

      {editing && (
        <EditSiteDialog
          site={editing}
          onClose={() => setEditing(null)}
          onSubmit={async (request) => {
            const saved = await updateSite(editing.id, request);
            setEditing(null);
            notify.notifySuccess(`${saved.siteCode} updated.`);
            refetch();
          }}
        />
      )}

      {retiring && (
        <LifecycleDialog
          noun="site"
          label={retiring.siteCode}
          current={retiring.lifecycleStatus}
          expectedVersion={retiring.metadata.version}
          onClose={() => setRetiring(null)}
          onSubmit={async (status, expectedVersion) => {
            const saved = await changeSiteLifecycle(retiring.id, { status, expectedVersion });
            setRetiring(null);
            notify.notifySuccess(`${saved.siteCode} is now ${status.toLowerCase()}.`);
            refetch();
          }}
        />
      )}
    </>
  );
};

export default SiteRegisterPage;

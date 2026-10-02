import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Button,
  MetricCard,
  MetricCards,
  PageSection,
  type TableColumn,
} from '@rfdtech/components';
import { Plus } from 'lucide-react';
import Greeting from 'modules/me/components/Greeting';
import { LocalTable } from 'modules/me/components/LocalTable';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { courierItemsApi } from 'modules/dispatch/api/dispatchApi';
import type { CourierItem } from 'modules/dispatch/api/dto';
import { itemDistributable } from 'modules/dispatch/api/workflow';
import {
  DistributeInboundDialog,
  RegisterItemDialog,
} from 'modules/dispatch/dialogs/itemDialogs';
import { canDistributeInbound, canRegisterInbound } from 'modules/fleet/api/access';

/**
 * The mailroom officer's day - S171, Derived from the Fleet / Logistics Officer class.
 *
 * The derivation is direct: the mapping's S171 entry is titled "Mailroom / Courier & Despatch
 * Tracking" and names inbound mail explicitly, and the Transportation & Logistics Unit that owns it
 * staffs a mailroom. `MAILROOM_OFFICER` was added additively under S171 decision D-14 for this desk.
 *
 * ## What the seven permissions allow, and what they do not
 *
 * Registering and distributing inbound mail is the whole of it. There is no
 * `DISPATCH_MANIFEST_CREATE` here - sealing and despatching a consignment is the controller's act,
 * not the mailroom's - so this screen does not offer it. Before the nav item carried a permission a
 * mailroom officer was offered the dispatch dashboard as their landing page and met a 403 on
 * arrival; that is the mistake this page exists to stop repeating.
 *
 * ## Not narrowed, and it does not pretend to be
 *
 * `assignedHandler` is free text with no relationship to a principal, so there is nothing to narrow
 * on and this is the site's inbound register rather than "mine". Recorded as C-16 in
 * `docs/fleet/S166_Gap_And_Conflict_Report.md`; the headings say "at this site" for that reason.
 */
const MailroomPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [site, setSite] = useState(defaultSite);
  const [registering, setRegistering] = useState(false);
  const [distributing, setDistributing] = useState<CourierItem | null>(null);

  const inbound = useApiQuery(
    (signal) => courierItemsApi.search({ siteCode: site, direction: 'INBOUND', size: 50 }, signal),
    [site],
  );

  const rows = useMemo(() => inbound.data?.content ?? [], [inbound.data]);
  const awaiting = useMemo(() => rows.filter((row) => row.status === 'RECEIVED'), [rows]);
  const acknowledged = useMemo(() => rows.filter((row) => Boolean(row.acknowledgedBy)), [rows]);

  const columns: TableColumn<CourierItem>[] = [
    { id: 'itemNumber', header: 'Item', cell: ({ row }) => row.itemNumber },
    { id: 'sender', header: 'From', cell: ({ row }) => row.sender ?? row.origin },
    { id: 'recipient', header: 'For', cell: ({ row }) => row.recipient ?? row.destination },
    { id: 'itemType', header: 'Type', cell: ({ row }) => <StatusBadge value={row.itemType} /> },
    {
      id: 'sensitivity',
      header: 'Sensitivity',
      cell: ({ row }) => <StatusBadge value={row.sensitivity} />,
    },
    { id: 'status', header: 'Status', cell: ({ row }) => <StatusBadge value={row.status} /> },
    {
      id: 'action',
      header: '',
      align: 'right',
      cell: ({ row }) =>
        canDistributeInbound() && itemDistributable(row) ? (
          <Button size="sm" variant="outline" onClick={() => setDistributing(row)}>
            Record distribution
          </Button>
        ) : null,
    },
  ];

  return (
    <>
      <PageHeading
        title="Mailroom"
        subtitle={'Inbound items at ' + site + ' - register what arrives, distribute what is due'}
        crumbs={[{ label: 'Mailroom' }]}
        actions={
          <>
            <SiteSelect value={site} onChange={setSite} required />
            {canRegisterInbound() && (
              <Button variant="primary" onClick={() => setRegistering(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Register inbound mail
              </Button>
            )}
          </>
        }
      />

      <Greeting />

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={inbound.initialising}
            label="Awaiting distribution"
            value={formatNumber(awaiting.length)}
            description="Received, not yet handed over"
          />
          <MetricCard
            variant="soft"
            loading={inbound.initialising}
            label="Acknowledged"
            value={formatNumber(acknowledged.length)}
            description="Handed over with a name"
          />
          <MetricCard
            variant="soft"
            loading={inbound.initialising}
            label="Inbound at this site"
            value={formatNumber(rows.length)}
            description="Everything registered here"
          />
        </MetricCards>
      </PageSection>

      <Panel title="Awaiting distribution" description="Hand each item over and record who took it.">
        <LocalTable
          paramPrefix="awaiting"
          columns={columns}
          rows={awaiting}
          rowKey={(row) => row.id}
          loading={inbound.loading}
          error={inbound.error}
          onRetry={inbound.refetch}
          caption="Inbound items received and waiting to be distributed"
          emptyTitle="Nothing is waiting to go out"
          emptyHint="Items you register arrive here until they are distributed and acknowledged."
          onRowClick={(row) => navigate(dispatchPaths.itemDetail(row.id))}
        />
      </Panel>

      <Panel title="All inbound at this site">
        <LocalTable
          paramPrefix="inbound"
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={inbound.loading}
          error={inbound.error}
          onRetry={inbound.refetch}
          caption="Every inbound item at this site"
          emptyTitle="No inbound items at this site"
          onRowClick={(row) => navigate(dispatchPaths.itemDetail(row.id))}
        />
      </Panel>

      {registering && (
        <RegisterItemDialog
          open
          inboundOnly
          defaultSiteCode={site}
          onClose={() => setRegistering(false)}
          onSaved={(item) => {
            notifySuccess(`${item.itemNumber} registered as inbound.`);
            inbound.refetch();
          }}
        />
      )}

      {distributing && (
        <DistributeInboundDialog
          open
          item={distributing}
          onClose={() => setDistributing(null)}
          onSaved={() => {
            notifySuccess('Distribution recorded with its acknowledgement.');
            inbound.refetch();
          }}
        />
      )}
    </>
  );
};

export default MailroomPage;

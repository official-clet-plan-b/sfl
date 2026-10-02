import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Button,
  MetricCard,
  MetricCards,
  PageSection,
  type TableColumn,
} from '@rfdtech/components';
import Greeting from 'modules/me/components/Greeting';
import { LocalTable } from 'modules/me/components/LocalTable';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { Callout } from 'modules/dispatch/components/formKit';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useNotifier } from 'shared/components/Notifier';
import { formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { manifestsApi } from 'modules/dispatch/api/dispatchApi';
import type { DispatchManifest } from 'modules/dispatch/api/dto';
import { ConfirmReceiptDialog } from 'modules/dispatch/dialogs/custodyDialogs';
import { canConfirmReceipt } from 'modules/fleet/api/access';

/**
 * The centre manager's receipts - S171's destination persona.
 *
 * ## A portal that cannot yet keep the promise its name makes
 *
 * `CENTRE_MANAGER` has **no §2.3 user class**; it was added additively under S171 decision D-14 for
 * the person at an examination centre who signs for a consignment. That is a real job, so the screen
 * is built - but it is a **Deviation**, and its limitation is stated on the page rather than buried
 * in a report.
 *
 * `Dispatch.destinationCentre` and `assignedHandler` are `VARCHAR(200)` free text supplied at
 * creation, with no relationship to a principal. There is nothing to narrow on. A rule built on them
 * would hold whenever somebody happened to type an actor id into the field and fail silently
 * otherwise, which is worse than no rule because it looks like enforcement. So this lists
 * consignments **at this site**, says so twice, and does not claim to be "my centre".
 *
 * Closing it needs a principal-bound centre reference on a dispatch - a schema change and an
 * identity decision for the Transportation & Logistics Unit. Recorded as C-16 in
 * `docs/fleet/S166_Gap_And_Conflict_Report.md`.
 */
const CentreReceiptsPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [site, setSite] = useState(defaultSite);
  const [confirming, setConfirming] = useState<DispatchManifest | null>(null);

  const manifests = useApiQuery(
    (signal) => manifestsApi.search({ siteCode: site, size: 50 }, signal),
    [site],
  );

  const rows = useMemo(() => manifests.data?.content ?? [], [manifests.data]);
  const inbound = useMemo(
    () => rows.filter((row) => row.status === 'IN_TRANSIT' || row.status === 'DISPATCHED'),
    [rows],
  );

  const columns: TableColumn<DispatchManifest>[] = [
    { id: 'manifestNumber', header: 'Manifest', cell: ({ row }) => row.manifestNumber },
    { id: 'destinationCentre', header: 'Destination', cell: ({ row }) => row.destinationCentre ?? '-' },
    { id: 'route', header: 'Route', cell: ({ row }) => row.route ?? '-' },
    { id: 'itemCount', header: 'Items', cell: ({ row }) => String(row.itemCount) },
    { id: 'status', header: 'Status', cell: ({ row }) => <StatusBadge value={row.status} /> },
  ];

  const onTheWayColumns: TableColumn<DispatchManifest>[] = [
    ...columns,
    {
      id: 'action',
      header: '',
      align: 'right',
      cell: ({ row }) =>
        canConfirmReceipt() ? (
          <Button size="sm" variant="outline" onClick={() => setConfirming(row)}>
            Confirm receipt
          </Button>
        ) : null,
    },
  ];

  const notice =
    'A dispatch records its destination centre as free text, with nothing tying it to your account, '
    + 'so the platform cannot yet tell which consignments are yours. Everything at '
    + site
    + ' is shown. Narrowing to your own consignments needs a change owned by the Transportation '
    + 'and Logistics Unit, and is not yet scheduled.';

  return (
    <>
      <PageHeading
        title="Centre receipts"
        subtitle={'Consignments at ' + site + ' - confirm receipt, record a variance, chase returns'}
        crumbs={[{ label: 'Centre receipts' }]}
        actions={<SiteSelect value={site} onChange={setSite} required />}
      />

      <Greeting />

      <PageSection>
        <MetricCards>
          <MetricCard
            variant="soft"
            loading={manifests.initialising}
            label="On the way"
            value={formatNumber(inbound.length)}
            description="Sealed and despatched"
          />
          <MetricCard
            variant="soft"
            loading={manifests.initialising}
            label="Consignments at this site"
            value={formatNumber(rows.length)}
            description="Everything on the register"
          />
        </MetricCards>
      </PageSection>

      <PageSection>
        <Callout tone="info" title="This list is not narrowed to your centre">
          {notice}
        </Callout>
      </PageSection>

      <Panel title="On the way">
        <LocalTable
          paramPrefix="on-the-way"
          columns={onTheWayColumns}
          rows={inbound}
          rowKey={(row) => row.id}
          loading={manifests.loading}
          error={manifests.error}
          onRetry={manifests.refetch}
          caption="Consignments sealed and despatched towards this site"
          emptyTitle="Nothing is in transit to this site"
          emptyHint="Consignments appear here once they are sealed and despatched."
          onRowClick={(row) => navigate(dispatchPaths.manifestDetail(row.id))}
        />
      </Panel>

      <Panel
        title="All consignments at this site"
        actions={
          <Button variant="outline" onClick={() => navigate(dispatchPaths.manifests)}>
            View manifests
          </Button>
        }
      >
        <LocalTable
          paramPrefix="consignments"
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={manifests.loading}
          error={manifests.error}
          onRetry={manifests.refetch}
          caption="Every consignment at this site"
          emptyTitle="No consignments at this site"
          onRowClick={(row) => navigate(dispatchPaths.manifestDetail(row.id))}
        />
      </Panel>

      {confirming && (
        <ConfirmReceiptDialog
          open
          manifest={confirming}
          onClose={() => setConfirming(null)}
          onSaved={() => {
            notifySuccess(`Receipt of ${confirming.manifestNumber} recorded.`);
            manifests.refetch();
          }}
        />
      )}
    </>
  );
};

export default CentreReceiptsPage;

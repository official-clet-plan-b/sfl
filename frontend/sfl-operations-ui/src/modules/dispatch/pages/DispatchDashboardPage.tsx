import { ReactNode, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  Badge,
  Button,
  EmptyState,
  HeroBanner,
  MetricCard,
  MetricCards,
  PageSection,
  Table,
  TableContent,
  type TableColumn,
} from '@rfdtech/components';
import { ChevronRight, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
import { DispatchManifest } from 'modules/dispatch/api/dto';
import { humanise } from 'modules/fleet/api/enums';
import {
  dispatchDashboardApi,
  dispatchExceptionsApi,
  manifestsApi,
} from 'modules/dispatch/api/dispatchApi';
import { exceptionSlaBreached } from 'modules/dispatch/api/workflow';
import ExceptionMixChart, { ExceptionBar } from 'modules/dispatch/charts/ExceptionMixChart';
import CellStack from 'modules/dispatch/components/CellStack';
import PageHeading from 'modules/dispatch/components/PageHeading';
import Panel from 'modules/dispatch/components/Panel';
import { filterLink } from 'modules/dispatch/components/registerTable';
import StatusBadge from 'modules/dispatch/components/StatusBadge';
import { CreateManifestDialog } from 'modules/dispatch/dialogs/manifestDialogs';
import { sflActor } from 'shared/api/config';
import DataState from 'shared/components/DataState';
import { useNotifier } from 'shared/components/Notifier';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { formatDateTime, formatNumber } from 'shared/components/format';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { dispatchPaths } from 'shared/layout/navigation';
import { canCreateManifests } from 'modules/fleet/api/access';

/** Header metadata: when the snapshot was taken and what it covers. Facts, so no tone. */
const MetaBadge = ({ children, stale }: { children: ReactNode; stale?: boolean }) => (
  <Badge variant="default">
    {stale && (
      <>
        <TriangleAlert size={13} strokeWidth={1.75} className="mr-1 inline text-warning" aria-hidden="true" />
        <span className="sr-only">May be out of date.</span>
      </>
    )}
    {children}
  </Badge>
);

interface AttentionRowProps {
  title: string;
  description: string;
  count: number;
  onOpen: () => void;
}

/** One line of "Needs attention": what it is, how many, and where to go and deal with it. */
const AttentionRow = ({ title, description, count, onOpen }: AttentionRowProps) => (
  <li className="border-b border-border last:border-b-0">
    <button
      type="button"
      onClick={onOpen}
      className="flex w-full items-center justify-between gap-4 py-3 text-left"
    >
      <span className="min-w-0">
        <span className="block text-sm font-semibold text-foreground">{title}</span>
        <span className="block text-xs text-muted-foreground">{description}</span>
      </span>
      <span className="flex shrink-0 items-center gap-3">
        <span
          className={count > 0 ? 'text-lg font-semibold text-foreground' : 'text-lg text-muted-foreground'}
        >
          {formatNumber(count)}
        </span>
        <ChevronRight size={16} strokeWidth={1.5} className="text-muted-foreground" aria-hidden="true" />
      </span>
    </button>
  </li>
);

/**
 * The Courier & Dispatch workspace.
 *
 * The dispatch dashboard endpoint is **entirely exception-shaped** - eight counts, every one of them
 * a thing going wrong. That is the right emphasis for a custody system and it decides this page's
 * layout: what needs attention comes first, and the volume figures underneath are counted from the
 * registers because the snapshot does not carry them.
 *
 * `siteCode` is required by every dispatch endpoint, so this page is single-site by construction.
 */
const DispatchDashboardPage = () => {
  const navigate = useNavigate();
  const { notifySuccess } = useNotifier();
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [creating, setCreating] = useState(false);

  const snapshot = useApiQuery(
    (signal) => dispatchDashboardApi.snapshot(siteCode, signal),
    [siteCode],
  );

  const exceptions = useApiQuery(
    (signal) => dispatchExceptionsApi.search({ siteCode }, signal),
    [siteCode],
  );

  const manifests = useApiQuery(
    (signal) => manifestsApi.search({ siteCode }, signal),
    [siteCode],
  );

  const data = snapshot.data;

  /**
   * Each list is now the service's own answer to its own question, not a sieve over one window.
   *
   * The exception queue asks for open cases and the manifest list for the ones in flight. Each is
   * capped at what a dashboard panel can usefully show, and the counts beside them come from the
   * service's `totalElements` rather than from the rows.
   */
  const openCases = useMemo(() => exceptions.data?.content ?? [], [exceptions.data]);
  const activeManifests = useMemo(() => manifests.data?.content ?? [], [manifests.data]);

  const exceptionBars = useMemo<ExceptionBar[]>(() => {
    const counts = new Map<string, { total: number; urgent: number }>();
    openCases.forEach((exceptionCase) => {
      const entry = counts.get(exceptionCase.type) ?? { total: 0, urgent: 0 };
      entry.total += 1;
      if (exceptionCase.securityRelevant || exceptionSlaBreached(exceptionCase)) {
        entry.urgent += 1;
      }
      counts.set(exceptionCase.type, entry);
    });
    return [...counts.entries()]
      .sort((left, right) => right[1].total - left[1].total)
      .flatMap(([type, entry]) => {
        const bars: ExceptionBar[] = [];
        if (entry.urgent > 0) {
          bars.push({ label: humanise(type), value: entry.urgent, urgent: true });
        }
        if (entry.total - entry.urgent > 0) {
          bars.push({ label: humanise(type), value: entry.total - entry.urgent });
        }
        return bars;
      });
  }, [openCases]);

  const manifestColumns = useMemo<TableColumn<DispatchManifest>[]>(
    () => [
      {
        id: 'manifest',
        header: 'Manifest',
        minWidth: 240,
        cell: ({ row }) => (
          // A row click is for the pointer; the first cell carries a real button for the keyboard.
          <button
            type="button"
            className="text-left"
            onClick={(event) => {
              event.stopPropagation();
              navigate(dispatchPaths.manifestDetail(row.id));
            }}
          >
            <CellStack primary={row.manifestNumber} secondary={row.route} />
          </button>
        ),
      },
      {
        id: 'destination',
        header: 'Destination',
        cell: ({ row }) =>
          row.destinationCentre ?? (
            <span className="text-muted-foreground">No destination centre recorded</span>
          ),
      },
      { id: 'handler', header: 'Handler', accessorKey: 'assignedHandler' },
      {
        id: 'items',
        header: 'Seals and items',
        cell: ({ row }) => (
          <CellStack
            primary={`${formatNumber(row.itemCount)} item${row.itemCount === 1 ? '' : 's'}`}
            secondary={`${formatNumber(row.sealIds.length)} seal${row.sealIds.length === 1 ? '' : 's'}`}
          />
        ),
      },
      {
        id: 'status',
        header: 'Custody stage',
        cell: ({ row }) => <StatusBadge value={row.status} />,
      },
    ],
    [navigate],
  );

  const refreshAll = () => {
    snapshot.refetch();
    exceptions.refetch();
    manifests.refetch();
  };

  const inFlight = activeManifests.slice(0, 6);

  return (
    <>
      <PageHeading
        title="Dispatch dashboard"
        crumbs={[{ label: 'Dispatch' }]}
        actions={
          <>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button variant="outline" onClick={refreshAll}>
              <RefreshCw size={14} strokeWidth={1.5} aria-hidden="true" />
              Refresh
            </Button>
            {/* DISPATCH_MANIFEST_CREATE. A reporting viewer reads the dashboard and creates nothing. */}
            {canCreateManifests() && (
              <Button variant="primary" onClick={() => setCreating(true)}>
                <Plus size={14} strokeWidth={1.5} aria-hidden="true" />
                Create manifest
              </Button>
            )}
          </>
        }
        meta={
          data && (
            <>
              <MetaBadge stale={data.stale}>{`Snapshot ${formatDateTime(data.generatedAt)}`}</MetaBadge>
              <MetaBadge>{`Site ${siteCode}`}</MetaBadge>
              <MetaBadge>
                {data.sourceUpdatedAt
                  ? `Records last changed ${formatDateTime(data.sourceUpdatedAt)}`
                  : 'No dispatch records at this site'}
              </MetaBadge>
            </>
          )
        }
      />

      <PageSection>
        <HeroBanner name={sflActor.displayName || 'Welcome'} />
      </PageSection>

      <DataState loading={false} error={snapshot.error} onRetry={snapshot.refetch} minHeight={360}>
        {/* See the fuel dashboard: when the figures were last true, not how staleness works. */}
        {data?.stale && data.sourceUpdatedAt && (
          <p className="mb-4 text-xs text-muted-foreground">
            Figures as at {formatDateTime(data.sourceUpdatedAt)}.
          </p>
        )}

        {/*
         * Every figure on this screen is published by the service, and every one of them counts
         * something that has gone wrong - which is what a custody system's dashboard should lead
         * with. The four cards are the headline; the rest are the rows under "Needs attention".
         */}
        <PageSection>
          <MetricCards>
            <MetricCard
              variant="soft"
              loading={snapshot.initialising}
              label="In transit"
              value={formatNumber(data?.inTransitCount ?? 0)}
              description="Consignments on the move"
            />
            <MetricCard
              variant="soft"
              loading={snapshot.initialising}
              label="Open exceptions"
              value={formatNumber(data?.openExceptionCount ?? 0)}
              description="Blocking the manifests they belong to"
            />
            <MetricCard
              variant="soft"
              loading={snapshot.initialising}
              label="Outstanding returns"
              value={formatNumber(data?.outstandingReturnCount ?? 0)}
              description="Went out and has not come back"
            />
            <MetricCard
              variant="soft"
              loading={snapshot.initialising}
              label="Overdue receipts"
              value={formatNumber(data?.overdueReceiptCount ?? 0)}
              description="Arrived without a confirmation"
            />
          </MetricCards>
        </PageSection>

        <PageSection>
          <div className="grid gap-6 xl:grid-cols-2">
            <Panel
              title="Needs attention"
              description="Anything that breaks or delays the custody chain, most urgent first"
            >
              <ul>
                <AttentionRow
                  title="Receipt variances"
                  description="Seal, count or recipient did not match the manifest"
                  count={data?.receiptVarianceCount ?? 0}
                  onOpen={() =>
                    navigate(
                      filterLink(dispatchPaths.exceptions, 'exceptions', 'type', 'RECEIPT_VARIANCE'),
                    )
                  }
                />
                <AttentionRow
                  title="Undelivered items"
                  description="Dispatched, never confirmed delivered"
                  count={data?.undeliveredCount ?? 0}
                  onOpen={() => navigate(dispatchPaths.items)}
                />
                <AttentionRow
                  title="SLA breaches"
                  description="Cases past their resolution target"
                  count={data?.slaBreachCount ?? 0}
                  onOpen={() => navigate(dispatchPaths.exceptions)}
                />
                <AttentionRow
                  title="Custody gaps"
                  description="Broken seals, count mismatches, missing hops"
                  count={data?.custodyGapCount ?? 0}
                  onOpen={() =>
                    navigate(
                      filterLink(dispatchPaths.exceptions, 'exceptions', 'type', 'CUSTODY_GAP'),
                    )
                  }
                />
              </ul>
            </Panel>

            <Panel
              title="Open cases by type"
              description="Where the exceptions are coming from"
              actions={
                <Button variant="outline" size="sm" onClick={() => navigate(dispatchPaths.exceptions)}>
                  View queue
                </Button>
              }
            >
              <DataState
                loading={exceptions.initialising}
                error={exceptions.error}
                empty={exceptionBars.length === 0}
                emptyTitle="No open cases"
                emptyHint="Nothing at this site is waiting on an explanation or a decision."
                onRetry={exceptions.refetch}
                minHeight={260}
              >
                <ExceptionMixChart bars={exceptionBars} />
                <p className="mt-3 text-xs text-muted-foreground">
                  Counted from the {openCases.length} open cases returned for this site. A case
                  counts as urgent when it is security relevant or past its SLA.
                </p>
              </DataState>
            </Panel>
          </div>
        </PageSection>

        <Panel
          title="Consignments in flight"
          description="Sealed through to reconciled"
          actions={
            <Button variant="outline" onClick={() => navigate(dispatchPaths.manifests)}>
              View manifests
            </Button>
          }
        >
          <DataState loading={false} error={manifests.error} onRetry={manifests.refetch}>
            <Table paramPrefix="flight" variant="soft" aria-label="Dispatch manifests at this site that are in flight, with their destination, handler, item and seal counts and custody stage.">
              <TableContent
                variant="soft"
                columns={manifestColumns}
                data={inFlight}
                rowKey={(row) => row.id}
                loading={manifests.initialising}
                onRowClick={(row) => navigate(dispatchPaths.manifestDetail(row.id))}
                emptyContent={
                  <EmptyState
                    title="Nothing in flight"
                    description="No consignment at this site is between sealing and reconciliation."
                  />
                }
              />
            </Table>
          </DataState>
        </Panel>
      </DataState>

      {creating && (
        <CreateManifestDialog
          open
          defaultSiteCode={siteCode}
          onClose={() => setCreating(false)}
          onSaved={(manifest) => {
            notifySuccess(
              `${manifest.manifestNumber} created as a draft.`,
              'Add its items before sealing - the contents freeze at that point.',
            );
            navigate(dispatchPaths.manifestDetail(manifest.id));
          }}
        />
      )}
    </>
  );
};

export default DispatchDashboardPage;

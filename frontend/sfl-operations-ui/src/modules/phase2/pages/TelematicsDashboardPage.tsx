import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  MetricCard,
  MetricCards,
  Notice,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
  Table,
  TableContent,
  type TableColumn,
} from '@rfdtech/components';
import { Checkbox } from 'modules/facilities/dialogs/dialogKit';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { trackingApi, type TrackedVehicle, type TrackingStatus } from '../api/phase2Api';
import { formatPosition, reportAge, summariseTracking } from '../api/tracking';

/** A position is only as good as its age, so the page re-asks every minute. */
const REFRESH_MS = 60_000;

const STATUS_BADGE: Record<TrackingStatus, { label: string; variant: 'success' | 'warning' | 'default' }> = {
  TRACKED: { label: 'Tracked', variant: 'success' },
  STALE: { label: 'Stale', variant: 'warning' },
  UNTRACKED: { label: 'Untracked', variant: 'default' },
};

const minutes = (seconds: number) => Math.round(seconds / 60);

/**
 * Where the fleet is, and which vehicles cannot be found.
 *
 * Three states, because "has a tracker" is not "can be located": a vehicle that has never reported is
 * untracked, one that reported but has gone quiet is stale (its last position may be wrong), and only
 * a recent report is tracked. The service decides which, using one lookup for the whole page, so the
 * screen has nothing to truncate and no per-vehicle requests to make.
 */
export const TelematicsDashboardPage = () => {
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [attentionOnly, setAttentionOnly] = useState(false);
  const tracking = useApiQuery((signal) => trackingApi.overview(siteCode, signal), [siteCode]);
  const { refetch } = tracking;

  useEffect(() => {
    const timer = window.setInterval(refetch, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [refetch]);

  const vehicles = useMemo(() => tracking.data?.content ?? [], [tracking.data]);
  const summary = useMemo(() => summariseTracking(vehicles), [vehicles]);
  const shown = attentionOnly ? vehicles.filter((vehicle) => vehicle.trackingStatus !== 'TRACKED') : vehicles;
  const total = tracking.data?.totalElements ?? 0;
  const staleMinutes = tracking.data ? minutes(tracking.data.staleAfterSeconds) : null;

  const columns: TableColumn<TrackedVehicle>[] = [
    {
      id: 'vehicle',
      header: 'Vehicle',
      cell: ({ row }) => (
        <div>
          <span className="font-medium">{row.registrationNumber}</span>
          <div className="text-xs text-muted-foreground">
            {row.make} {row.model}
          </div>
        </div>
      ),
    },
    {
      id: 'tracking',
      header: 'Tracking',
      cell: ({ row }) => (
        <Badge variant={STATUS_BADGE[row.trackingStatus].variant}>{STATUS_BADGE[row.trackingStatus].label}</Badge>
      ),
    },
    { id: 'position', header: 'Last position', cell: ({ row }) => formatPosition(row) },
    {
      id: 'reported',
      header: 'Last report',
      cell: ({ row }) => (
        <span title={row.recordedAt ? new Date(row.recordedAt).toLocaleString() : undefined}>
          {reportAge(row.recordedAt)}
        </span>
      ),
    },
    { id: 'source', header: 'Source', cell: ({ row }) => row.sourceSystem ?? '-' },
  ];

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>GPS & telematics</SectionTitle>
          <SectionActions style={{ alignItems: 'flex-end' }}>
            <SiteSelect value={siteCode} onChange={setSiteCode} required />
            <Button variant="outline" className="whitespace-nowrap" onClick={() => refetch()}>
              Refresh
            </Button>
          </SectionActions>
        </SectionHeader>
        <p className="max-w-3xl text-sm text-muted-foreground">
          Every active vehicle at the site. A vehicle with no tracker stays visible as untracked, and one
          whose tracker has gone quiet is marked stale
          {staleMinutes !== null && ` after ${staleMinutes} minutes without a report`}. Positions arrive
          through the fleet integrations inbox from whichever provider is connected.
        </p>
      </PageSection>

      <PageSection>
        <MetricCards>
          <MetricCard variant="soft" label="Tracked" value={summary.tracked} description="Reported recently" loading={tracking.initialising} />
          <MetricCard variant="soft" label="Stale" value={summary.stale} description="Last position may be wrong" loading={tracking.initialising} />
          <MetricCard variant="soft" label="Untracked" value={summary.untracked} description="Never reported" loading={tracking.initialising} />
        </MetricCards>
      </PageSection>

      <PageSection>
        {total > vehicles.length && (
          <Notice variant="warning" title="Showing part of the fleet">
            <p className="text-sm">
              The first {vehicles.length} of {total} vehicles are listed. Narrow the site to see the rest.
            </p>
          </Notice>
        )}
        <div className="mb-3">
          <Checkbox
            checked={attentionOnly}
            onChange={setAttentionOnly}
            label="Only vehicles that cannot be located right now"
          />
        </div>
        {tracking.error ? (
          <EmptyState title="Telematics is unavailable" description={tracking.error.message} />
        ) : (
          <Table paramPrefix="telematics" variant="soft">
            <Card bordered>
              <TableContent
                columns={columns}
                data={shown}
                loading={tracking.initialising}
                rowKey={(row) => row.vehicleId}
                emptyContent={
                  <EmptyState
                    title={attentionOnly ? 'Every vehicle is being tracked' : 'No active vehicles in scope'}
                  />
                }
              />
            </Card>
          </Table>
        )}
      </PageSection>
    </>
  );
};

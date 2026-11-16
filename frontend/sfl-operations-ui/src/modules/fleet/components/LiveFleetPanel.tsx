import { useEffect, useMemo } from 'react';
import { Link } from 'react-router';
import { LoadingBlock, Spinner } from 'shared/components/Spinner';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { phase2FleetPaths } from 'shared/layout/navigation';
import { liveMapApi, MOVEMENT_COLOUR, summariseMovement } from '../api/liveMapApi';
import { LIVE_REFRESH_MS } from '../pages/LiveFleetMapPage';
import FleetLiveMap from './FleetLiveMap';
import Panel from './Panel';

/**
 * The fleet dashboard's live view: a compact map of every vehicle with moving / stopped / not
 * reporting counts, refreshing on its own, and a link to the full live map.
 */
const LiveFleetPanel = ({ siteCode }: { siteCode?: string }) => {
  const live = useApiQuery((signal) => liveMapApi.live(siteCode, 30, signal), [siteCode]);
  const { refetch } = live;

  useEffect(() => {
    const timer = window.setInterval(refetch, LIVE_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [refetch]);

  const vehicles = useMemo(() => live.data?.vehicles ?? [], [live.data]);
  const summary = useMemo(() => summariseMovement(vehicles), [vehicles]);

  const counts: Array<{ label: string; value: number; colour: string }> = [
    { label: 'Moving', value: summary.moving, colour: MOVEMENT_COLOUR.MOVING },
    { label: 'Stopped', value: summary.idle, colour: MOVEMENT_COLOUR.IDLE },
    { label: 'Not reporting', value: summary.offline, colour: MOVEMENT_COLOUR.OFFLINE },
    { label: 'No position', value: summary.noPosition, colour: '#d0d5dd' },
  ];

  return (
    <Panel
      title="Live fleet"
      description={
        <span className="inline-flex items-center gap-2">
          Where every vehicle is right now - refreshes every {LIVE_REFRESH_MS / 1000}s
          {live.loading && !live.initialising && <Spinner size={13} label="Refreshing" />}
        </span>
      }
      actions={
        <Link
          to={phase2FleetPaths.liveMap}
          className="inline-flex h-10 items-center rounded-lg border border-gray-300 px-4 text-theme-sm font-medium text-gray-800 hover:bg-gray-50"
        >
          Open live map
        </Link>
      }
    >
      {live.error ? (
        <p className="py-10 text-center text-theme-sm text-gray-600">{live.error.message}</p>
      ) : live.initialising ? (
        <LoadingBlock label="Locating the fleet…" minHeight={360} />
      ) : (
        <>
          <div className="mb-3 flex flex-wrap gap-x-6 gap-y-2" data-testid="live-fleet-counts">
            {counts.map((count) => (
              <span key={count.label} className="inline-flex items-center gap-2 text-theme-sm text-gray-700">
                <span className="h-2.5 w-2.5 rounded-full" style={{ background: count.colour }} />
                <span className="font-semibold text-gray-900">{count.value}</span> {count.label}
              </span>
            ))}
          </div>
          <FleetLiveMap vehicles={vehicles} height={360} scrollZoom={false} />
        </>
      )}
    </Panel>
  );
};

export default LiveFleetPanel;

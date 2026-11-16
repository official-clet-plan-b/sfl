import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Dropdown,
  MetricCard,
  MetricCards,
  Notice,
  PageSection,
  SectionActions,
  SectionHeader,
  SectionTitle,
} from '@rfdtech/components';
import SiteSelect, { defaultSite } from 'shared/components/SiteSelect';
import { LoadingBlock, Spinner } from 'shared/components/Spinner';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { fleetPaths } from 'shared/layout/navigation';
import {
  ageOf,
  compassPoint,
  liveMapApi,
  MOVEMENT_COLOUR,
  MOVEMENT_LABEL,
  summariseMovement,
  type LiveVehicle,
  type Movement,
} from '../api/liveMapApi';
import FleetLiveMap from '../components/FleetLiveMap';

/** Positions from a driver's phone arrive every 30 s or so; refreshing faster only redraws the same map. */
export const LIVE_REFRESH_MS = 15_000;

const TRAIL_OPTIONS = [
  { minutes: 30, label: '30 min' },
  { minutes: 60, label: '1 hour' },
  { minutes: 180, label: '3 hours' },
];

type Filter = 'ALL' | Movement | 'NO_POSITION';

const FILTERS: Array<{ value: Filter; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'MOVING', label: 'Moving' },
  { value: 'IDLE', label: 'Stopped' },
  { value: 'OFFLINE', label: 'Not reporting' },
  { value: 'NO_POSITION', label: 'No position' },
];

const matches = (vehicle: LiveVehicle, filter: Filter): boolean => {
  if (filter === 'ALL') return true;
  const located = vehicle.latitude !== null && vehicle.longitude !== null;
  if (filter === 'NO_POSITION') return !located;
  return located && vehicle.movement === filter;
};

/** Ticks once a second so "12s ago" stays true between refreshes. */
const useNow = () => {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
};

/**
 * The live fleet map: where every vehicle is, which are moving and which way, the path each took
 * recently, and the trip it is on. Refreshes itself every {@link LIVE_REFRESH_MS}.
 */
const LiveFleetMapPage = () => {
  const [siteCode, setSiteCode] = useState(defaultSite);
  const [trailMinutes, setTrailMinutes] = useState(60);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const now = useNow();

  const live = useApiQuery((signal) => liveMapApi.live(siteCode, trailMinutes, signal), [siteCode, trailMinutes]);
  const { refetch } = live;

  useEffect(() => {
    const timer = window.setInterval(refetch, LIVE_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [refetch]);

  const vehicles = useMemo(() => live.data?.vehicles ?? [], [live.data]);
  const summary = useMemo(() => summariseMovement(vehicles), [vehicles]);
  const needle = search.trim().toLowerCase();
  const listed = vehicles
    .filter((vehicle) => matches(vehicle, filter))
    .filter(
      (vehicle) =>
        !needle ||
        vehicle.registrationNumber.toLowerCase().includes(needle) ||
        `${vehicle.make} ${vehicle.model}`.toLowerCase().includes(needle) ||
        (vehicle.trip?.driverName ?? '').toLowerCase().includes(needle),
    )
    .sort((a, b) => {
      const rank = (v: LiveVehicle) => (v.latitude === null ? 3 : v.movement === 'MOVING' ? 0 : v.movement === 'IDLE' ? 1 : 2);
      return rank(a) - rank(b) || a.registrationNumber.localeCompare(b.registrationNumber);
    });
  const mapped = vehicles.filter((vehicle) => matches(vehicle, filter));
  const updatedAt = live.data ? new Date(live.data.generatedAt).getTime() : null;

  return (
    <>
      <PageSection>
        <SectionHeader>
          <SectionTitle>Live fleet map</SectionTitle>
          <SectionActions className="items-center [&_button]:whitespace-nowrap">
            <SiteSelect inline value={siteCode} onChange={setSiteCode} required />
            <div className="min-w-36 shrink-0">
            <Dropdown
              aria-label="Trail"
              value={String(trailMinutes)}
              onValueChange={(value) => value && setTrailMinutes(Number(value))}
              options={TRAIL_OPTIONS.map((option) => ({
                value: String(option.minutes),
                label: `Trail: ${option.label}`,
              }))}
            />
            </div>
            <Button variant="outline" onClick={() => refetch()} disabled={live.loading}>
              {live.loading ? <Spinner size={14} /> : null}
              Refresh
            </Button>
          </SectionActions>
        </SectionHeader>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span className="relative inline-flex h-2.5 w-2.5">
            <span className="absolute inline-flex h-full w-full rounded-full bg-success-500 opacity-60 motion-safe:animate-ping" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-success-600" />
          </span>
          Live - refreshes every {LIVE_REFRESH_MS / 1000} seconds.
          {updatedAt !== null && <> Updated {ageOf(new Date(updatedAt).toISOString(), now)}.</>}
          {live.loading && !live.initialising && <Spinner size={14} label="Refreshing" />}
        </p>
      </PageSection>

      <PageSection>
        <MetricCards>
          <MetricCard variant="soft" label="Moving" value={summary.moving} description="Reporting and on the move" loading={live.initialising} />
          <MetricCard variant="soft" label="Stopped" value={summary.idle} description="Reporting, not moving" loading={live.initialising} />
          <MetricCard variant="soft" label="Not reporting" value={summary.offline} description="Last position may be out of date" loading={live.initialising} />
          <MetricCard variant="soft" label="No position" value={summary.noPosition} description="Never reported a location" loading={live.initialising} />
        </MetricCards>
      </PageSection>

      <PageSection>
        {live.error ? (
          <EmptyState title="The live map is unavailable" description={live.error.message} />
        ) : live.initialising ? (
          <Card bordered>
            <LoadingBlock label="Locating the fleet…" minHeight={520} />
          </Card>
        ) : (
          <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
            <div className="min-w-0">
              {(live.data?.totalVehicles ?? 0) > vehicles.length && (
                <Notice variant="warning" title="Showing part of the fleet" className="mb-3">
                  <p className="text-sm">The first {vehicles.length} of {live.data?.totalVehicles} vehicles are shown. Choose a site to narrow it.</p>
                </Notice>
              )}
              <FleetLiveMap vehicles={mapped} selectedId={selectedId} onSelect={setSelectedId} height={600} frameKey={`${siteCode}|${filter}`} />
              <div className="mt-2 flex flex-wrap gap-4 text-theme-xs text-gray-600">
                {(Object.keys(MOVEMENT_LABEL) as Movement[]).map((movement) => (
                  <span key={movement} className="inline-flex items-center gap-1.5">
                    <span className="h-3 w-3 rounded-full" style={{ background: MOVEMENT_COLOUR[movement] }} />
                    {MOVEMENT_LABEL[movement]}
                  </span>
                ))}
                <span>Arrow = direction of travel. Line = path over the last {TRAIL_OPTIONS.find((o) => o.minutes === trailMinutes)?.label}.</span>
              </div>
            </div>

            <Card bordered className="flex max-h-[680px] min-w-0 flex-col">
              <div className="space-y-3 border-b border-gray-100 p-4">
                <input
                  type="search"
                  placeholder="Search registration, model or driver"
                  aria-label="Search vehicles"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  className="h-10 w-full rounded-lg border border-gray-300 px-3 text-theme-sm"
                />
                <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by movement">
                  {FILTERS.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={filter === option.value}
                      onClick={() => setFilter(option.value)}
                      className={`rounded-full border px-3 py-1 text-theme-xs font-medium ${filter === option.value ? 'border-teal-700 bg-teal-700 text-white' : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'}`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>
              <ul className="flex-1 divide-y divide-gray-100 overflow-y-auto" aria-label="Vehicles">
                {listed.length === 0 && (
                  <li className="p-6 text-center text-theme-sm text-gray-500">
                    {vehicles.length === 0 ? 'No active vehicles in scope.' : 'No vehicle matches.'}
                  </li>
                )}
                {listed.map((vehicle) => {
                  const located = vehicle.latitude !== null && vehicle.longitude !== null;
                  const heading = compassPoint(vehicle.headingDegrees);
                  return (
                    <li key={vehicle.vehicleId}>
                      <button
                        type="button"
                        disabled={!located}
                        onClick={() => setSelectedId(vehicle.vehicleId)}
                        className={`flex w-full items-start gap-3 px-4 py-3 text-left disabled:cursor-default ${selectedId === vehicle.vehicleId ? 'bg-teal-50' : 'hover:bg-gray-50'}`}
                      >
                        <span
                          className="mt-1 h-3 w-3 shrink-0 rounded-full"
                          style={{ background: located ? MOVEMENT_COLOUR[vehicle.movement] : '#d0d5dd' }}
                        />
                        <span className="min-w-0 flex-1">
                          <span className="flex items-center justify-between gap-2">
                            <span className="font-semibold text-gray-900">{vehicle.registrationNumber}</span>
                            {located ? (
                              <Badge variant={vehicle.movement === 'MOVING' ? 'success' : vehicle.movement === 'IDLE' ? 'warning' : 'default'}>
                                {vehicle.movement === 'MOVING' && vehicle.speedKph !== null
                                  ? `${Math.round(vehicle.speedKph)} km/h${heading ? ` ${heading}` : ''}`
                                  : MOVEMENT_LABEL[vehicle.movement]}
                              </Badge>
                            ) : (
                              <Badge variant="default">No position</Badge>
                            )}
                          </span>
                          <span className="block text-theme-xs text-gray-600">
                            {vehicle.make} {vehicle.model} · {ageOf(vehicle.recordedAt, now)}
                          </span>
                          {vehicle.trip && (
                            <span className="mt-0.5 block truncate text-theme-xs text-gray-700">
                              {vehicle.trip.tripNumber}: {vehicle.trip.destination}
                              {vehicle.trip.driverName ? ` · ${vehicle.trip.driverName}` : ''}
                            </span>
                          )}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
              <div className="border-t border-gray-100 p-3 text-theme-xs text-gray-500">
                Positions come from drivers&apos; phones during a trip and from any connected tracker.{' '}
                <Link className="text-teal-700 underline" to={fleetPaths.trips}>Trips</Link>
              </div>
            </Card>
          </div>
        )}
      </PageSection>
    </>
  );
};

export default LiveFleetMapPage;

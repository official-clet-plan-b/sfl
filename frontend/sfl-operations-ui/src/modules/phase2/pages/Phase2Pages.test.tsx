import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';
import type { AssetReference, TrackedVehicle, TrackingPage } from '../api/phase2Api';

const trackingApi = vi.hoisted(() => ({ overview: vi.fn() }));
const assetVisibilityApi = vi.hoisted(() => ({
  search: vi.fn(),
  history: vi.fn(),
  register: vi.fn(),
  move: vi.fn(),
  custody: vi.fn(),
  tag: vi.fn(),
}));
const tripsApi = vi.hoisted(() => ({ search: vi.fn(), create: vi.fn() }));
const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());

vi.mock('../api/phase2Api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/phase2Api')>()),
  trackingApi,
  assetVisibilityApi,
}));
vi.mock('modules/fleet/api/fleetApi', () => ({ tripsApi }));
vi.mock('shared/layout/actorPermissions', () => ({ permits }));

const { TelematicsDashboardPage, VipTripPortalPage, AssetVisibilityPage } = await import(
  './Phase2OperationsPages'
);

const renderPage = (page: React.ReactElement) =>
  render(
    <MemoryRouter>
      <NotifierProvider>{page}</NotifierProvider>
    </MemoryRouter>,
  );

const vehicle = (overrides: Partial<TrackedVehicle> = {}): TrackedVehicle => ({
  vehicleId: 'v-1',
  registrationNumber: 'GT-1-26',
  make: 'Toyota',
  model: 'Hilux',
  siteCode: 'CLET-HQ',
  trackingStatus: 'TRACKED',
  latitude: 5.6037,
  longitude: -0.187,
  recordedAt: new Date(Date.now() - 120_000).toISOString(),
  sourceSystem: 'TRACKER-CO',
  ...overrides,
});

const trackingPage = (content: TrackedVehicle[], totalElements = content.length): TrackingPage => ({
  content,
  page: 0,
  size: 200,
  totalElements,
  totalPages: 1,
  staleAfterSeconds: 900,
});

const asset = (overrides: Partial<AssetReference> = {}): AssetReference => ({
  id: 'a-1',
  assetCode: 'AST-1',
  name: 'Projector',
  category: 'EQUIPMENT',
  status: 'ACTIVE',
  siteCode: 'CLET-HQ',
  locationType: 'ROOM',
  locationReference: 'ROOM 12',
  custodianReference: null,
  externalReference: null,
  evidenceReference: null,
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
  ...overrides,
});

beforeEach(() => {
  permits.mockReset();
  permits.mockReturnValue(true);
  Object.values({ ...trackingApi, ...assetVisibilityApi, ...tripsApi }).forEach((fn) => fn.mockReset());
});

describe('GPS & telematics', () => {
  it('shows tracked, stale and untracked vehicles, each as what it is', async () => {
    trackingApi.overview.mockResolvedValue(
      trackingPage([
        vehicle({ vehicleId: 'v-1', registrationNumber: 'LIVE-1' }),
        vehicle({
          vehicleId: 'v-2',
          registrationNumber: 'STALE-1',
          trackingStatus: 'STALE',
          recordedAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
        }),
        vehicle({
          vehicleId: 'v-3',
          registrationNumber: 'BARE-1',
          trackingStatus: 'UNTRACKED',
          latitude: null,
          longitude: null,
          recordedAt: null,
          sourceSystem: null,
        }),
      ]),
    );

    renderPage(<TelematicsDashboardPage />);

    const live = (await screen.findByText('LIVE-1')).closest('tr') as HTMLElement;
    expect(within(live).getByText('Tracked')).toBeInTheDocument();
    expect(within(live).getByText('5.60370, -0.18700')).toBeInTheDocument();
    expect(within(live).getByText('2 minutes ago')).toBeInTheDocument();

    const stale = screen.getByText('STALE-1').closest('tr') as HTMLElement;
    expect(within(stale).getByText('Stale')).toBeInTheDocument();
    expect(within(stale).getByText('2 days ago')).toBeInTheDocument();

    const bare = screen.getByText('BARE-1').closest('tr') as HTMLElement;
    expect(within(bare).getByText('Untracked')).toBeInTheDocument();
    expect(within(bare).getByText('No position reported')).toBeInTheDocument();
    expect(within(bare).getByText('Never reported')).toBeInTheDocument();
  });

  it('can be narrowed to the vehicles that cannot be located right now', async () => {
    trackingApi.overview.mockResolvedValue(
      trackingPage([
        vehicle({ vehicleId: 'v-1', registrationNumber: 'LIVE-1' }),
        vehicle({ vehicleId: 'v-2', registrationNumber: 'STALE-1', trackingStatus: 'STALE' }),
      ]),
    );
    renderPage(<TelematicsDashboardPage />);
    await screen.findByText('LIVE-1');

    await userEvent.click(screen.getByRole('checkbox', { name: /cannot be located/i }));

    expect(screen.queryByText('LIVE-1')).not.toBeInTheDocument();
    expect(screen.getByText('STALE-1')).toBeInTheDocument();
  });

  it('says so when it is showing only part of a large fleet, instead of dropping the rest silently', async () => {
    trackingApi.overview.mockResolvedValue(trackingPage([vehicle()], 450));
    renderPage(<TelematicsDashboardPage />);

    expect(await screen.findByText(/first 1 of 450 vehicles/i)).toBeInTheDocument();
  });

  it('reports a failure instead of an empty fleet', async () => {
    trackingApi.overview.mockRejectedValue(Object.assign(new Error('Service down'), { status: 503 }));
    renderPage(<TelematicsDashboardPage />);

    expect(await screen.findByText('Telematics is unavailable')).toBeInTheDocument();
  });
});

describe('VIP trip portal', () => {
  beforeEach(() => {
    tripsApi.search.mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
  });

  it('offers the request form to a role that may create trips', async () => {
    permits.mockImplementation((permission) => permission === 'FLEET_TRIP_MANAGE');
    renderPage(<VipTripPortalPage />);

    expect(await screen.findByRole('button', { name: 'Submit request' })).toBeInTheDocument();
  });

  it('shows a role that cannot create trips the requests, and who raises them - not a button that would 403', async () => {
    permits.mockReturnValue(false);
    renderPage(<VipTripPortalPage />);

    expect(await screen.findByText('Requests are raised by the transport office')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit request' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Assign trips' })).not.toBeInTheDocument();
  });

  it('asks the service only for VIP requests, and names no vehicle or driver on submit', async () => {
    permits.mockReturnValue(true);
    renderPage(<VipTripPortalPage />);
    await screen.findByRole('button', { name: 'Submit request' });

    expect(tripsApi.search).toHaveBeenCalledWith(
      expect.objectContaining({ purposePrefix: 'VIP transport: ' }),
      expect.anything(),
    );

    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByText('Say who is travelling.')).toBeInTheDocument();
    expect(screen.getByText('Choose when the trip starts.')).toBeInTheDocument();
    expect(tripsApi.create).not.toHaveBeenCalled();
  });

  it('shows who is travelling, the chase-car note and whether a vehicle has been assigned', async () => {
    tripsApi.search.mockResolvedValue({
      content: [
        {
          id: 't-1',
          tripNumber: 'TRP-1',
          purpose: 'VIP transport: Registrar · chase-car requested',
          origin: 'CLET campus',
          destination: 'Airport',
          plannedStart: '2026-10-09T09:00:00Z',
          plannedEnd: '2026-10-09T11:00:00Z',
          status: 'PLANNED',
          vehicleId: null,
          driverId: null,
        },
        {
          id: 't-2',
          tripNumber: 'TRP-2',
          purpose: 'VIP transport: Board chair',
          origin: 'CLET campus',
          destination: 'Ministry',
          plannedStart: '2026-10-10T09:00:00Z',
          plannedEnd: '2026-10-10T11:00:00Z',
          status: 'ASSIGNED',
          vehicleId: 'v-1',
          driverId: 'd-1',
        },
      ],
      page: 0,
      size: 50,
      totalElements: 2,
      totalPages: 1,
    });
    renderPage(<VipTripPortalPage />);

    const first = (await screen.findByText('TRP-1')).closest('tr') as HTMLElement;
    expect(within(first).getByText('Registrar')).toBeInTheDocument();
    expect(within(first).getByText('Chase-car support requested')).toBeInTheDocument();
    expect(within(first).getByText('Awaiting assignment')).toBeInTheDocument();

    const second = screen.getByText('TRP-2').closest('tr') as HTMLElement;
    expect(within(second).getByText('Assigned')).toBeInTheDocument();
  });
});

describe('Asset tagging & inventory', () => {
  beforeEach(() => {
    assetVisibilityApi.search.mockResolvedValue([asset(), asset({ id: 'a-2', assetCode: 'AST-2', externalReference: 'RFID-2' })]);
  });

  it('gives a role that may manage assets the register, update and history controls', async () => {
    permits.mockImplementation((permission) => permission === 'ASSET_REFERENCE_MANAGE');
    renderPage(<AssetVisibilityPage />);

    expect(await screen.findByRole('button', { name: 'Register asset' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Update' })).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'History' })).toHaveLength(2);
  });

  it('gives a read-only role history but no write controls - nothing that would answer 403', async () => {
    permits.mockReturnValue(false);
    renderPage(<AssetVisibilityPage />);

    await screen.findByText('AST-1');
    expect(screen.queryByRole('button', { name: 'Register asset' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Update' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'History' })).toHaveLength(2);
  });

  it('shows a tag, or says the asset is not tagged', async () => {
    renderPage(<AssetVisibilityPage />);

    const tagged = (await screen.findByText('AST-2')).closest('tr') as HTMLElement;
    expect(within(tagged).getByText('RFID-2')).toBeInTheDocument();
    const untagged = screen.getByText('AST-1').closest('tr') as HTMLElement;
    expect(within(untagged).getByText('Not tagged')).toBeInTheDocument();
  });

  it('opens an asset’s history in words a custodian can read', async () => {
    assetVisibilityApi.history.mockResolvedValue([
      {
        id: 'h-2',
        assetId: 'a-1',
        changeType: 'MOVED',
        fromValue: 'SITE:CLET-HQ',
        toValue: 'ROOM:ROOM 12',
        source: 'READER',
        sourceReference: 'reader-7',
        actorId: 'svc-readers',
        occurredAt: '2026-10-02T09:00:00Z',
      },
    ]);
    renderPage(<AssetVisibilityPage />);
    const row = (await screen.findByText('AST-1')).closest('tr') as HTMLElement;

    await userEvent.click(within(row).getByRole('button', { name: 'History' }));

    expect(await screen.findByText('Site · CLET-HQ → Room · ROOM 12')).toBeInTheDocument();
    expect(screen.getByText('Reader reader-7')).toBeInTheDocument();
    await waitFor(() => expect(assetVisibilityApi.history).toHaveBeenCalledWith('a-1', expect.anything()));
  });

  it('refuses to register an empty asset and sends nothing', async () => {
    permits.mockReturnValue(true);
    renderPage(<AssetVisibilityPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Register asset' }));

    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Register asset' }));

    expect(await screen.findByText('Give the asset a code.')).toBeInTheDocument();
    expect(assetVisibilityApi.register).not.toHaveBeenCalled();
  });
});

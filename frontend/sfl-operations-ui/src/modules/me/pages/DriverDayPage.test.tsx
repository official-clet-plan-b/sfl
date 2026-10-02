import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

/**
 * The driver's own end of the trip, which did not exist before.
 *
 * <p>A driver could be assigned a journey and had no way to finish it: this screen showed logbooks
 * and fuel and no trips at all, and `FLEET_TRIP_CLOSE` belongs to a dispatcher. So the journey stayed
 * open until somebody at a desk closed it from a message, and the closing odometer - which feeds the
 * fuel consumption and odometer-jump rules - was second-hand by the time it was recorded.
 *
 * <p>What these assert is the shape of the workflow rather than the wire: which trips appear, when
 * the button is offered, and that finishing one points the driver at the logbook. The service-side
 * rule that it must be *their* trip is `TripApplicationServiceTest`, where it belongs.
 */

const tripsApi = vi.hoisted(() => ({ search: vi.fn(), start: vi.fn(), close: vi.fn() }));
const driverLogbooksApi = vi.hoisted(() => ({ search: vi.fn() }));
const fuelTransactionsApi = vi.hoisted(() => ({ search: vi.fn() }));
const evidenceFilesApi = vi.hoisted(() => ({ upload: vi.fn() }));
const searchEvidenceChoices = vi.hoisted(() => vi.fn());

vi.mock('modules/fleet/api/fleetApi', () => ({
  tripsApi,
  searchEvidenceChoices,
  driversApi: { search: vi.fn() },
  vehiclesApi: { search: vi.fn() },
}));
vi.mock('modules/fuel/api/fuelApi', () => ({ driverLogbooksApi, fuelTransactionsApi }));
vi.mock('shared/evidence/evidenceFilesApi', async () => {
  const actual = await vi.importActual<typeof import('shared/evidence/evidenceFilesApi')>(
    'shared/evidence/evidenceFilesApi',
  );
  return { ...actual, evidenceFilesApi };
});

const DriverDayPage = (await import('./DriverDayPage')).default;

const page = <T,>(content: T[]) => ({
  content,
  page: 0,
  size: 25,
  totalElements: content.length,
  totalPages: content.length === 0 ? 0 : 1,
  first: true,
  last: true,
  sort: null,
});

const trip = (overrides: Record<string, unknown> = {}) =>
  ({
    id: 'trip-1',
    tripNumber: 'TRP-0001',
    siteCode: 'CLET-HQ',
    origin: 'Accra HQ',
    destination: 'Kumasi Centre',
    status: 'IN_PROGRESS',
    plannedStart: '2026-08-10T08:00:00Z',
    startOdometer: 42_000,
    version: 1,
    ...overrides,
  }) as never;

const renderPage = () =>
  render(
    <DomMemoryRouter>
      <MemoryRouter>
        <NotifierProvider>
          <DriverDayPage />
        </NotifierProvider>
      </MemoryRouter>
    </DomMemoryRouter>,
  );

describe('DriverDayPage assignments', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:preview');
    URL.revokeObjectURL = vi.fn();
    driverLogbooksApi.search.mockResolvedValue(page([]));
    fuelTransactionsApi.search.mockResolvedValue(page([]));
    searchEvidenceChoices.mockResolvedValue([]);
    evidenceFilesApi.upload.mockResolvedValue({ id: 'evidence-1' });
    tripsApi.search.mockResolvedValue(page([trip()]));
    tripsApi.start.mockResolvedValue(trip({ status: 'IN_PROGRESS' }));
    tripsApi.close.mockResolvedValue(trip({ status: 'COMPLETED' }));
  });

  it('shows the trip assigned to the driver, with a way to finish it', async () => {
    renderPage();

    expect(await screen.findByText('TRP-0001')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /complete trip/i })).toBeInTheDocument();
  });

  it('offers to start a trip that has not begun, not to complete it', async () => {
    tripsApi.search.mockResolvedValue(page([trip({ status: 'ASSIGNED' })]));
    renderPage();

    // The service refuses a closure from ASSIGNED, so offering that button would offer a refusal;
    // it accepts a start from here, which is the transition this status is actually waiting on.
    expect(await screen.findByRole('button', { name: /start trip/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /complete trip/i })).not.toBeInTheDocument();
  });

  it('starts the trip on its own odometer reading', async () => {
    tripsApi.search.mockResolvedValue(page([trip({ status: 'ASSIGNED' })]));
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /start trip/i }));

    const dialog = await screen.findByRole('dialog', { name: /start trip/i });
    await userEvent.type(within(dialog).getByLabelText(/start odometer/i), '42000');
    await userEvent.click(within(dialog).getByRole('button', { name: /start trip/i }));

    await waitFor(() =>
      expect(tripsApi.start).toHaveBeenCalledWith(
        'trip-1',
        expect.objectContaining({ startOdometer: 42000, expectedVersion: 1 }),
      ),
    );
  });

  it('completes the trip and points the driver at the logbook', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /complete trip/i }));

    const dialog = await screen.findByRole('dialog', { name: /close trip/i });
    await userEvent.type(
      within(dialog).getByLabelText(/closure reason/i),
      'Delivered and signed for.',
    );
    await userEvent.clear(within(dialog).getByLabelText(/end odometer/i));
    await userEvent.type(within(dialog).getByLabelText(/end odometer/i), '42500');

    // The document itself. A driver has no Evidence & audit screen, so an identifier to paste would
    // be a step they cannot take on a field they cannot leave blank.
    await userEvent.upload(
      dialog.querySelector('input[type="file"]') as HTMLInputElement,
      new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0xff, 0xd9])], 'delivery-note.jpg', {
        type: 'image/jpeg',
      }),
    );

    await userEvent.click(within(dialog).getByRole('button', { name: /^close trip$/i }));

    await waitFor(() =>
      expect(tripsApi.close).toHaveBeenCalledWith(
        'trip-1',
        expect.objectContaining({ endOdometer: 42500, closureEvidenceId: 'evidence-1' }),
      ),
    );

    // The logbook is the point of finishing the trip, not an afterthought - it is the record the
    // fuel anti-fraud rules read.
    expect(await screen.findByText(/file the driver logbook/i)).toBeInTheDocument();
  });

  it('lists completed trips, which is what the fleet office sees too', async () => {
    tripsApi.search.mockResolvedValue(page([trip({ id: 'trip-2', status: 'COMPLETED' })]));
    renderPage();

    // By role: "Completed" is also the status chip on the row, so plain text matches twice.
    expect(await screen.findByRole('heading', { name: 'Completed' })).toBeInTheDocument();
    expect(screen.getByText('TRP-0001')).toBeInTheDocument();
  });
});

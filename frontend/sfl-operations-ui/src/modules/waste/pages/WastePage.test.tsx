import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const api = vi.hoisted(() => ({
  dashboard: vi.fn(), configuration: vi.fn(), collections: vi.fn(), collection: vi.fn(), exceptions: vi.fn(), report: vi.fn(),
  confirmDestination: vi.fn(), close: vi.fn(), reviewEvidence: vi.fn(),
}));

vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));
vi.mock('../api/wasteApi', () => ({ wasteApi: api }));

const { default: WastePage } = await import('./WastePage');

const page = <T,>(items: T[]) => ({ items, totalElements: items.length, totalPages: 1, page: 0, size: 25 });

const stream = { id: 's-1', code: 'HAZ', name: 'Lab chemicals', category: 'CHEMICAL', hazardous: true, diverted: false, handlingRules: null, active: true, version: 0 };
const point = { id: 'p-1', siteCode: 'CLET-HQ', code: 'BAY', name: 'Loading bay', containerDescription: 'Skip 1', active: true, version: 0 };
const carrier = { id: 'c-1', code: 'SAFE', name: 'SafeHaul', licenceReference: 'L-1', licenceExpiresOn: '2027-01-01', hazardousApproved: true, status: 'APPROVED', version: 0 };
const destination = { id: 'd-1', code: 'TRT', name: 'ChemSafe', destinationType: 'TREATMENT', permitReference: 'P-1', permitExpiresOn: '2027-01-01', acceptsHazardous: true, status: 'APPROVED', version: 0 };

const collection = (overrides = {}) => ({
  id: 'col-1', reference: 'WST-C-000001', siteCode: 'CLET-HQ', streamId: 's-1', pointId: 'p-1', carrierId: 'c-1', destinationId: 'd-1',
  hazardous: true, scheduledFor: '2026-09-01', collectedOn: '2026-09-01', quantity: 2, unit: 'TONNE', quantityKg: 2000, quantityBasis: 'ESTIMATED',
  manifestReference: 'MAN-1', certificateReference: null, contaminated: false, quantityReconciled: true, status: 'HANDED_OVER', version: 3, ...overrides,
});

const renderAt = (path: string) =>
  render(
    <DomMemoryRouter initialEntries={[path]}>
      <NotifierProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/facilities/waste" element={<WastePage />} />
            <Route path="/facilities/waste/:view" element={<WastePage />} />
          </Routes>
        </MemoryRouter>
      </NotifierProvider>
    </DomMemoryRouter>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  api.configuration.mockResolvedValue({ streams: [stream], points: [point], carriers: [carrier], destinations: [destination], units: [{ code: 'KG', name: 'Kilogram', kilogramsPerUnit: 1 }] });
  api.dashboard.mockResolvedValue({
    siteCode: 'CLET-HQ', periodDays: 90, measuredKg: 4000, estimatedKg: 500, diversionRatePercent: 50, certificateCompletionPercent: 75,
    handedOverCollections: 4, certifiedCollections: 3, missedCollections: { missedOpen: 2, upTo7Days: 1, from8To14Days: 0, over14Days: 1, oldestDays: 20 },
    openHazardousChainExceptions: 1, openExceptions: 3, overdueExceptions: 1,
  });
  api.collections.mockResolvedValue(page([collection()]));
  api.exceptions.mockResolvedValue(page([]));
});

describe('the overview', () => {
  it('states diversion as measured only and shows estimates apart, without an error on first load', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/waste');

    expect(await screen.findByText('50%')).toBeInTheDocument();
    expect(screen.getByText('Excluded from every percentage')).toBeInTheDocument();
    expect(screen.getByText(/measured only/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded|something went wrong/i)).not.toBeInTheDocument();
  });
});

describe('write controls follow the waste permissions', () => {
  it('offers Schedule collection to a role that holds FACILITIES_WASTE_MANAGE', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_WASTE_MANAGE' || p === 'FACILITIES_WASTE_READ');
    renderAt('/facilities/waste');
    expect(await screen.findByRole('button', { name: /schedule collection/i })).toBeInTheDocument();
  });

  it('shows a read-only role the register and no write control', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_WASTE_READ');
    renderAt('/facilities/waste/collections');
    expect(await screen.findByText('Lab chemicals')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /schedule collection/i })).not.toBeInTheDocument();
  });
});

describe('collections', () => {
  it('flags an estimated quantity and a hazardous stream in the register', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/waste/collections');

    const row = within(await screen.findByRole('row', { name: /Lab chemicals/ }));
    expect(row.getByText('Estimated')).toBeInTheDocument();
    expect(row.getByText('Hazardous')).toBeInTheDocument();
    expect(row.getByText(/2 TONNE \(2000 kg\)/)).toBeInTheDocument();
  });

  it('shows what keeps the chain open and offers closure to a role that manages waste', async () => {
    api.collection.mockResolvedValue({
      collection: collection(), stream, point, carrier, destination, custody: [], evidence: [], history: [], estimated: true,
      openExceptions: [{ id: 'x-1', reference: 'WST-X-000001', exceptionType: 'MISSING_RECEIVING_EVIDENCE', status: 'OPEN' }],
    });
    permits.mockImplementation((p) => p === 'FACILITIES_WASTE_READ' || p === 'FACILITIES_WASTE_MANAGE');
    renderAt('/facilities/waste/collections');

    await userEvent.click(await screen.findByText('Lab chemicals'));

    expect(await screen.findByText('The chain is open')).toBeInTheDocument();
    expect(screen.getByText(/Missing receiving evidence/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close chain/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument();
  });

  it('offers evidence acceptance only to a role with the verify grant', async () => {
    api.collection.mockResolvedValue({
      collection: collection(), stream, point, carrier, destination, custody: [], history: [], estimated: true, openExceptions: [],
      evidence: [{ id: 'e-1', kind: 'RECEIVING', reference: 'REC', fileName: 'r.pdf', contentHash: 'a'.repeat(64), retentionClass: 'SAFETY_CRITICAL', status: 'SUBMITTED', submittedBy: 'u', reviewedBy: null, reviewReason: null }],
    });
    permits.mockReturnValue(true);
    renderAt('/facilities/waste/collections');

    await userEvent.click(await screen.findByText('Lab chemicals'));

    expect(await screen.findByRole('button', { name: 'Accept' })).toBeInTheDocument();
  });
});

describe('the report', () => {
  it('names its source collections and states the estimation rule', async () => {
    api.report.mockResolvedValue({
      siteCode: 'CLET-HQ', from: '2026-06-01', to: '2026-09-01', measuredKg: 4000, estimatedKg: 500, diversionRatePercent: 50, collections: 3, estimatedCollections: 1,
      estimationRule: 'Quantities flagged ESTIMATED are totalled separately.', diversionRule: 'Diversion is measured only.',
      lines: [{ streamCode: 'REC', streamName: 'Paper', hazardous: false, diverted: true, measuredKg: 2000, estimatedKg: 500, divertedMeasuredKg: 2000, collections: 2, estimatedCollections: 1, sourceReferences: ['WST-C-000001', 'WST-C-000003'] }],
    });
    permits.mockReturnValue(true);
    renderAt('/facilities/waste/report');

    expect(await screen.findByText('WST-C-000001, WST-C-000003')).toBeInTheDocument();
    expect(screen.getByText('Quantities flagged ESTIMATED are totalled separately.')).toBeInTheDocument();
    await waitFor(() => expect(api.report).toHaveBeenCalledWith('CLET-HQ', expect.any(String), expect.any(String), expect.anything()));
  });
});

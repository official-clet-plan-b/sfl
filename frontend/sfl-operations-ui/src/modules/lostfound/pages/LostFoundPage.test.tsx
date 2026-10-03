import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const api = vi.hoisted(() => ({
  dashboard: vi.fn(), configuration: vi.fn(), items: vi.fn(), item: vi.fn(), evidence: vi.fn(), claims: vi.fn(), escalations: vi.fn(), claimantView: vi.fn(),
}));

vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));
vi.mock('../api/lostFoundApi', () => ({ lostFoundApi: api }));

const { default: LostFoundPage } = await import('./LostFoundPage');

const page = <T,>(items: T[]) => ({ items, totalElements: items.length, totalPages: 1, page: 0, size: 25 });

const item = (overrides = {}) => ({
  id: 'i-1', reference: 'LF-I-000001', claimReference: 'LF-ABCD2345', siteCode: 'CLET-HQ', category: 'BAG', publicDescription: 'Black rucksack',
  privateDescription: null, foundLocation: 'Main lobby', foundAt: '2026-09-01T10:00:00Z', finderReference: null, initialCondition: 'Good', status: 'STORED',
  unsafe: false, unsafeReason: null, storageLocationId: 's-1', retentionUntil: '2026-12-01', closedAt: null, version: 0, ...overrides,
});

const detail = (overrides = {}, privateView = false) => ({
  item: item(), storageLocation: { id: 's-1', siteCode: 'CLET-HQ', code: 'SAFE', name: 'Cash safe', secure: true, active: true, version: 0 },
  claims: [{ id: 'c-1', reference: 'LF-C-000001', itemId: 'i-1', siteCode: 'CLET-HQ', claimantName: privateView ? 'A. Person' : null, claimantContact: null, claimantDescription: null,
    status: 'VERIFIED', identityVerified: true, verificationMethod: 'ID_DOCUMENT', verifiedBy: 'reception', decisionReason: null, decidedBy: null, personalDataPurgedAt: null, createdAt: '2026-09-02T10:00:00Z', version: 1 }],
  custody: [{ id: 'e-1', fromParty: 'Finder', toParty: 'Front desk', location: 'Main lobby', occurredAt: '2026-09-01T10:00:00Z', reason: null, recordedBy: 'u' }],
  custodyComplete: true, evidenceCount: 1, history: [], privateView, ...overrides,
});

const renderAt = (path: string) =>
  render(
    <DomMemoryRouter initialEntries={[path]}>
      <NotifierProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/facilities/lost-found" element={<LostFoundPage />} />
            <Route path="/facilities/lost-found/:view" element={<LostFoundPage />} />
          </Routes>
        </MemoryRouter>
      </NotifierProvider>
    </DomMemoryRouter>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  api.configuration.mockResolvedValue({ locations: [{ id: 's-1', siteCode: 'CLET-HQ', code: 'SAFE', name: 'Cash safe', secure: true, active: true, version: 0 }], policies: [] });
  api.dashboard.mockResolvedValue({
    siteCode: 'CLET-HQ', openByAge: { upTo7Days: 3, from8To30Days: 2, from31To90Days: 1, over90Days: 0 }, openByLocation: [{ location: 'Cash safe', items: 4 }, { location: 'Not yet stored', items: 2 }],
    meanHoursToVerifiedRelease: 26.4, custodyCompletenessPercent: 100, itemsWithChain: 9, withinPolicyPercent: 80, itemsClosed: 5, retentionExpired: 1, openEscalations: 2, isolatedItems: 1,
  });
  api.items.mockResolvedValue(page([item()]));
  api.claims.mockResolvedValue(page([]));
  api.escalations.mockResolvedValue(page([]));
  api.evidence.mockResolvedValue([]);
});

describe('the overview', () => {
  it('shows the age of open items and where they are, without an error on first load', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/lost-found');

    expect(await screen.findByText(/3 this week/)).toBeInTheDocument();
    expect(screen.getByText('Cash safe')).toBeInTheDocument();
    expect(screen.getByText('26 h')).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded|something went wrong/i)).not.toBeInTheDocument();
  });
});

describe('write controls follow the lost-and-found permissions', () => {
  it('offers Register item to a role that holds FACILITIES_LOSTFOUND_MANAGE', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_LOSTFOUND_MANAGE' || p === 'FACILITIES_LOSTFOUND_READ');
    renderAt('/facilities/lost-found');
    expect(await screen.findByRole('button', { name: /register item/i })).toBeInTheDocument();
  });

  it('shows a read-only role the register and no write control', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_LOSTFOUND_READ');
    renderAt('/facilities/lost-found/items');
    expect(await screen.findByText('Black rucksack')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /register item/i })).not.toBeInTheDocument();
  });
});

describe('masking', () => {
  it('says so when the view is masked, and shows no finder, private detail or claimant', async () => {
    api.item.mockResolvedValue(detail());
    permits.mockImplementation((p) => p === 'FACILITIES_LOSTFOUND_READ');
    renderAt('/facilities/lost-found/items');

    await userEvent.click(await screen.findByText('Black rucksack'));

    expect(await screen.findByText('Masked view')).toBeInTheDocument();
    expect(screen.queryByText('Private detail')).not.toBeInTheDocument();
    expect(screen.queryByText('A. Person')).not.toBeInTheDocument();
    expect(screen.getByText('Not shown')).toBeInTheDocument();
    expect(api.evidence).not.toHaveBeenCalled();
  });

  it('shows the private view, the claimant and the evidence to a role with private access', async () => {
    api.item.mockResolvedValue(detail({ item: item({ privateDescription: 'Passport inside', finderReference: 'Visitor J. Doe' }) }, true));
    api.evidence.mockResolvedValue([{ id: 'ev-1', claimId: null, kind: 'PHOTO', reference: 'REC-1', fileName: 'bag.jpg', contentHash: 'a'.repeat(64), retentionClass: 'COMPLIANCE', submittedBy: 'u', submittedAt: '2026-09-01T10:00:00Z' }]);
    permits.mockImplementation((p) => p === 'FACILITIES_LOSTFOUND_READ' || p === 'FACILITIES_LOSTFOUND_PRIVATE_READ');
    renderAt('/facilities/lost-found/items');

    await userEvent.click(await screen.findByText('Black rucksack'));

    expect(await screen.findByText('Passport inside')).toBeInTheDocument();
    expect(screen.getByText(/A\. Person/)).toBeInTheDocument();
    expect(await screen.findByText(/bag\.jpg/)).toBeInTheDocument();
    expect(screen.queryByText('Masked view')).not.toBeInTheDocument();
  });
});

describe('claim actions follow the grant that allows them', () => {
  it('offers approval only to a role with the approve grant, and release to one that manages', async () => {
    api.item.mockResolvedValue(detail({}, true));
    permits.mockImplementation((p) => p === 'FACILITIES_LOSTFOUND_READ' || p === 'FACILITIES_LOSTFOUND_MANAGE');
    renderAt('/facilities/lost-found/items');

    await userEvent.click(await screen.findByText('Black rucksack'));

    const claim = within((await screen.findByText(/LF-C-000001/)).closest('li') as HTMLElement);
    expect(claim.getByRole('button', { name: 'Release' })).toBeInTheDocument();
    expect(claim.queryByRole('button', { name: 'Approve release' })).not.toBeInTheDocument();
  });

  it('shows an unsafe item as isolated and offers no way to claim it', async () => {
    api.item.mockResolvedValue(detail({ item: item({ status: 'ISOLATED', unsafe: true, unsafeReason: 'Unattended and ticking' }) }, true));
    permits.mockReturnValue(true);
    renderAt('/facilities/lost-found/items');

    await userEvent.click(await screen.findByText('Black rucksack'));

    expect(await screen.findByText('Isolated as unsafe')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /receive a claim/i })).not.toBeInTheDocument();
  });
});

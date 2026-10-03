import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const api = vi.hoisted(() => ({
  portfolio: vi.fn(), calendar: vi.fn(), agreements: vi.fn(), agreement: vi.fn(), amendments: vi.fn(), obligations: vi.fn(), alerts: vi.fn(),
  approve: vi.fn(), submit: vi.fn(),
}));

vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));
vi.mock('../api/leaseApi', () => ({ leaseApi: api }));

const { default: LeasePage } = await import('./LeasePage');

const page = <T,>(items: T[]) => ({ items, totalElements: items.length, totalPages: 1, page: 0, size: 25 });

const agreement = (overrides = {}) => ({
  id: 'a-1', reference: 'LSE-000001', siteCode: 'CLET-HQ', propertyReference: 'Block A', kind: 'LEASE', direction: 'INBOUND', title: 'Block A office lease',
  counterpartyReference: null, counterpartyState: 'UNRESOLVED', contractReference: null, financeReference: null, ownerReference: 'estates', startDate: '2026-01-01',
  endDate: '2028-12-31', renewalType: 'OPTION', renewalTermMonths: 12, noticeDays: null, noticeDate: null, rentReviewDate: null, annualRent: null, depositAmount: null,
  currency: null, status: 'IN_REVIEW', versionNumber: 1, requestedBy: 'manager', approvedBy: null, version: 2, ...overrides,
});

const detail = (overrides = {}) => ({
  agreement: agreement(), blockers: ['No notice period is set, so no notice date can be calculated.', 'No approval evidence is filed.'], warnings: ['The counterparty is recorded, not verified.'],
  documents: [], obligations: [], amendments: [], versions: [], alerts: [], history: [], financialView: false, pastEndDate: false, workOrders: [], ownerVerified: false, ...overrides,
});

const renderAt = (path: string) =>
  render(
    <DomMemoryRouter initialEntries={[path]}>
      <NotifierProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/facilities/leases" element={<LeasePage />} />
            <Route path="/facilities/leases/:view" element={<LeasePage />} />
          </Routes>
        </MemoryRouter>
      </NotifierProvider>
    </DomMemoryRouter>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  api.portfolio.mockResolvedValue({
    siteCode: 'CLET-HQ', asOf: '2026-10-03', timezone: 'Africa/Accra', agreementsByStatus: { ACTIVE: 4, DRAFT: 1, IN_REVIEW: 1 }, expiringWithin: { '30': 1, '90': 2 },
    obligationsDueWithin: { '30': 3 }, overdueObligations: 2, incompleteAgreements: 1, unresolvedCounterparties: 5, expiredAgreements: 0, renewalsOnTimePercent: 80, renewalsDueOrDone: 5,
    meanAmendmentCycleHours: 12.5, byOwner: [], financialExposure: null,
  });
  api.calendar.mockResolvedValue({ timezone: 'Africa/Accra', weekend: ['SATURDAY', 'SUNDAY'], holidays: [] });
  api.agreements.mockResolvedValue(page([agreement()]));
  api.amendments.mockResolvedValue(page([]));
  api.obligations.mockResolvedValue(page([]));
  api.alerts.mockResolvedValue(page([]));
});

describe('the overview', () => {
  it('shows the portfolio figures and says rent is not shown to a role without the financial grant', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/leases');

    expect(await screen.findByText('80%')).toBeInTheDocument();
    expect(screen.getByText(/3 more due within 30 days/)).toBeInTheDocument();
    expect(screen.getByText(/only to roles holding the financial grant/)).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded|something went wrong/i)).not.toBeInTheDocument();
  });
});

describe('write controls follow the lease permissions', () => {
  it('offers Register agreement to a role that can manage', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_LEASE_MANAGE' || p === 'FACILITIES_LEASE_READ');
    renderAt('/facilities/leases');
    expect(await screen.findByRole('button', { name: /register agreement/i })).toBeInTheDocument();
  });

  it('shows a read-only role the register and no write control, and rent as not shown', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_LEASE_READ');
    renderAt('/facilities/leases/agreements');
    expect(await screen.findByText('Block A office lease')).toBeInTheDocument();
    expect(screen.getAllByText('Not shown').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /register agreement/i })).not.toBeInTheDocument();
  });
});

describe('an agreement on screen', () => {
  it('lists what stops it being approved and the unverified counterparty', async () => {
    api.agreement.mockResolvedValue(detail());
    permits.mockReturnValue(true);
    renderAt('/facilities/leases/agreements');

    await userEvent.click(await screen.findByText('Block A office lease'));

    await screen.findByText(/Incomplete - cannot be approved yet/);
    expect(screen.getByText(/No notice period is set/)).toBeInTheDocument();
    expect(screen.getByText(/recorded, not verified/)).toBeInTheDocument();
    expect(screen.getByText(/not shown to your role/i)).toBeInTheDocument();
  });

  it('offers Approve to an approver on an agreement in review', async () => {
    api.agreement.mockResolvedValue(detail());
    permits.mockImplementation((p) => p === 'FACILITIES_LEASE_APPROVE' || p === 'FACILITIES_LEASE_READ');
    renderAt('/facilities/leases/agreements');

    await userEvent.click(await screen.findByText('Block A office lease'));

    expect(await screen.findByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /submit for approval/i })).not.toBeInTheDocument();
  });
});

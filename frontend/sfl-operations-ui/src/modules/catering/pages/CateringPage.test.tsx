import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const api = vi.hoisted(() => ({
  dashboard: vi.fn(), configuration: vi.fn(), services: vi.fn(), service: vi.fn(), exceptions: vi.fn(), exceptionWorkOrders: vi.fn(), retryWorkOrder: vi.fn(),
}));

vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));
vi.mock('../api/cateringApi', () => ({ cateringApi: api }));

const { default: CateringPage } = await import('./CateringPage');

const page = <T,>(items: T[]) => ({ items, totalElements: items.length, totalPages: 1, page: 0, size: 25 });

const venue = { id: 'v-1', siteCode: 'CLET-HQ', code: 'HALL', name: 'Main hall', capacity: 100, active: true, version: 0 };
const supplier = { id: 'su-1', code: 'ACC', name: 'Accra Kitchens', certificateReference: 'C-1', certificateExpiresOn: '2027-01-01', status: 'APPROVED', version: 0 };
const menu = { id: 'm-1', siteCode: 'CLET-HQ', code: 'LUNCH', name: 'Lunch menu', description: null, status: 'APPROVED', approvedBy: 'director', version: 1 };
const items = [
  { id: 'i-1', menuId: 'm-1', name: 'Chicken satay', allergens: ['PEANUTS'], allergensDeclared: true, dietaryTags: [] },
  { id: 'i-2', menuId: 'm-1', name: 'Mystery stew', allergens: [], allergensDeclared: false, dietaryTags: [] },
];

const service = (overrides = {}) => ({
  id: 'sv-1', reference: 'CAT-S-000001', siteCode: 'CLET-HQ', venueId: 'v-1', menuId: 'm-1', supplierId: 'su-1', contextType: 'ROUTINE', contextReference: null,
  title: 'Staff lunch', serviceDate: '2026-10-10', startsAt: '2026-10-10T12:00:00Z', cancellationCutoff: '2026-10-09T12:00:00Z', expectedGuests: 120, plannedPortions: 120,
  deliveredPortions: null, status: 'PENDING_APPROVAL', requestedBy: 'coordinator', approvedBy: null, capacityExceptionReason: null, supplierExceptionReason: null,
  purchaseReference: null, invoiceReference: null, financeState: 'NOT_STARTED', version: 2, ...overrides,
});

const detail = (overrides = {}, dietaryView = true) => ({
  service: service(), venue, menu, items, supplier, dietaryCount: 1, checks: [], exceptions: [], variances: [], evidenceCount: 0, history: [], dietaryView,
  readiness: [
    { code: 'CAPACITY_EXCEEDED', message: '120 guests exceed the capacity of 100 at HALL; an approver must accept it.', exceptionable: true },
    { code: 'SUPPLIER_CHECK_OVERDUE', message: 'Supplier ACC has no passing check in the last 30 days.', exceptionable: true },
  ],
  needs: dietaryView ? [{ request: { id: 'n-1', serviceId: 'sv-1', personReference: 'P-17', needType: 'ALLERGY', needCode: 'PEANUTS', authorisedBy: 'Event lead', status: 'OPEN', substituteItemId: null, waiverReason: null, version: 0 },
    flaggedItems: ['Chicken satay'], blocked: false, message: null }] : [],
  ...overrides,
});

const renderAt = (path: string) =>
  render(
    <DomMemoryRouter initialEntries={[path]}>
      <NotifierProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/facilities/catering" element={<CateringPage />} />
            <Route path="/facilities/catering/:view" element={<CateringPage />} />
          </Routes>
        </MemoryRouter>
      </NotifierProvider>
    </DomMemoryRouter>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  api.configuration.mockResolvedValue({ suppliers: [supplier], venues: [venue], menus: [{ menu, items }] });
  api.dashboard.mockResolvedValue({
    siteCode: 'CLET-HQ', periodDays: 90, confirmedServices: 10, deliveredServices: 9, deliveredPercent: 90, servicesDelivered: 9, servicesWithPassingCheck: 8, checkedPercent: 88.9,
    dietaryRequests: 20, dietaryExceptions: 3, dietaryExceptionPercent: 15, openVariances: 4, variancesUpTo7Days: 2, variances8To14Days: 1, variancesOver14Days: 1, netQuantityVariance: -12,
    openExceptions: 2, awaitingFinance: 3,
  });
  api.services.mockResolvedValue(page([service()]));
  api.exceptions.mockResolvedValue(page([]));
  api.exceptionWorkOrders.mockResolvedValue([]);
});

describe('the overview', () => {
  it('shows delivery, checks, dietary exceptions and variance ageing without an error on first load', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/catering');

    expect(await screen.findByText('90%')).toBeInTheDocument();
    expect(screen.getByText(/8 of 9 delivered services had a passing check/)).toBeInTheDocument();
    expect(screen.getByText(/1 older/)).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded|something went wrong/i)).not.toBeInTheDocument();
  });
});

describe('write controls follow the catering permissions', () => {
  it('offers Plan a service to a role that holds FACILITIES_CATERING_MANAGE', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_CATERING_MANAGE' || p === 'FACILITIES_CATERING_READ');
    renderAt('/facilities/catering');
    expect(await screen.findByRole('button', { name: /plan a service/i })).toBeInTheDocument();
  });

  it('shows a read-only role the register and no write control', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_CATERING_READ');
    renderAt('/facilities/catering/services');
    expect(await screen.findByText('Staff lunch')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /plan a service/i })).not.toBeInTheDocument();
  });
});

describe('a service on screen', () => {
  it('shows the controls that stand between it and confirmation, and every dish with its allergen labels', async () => {
    api.service.mockResolvedValue(detail());
    permits.mockReturnValue(true);
    renderAt('/facilities/catering/services');

    await userEvent.click(await screen.findByText('Staff lunch'));

    await screen.findByText('Not valid to confirm yet');
    expect(screen.getByText(/exceed the capacity of 100/)).toBeInTheDocument();
    expect(screen.getByText(/no passing check in the last 30 days/)).toBeInTheDocument();
    expect(screen.getByText('Contains peanuts')).toBeInTheDocument();
    expect(screen.getByText('Allergens not declared')).toBeInTheDocument();
  });

  it('shows who needs what, and the dishes to avoid, to a role with the dietary grant', async () => {
    api.service.mockResolvedValue(detail());
    permits.mockReturnValue(true);
    renderAt('/facilities/catering/services');

    await userEvent.click(await screen.findByText('Staff lunch'));

    expect(await screen.findByText(/P-17/)).toBeInTheDocument();
    expect(screen.getByText(/avoid: Chicken satay/)).toBeInTheDocument();
  });

  it('shows only a count of dietary needs to a role without the dietary grant', async () => {
    api.service.mockResolvedValue(detail({}, false));
    permits.mockImplementation((p) => p === 'FACILITIES_CATERING_READ');
    renderAt('/facilities/catering/services');

    await userEvent.click(await screen.findByText('Staff lunch'));

    expect(await screen.findByText(/not who needs what/)).toBeInTheDocument();
    expect(screen.queryByText(/P-17/)).not.toBeInTheDocument();
  });

  it('offers approval only to a role with the approve grant', async () => {
    api.service.mockResolvedValue(detail());
    permits.mockImplementation((p) => p === 'FACILITIES_CATERING_READ' || p === 'FACILITIES_CATERING_MANAGE');
    renderAt('/facilities/catering/services');

    await userEvent.click(await screen.findByText('Staff lunch'));

    await screen.findByText('Not valid to confirm yet');
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
  });

  it('says honestly that reconciliation is pending finance', async () => {
    api.service.mockResolvedValue(detail({ service: service({ status: 'DELIVERED', deliveredPortions: 110, financeState: 'PENDING_FINANCE', purchaseReference: 'PO-1' }), readiness: [] }));
    permits.mockReturnValue(true);
    renderAt('/facilities/catering/services');

    await userEvent.click(await screen.findByText('Staff lunch'));

    const summary = within((await screen.findByText('Summary')).closest('section') as HTMLElement);
    expect(summary.getByText('Pending finance')).toBeInTheDocument();
    expect(summary.getByText('PO-1 / pending')).toBeInTheDocument();
  });
});

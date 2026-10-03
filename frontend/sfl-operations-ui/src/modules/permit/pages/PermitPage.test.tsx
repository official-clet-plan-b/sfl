import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const api = vi.hoisted(() => ({
  search: vi.fn(), get: vi.fn(), dashboard: vi.fn(), analytics: vi.fn(), types: vi.fn(), approve: vi.fn(), submit: vi.fn(), suspend: vi.fn(),
}));

vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));
vi.mock('../api/permitApi', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api/permitApi')>()), permitApi: api }));

const { default: PermitPage } = await import('./PermitPage');

const type = { id: 't-1', code: 'HOT_WORK', name: 'Hot work', description: null, riskLevel: 'HIGH', activityType: 'HOT_WORK', riskAssessmentRequired: true, twoStage: true, requiresIsolation: true, maxValidityHours: 8, requiredCompetencies: ['FIRE_WATCH'], active: true, version: 0 };

const permit = (overrides = {}) => ({
  id: 'p-1', siteCode: 'CLET-HQ', reference: 'PTW-000001', permitTypeId: 't-1', workType: 'HOT_WORK', title: 'Weld the pipe rack', workDescription: 'Welding supports', locationCode: 'Plant room B', zoneId: null, zoneCode: null,
  startsAt: '2026-10-10T08:00:00Z', endsAt: '2026-10-10T16:00:00Z', status: 'SUBMITTED', statusReason: null, riskAssessmentId: 'ra-1', riskAssessmentReference: 'RA-1', riskAssessmentVersion: 1, riskLevel: 'LOW',
  riskReviewDueAt: null, contractorReference: 'ACME', supervisorReference: 'sup-1', supervisorContact: null, originSystem: 'S153', originReference: 'WO-1', approvalRound: 1, requestedBy: 'req-1', submittedAt: null,
  issuedAt: null, completionStatement: null, workCompletedAt: null, workCompletedBy: null, closedAt: null, closedBy: null, version: 3, ...overrides,
});

const detail = (overrides = {}) => ({
  permit: permit(), type, workers: [{ id: 'w-1', personReference: 'W-1', displayName: 'Esi Boateng', workRole: 'SUPERVISOR' }], competencyChecks: [],
  competencyExceptions: [{ workerId: 'w-1', workerName: 'Esi Boateng', competency: 'FIRE_WATCH', reason: 'MISSING' }],
  isolations: [{ id: 'i-1', kind: 'ELECTRICAL', description: 'Rack lighting supply', tagReference: 'LOTO-12', status: 'REQUIRED', verifiedBy: null, verifiedAt: null, removedBy: null, removedAt: null }],
  approvals: [], nextStage: null, extensions: [], suspensions: [], notifications: [], evidence: [], flags: [], escalations: [], history: [],
  blockers: ['1 isolation(s) still need verification by someone other than the requester.'], riskAssessment: { assessmentId: 'ra-1', found: true, reference: 'RA-1', version: 1, activityType: 'HOT_WORK', riskLevel: 'LOW', reviewDueAt: null, current: true, reason: null },
  overdue: false, unverified: ['Contractor ACME is recorded, not verified against the vendor master (S133).'], ...overrides,
});

const renderAt = (path: string) =>
  render(
    <DomMemoryRouter initialEntries={[path]}>
      <NotifierProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/safetysecurity/permits" element={<PermitPage />} />
            <Route path="/safetysecurity/permits/:view" element={<PermitPage />} />
          </Routes>
        </MemoryRouter>
      </NotifierProvider>
    </DomMemoryRouter>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  api.dashboard.mockResolvedValue({
    siteCode: 'CLET-HQ', asOf: '2026-10-10T09:00:00Z', open: 4, awaitingVerification: 2, awaitingApproval: 1, openByTypeAndRisk: [{ typeCode: 'HOT_WORK', typeName: 'Hot work', riskLevel: 'HIGH', open: 3, active: 2, suspended: 1 }],
    nearingExpiry: [permit({ id: 'p-2', title: 'Re-lag the pipe', status: 'ACTIVE' })], overdueCloseOuts: [permit({ id: 'p-3', title: 'Cut the duct', status: 'ACTIVE' })], isolationByZone: [{ zone: 'ZONE-A', permits: 2, required: 1, verified: 3, removed: 0 }],
    competencyExceptions: [{ permitReference: 'PTW-000001', permitId: 'p-1', contractor: 'ACME', worker: 'Esi Boateng', competency: 'FIRE_WATCH', reason: 'MISSING' }], openFlags: [], warnMinutes: 60,
  });
  api.search.mockResolvedValue({ content: [permit()], page: 0, size: 25, totalElements: 1, totalPages: 1 });
  api.types.mockResolvedValue([type]);
  api.get.mockResolvedValue(detail());
  api.analytics.mockResolvedValue({ from: null, to: null, permits: 5, byType: [{ key: 'HOT_WORK', count: 5 }], byContractor: [], byOutcome: [], meanOpenHours: 3.2, flaggedForIncident: 1, incidentCorrelationPercent: 20 });
});

describe('the dashboard', () => {
  it('shows what is open, nearing expiry, overdue and excepted, and isolation standing per zone, without an error on first load', async () => {
    permits.mockReturnValue(true);
    renderAt('/safetysecurity/permits');

    expect(await screen.findByText('Cut the duct')).toBeInTheDocument();
    expect(screen.getByText('Re-lag the pipe')).toBeInTheDocument();
    expect(screen.getByText(/3 open · 2 active · 1 suspended/)).toBeInTheDocument();
    expect(screen.getByText(/1 to verify · 3 in place · 0 removed/)).toBeInTheDocument();
    expect(screen.getByText(/FIRE_WATCH|Fire watch: Missing/i)).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded|something went wrong/i)).not.toBeInTheDocument();
  });
});

describe('controls follow the permit permissions', () => {
  it('offers Request permit to a requester, and the types and analytics tabs only to those who may', async () => {
    permits.mockImplementation((p) => p === 'PERMIT_REQUEST' || p === 'PERMIT_READ');
    renderAt('/safetysecurity/permits');

    expect(await screen.findByRole('button', { name: /request permit/i })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Permit types' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'Analytics' })).not.toBeInTheDocument();
  });

  it('gives a read-only role the register and no write control', async () => {
    permits.mockImplementation((p) => p === 'PERMIT_READ');
    renderAt('/safetysecurity/permits/register');

    expect(await screen.findByText('Weld the pipe rack')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /request permit/i })).not.toBeInTheDocument();
  });

  it('shows the analytics tab to a role that holds the analytics grant', async () => {
    permits.mockImplementation((p) => p === 'PERMIT_READ' || p === 'PERMIT_ANALYTICS_READ');
    renderAt('/safetysecurity/permits/analytics');

    expect(await screen.findByText('3.2 h')).toBeInTheDocument();
    expect(screen.getByText(/20% of permits/)).toBeInTheDocument();
  });
});

describe('a permit on screen', () => {
  it('lists what stands between it and approval, the missing competence and what is held unverified', async () => {
    permits.mockReturnValue(true);
    renderAt('/safetysecurity/permits/register');

    await userEvent.click(await screen.findByText('Weld the pipe rack'));

    await screen.findByText(/What stands between this permit and its next step/);
    expect(screen.getByText(/still need verification by someone other than the requester/)).toBeInTheDocument();
    expect(screen.getByText(/Fire Watch: Missing/i)).toBeInTheDocument();
    expect(screen.getByText(/recorded, not verified against the vendor master/)).toBeInTheDocument();
  });

  it('offers the verifier the verification step and no approve while the isolation is unverified', async () => {
    permits.mockImplementation((p) => p === 'PERMIT_READ' || p === 'PERMIT_VERIFY_ISOLATION');
    renderAt('/safetysecurity/permits/register');

    await userEvent.click(await screen.findByText('Weld the pipe rack'));

    expect(await screen.findByRole('button', { name: /record isolation verification complete/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^approve/i })).not.toBeInTheDocument();
  });

  it('offers an authoriser the stage the permit is waiting on, named', async () => {
    api.get.mockResolvedValue(detail({ permit: permit({ status: 'STAGE1_APPROVED' }), nextStage: 'SAFETY_SIGN_OFF', blockers: [], competencyExceptions: [] }));
    permits.mockImplementation((p) => p === 'PERMIT_READ' || p === 'PERMIT_SAFETY_SIGN_OFF');
    renderAt('/safetysecurity/permits/register');

    await userEvent.click(await screen.findByText('Weld the pipe rack'));

    expect(await screen.findByRole('button', { name: 'Approve: Safety sign-off' })).toBeInTheDocument();
  });

  it('offers the SOC a suspension on an active permit and says what suspending does', async () => {
    api.get.mockResolvedValue(detail({ permit: permit({ status: 'ACTIVE' }), blockers: [], competencyExceptions: [] }));
    permits.mockImplementation((p) => p === 'PERMIT_READ' || p === 'PERMIT_SUSPEND');
    renderAt('/safetysecurity/permits/register');

    await userEvent.click(await screen.findByText('Weld the pipe rack'));
    await userEvent.click(await screen.findByRole('button', { name: 'Suspend' }));

    expect(await screen.findByText(/queued for notification as part of this action/)).toBeInTheDocument();
  });

  it('says a permit past its validity is overdue and that nothing closes it automatically', async () => {
    api.get.mockResolvedValue(detail({ permit: permit({ status: 'ACTIVE' }), overdue: true, blockers: [], competencyExceptions: [] }));
    permits.mockReturnValue(true);
    renderAt('/safetysecurity/permits/register');

    await userEvent.click(await screen.findByText('Weld the pipe rack'));

    expect(await screen.findByText(/Past its validity window without close-out/)).toBeInTheDocument();
    expect(screen.getByText(/nothing closes it automatically/i)).toBeInTheDocument();
  });
});

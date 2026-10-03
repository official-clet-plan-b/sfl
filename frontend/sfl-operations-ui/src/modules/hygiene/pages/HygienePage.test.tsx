import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const api = vi.hoisted(() => ({
  dashboard: vi.fn(),
  controls: vi.fn(),
  control: vi.fn(),
  findings: vi.fn(),
  finding: vi.fn(),
  evidence: vi.fn(),
  escalations: vi.fn(),
  createControl: vi.fn(),
  acknowledge: vi.fn(),
}));

vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));
vi.mock('../api/hygieneApi', () => ({ hygieneApi: api }));

const { default: HygienePage } = await import('./HygienePage');

const page = <T,>(items: T[]) => ({ items, totalElements: items.length, totalPages: 1, page: 0, size: 25 });

const control = (overrides = {}) => ({
  id: 'c-1', reference: 'HYG-C-000001', siteCode: 'CLET-HQ', roomId: null, locationLabel: 'Main kitchen',
  controlType: 'AUDIT', riskCategory: 'FOOD_SAFETY', title: 'Kitchen audit', ownerReference: 'J. Mensah',
  frequency: 'MONTHLY', dueOn: '2026-09-01', status: 'SCHEDULED', completedOn: null, providerReference: null,
  providerConfirmed: false, previousControlId: null, notes: null, version: 0, ...overrides,
});

const finding = (overrides = {}) => ({
  id: 'f-1', reference: 'HYG-F-000001', controlId: 'c-1', siteCode: 'CLET-HQ', category: 'PEST', title: 'Droppings near store',
  description: null, severity: 'CRITICAL', status: 'OPEN', ownerReference: 'K. Owusu', targetDate: '2026-09-05',
  requiresIncident: true, incidentState: 'PENDING_MANUAL', incidentReference: null, workOrderState: 'RAISED',
  workOrderNumber: 'WO-1', repeatOfId: null, escalationLevel: 'HSE', closureMode: null, closureReason: null,
  closureApprovedBy: null, closedAt: null, version: 0, ...overrides,
});

const renderAt = (path: string) =>
  render(
    <DomMemoryRouter initialEntries={[path]}>
      <NotifierProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/facilities/hygiene" element={<HygienePage />} />
            <Route path="/facilities/hygiene/:view" element={<HygienePage />} />
          </Routes>
        </MemoryRouter>
      </NotifierProvider>
    </DomMemoryRouter>,
  );

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  api.dashboard.mockResolvedValue({
    siteCode: 'CLET-HQ', periodDays: 90, completionRatePercent: 80,
    kpis: { controlsDue: 5, controlsCompleted: 4, overdueControls: 2, openFindings: 3, openCriticalFindings: 1, overdueFindings: 1, overdueActions: 0, openEscalations: 1, meanHoursToCloseCritical: 30 },
  });
  api.controls.mockResolvedValue(page([{ control: control(), effectiveStatus: 'OVERDUE' }]));
  api.findings.mockResolvedValue(page([finding()]));
  api.escalations.mockResolvedValue(page([]));
});

describe('the overview', () => {
  it('shows the programme figures for the site without an error on first load', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene');

    expect(await screen.findByText('80%')).toBeInTheDocument();
    expect(screen.getByText('Overdue controls')).toBeInTheDocument();
    expect(screen.queryByText(/could not be loaded|something went wrong/i)).not.toBeInTheDocument();
    expect(api.dashboard).toHaveBeenCalledWith('CLET-HQ', expect.anything());
  });
});

describe('write controls follow the hygiene permission', () => {
  it('offers Schedule control to a role that holds FACILITIES_HYGIENE_MANAGE', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_HYGIENE_MANAGE' || p === 'FACILITIES_HYGIENE_READ');
    renderAt('/facilities/hygiene');

    expect(await screen.findByRole('button', { name: /schedule control/i })).toBeInTheDocument();
  });

  it('shows a read-only role the registers and no write control - the service would refuse every write', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_HYGIENE_READ');
    renderAt('/facilities/hygiene/controls');

    expect(await screen.findByText('Kitchen audit')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /schedule control/i })).not.toBeInTheDocument();
  });
});

describe('the registers', () => {
  it('lists controls with their derived status, and queries the service one page at a time', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene/controls');

    expect(await screen.findByText('Kitchen audit')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /Kitchen audit/ })).getByText('Overdue')).toBeInTheDocument();
    expect(api.controls).toHaveBeenCalledWith(expect.objectContaining({ siteCode: 'CLET-HQ', page: 0, size: 25, overdueOnly: false }), expect.anything());
  });

  it('lists findings with their severity and work-order state', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene/findings');

    expect(await screen.findByText('Droppings near store')).toBeInTheDocument();
    const row = within(screen.getByRole('row', { name: /Droppings near store/ }));
    expect(row.getByText('Critical')).toBeInTheDocument();
    expect(row.getByText('WO-1')).toBeInTheDocument();
  });

  it('opens a finding and offers closure only to a role that may verify', async () => {
    api.finding.mockResolvedValue({ finding: finding(), overdue: false, actions: [], evidenceCount: 0, history: [] });
    api.evidence.mockResolvedValue([]);
    permits.mockImplementation((p) => p === 'FACILITIES_HYGIENE_READ' || p === 'FACILITIES_HYGIENE_MANAGE' || p === 'FACILITIES_HYGIENE_EVIDENCE_READ');
    renderAt('/facilities/hygiene/findings');

    await userEvent.click(await screen.findByText('Droppings near store'));

    expect(await screen.findByText(/HSE has been notified/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /close with evidence/i })).not.toBeInTheDocument();
  });

  it('tells a pending S163 link from a made one', async () => {
    api.finding.mockResolvedValue({ finding: finding(), overdue: false, actions: [], evidenceCount: 0, history: [] });
    api.evidence.mockResolvedValue([]);
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene/findings');

    await userEvent.click(await screen.findByText('Droppings near store'));

    expect(await screen.findByText(/S163 has not confirmed it/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close with evidence/i })).toBeInTheDocument();
  });

  it('lets a manager acknowledge an escalation', async () => {
    api.escalations.mockResolvedValue(page([{
      id: 'e-1', subjectType: 'FINDING', subjectId: 'f-1', subjectReference: 'HYG-F-000001', level: 'HSE',
      reason: 'CRITICAL_FINDING', detail: null, raisedAt: '2026-09-01T10:00:00Z', acknowledgedBy: null, acknowledgedAt: null,
    }]));
    api.acknowledge.mockResolvedValue({});
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene/escalations');

    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));

    await waitFor(() => expect(api.acknowledge).toHaveBeenCalledWith('e-1'));
  });
});

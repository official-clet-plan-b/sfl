import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const readIfimpDataset = vi.hoisted(() => vi.fn());
const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());

vi.mock('../api/ifimpPhase2Api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/ifimpPhase2Api')>()),
  readIfimpDataset,
}));
vi.mock('shared/layout/actorPermissions', () => ({
  permits,
  permitsAny: (p: string | string[]) => (Array.isArray(p) ? p : [p]).some((x) => permits(x)),
  actorIsReviewer: () => false,
  permissionFailure: () => undefined,
}));

const { HygienePage, CateringPage } = await import('./registerPages');

const record = (id: string, type: string, title: string) => ({
  id,
  systemCode: 'S170',
  siteCode: 'CLET-HQ',
  recordType: type,
  title,
  status: 'PLANNED',
  ownerReference: 'J. Mensah',
  dueAt: '2026-12-01T09:00:00Z',
  severity: 'MEDIUM',
  details: 'ref',
  version: 0,
});

const renderAt = (path: string, page: React.ReactElement) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/facilities/hygiene" element={page} />
        <Route path="/facilities/hygiene/:view" element={page} />
        <Route path="/facilities/catering" element={page} />
      </Routes>
    </MemoryRouter>,
  );

beforeEach(() => {
  readIfimpDataset.mockReset();
  readIfimpDataset.mockResolvedValue({ rows: [], total: 0 });
  permits.mockReset();
});

describe('the hygiene tabs list every record type their form can create', () => {
  it('asks for hygiene audits and pest-control visits together on the Hygiene controls tab', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene', <HygienePage />);

    await waitFor(() => expect(readIfimpDataset).toHaveBeenCalled());
    expect(readIfimpDataset.mock.calls[0][0]).toBe('/api/v1/facilities/registers/S170/records');
    expect(readIfimpDataset.mock.calls[0][2]).toEqual({ recordType: 'HYGIENE_AUDIT,PEST_VISIT' });
  });

  it('asks for findings and corrective actions together on the Findings tab, which is reachable by its own route', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/hygiene/findings', <HygienePage />);

    await waitFor(() => expect(readIfimpDataset).toHaveBeenCalled());
    expect(readIfimpDataset.mock.calls[0][2]).toEqual({ recordType: 'FINDING,CORRECTIVE_ACTION' });
  });

  it('does the same for the other four systems, whose tabs had the same blind spot', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/catering', <CateringPage />);

    await waitFor(() => expect(readIfimpDataset).toHaveBeenCalled());
    expect(readIfimpDataset.mock.calls[0][2]).toEqual({ recordType: 'SERVICE,MENU' });
  });
});

describe('write controls follow the hygiene permission', () => {
  beforeEach(() => {
    readIfimpDataset.mockResolvedValue({
      rows: [record('r-1', 'PEST_VISIT', 'Quarterly rodent inspection')],
      total: 1,
    });
  });

  it('offers Create and Manage to a role that holds FACILITIES_HYGIENE_MANAGE', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_HYGIENE_MANAGE');
    renderAt('/facilities/hygiene', <HygienePage />);

    expect(await screen.findByRole('button', { name: /create hygiene control/i })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Manage' })).toBeInTheDocument();
  });

  it('shows a read-only role the records and no Create or Manage - the service would refuse every write', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_HYGIENE_READ');
    renderAt('/facilities/hygiene', <HygienePage />);

    expect(await screen.findByText('Quarterly rodent inspection')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /create hygiene control/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View' })).toBeInTheDocument();
  });
});

import { render, waitFor } from '@testing-library/react';
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

const { LeasePage } = await import('./registerPages');

const renderAt = (path: string, page: React.ReactElement) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/facilities/leases" element={page} />
      </Routes>
    </MemoryRouter>,
  );

beforeEach(() => {
  readIfimpDataset.mockReset();
  readIfimpDataset.mockResolvedValue({ rows: [], total: 0 });
  permits.mockReset();
});

describe('the lease register lists every record type its form can create', () => {
  it('asks for leases and tenancies together on the first Lease tab', async () => {
    permits.mockReturnValue(true);
    renderAt('/facilities/leases', <LeasePage />);

    await waitFor(() => expect(readIfimpDataset).toHaveBeenCalled());
    expect(readIfimpDataset.mock.calls[0][2]).toEqual({ recordType: 'LEASE,TENANCY' });
  });
});

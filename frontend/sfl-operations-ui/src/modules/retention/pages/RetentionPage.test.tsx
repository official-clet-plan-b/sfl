import { render, screen } from '@testing-library/react';
import { MemoryRouter as DomMemoryRouter } from 'react-router-dom';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ policies: vi.fn(), due: vi.fn(), set: vi.fn() }));
const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
vi.mock('shared/layout/actorPermissions', () => ({ permits }));
vi.mock('../api/retentionApi', () => ({ retentionApi: api }));

const { default: RetentionPage } = await import('./RetentionPage');

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset());
  permits.mockReset();
  permits.mockReturnValue(true);
  api.policies.mockResolvedValue([
    { systemCode: 'S172', recordClass: 'DIETARY_DATA', retentionDays: 90, action: 'ANONYMISE', basis: 'Sensitive: anonymised after the service', updatedBy: 'system', updatedAt: '2026-10-01T00:00:00Z' },
    { systemCode: 'S177', recordClass: 'LEGAL', retentionDays: 3650, action: 'REVIEW', basis: 'Default pending statutory confirmation', updatedBy: 'system', updatedAt: '2026-10-01T00:00:00Z' },
  ]);
  api.due.mockResolvedValue([]);
});

describe('record retention', () => {
  it('lists each period with its action and basis, and says when nothing has outlived its period', async () => {
    render(<DomMemoryRouter><MemoryRouter><RetentionPage /></MemoryRouter></DomMemoryRouter>);

    expect(await screen.findByText(/Catering & cafeteria · Dietary data/)).toBeInTheDocument();
    expect(screen.getByText(/90 days · anonymised afterwards/)).toBeInTheDocument();
    expect(screen.getByText(/3650 days · reported for disposal afterwards/)).toBeInTheDocument();
    expect(await screen.findByText('Nothing has outlived its period.')).toBeInTheDocument();
  });

  it('shows the periods to a role that can only read them, without a way to change one', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_RETENTION_READ');
    render(<DomMemoryRouter><MemoryRouter><RetentionPage /></MemoryRouter></DomMemoryRouter>);

    expect(await screen.findByText(/90 days · anonymised afterwards/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument();
  });
});

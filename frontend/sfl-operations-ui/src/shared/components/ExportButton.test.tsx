import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from './Notifier';

const permits = vi.hoisted(() => vi.fn<(permission?: string) => boolean>());
const downloadFile = vi.hoisted(() => vi.fn());
vi.mock('shared/layout/actorPermissions', () => ({ permits }));
vi.mock('shared/api/client', async (importOriginal) => ({ ...(await importOriginal<typeof import('shared/api/client')>()), downloadFile }));

const { default: ExportButton } = await import('./ExportButton');

const renderButton = () => render(<NotifierProvider><ExportButton path="/api/v1/facilities/leases/exports/agreements" siteCode="CLET-HQ" /></NotifierProvider>);

beforeEach(() => {
  permits.mockReset();
  downloadFile.mockReset();
  downloadFile.mockResolvedValue('x.csv');
});

describe('the export button', () => {
  it('is not offered to a role without the export grant', () => {
    permits.mockReturnValue(false);
    renderButton();
    expect(screen.queryByRole('button', { name: /export/i })).not.toBeInTheDocument();
  });

  it('will not download until a reason of ten characters is given, then sends it with the site', async () => {
    permits.mockImplementation((p) => p === 'FACILITIES_REGISTER_EXPORT');
    renderButton();

    await userEvent.click(screen.getByRole('button', { name: /export/i }));
    const submit = await screen.findByRole('button', { name: 'Download' });
    expect(submit).toBeDisabled();

    await userEvent.type(screen.getByRole('textbox'), 'Quarterly audit');
    await userEvent.click(submit);

    expect(downloadFile).toHaveBeenCalledWith('/api/v1/facilities/leases/exports/agreements', { siteCode: 'CLET-HQ', reason: 'Quarterly audit' }, 'export.csv', 'text/csv, application/json', 'facilities');
  });
});

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

/**
 * Closing an anomaly asks for the document, not for an identifier to paste.
 *
 * <p>Closure is the one anomaly transition the domain refuses without evidence, and the dialog used
 * to satisfy that with a bare text field reading "register the closure evidence under Evidence &
 * audit, then paste its identifier". The identifier is a UUID on no paperwork, so the real workflow
 * was: leave the case, find the record, copy the id, come back - on the mandatory field standing
 * between an operator and finishing the case. The compliance form and the fuel capture form were
 * both rewritten to remove exactly that instruction; this one was missed.
 *
 * <p>The assertions are about which control is on screen, because that is the regression: a version
 * that goes back to asking for an id would still pass any test that only checked the request body.
 */

const fuelAnomaliesApi = vi.hoisted(() => ({ transition: vi.fn() }));
const evidenceFilesApi = vi.hoisted(() => ({ upload: vi.fn() }));
const searchEvidenceChoices = vi.hoisted(() => vi.fn());

vi.mock('modules/fuel/api/fuelApi', async () => {
  const actual = await vi.importActual<typeof import('modules/fuel/api/fuelApi')>(
    'modules/fuel/api/fuelApi',
  );
  return { ...actual, fuelAnomaliesApi };
});
vi.mock('modules/fleet/api/fleetApi', () => ({ searchEvidenceChoices }));
vi.mock('shared/evidence/evidenceFilesApi', async () => {
  const actual = await vi.importActual<typeof import('shared/evidence/evidenceFilesApi')>(
    'shared/evidence/evidenceFilesApi',
  );
  return { ...actual, evidenceFilesApi };
});

const { AnomalyActionDialog } = await import('./anomalyDialogs');

/** Approved and explained, so the only thing closure still needs is the evidence. */
const closeable = {
  id: 'anomaly-1',
  anomalyNumber: 'FA-0001',
  siteCode: 'CLET-HQ',
  transactionId: 'txn-1',
  logbookId: null,
  vehicleId: 'vehicle-1',
  driverId: 'driver-1',
  tripId: 'trip-1',
  type: 'LIMIT_EXCEEDED',
  severity: 'HIGH',
  material: true,
  status: 'APPROVED',
  assignee: 'kofi.mensah',
  slaDueAt: '2026-08-10T00:00:00Z',
  explanation: 'Attendant overcharged; statement attached.',
  evidenceId: null,
  decision: 'APPROVED',
  closureReason: null,
  escalationLevel: 0,
  detectedRules: ['MAX_PER_TRANSACTION'],
} as never;

const renderClose = () =>
  render(
    <MemoryRouter>
      <NotifierProvider>
        <AnomalyActionDialog
          open
          anomaly={closeable}
          action="close"
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />
      </NotifierProvider>
    </MemoryRouter>,
  );

describe('AnomalyActionDialog closure evidence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:preview');
    URL.revokeObjectURL = vi.fn();
    searchEvidenceChoices.mockResolvedValue([]);
    evidenceFilesApi.upload.mockResolvedValue({ id: 'evidence-9' });
    fuelAnomaliesApi.transition.mockResolvedValue({});
  });

  it('offers a file, and never an identifier to paste', () => {
    renderClose();

    expect(screen.getByText(/closure evidence/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /upload file/i })).toBeInTheDocument();
    expect(screen.queryByText(/paste its identifier/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/evidence reference/i)).not.toBeInTheDocument();
  });

  it('uploads the document and closes the case with the id it returns', async () => {
    const { container } = renderClose();

    await userEvent.type(screen.getByLabelText(/closure reason/i), 'Recovered from the vendor.');

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    await userEvent.upload(
      input,
      new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0xff, 0xd9])], 'statement.jpg', {
        type: 'image/jpeg',
      }),
    );

    // By type, not by name: the dialog's dismiss button is also called "Close", and the submit
    // control is the one that matters here.
    await userEvent.click(container.querySelector('button[type="submit"]') as HTMLButtonElement);

    // Filed against the case rather than the vehicle: it documents this decision, and an anomaly
    // raised from a logbook has no vehicle to file under at all.
    await waitFor(() =>
      expect(evidenceFilesApi.upload).toHaveBeenCalledWith(
        expect.objectContaining({
          siteCode: 'CLET-HQ',
          relatedRecordType: 'FuelAnomalyCase',
          relatedRecordId: 'anomaly-1',
          retentionClass: 'COMPLIANCE_7_YEARS',
        }),
      ),
    );

    // The transition carries the id the upload returned - nobody copies it by hand.
    await waitFor(() =>
      expect(fuelAnomaliesApi.transition).toHaveBeenCalledWith(
        'anomaly-1',
        'close',
        expect.objectContaining({ evidenceId: 'evidence-9' }),
      ),
    );
  });

  it('still allows a document already filed against the case', async () => {
    renderClose();

    await userEvent.click(
      screen.getByRole('button', { name: /use a document already filed against this case/i }),
    );

    // One certificate can settle two cases, so the picker does not go away - it just stops being
    // the only way in.
    expect(searchEvidenceChoices).toHaveBeenCalledWith(
      'FuelAnomalyCase',
      'anomaly-1',
      expect.anything(),
    );
  });

  it('does not upload anything when the case is not closeable', () => {
    render(
      <NotifierProvider>
        <AnomalyActionDialog
          open
          anomaly={{ ...(closeable as object), status: 'DETECTED', decision: null, explanation: null } as never}
          action="close"
          onClose={vi.fn()}
          onSaved={vi.fn()}
        />
      </NotifierProvider>,
    );

    // The dialog names what is missing and blocks, rather than letting the operator write a reason
    // and attach a file only to be refused by the service.
    expect(screen.getByText(/the service will refuse this closure/i)).toBeInTheDocument();
    expect(evidenceFilesApi.upload).not.toHaveBeenCalled();
  });
});

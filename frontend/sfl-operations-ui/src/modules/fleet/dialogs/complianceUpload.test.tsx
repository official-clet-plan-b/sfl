import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotifierProvider } from 'shared/components/Notifier';

/**
 * The compliance document form asks for the document, and will not register one without it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The change it protects was reported as not working, and the report was reasonable: the code was
 * correct, the dev server was serving it, and the browser was showing a cached bundle. Nothing in the
 * suite would have caught the opposite case - a form that silently lost its upload field - because
 * the whole of this dialog's behaviour lived in a screenshot nobody was taking.
 *
 * <p>So it asserts the three things that actually matter to the requirement, in the terms the
 * requirement used: the file control is there, only PDF/JPG/JPEG are offered, and submitting without
 * a file does not create a record.
 */

const vehiclesApi = vi.hoisted(() => ({ registerComplianceDocument: vi.fn() }));
const searchEvidenceChoices = vi.hoisted(() => vi.fn());
const evidenceFilesApi = vi.hoisted(() => ({ upload: vi.fn() }));

vi.mock('modules/fleet/api/fleetApi', () => ({ vehiclesApi, searchEvidenceChoices }));
vi.mock('shared/evidence/evidenceFilesApi', async () => {
  // The constants are real: a test that invented its own accept list would pass while the field
  // offered something else entirely.
  const actual = await vi.importActual<typeof import('shared/evidence/evidenceFilesApi')>(
    'shared/evidence/evidenceFilesApi',
  );
  return { ...actual, evidenceFilesApi };
});

const { RegisterComplianceDocumentDialog } = await import('./vehicleDialogs');

const renderDialog = () =>
  render(
    <NotifierProvider>
      <RegisterComplianceDocumentDialog
        open
        vehicleId="vehicle-1"
        siteCode="CLET-HQ"
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    </NotifierProvider>,
  );

/**
 * Picks from the dashboard's own listbox, which is not a native `<select>`.
 *
 * <p>`Select` is a custom ARIA combobox - a trigger button that opens a `role="listbox"` - because a
 * native select cannot be styled consistently across browsers. So `user.selectOptions` does not apply
 * to it, and a test that reaches for it fails on the control rather than on the behaviour.
 */
const choose = async (user: ReturnType<typeof userEvent.setup>, label: RegExp, option: RegExp) => {
  await user.click(screen.getByRole('combobox', { name: label }));
  await user.click(await screen.findByRole('option', { name: option }));
};

/**
 * Sets a date field through its calendar, the way a person does.
 *
 * <p>The field is the library's `DateSelector`: a trigger button that opens a calendar popover. The
 * dialog's own rule is that a document must expire after it was issued, and issue defaults to today,
 * so the first selectable day of next month satisfies it whatever day the suite runs on.
 */
const pickNextMonthDate = async (user: ReturnType<typeof userEvent.setup>, label: RegExp) => {
  const field = screen.getByRole('group', { name: label });
  await user.click(within(field).getByRole('button'));
  await user.click(await screen.findByRole('button', { name: 'Next month' }));
  const days = (await screen.findAllByRole('gridcell')).filter(
    (day) => !day.hasAttribute('disabled'),
  );
  await user.click(days[0]);
};

const fillTheTextFields = async (user: ReturnType<typeof userEvent.setup>) => {
  await choose(user, /document type/i, /roadworthiness/i);
  await user.type(screen.getByLabelText(/document reference/i), 'RW-2026-0001');
  await user.type(screen.getByLabelText(/issuing authority/i), 'DVLA');
  // Issued on is already defaulted to today; only the expiry needs choosing.
  await pickNextMonthDate(user, /expires on/i);
};

describe('RegisterComplianceDocumentDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    /*
      jsdom implements neither half of the object-URL API, and `EvidenceFileField` builds a preview
      from `createObjectURL` the moment an image is chosen. Without these the component throws on
      selection and the failure surfaces as "cannot find the submit button", which points at entirely
      the wrong thing. Stubbed rather than guarded in the component: every real browser has them, and
      a runtime check would be dead code shaped like caution.
    */
    URL.createObjectURL = vi.fn(() => 'blob:preview');
    URL.revokeObjectURL = vi.fn();
    searchEvidenceChoices.mockResolvedValue([]);
    evidenceFilesApi.upload.mockResolvedValue({ id: 'evidence-1' });
    vehiclesApi.registerComplianceDocument.mockResolvedValue({ id: 'doc-1' });
  });

  it('asks for the document itself, not for an evidence identifier to paste', () => {
    renderDialog();

    expect(screen.getByText('The document')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /upload file/i })).toBeInTheDocument();
    // The instruction that could not be followed. A driver - and most operators - have no Evidence
    // and audit screen, so an identifier to paste was never obtainable.
    expect(screen.queryByText(/paste its identifier/i)).not.toBeInTheDocument();
  });

  it('accepts only PDF, JPG and JPEG', () => {
    renderDialog();

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input).not.toBeNull();
    expect(input.accept).toBe('.pdf,.jpg,.jpeg,application/pdf,image/jpeg');
  });

  it('refuses to register a document when no file is attached', async () => {
    const user = userEvent.setup();
    renderDialog();
    await fillTheTextFields(user);

    await user.click(screen.getByRole('button', { name: /register document/i }));

    await waitFor(() =>
      expect(screen.getByText(/attach the document itself/i)).toBeInTheDocument(),
    );
    // The point of the ordering: no compliance record is created asserting a certificate that was
    // never supplied.
    expect(vehiclesApi.registerComplianceDocument).not.toHaveBeenCalled();
  });

  it('uploads the file first, then registers the document against the id it returns', async () => {
    const user = userEvent.setup();
    renderDialog();
    await fillTheTextFields(user);

    const file = new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], 'certificate.jpg', {
      type: 'image/jpeg',
    });
    await user.upload(document.querySelector('input[type="file"]') as HTMLInputElement, file);
    await user.click(screen.getByRole('button', { name: /register document/i }));

    await waitFor(() => expect(evidenceFilesApi.upload).toHaveBeenCalledTimes(1));
    expect(evidenceFilesApi.upload).toHaveBeenCalledWith(
      expect.objectContaining({
        siteCode: 'CLET-HQ',
        relatedRecordType: 'Vehicle',
        relatedRecordId: 'vehicle-1',
        evidenceType: 'ROADWORTHINESS_CERTIFICATE',
        // The class the fleet service actually defines. An invented one is refused at the boundary
        // with a type-conversion error, which is how this was found the first time.
        retentionClass: 'COMPLIANCE_7_YEARS',
        file,
      }),
    );
    expect(vehiclesApi.registerComplianceDocument).toHaveBeenCalledWith(
      'vehicle-1',
      expect.objectContaining({ evidenceId: 'evidence-1' }),
    );
  });
});

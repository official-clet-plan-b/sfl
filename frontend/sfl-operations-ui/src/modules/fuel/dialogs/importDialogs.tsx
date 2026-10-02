import { ImportResult } from 'modules/fuel/api/dto';
import { CSV_OPTIONAL_HEADERS, CSV_REQUIRED_HEADERS } from 'modules/fuel/api/enums';
import { fuelImportsApi } from 'modules/fuel/api/fuelApi';
import { FileField } from 'modules/fuel/components/fuelUi';
import { MAX_IMPORT_BYTES } from 'shared/evidence/evidenceFilesApi';
import { Alert, FormDialog } from 'modules/fuel/components/fuelUi';
import SiteSelect from 'shared/components/SiteSelect';
import { TextInput } from 'modules/fuel/components/fuelUi';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';

interface CsvImportDialogProps {
  open: boolean;
  onClose: () => void;
  onImported: (result: ImportResult) => void;
  defaultSiteCode: string;
}

/**
 * CSV import - `POST /api/v1/fuel/imports/csv`, multipart.
 *
 * Every row goes through the same `capture` command as a manual entry, so a row can fail for any
 * reason a capture can: an unknown vehicle, a total that does not match, an unparseable instant. The
 * service records each outcome and returns them all; **it never rejects the batch for one bad row**,
 * which is why the result screen matters more than this dialog does.
 *
 * The file itself is validated here for presence only. Header names, column count and row content
 * are the service's to judge, and duplicating that check would mean maintaining a second parser
 * that can disagree with the first.
 *
 * The batch is recorded and readable afterwards, so an operator who leaves this screen can come
 * back to the rejected rows rather than losing them.
 */
export const CsvImportDialog = ({
  open,
  onClose,
  onImported,
  defaultSiteCode,
}: CsvImportDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      sourceSystem: 'CSV-IMPORT',
      file: null as File | null,
    },
    schema: {
      siteCode: required('Site code'),
      sourceSystem: compose(required('Source system'), maxLength('Source system', 100)),
      file: required('CSV file'),
    },
    onSubmit: async (values) => {
      const file = values.file as File;
      const result = await fuelImportsApi.uploadCsv(
        values.siteCode.trim().toUpperCase(),
        values.sourceSystem.trim(),
        file,
      );
      onImported(result);
      onClose();
    },
  });

  return (
    <FormDialog
      open={open}
      title="Import fuel transactions from CSV"
      description="Each row is captured exactly as a manual entry would be, and each is accepted or rejected on its own."
      submitLabel="Upload and import"
      submitting={form.submitting}
      formError={form.formError}
      maxWidth="lg"
      onClose={onClose}
      onSubmit={form.submit}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <SiteSelect
          required
          value={form.values.siteCode}
          onChange={(value) => form.setValue('siteCode', value)}
          {...form.fieldProps('siteCode')}
        />
        <TextInput
          label="Source system"
          required
          value={form.values.sourceSystem}
          onChange={(value) => form.setValue('sourceSystem', value)}
          {...form.fieldProps(
            'sourceSystem',
            'Recorded as the provenance of every row in this batch.',
          )}
        />
      </div>

      {/*
        A provider's monthly ledger, not a photograph, so it answers to the import ceiling rather
        than the evidence one - `BulkImportPolicy` on the service enforces the same number.
      */}
      <FileField
        label="CSV file"
        required
        accept=".csv,text/csv"
        maxBytes={MAX_IMPORT_BYTES}
        value={form.values.file}
        onChange={(file) => form.setValue('file', file)}
        {...form.fieldProps('file', 'A header row plus at least one data row.')}
      />

      <Alert variant="info" title="Required column headers">
        <p className="mt-1">
          The first row must name the columns. These ten are required on every row:
        </p>
        <p className="mt-1.5 font-mono text-theme-xs break-words text-gray-900">
          {CSV_REQUIRED_HEADERS.join(', ')}
        </p>
        <p className="mt-2">These may also be present and are optional:</p>
        <p className="mt-1.5 font-mono text-theme-xs break-words text-gray-900">
          {CSV_OPTIONAL_HEADERS.join(', ')}
        </p>
        <p className="mt-2">
          Every row must have the same number of columns as the header.
        </p>
      </Alert>

      <Alert variant="info" title="A file can only be imported once">
        The batch is keyed on the file’s own content hash. Re-uploading the same file for this site
        and source system is refused before any row is captured, and the error names the batch that
        already holds it.
      </Alert>

    </FormDialog>
  );
};

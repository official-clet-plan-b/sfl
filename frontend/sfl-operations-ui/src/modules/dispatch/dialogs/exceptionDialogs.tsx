import { DispatchExceptionCase } from 'modules/dispatch/api/dto';
import { ExceptionAction, dispatchExceptionsApi, scanImportsApi } from 'modules/dispatch/api/dispatchApi';
import { SCAN_CSV_HEADERS } from 'modules/dispatch/api/enums';
import { EXCEPTION_RULES, exceptionClosureBlockers } from 'modules/dispatch/api/workflow';
import { MAX_IMPORT_BYTES } from 'shared/evidence/evidenceFilesApi';
import SiteSelect from 'shared/components/SiteSelect';
import { humanise } from 'modules/fleet/api/enums';
// One evidence store serves the whole FTLMP service - a dispatch case's evidence is written into
// the same table as a trip's, by RecordedDispatchEvidenceAdapter. Same reuse as `humanise` above.
import { searchEvidenceChoices } from 'modules/fleet/api/fleetApi';
import { EvidenceSelect } from 'shared/components/EvidenceSelect';
import { AcceptingFileField } from 'modules/dispatch/components/dialogParts';
import {
  Callout,
  FormModal,
  TextAreaField,
  TextField,
} from 'modules/dispatch/components/formKit';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';
import { ScanImportBatch } from 'modules/dispatch/api/dto';

interface ExceptionActionDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  exceptionCase: DispatchExceptionCase;
  action: ExceptionAction;
}

/**
 * All thirteen dispatch exception transitions in one dialog.
 *
 * `POST /exceptions/{id}/{action}` takes a single `ActionRequest { value, evidenceId }`, and the
 * service overloads `value` by action - an assignee for assign and reassign, the explanation text
 * for explain, the reason for everything else. One endpoint, one request shape, one dialog.
 *
 * `close` is the one with a gate: `DispatchExceptionCase.close` demands an explanation and a
 * decision already on the record plus evidence supplied with the closure, and refuses with a single
 * message naming all three. The dialog shows which are actually missing and blocks submission.
 */
export const ExceptionActionDialog = ({
  open,
  onClose,
  onSaved,
  exceptionCase,
  action,
}: ExceptionActionDialogProps) => {
  const rule = EXCEPTION_RULES[action];
  const needsValue = rule.requiredField !== null;
  const needsEvidence = Boolean(rule.requiresEvidence);
  const blockers = action === 'close' ? exceptionClosureBlockers(exceptionCase) : [];

  const form = useFleetForm({
    initialValues: {
      value: action === 'reassign' ? (exceptionCase.assignee ?? '') : '',
      evidenceId: '',
    },
    schema: {
      value: needsValue
        ? compose(required(fieldLabel(action)), maxLength(fieldLabel(action), 2000))
        : maxLength(fieldLabel(action), 2000),
      evidenceId: needsEvidence ? required('Evidence reference') : undefined,
    },
    onSubmit: async (values) => {
      await dispatchExceptionsApi.transition(exceptionCase.id, action, {
        value: values.value.trim() || null,
        evidenceId: values.evidenceId.trim() || null,
      });
      onSaved();
      onClose();
    },
  });

  const note = ACTION_NOTES[action];
  const longText = action !== 'assign' && action !== 'reassign';

  return (
    <FormModal
      open={open}
      title={`${rule.label} · ${exceptionCase.exceptionNumber}`}
      description={describe(exceptionCase)}
      submitLabel={rule.label}
      submitting={form.submitting}
      submitDisabled={blockers.length > 0}
      formError={form.formError}
      destructive={action === 'cancel' || action === 'reject'}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {blockers.length > 0 && (
        <Callout tone="danger" title="The service will refuse this closure">
          <ul className="mt-1 list-disc space-y-1 pl-4">
            {blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
        </Callout>
      )}

      {exceptionCase.securityRelevant && action === 'escalate' && (
        <Callout tone="warning" title="This case is security relevant">
          Escalating it surfaces the case to the security function as well as to the dispatch
          manager.
        </Callout>
      )}

      {note && <Callout tone={rule.privileged ? 'warning' : 'info'}>{note}</Callout>}

      {(needsValue || action === 'explain') &&
        (longText ? (
          <TextAreaField
            label={fieldLabel(action)}
            required={needsValue}
            rows={4}
            value={form.values.value}
            onChange={(value) => form.setValue('value', value)}
            {...form.fieldProps('value')}
          />
        ) : (
          <TextField
            label={fieldLabel(action)}
            required={needsValue}
            value={form.values.value}
            onChange={(value) => form.setValue('value', value)}
            {...form.fieldProps('value')}
          />
        ))}

      {/*
        The case itself holds no evidence - dispatch files evidence against the records a case points
        at. `CourierItem` is the only link with a single unambiguous record type; a dispatch-level
        case spreads its evidence across CustodyHandover, DispatchReceipt and ReturnReconciliation,
        and the search takes one type, so those fall through to the text field rather than showing a
        list that is quietly missing two thirds of the evidence.
      */}
      {(needsEvidence || action === 'explain') && (
        <EvidenceSelect
          label="Evidence"
          required={needsEvidence}
          search={searchEvidenceChoices}
          relatedRecordType={exceptionCase.courierItemId ? 'CourierItem' : null}
          relatedRecordId={exceptionCase.courierItemId}
          value={form.values.evidenceId}
          onChange={(value) => form.setValue('evidenceId', value)}
          {...form.fieldProps(
            'evidenceId',
            needsEvidence
              ? 'Anything filed against this case is offered here.'
              : 'Optional. Attach what the explanation refers to.',
          )}
        />
      )}
    </FormModal>
  );
};

const fieldLabel = (action: ExceptionAction): string => {
  switch (action) {
    case 'assign':
    case 'reassign':
      return 'Assignee';
    case 'explain':
      return 'Explanation';
    case 'approve':
    case 'reject':
      return 'Decision reason';
    case 'escalate':
      return 'Reason for escalating';
    case 'hold':
      return 'Reason for the hold';
    case 'cancel':
      return 'Reason for cancelling';
    case 'close':
      return 'Closure reason';
    case 'reopen':
      return 'Reason for reopening';
    default:
      return 'Note';
  }
};

const ACTION_NOTES: Partial<Record<ExceptionAction, string>> = {
  assign: 'The assignee is notified and becomes accountable for the case.',
  reassign: 'The new assignee is notified. The case returns to the assigned state.',
  review: 'Moves the case to under review, from where a decision can be recorded.',
  'request-explanation':
    'Moves the case to awaiting explanation. Record the response when it arrives.',
  explain: 'The explanation is one of the three things closure requires.',
  approve: 'Privileged - needs DISPATCH_EXCEPTION_APPROVE. The case still has to be closed.',
  reject: 'Privileged - needs DISPATCH_EXCEPTION_APPROVE. The case still has to be closed.',
  escalate: 'Privileged - needs DISPATCH_EXCEPTION_ESCALATE. Raises the escalation level.',
  hold: 'The assignee is notified that the case is blocked. Resume it when the block clears.',
  resume: 'Returns the case to under review.',
  cancel: 'Privileged. The case stays in the register and in the audit trail.',
  close:
    'Privileged. While this case is open, the manifest it belongs to cannot be closed - so closing it here is what unblocks the consignment.',
  reopen: 'Privileged. The case returns to the queue and blocks manifest closure again.',
};

const describe = (exceptionCase: DispatchExceptionCase): string => {
  const parts = [
    humanise(exceptionCase.type).toLowerCase(),
    `${exceptionCase.severity.toLowerCase()} severity`,
  ];
  if (exceptionCase.securityRelevant) {
    parts.push('security relevant');
  }
  if (exceptionCase.assignee) {
    parts.push(`assigned to ${exceptionCase.assignee}`);
  }
  const sentence = parts.join(' · ');
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
};

/* ------------------------------------------------------------ scan import */

interface ScanImportDialogProps {
  open: boolean;
  onClose: () => void;
  onImported: (batch: ScanImportBatch) => void;
  defaultSiteCode: string;
  /** Pre-selects the consignment the scans are checked against. */
  dispatchId?: string;
}

/**
 * Import a scanner batch - `POST /scans/imports`, multipart.
 *
 * Two positional columns: the row reference, then the scanned code. A single-column file is read as
 * the code with a reference generated for it. Each row is classified against the manifest and lands
 * as MATCHED, MISMATCH or UNREGISTERED - a mismatch or an unregistered code raises an exception
 * case, which is the whole reason for importing the batch.
 */
export const ScanImportDialog = ({
  open,
  onClose,
  onImported,
  defaultSiteCode,
  dispatchId,
}: ScanImportDialogProps) => {
  const form = useFleetForm({
    initialValues: {
      siteCode: defaultSiteCode,
      sourceSystem: 'HANDHELD-SCANNER',
      batchReference: '',
      file: null as File | null,
    },
    schema: {
      siteCode: required('Site code'),
      sourceSystem: compose(required('Source system'), maxLength('Source system', 100)),
      batchReference: maxLength('Batch reference', 120),
      file: required('CSV file'),
    },
    onSubmit: async (values) => {
      const batch = await scanImportsApi.upload(
        values.siteCode.trim().toUpperCase(),
        values.sourceSystem.trim(),
        values.file as File,
        {
          batchReference: values.batchReference.trim() || undefined,
          dispatchId,
        },
      );
      onImported(batch);
      onClose();
    },
  });

  return (
    <FormModal
      open={open}
      title="Import a scanner batch"
      description="Each scanned code is checked against the manifest and recorded with its outcome."
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
        <TextField
          label="Source system"
          required
          value={form.values.sourceSystem}
          onChange={(value) => form.setValue('sourceSystem', value)}
          {...form.fieldProps('sourceSystem', 'Which scanner or gateway produced the file.')}
        />
        <TextField
          label="Batch reference"
          value={form.values.batchReference}
          onChange={(value) => form.setValue('batchReference', value)}
          {...form.fieldProps('batchReference', 'Optional. The scanner’s own batch identifier.')}
        />
      </div>

      {/* A scanner's whole batch answers to the import ceiling, not the evidence one. */}
      <AcceptingFileField
        label="Scan CSV"
        required
        accept=".csv,text/csv"
        maxBytes={MAX_IMPORT_BYTES}
        value={form.values.file}
        onChange={(file) => form.setValue('file', file)}
        {...form.fieldProps('file', 'A header row plus at least one scanned row.')}
      />

      <Callout tone="info" title="File format">
        <p className="mt-1">
          Two columns, read by position rather than by name - the header row is skipped:
        </p>
        <p className="mt-1.5 font-mono text-xs text-foreground">
          {SCAN_CSV_HEADERS.join(', ')}
        </p>
        <p className="mt-2">
          A file with a single column is read as the scanned code, with a row reference generated for
          each line.
        </p>
      </Callout>

      <Callout tone="warning" title="Mismatches raise cases">
        A code that does not match the manifest, or that belongs to no registered item, records the
        row as a mismatch and opens an exception case. That is the point of the import - but it means
        a batch scanned against the wrong consignment will raise a case per row.
      </Callout>
    </FormModal>
  );
};

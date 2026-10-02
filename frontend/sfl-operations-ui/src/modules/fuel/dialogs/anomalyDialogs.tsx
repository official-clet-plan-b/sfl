import { useState } from 'react';
import { FuelAnomalyCase } from 'modules/fuel/api/dto';
import { AnomalyAction, fuelAnomaliesApi } from 'modules/fuel/api/fuelApi';
import { ANOMALY_RULES, anomalyClosureBlockers } from 'modules/fuel/api/workflow';
import { searchEvidenceChoices } from 'modules/fleet/api/fleetApi';
import { Alert, Button, EvidenceFileField, EvidenceSelect, FormDialog, TextAreaInput, TextInput } from 'modules/fuel/components/fuelUi';
import { evidenceFilesApi } from 'shared/evidence/evidenceFilesApi';
import { FleetApiError } from 'shared/errors/FleetApiError';
import { useFleetForm } from 'shared/validation/useFleetForm';
import { compose, maxLength, required } from 'shared/validation/validators';

/**
 * Closure evidence is filed against the case, not against the vehicle.
 *
 * <p>It documents a decision about this anomaly - an attendant's statement, a corrected receipt, a
 * manager's note - so the case is the record it belongs to. Filing it under the vehicle would put it
 * in a list where nothing says which of forty anomalies it settles, and an anomaly raised from a
 * logbook has no vehicle to file under at all.
 */
const EVIDENCE_RECORD_TYPE = 'FuelAnomalyCase';

interface AnomalyActionDialogProps {
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
  anomaly: FuelAnomalyCase;
  action: AnomalyAction;
}

/**
 * All thirteen anomaly transitions in one dialog.
 *
 * `POST /anomalies/{id}/{action}` takes a single `ActionRequest { value, evidenceId }`, and the
 * service overloads `value` by action: an assignee for assign and reassign, the explanation text
 * for explain, and the reason for everything else. One endpoint, one request shape, one dialog -
 * what changes is the label on the field, whether it is mandatory, and whether evidence is asked
 * for. Splitting this into thirteen components would duplicate the same twenty lines thirteen times
 * and make it easy for one of them to drift from the service.
 *
 * `close` is the interesting one. `FuelAnomalyCase.close` demands three things - an explanation and
 * a decision already on the record, plus evidence supplied with the closure - and refuses with one
 * message naming all three. The dialog shows which of them are actually missing and blocks
 * submission, rather than letting the operator write a closure reason and then be told no.
 */
export const AnomalyActionDialog = ({
  open,
  onClose,
  onSaved,
  anomaly,
  action,
}: AnomalyActionDialogProps) => {
  const rule = ANOMALY_RULES[action];
  const needsValue = rule.requiredField !== null;
  const needsEvidence = Boolean(rule.requiresEvidence);
  const showsEvidence = needsEvidence || action === 'explain';
  const blockers = action === 'close' ? anomalyClosureBlockers(anomaly) : [];

  const [file, setFile] = useState<File | null>(null);
  const [useExisting, setUseExisting] = useState(false);
  const [uploading, setUploading] = useState(false);

  const form = useFleetForm({
    initialValues: { value: defaultValue(anomaly, action), evidenceId: '' },
    schema: {
      value: needsValue
        ? compose(required(fieldLabel(action)), maxLength(fieldLabel(action), 2000))
        : maxLength(fieldLabel(action), 2000),
      // Only when picking one already filed. An upload has no id to require until it has happened.
      evidenceId: needsEvidence && useExisting ? required('Evidence reference') : undefined,
    },
    onSubmit: async (values) => {
      /*
        The document goes up first, and the transition carries the id it returns.

        That ordering matters for the same reason it does on the compliance form: if the upload is
        refused - wrong type, active content in a PDF, over the size limit - the operator is told why
        while still holding the dialog, and no case is closed citing evidence that was never stored.
        The reverse order would leave exactly that.
      */
      let evidenceId = values.evidenceId.trim();
      if (showsEvidence && !useExisting && file) {
        setUploading(true);
        try {
          evidenceId = (
            await evidenceFilesApi.upload({
              siteCode: String(anomaly.siteCode),
              relatedRecordType: EVIDENCE_RECORD_TYPE,
              relatedRecordId: anomaly.id,
              evidenceType: action === 'explain' ? 'FUEL_ANOMALY_EXPLANATION' : 'FUEL_ANOMALY_CLOSURE',
              // The financial record this supports lives seven years, and there is no finance class
              // of its own - inventing one would put these outside every retention sweep that exists.
              retentionClass: 'COMPLIANCE_7_YEARS',
              file,
            })
          ).id;
        } finally {
          setUploading(false);
        }
      }

      if (needsEvidence && !evidenceId) {
        throw FleetApiError.validation(
          'Attach the closure evidence, or choose one already filed against this case.',
        );
      }

      await fuelAnomaliesApi.transition(anomaly.id, action, {
        value: values.value.trim() || null,
        evidenceId: evidenceId || null,
      });
      onSaved();
      onClose();
    },
  });

  const note = ACTION_NOTES[action];

  return (
    <FormDialog
      open={open}
      title={`${rule.label} · ${anomaly.anomalyNumber}`}
      description={anomalyDescription(anomaly)}
      submitLabel={rule.label}
      submitting={form.submitting}
      // The upload happens before the transition, and on a slow connection a silent button looks
      // broken. This is pinned above the actions, where the operator is already looking.
      summary={uploading ? 'Uploading the evidence…' : undefined}
      submitDisabled={blockers.length > 0}
      formError={form.formError}
      destructive={action === 'cancel' || action === 'reject'}
      onClose={onClose}
      onSubmit={form.submit}
    >
      {blockers.length > 0 && (
        <Alert variant="error" title="The service will refuse this closure">
          <ul className="mt-1 list-disc space-y-1 pl-4">
            {blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
        </Alert>
      )}

      {note && <Alert variant={rule.privileged ? 'warning' : 'info'}>{note}</Alert>}

      {(needsValue || action === 'explain') && (
        <>
          {isLongText(action) ? (
            <TextAreaInput
              label={fieldLabel(action)}
              required={needsValue}
              rows={4}
              value={form.values.value}
              onChange={(value) => form.setValue('value', value)}
              {...form.fieldProps('value')}
            />
          ) : (
            <TextInput
              label={fieldLabel(action)}
              required={needsValue}
              value={form.values.value}
              onChange={(value) => form.setValue('value', value)}
              {...form.fieldProps('value')}
            />
          )}
        </>
      )}

      {/*
        The document itself, not an identifier to paste.

        This asked the operator to "register the closure evidence under Evidence & audit, then paste
        its identifier" - the same instruction the compliance form and the fuel capture form were
        both rewritten to remove, and for the same reason: the id is a UUID that appears on no
        paperwork, so the real workflow was to open another screen, find the record, copy the id and
        come back. Closure *demands* evidence, so this was the mandatory field standing between an
        operator and finishing a case.
      */}
      {showsEvidence &&
        (useExisting ? (
          <div>
            <EvidenceSelect
              label="Evidence"
              required={needsEvidence}
              search={searchEvidenceChoices}
              relatedRecordType={EVIDENCE_RECORD_TYPE}
              relatedRecordId={anomaly.id}
              value={form.values.evidenceId}
              onChange={(value) => form.setValue('evidenceId', value)}
              {...form.fieldProps(
                'evidenceId',
                'A document already filed against this case.',
              )}
            />
            <Button
              size="sm"
              variant="ghost"
              className="mt-1.5"
              onClick={() => {
                setUseExisting(false);
                form.setValue('evidenceId', '');
              }}
            >
              Upload a new document instead
            </Button>
          </div>
        ) : (
          <div>
            <EvidenceFileField
              label={needsEvidence ? 'Closure evidence' : 'Supporting evidence'}
              required={needsEvidence}
              value={file}
              onChange={setFile}
              helperText={
                needsEvidence
                  ? 'The statement, corrected receipt or note this closure rests on.'
                  : 'Optional. The receipt or statement the explanation refers to.'
              }
            />
            <Button size="sm" variant="ghost" className="mt-1.5" onClick={() => setUseExisting(true)}>
              Use a document already filed against this case
            </Button>
          </div>
        ))}
    </FormDialog>
  );
};

/** Reassigning pre-fills the current owner, which is what an operator is usually amending. */
const defaultValue = (anomaly: FuelAnomalyCase, action: AnomalyAction): string =>
  action === 'reassign' ? (anomaly.assignee ?? '') : '';

const fieldLabel = (action: AnomalyAction): string => {
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

const isLongText = (action: AnomalyAction): boolean =>
  action !== 'assign' && action !== 'reassign';

/** What each action does that the operator cannot see from the button. */
const ACTION_NOTES: Partial<Record<AnomalyAction, string>> = {
  assign: 'The assignee is notified. Their name is recorded against the case.',
  reassign: 'The new assignee is notified. The case returns to the assigned state.',
  review: 'Moves the case to under review, from where a decision can be recorded.',
  'request-explanation':
    'Moves the case to awaiting explanation. Record the response when it arrives.',
  explain:
    'The explanation is one of the three things closure requires. Attach the supporting evidence if you have it.',
  approve:
    'Privileged - needs FUEL_ANOMALY_APPROVE. Records an approved decision; the case still has to be closed.',
  reject:
    'Privileged - needs FUEL_ANOMALY_APPROVE. Records a rejected decision; the case still has to be closed.',
  escalate:
    'Privileged - needs FUEL_ANOMALY_ESCALATE. Raises the escalation level and notifies the fleet manager. A material case is also surfaced to finance and audit.',
  hold: 'The assignee is notified that the case is blocked. Resume it when the block clears.',
  resume: 'Returns the case to under review.',
  cancel: 'Privileged. The case stays in the register and in the audit trail.',
  close: 'Privileged - needs FUEL_ANOMALY_APPROVE. Closure is final unless the case is reopened.',
  reopen: 'Privileged. The case returns to the queue and can be reassigned.',
};

const anomalyDescription = (anomaly: FuelAnomalyCase): string => {
  const parts = [
    anomaly.type.replace(/_/g, ' ').toLowerCase(),
    `${anomaly.severity.toLowerCase()} severity`,
  ];
  if (anomaly.material) {
    parts.push('material');
  }
  if (anomaly.assignee) {
    parts.push(`assigned to ${anomaly.assignee}`);
  }
  const sentence = parts.join(' · ');
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
};

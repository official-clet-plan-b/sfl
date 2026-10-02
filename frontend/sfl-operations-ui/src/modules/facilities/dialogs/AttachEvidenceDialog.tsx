import { useState } from 'react';
import { Button, Notice, UploadField } from '@rfdtech/components';
import Icon from 'shared/components/Icon';
import { FleetApiError, isFleetApiError } from 'shared/errors/FleetApiError';
import { MAX_UPLOAD_BYTES } from 'shared/evidence/evidenceFilesApi';
import { digestUnavailable, formatBytes, sha256OfFile } from 'shared/files/digest';
import type { AttachEvidenceRequest } from '../api/dto';
import type { EvidenceType, RetentionClass } from '../api/enums';
import { evidenceTypes, retentionClasses } from '../api/enums';
import { humaniseCode } from '../components/facilitiesFormat';
import { FieldFrame, FormDialog, SelectInput, TextAreaInput, TextInput } from 'modules/facilities/dialogs/dialogKit';

interface AttachEvidenceDialogProps {
  onClose: () => void;
  onSubmit: (request: AttachEvidenceRequest) => Promise<void>;
}

/** Lower-case hex SHA-256. The service applies the same pattern, and so does the database. */
const SHA_256 = /^[0-9a-fA-F]{64}$/;

/**
 * Attaching closure evidence - SRS-SFL-S153-03.
 *
 * ## The file is read here and uploaded nowhere
 *
 * The architecture standard stores evidence **by reference**: the bytes live in the document and
 * object-storage service, and this service records where they landed and what they hashed to. That
 * has not changed and this dialog does not upload anything.
 *
 * What has changed is who computes the digest. Choosing the file fills the name, the media type, the
 * size and - the one that matters - the SHA-256, because the browser has the bytes and can hash them.
 * Before this, a technician standing in a plant room with a photograph had to obtain a digest from
 * somewhere else and type sixty-four hexadecimal characters into a form. Nobody does that correctly,
 * and a mistyped digest is the one error in this system that surfaces years later, during an
 * integrity check, on evidence nobody can now re-hash - as a **false report that the file was
 * tampered with**.
 *
 * The storage reference is still typed, because it genuinely cannot be known here: it is what the
 * document service gives back, and this dashboard cannot call it. `S153_UI_Gap_Report.md` §2.1 owns
 * that half, and it belongs to whoever builds that integration.
 *
 * ## Why the digest can still be entered by hand
 *
 * Three cases where the browser cannot hash: a file above the size cap, a browser without Web Crypto,
 * and a dashboard served over plain HTTP. In each the field falls back to what it was - manual - with
 * the reason on the field rather than a control that silently does nothing. Somebody re-recording
 * evidence from a paper trail also has a digest and no file, and that has always been legitimate.
 *
 * ## Why the retention class cannot be skipped
 *
 * Disposal is the irreversible half of retention. Evidence with no class attached has no defensible
 * date on which anybody may delete it, so in practice it is either kept forever or deleted by whoever
 * is clearing space - and only one of those failures is visible. The service refuses without it; the
 * form says why.
 */
const AttachEvidenceDialog = ({ onClose, onSubmit }: AttachEvidenceDialogProps) => {
  const [evidenceType, setEvidenceType] = useState<EvidenceType>('AFTER_PHOTO');
  const [file, setFile] = useState<File | null>(null);
  const [hashing, setHashing] = useState(false);
  const [hashNote, setHashNote] = useState<string | null>(null);
  const [derived, setDerived] = useState(false);
  const [fileReference, setFileReference] = useState('');
  const [fileName, setFileName] = useState('');
  const [mediaType, setMediaType] = useState('');
  const [sizeBytes, setSizeBytes] = useState('');
  const [contentHash, setContentHash] = useState('');
  const [retentionClass, setRetentionClass] = useState<RetentionClass>('OPERATIONAL');
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<FleetApiError | undefined>();

  const missingReference = fileReference.trim().length === 0;
  const badHash = !SHA_256.test(contentHash.trim());
  const invalid = missingReference || badHash;
  const unavailable = digestUnavailable();

  /**
   * Takes everything the file itself knows.
   *
   * The three metadata fields are overwritten without asking, and the digest with them: they describe
   * *this* file, and leaving a previous file's values beside a new one is how evidence ends up
   * recorded against the wrong hash. Only the storage reference and the notes survive a re-pick,
   * because neither comes from the bytes.
   */
  const choose = async (chosen: File | null) => {
    setFile(chosen);
    setHashNote(null);
    setDerived(false);
    if (!chosen) {
      return;
    }
    setFileName(chosen.name);
    setMediaType(chosen.type || '');
    setSizeBytes(String(chosen.size));

    setHashing(true);
    setContentHash('');
    const result = await sha256OfFile(chosen);
    setHashing(false);
    if (result.ok) {
      setContentHash(result.hex);
      setDerived(true);
    } else {
      setHashNote(result.message);
    }
  };

  const submit = async () => {
    setTouched(true);
    if (invalid) {
      return;
    }
    setSubmitting(true);
    setFormError(undefined);
    try {
      await onSubmit({
        evidenceType,
        fileReference: fileReference.trim(),
        fileName: fileName.trim() || null,
        mediaType: mediaType.trim() || null,
        sizeBytes: sizeBytes.trim() === '' ? null : Number(sizeBytes),
        contentHash: contentHash.trim().toLowerCase(),
        retentionClass,
        notes: notes.trim() || null,
      });
    } catch (cause) {
      setFormError(isFleetApiError(cause) ? cause : undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <FormDialog
      open
      title="Attach evidence"
      description="Choose the file to read its digest, then say where it was stored."
      submitLabel="Attach"
      submitting={submitting}
      submitDisabled={hashing}
      formError={formError}
      onClose={onClose}
      onSubmit={submit}
    >
      <div className="space-y-4">
        <SelectInput
          label="What it is"
          value={evidenceType}
          onChange={(value) => setEvidenceType(value as EvidenceType)}
          required
          options={evidenceTypes.map((value) => ({ value, label: humaniseCode(value) }))}
          helperText={
            evidenceType === 'INVOICE'
              ? 'An invoice proves money was spent, not that the work was done - it does not count towards closure.'
              : 'Counts towards the closure requirement.'
          }
        />

        <FieldFrame
          label="The file"
          helperText={
            unavailable
              ? unavailable
              : 'Read in this browser to take its digest, name, type and size. Nothing is uploaded.'
          }
          customControl
        >
          <UploadField
            aria-label="The file"
            variant="inline"
            value={file}
            onChange={(next) => choose(Array.isArray(next) ? (next[0] ?? null) : next)}
            maxSize={MAX_UPLOAD_BYTES}
            disabled={hashing}
          />
        </FieldFrame>

        {hashing && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
            <Icon name="refresh" size={15} className="animate-spin" />
            Reading {file ? formatBytes(file.size) : 'the file'} and computing its SHA-256…
          </p>
        )}

        {hashNote && (
          <Notice variant="warning" title="The digest could not be computed here">
            <p className="text-sm">{hashNote}</p>
          </Notice>
        )}

        <TextInput
          label="File reference"
          value={fileReference}
          onChange={setFileReference}
          onBlur={() => setTouched(true)}
          required
          maxLength={500}
          placeholder="e.g. s3://clet-evidence/work-orders/…/after.jpg"
          error={touched && missingReference}
          helperText={
            touched && missingReference
              ? 'Where the file is stored is required.'
              : 'Given back by document storage when the file was uploaded. This dashboard cannot upload for you.'
          }
        />

        <TextInput
          label="SHA-256"
          value={contentHash}
          onChange={(value) => {
            setContentHash(value);
            // Typing over a derived hash makes it a typed hash, and the field should stop claiming
            // it came from the file - the whole point of the badge is that it cannot be mistyped.
            setDerived(false);
          }}
          onBlur={() => setTouched(true)}
          required
          maxLength={64}
          disabled={hashing}
          placeholder="64 hexadecimal characters"
          error={touched && badHash && !hashing}
          helperText={
            hashing
              ? 'Computing…'
              : derived
                ? 'Computed from the file you chose. Nothing was typed, so nothing can be mistyped.'
                : touched && badHash
                  ? 'Must be a 64-character hex SHA-256 digest.'
                  : 'Recorded at upload, so a later integrity check can tell if the stored file changed.'
          }
        />

        {derived && (
          <p className="-mt-2 flex items-center gap-1.5 text-xs font-medium text-success-text">
            <Icon name="check-circle" size={14} />
            Digest read from {fileName}
          </p>
        )}

        <SelectInput
          label="Retention class"
          value={retentionClass}
          onChange={(value) => setRetentionClass(value as RetentionClass)}
          required
          options={retentionClasses.map((value) => ({ value, label: humaniseCode(value) }))}
          helperText="Mandatory. It is what sets the date this may eventually be disposed of."
        />

        <details className="rounded-lg border border-border px-4 py-3">
          <summary className="cursor-pointer text-sm font-medium text-foreground select-none">
            File details
            {file ? ` - taken from ${fileName}` : ''}
          </summary>
          <div className="mt-4 space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <TextInput
                label="File name"
                value={fileName}
                onChange={setFileName}
                maxLength={300}
                placeholder="after.jpg"
              />
              <TextInput
                label="Media type"
                value={mediaType}
                onChange={setMediaType}
                maxLength={120}
                placeholder="image/jpeg"
              />
            </div>
            {/*
              Read-only rather than a NumberInput: it is a fact about the chosen file, and an
              operator editing it would be recording a size the digest cannot corroborate.
            */}
            <p className="text-sm text-muted-foreground">
              Size:{' '}
              {sizeBytes === ''
                ? 'not known - choose a file, or leave it unrecorded'
                : `${formatBytes(Number(sizeBytes))} (${Number(sizeBytes).toLocaleString()} bytes)`}
              {sizeBytes !== '' && (
                <Button
                  size="sm"
                  variant="ghost"
                  className="ml-2"
                  onClick={() => setSizeBytes('')}
                >
                  Clear
                </Button>
              )}
            </p>
          </div>
        </details>

        <TextAreaInput
          label="Notes"
          value={notes}
          onChange={setNotes}
          rows={2}
          placeholder="Anything a reviewer would need to make sense of it."
        />

      </div>
    </FormDialog>
  );
};

export default AttachEvidenceDialog;

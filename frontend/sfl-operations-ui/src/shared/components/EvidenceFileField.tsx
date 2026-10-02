import { useEffect, useId, useMemo, useState } from 'react';
import {
  Button,
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
  UploadField,
} from '@rfdtech/components';
import { Camera, Download, Eye, FileText } from 'lucide-react';
import {
  ACCEPTED_FILE_ACCEPT,
  ACCEPTED_FILE_DESCRIPTION,
  MAX_UPLOAD_BYTES,
  rejectionReason,
  sizeRejectionReason,
} from 'shared/evidence/evidenceFilesApi';

/**
 * Choose a file, see what you chose, before anything is uploaded.
 *
 * <h2>Why a preview here and not only after the upload</h2>
 *
 * <p>The thing being uploaded is usually a photograph taken thirty seconds ago on a phone, and the
 * commonest failure is not a malicious file - it is a picture of the wrong pump, a thumb over the
 * display, or a receipt too blurred to read. Every one of those is obvious from a thumbnail and
 * invisible from a file name, and once the transaction is submitted the correction costs an anomaly
 * case and somebody's afternoon. Showing the image at the moment of choosing is the cheapest quality
 * control in the whole workflow.
 *
 * <p>The preview is a local object URL, so it costs no request and works with no signal. PDFs get an
 * icon and a page count of nothing rather than an embedded viewer: a driver's phone is the wrong
 * place to render one, and the file name plus size is enough to confirm the right file was picked.
 *
 * <p>The refusal shown under the field is the client-side courtesy check. It is not the security
 * boundary - the service re-checks every file by its magic bytes and refuses anything that
 * disagrees with its name - and this component would be wrong to imply otherwise.
 */

interface EvidenceFileFieldProps {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  disabled?: boolean;
  onBlur?: () => void;
  /**
   * Asks the camera for it instead of the file browser, on a phone.
   *
   * <p>Set for the pump-meter photograph, which must be taken at the pump rather than chosen from a
   * gallery. It is a hint, not a control - a determined person picks an old photo on a desktop
   * browser regardless - which is exactly why the platform also checks the image has not been
   * submitted before.
   */
  capture?: boolean;
}

const EvidenceFileField = ({
  label,
  value,
  onChange,
  required,
  error,
  helperText,
  disabled,
  onBlur,
  capture,
}: EvidenceFileFieldProps) => {
  const labelId = useId();
  const hintId = useId();
  const [sizeRefusal, setSizeRefusal] = useState<string | null>(null);
  const localRefusal = sizeRefusal ?? (value ? rejectionReason(value) : null);

  /**
   * The size cap sits here rather than on the upload field's `maxSize`, which answers an oversized
   * file with a dialog. A refused file is not handed to the form at all: reporting it while leaving
   * it selected would let a submit go ahead with a file the service is certain to reject.
   */
  const choose = (next: File | File[] | null) => {
    const chosen = Array.isArray(next) ? (next[0] ?? null) : next;
    const reason = chosen ? sizeRejectionReason(chosen, MAX_UPLOAD_BYTES) : null;
    setSizeRefusal(reason);
    onChange(reason ? null : chosen);
  };
  const invalid = Boolean(error || localRefusal);
  const hint = localRefusal ?? helperText ?? ACCEPTED_FILE_DESCRIPTION;

  // Derived during render rather than pushed into state from an effect: the URL is a pure function of
  // the chosen file, and the state-plus-effect version rendered once with a stale preview before
  // correcting itself.
  const previewUrl = useMemo(
    () => (value && value.type !== 'application/pdf' ? URL.createObjectURL(value) : null),
    [value],
  );

  // Revoked when the file changes or the form unmounts; without this every re-pick leaks a copy of
  // the image for the life of the tab.
  useEffect(
    () => () => {
      if (previewUrl) {
        URL.revokeObjectURL(previewUrl);
      }
    },
    [previewUrl],
  );

  return (
    <div>
      <Field invalid={invalid}>
        <FieldLabel id={labelId} htmlFor={undefined}>
          {label}
          {required && (
            <>
              <span className="ml-0.5 text-error" aria-hidden="true">
                *
              </span>
              <span className="sr-only"> (required)</span>
            </>
          )}
        </FieldLabel>
        <UploadField
          variant="inline"
          aria-labelledby={labelId}
          aria-describedby={hintId}
          value={value}
          onChange={choose}
          accept={ACCEPTED_FILE_ACCEPT}
          subtitle=""
          invalid={invalid}
          disabled={disabled}
          onBlur={onBlur}
        />
        {invalid ? (
          <FieldError id={hintId}>{hint}</FieldError>
        ) : (
          <FieldDescription id={hintId}>{hint}</FieldDescription>
        )}
      </Field>

      {/*
        `capture` cannot be passed through the upload field without widening it for one caller, and the
        attribute has to sit on the real input. Rendering a second, camera-only input beside the
        picker keeps both routes open, which matters: the same form is used at a pump on a phone and
        at a desk on a laptop, and a camera-only field is unusable on the second.
      */}
      {capture && !disabled && (
        <label className="mt-1.5 inline-flex cursor-pointer items-center gap-1.5 text-xs font-medium text-primary hover:underline">
          <Camera size={14} strokeWidth={1.75} aria-hidden="true" />
          Take a photo instead
          <input
            type="file"
            accept="image/jpeg"
            capture="environment"
            className="sr-only"
            onChange={(event) => onChange(event.target.files?.[0] ?? null)}
          />
        </label>
      )}

      {previewUrl && (
        <div className="mt-2 overflow-hidden rounded-lg border border-border bg-muted">
          <img
            src={previewUrl}
            alt={`Preview of ${value?.name ?? 'the chosen file'}`}
            className="max-h-56 w-full object-contain"
          />
        </div>
      )}

      {value?.type === 'application/pdf' && (
        <p className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground">
          <FileText size={14} strokeWidth={1.75} aria-hidden="true" />
          PDF chosen - it can be opened once the record is saved.
        </p>
      )}
    </div>
  );
};

export default EvidenceFileField;

/**
 * Opens and downloads a file the platform already holds.
 *
 * <p>Separate from the field above because the two never appear together: one is for a file being
 * chosen, this is for one already stored. Both are here so a screen showing evidence does not have to
 * know that the bytes arrive through an authorised fetch rather than a URL.
 *
 * <p>It takes an evidence id and nothing else it could get wrong. The file name and the media type
 * both come from the response, so a caller holding only a foreign key - which is every compliance row
 * and every fuel transaction - can offer preview and download without first fetching metadata it
 * would only use to label a button.
 */
export const EvidenceFileActions = ({
  evidenceId,
  fileName,
  hasContent = true,
  compact,
  onError,
}: {
  evidenceId: string;
  /** Only a fallback for the saved file name; the response's own name wins. */
  fileName?: string;
  /**
   * Pass false for a record known to predate the file store, so the row explains itself instead of
   * offering a button that can only fail. Defaults to true for callers that cannot know.
   */
  hasContent?: boolean;
  /** Icon-only, for a table cell where two labelled buttons would dominate the row. */
  compact?: boolean;
  onError?: (message: string) => void;
}) => {
  const [preview, setPreview] = useState<{ url: string; type: string; name: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(
    () => () => {
      if (preview) {
        URL.revokeObjectURL(preview.url);
      }
    },
    [preview],
  );

  if (!hasContent) {
    return (
      <span className="text-xs text-muted-foreground">
        Registered before file upload - no file is stored.
      </span>
    );
  }

  const run = async (action: 'preview' | 'download') => {
    setBusy(true);
    try {
      const { evidenceFilesApi } = await import('shared/evidence/evidenceFilesApi');
      if (action === 'download') {
        await evidenceFilesApi.download(evidenceId, fileName);
      } else {
        const fetched = await evidenceFilesApi.preview(evidenceId, fileName);
        setPreview({ url: fetched.objectUrl, type: fetched.contentType, name: fetched.fileName });
      }
    } catch (cause) {
      onError?.(cause instanceof Error ? cause.message : 'The file could not be opened.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button
        size="sm"
        variant="outline"
        disabled={busy}
        aria-label={compact ? 'Preview the document' : undefined}
        title={compact ? 'Preview' : undefined}
        onClick={() => void run('preview')}
      >
        <Eye size={14} strokeWidth={1.75} aria-hidden="true" />
        {compact ? '' : 'Preview'}
      </Button>
      <Button
        size="sm"
        variant="ghost"
        disabled={busy}
        aria-label={compact ? 'Download the document' : undefined}
        title={compact ? 'Download' : undefined}
        onClick={() => void run('download')}
      >
        <Download size={14} strokeWidth={1.75} aria-hidden="true" />
        {compact ? '' : 'Download'}
      </Button>

      {preview && (
        <div className="mt-2 w-full">
          {preview.type === 'application/pdf' ? (
            // `sandbox` on the frame as well as on the response. The header is what actually
            // constrains the document; this is the belt to its braces, and costs nothing.
            <iframe
              src={preview.url}
              title={preview.name}
              sandbox=""
              className="h-[28rem] w-full rounded-lg border border-border"
            />
          ) : (
            <img
              src={preview.url}
              alt={preview.name}
              className="max-h-[28rem] w-full rounded-lg border border-border object-contain"
            />
          )}
          <Button
            size="sm"
            variant="ghost"
            className="mt-1.5"
            onClick={() => {
              URL.revokeObjectURL(preview.url);
              setPreview(null);
            }}
          >
            Close preview
          </Button>
        </div>
      )}
    </div>
  );
};

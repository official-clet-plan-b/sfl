import { useId, useState } from 'react';
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
  UploadField,
} from '@rfdtech/components';
import { MAX_UPLOAD_BYTES, sizeRejectionReason } from 'shared/evidence/evidenceFilesApi';

interface AcceptingFileFieldProps {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  /** Which file types the picker offers - a scanner's CSV, or a photograph and a PDF. */
  accept?: string;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  disabled?: boolean;
  onBlur?: () => void;
  /** The ceiling for this field. It has to match what the service enforces for that endpoint. */
  maxBytes?: number;
}

/**
 * File selection on the library's upload field, for the dialogs that restrict what can be picked.
 *
 * The size cap sits here rather than on the field's own `maxSize`, which answers an oversized file
 * with a dialog: a refused file is never handed to the form, because reporting it while leaving it
 * selected would let a submit go ahead with a file the service is certain to reject. The refusal is a
 * courtesy; the service enforces the same number on the bytes it received.
 */
export const AcceptingFileField = ({
  label,
  value,
  onChange,
  accept,
  required,
  error,
  helperText,
  disabled,
  onBlur,
  maxBytes = MAX_UPLOAD_BYTES,
}: AcceptingFileFieldProps) => {
  const labelId = useId();
  const hintId = useId();
  const [refusal, setRefusal] = useState<string | null>(null);

  const choose = (next: File | File[] | null) => {
    const chosen = Array.isArray(next) ? (next[0] ?? null) : next;
    const reason = chosen ? sizeRejectionReason(chosen, maxBytes) : null;
    setRefusal(reason);
    onChange(reason ? null : chosen);
  };

  const invalid = Boolean(error || refusal);
  const hint = refusal ?? helperText ?? `Up to ${Math.round(maxBytes / (1024 * 1024))} MB.`;

  return (
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
        accept={accept}
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
  );
};

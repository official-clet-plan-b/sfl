import { KeyboardEvent, ReactNode, useId, useState } from 'react';
import {
  Banner,
  Button,
  Checkbox,
  DateSelector,
  Dropdown,
  Field,
  FieldControl,
  FieldDescription,
  FieldError,
  FieldLabel,
  Input,
  Modal,
  ModalBody,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalOverlay,
  ModalPortal,
  ModalTitle,
  Textarea,
  TimeSelector,
  UploadField,
  type ModalSize,
} from '@rfdtech/components';
import dayjs from 'dayjs';
import { humanise } from 'modules/fleet/api/enums';
import { FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';
import { MAX_UPLOAD_BYTES, sizeRejectionReason } from 'shared/evidence/evidenceFilesApi';

/**
 * The form controls and dialog shell the dispatch and "my work" screens share.
 *
 * Composed from the library's `Field` family rather than a parallel kit, so the props below only
 * adapt the dashboard's form state (`useFleetForm`: string values, `error` and `helperText` from
 * `fieldProps`) to what the library components take. Validation stays where it was; these render it.
 */

interface CommonProps {
  label?: string;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  autoFocus?: boolean;
}

/** Label, the one required marker, and the helper line that turns into the error text. */
const FieldFrame = ({
  label,
  required,
  error,
  helperText,
  className,
  children,
  onBlur,
  labelFor = true,
}: Pick<CommonProps, 'label' | 'required' | 'error' | 'helperText' | 'className' | 'onBlur'> & {
  children: ReactNode;
  /** False where the control carries its own accessible name and the label cannot point at it. */
  labelFor?: boolean;
}) => (
  <Field invalid={Boolean(error)} className={className} onBlur={onBlur}>
    {label && (
      <FieldLabel {...(labelFor ? {} : { htmlFor: undefined })}>
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
    )}
    {children}
    {error ? <FieldError>{helperText}</FieldError> : <FieldDescription>{helperText}</FieldDescription>}
  </Field>
);

export interface TextFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  maxLength?: number;
  type?: 'text' | 'email' | 'tel' | 'url';
  name?: string;
}

export const TextField = ({
  value,
  onChange,
  label,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  placeholder,
  className,
  maxLength,
  autoFocus,
  type = 'text',
  name,
}: TextFieldProps) => (
  <FieldFrame
    label={label}
    required={required}
    error={error}
    helperText={helperText}
    className={className}
  >
    <FieldControl>
      <Input
        type={type}
        name={name}
        value={value}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={disabled}
        autoFocus={autoFocus}
        invalid={error}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
      />
    </FieldControl>
  </FieldFrame>
);

export interface NumberFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
  /** Named in the label's own words by the caller - "km", "L" - and shown after it. */
  suffix?: string;
}

/**
 * Numeric entry kept as a string in form state, so an empty field stays empty rather than
 * collapsing to 0 and the validator can report "must be a whole number" instead of coercing.
 */
export const NumberField = ({
  value,
  onChange,
  label,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  placeholder,
  className,
  min = 0,
  max,
  step = 1,
  suffix,
}: NumberFieldProps) => (
  <FieldFrame
    label={label && suffix ? `${label} (${suffix})` : label}
    required={required}
    error={error}
    helperText={helperText}
    className={className}
  >
    <FieldControl>
      <Input
        type="number"
        inputMode="numeric"
        value={value}
        min={min}
        max={max}
        step={step}
        placeholder={placeholder}
        disabled={disabled}
        invalid={error}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
      />
    </FieldControl>
  </FieldFrame>
);

export interface TextAreaFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  maxLength?: number;
}

export const TextAreaField = ({
  value,
  onChange,
  label,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  placeholder,
  className,
  rows = 3,
  maxLength,
}: TextAreaFieldProps) => (
  <FieldFrame
    label={label}
    required={required}
    error={error}
    helperText={helperText}
    className={className}
  >
    <FieldControl>
      <Textarea
        rows={rows}
        value={value}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={disabled}
        invalid={error}
        className="w-full"
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
      />
    </FieldControl>
  </FieldFrame>
);

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

interface SelectFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  /** Adds a blank choice - for an optional field or a filter, never a required request field. */
  allowEmpty?: boolean;
  emptyLabel?: string;
}

export const SelectField = ({
  value,
  onChange,
  options,
  label,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  className,
  allowEmpty,
  emptyLabel = 'Any',
  placeholder,
}: SelectFieldProps) => (
  <FieldFrame
    label={label}
    required={required}
    error={error}
    helperText={helperText}
    className={className}
    onBlur={onBlur}
    labelFor={false}
  >
    <Dropdown
      aria-label={label ? (required ? `${label} (required)` : label) : (placeholder ?? 'Choose')}
      value={value || null}
      onValueChange={(next) => onChange(next ?? '')}
      options={options}
      placeholder={allowEmpty ? emptyLabel : (placeholder ?? 'Select…')}
      clearable={allowEmpty}
      disabled={disabled}
      invalid={error}
    />
  </FieldFrame>
);

interface EnumSelectFieldProps<T extends string> extends CommonProps {
  value: T | '';
  options: readonly T[];
  onChange: (value: T | '') => void;
  allowEmpty?: boolean;
  emptyLabel?: string;
  renderOptionLabel?: (option: T) => string;
}

/** A select bound to a backend enum, so an operator can never submit a value the service rejects. */
export function EnumSelectField<T extends string>({
  value,
  options,
  onChange,
  renderOptionLabel,
  ...rest
}: EnumSelectFieldProps<T>) {
  return (
    <SelectField
      {...rest}
      value={value}
      onChange={(next) => onChange(next as T | '')}
      options={options.map((option) => ({
        value: option,
        label: renderOptionLabel ? renderOptionLabel(option) : humanise(option),
      }))}
    />
  );
}

interface CheckboxFieldProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: ReactNode;
  hint?: string;
  disabled?: boolean;
}

export const CheckboxField = ({ checked, onChange, label, hint, disabled }: CheckboxFieldProps) => (
  <Checkbox
    checked={checked}
    onCheckedChange={onChange}
    disabled={disabled}
    label={
      hint ? (
        <>
          <span className="block">{label}</span>
          <span className="block text-xs text-muted-foreground">{hint}</span>
        </>
      ) : (
        label
      )
    }
  />
);

const DATE_FORMAT = 'YYYY-MM-DD';
const DATE_TIME_FORMAT = 'YYYY-MM-DDTHH:mm';

const parseDate = (value: string): Date | null => {
  if (!value) {
    return null;
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.toDate() : null;
};

interface DateFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  minDate?: string;
  maxDate?: string;
}

/** A calendar date. Form state keeps the wire string, `YYYY-MM-DD`, exactly as it always did. */
export const DateField = ({
  value,
  onChange,
  label,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  minDate,
  maxDate,
  placeholder,
  className,
}: DateFieldProps) => (
  <FieldFrame
    label={label}
    required={required}
    error={error}
    helperText={helperText}
    className={className}
    onBlur={onBlur}
  >
    <FieldControl>
      <DateSelector
        value={parseDate(value)}
        onChange={(next) => onChange(next ? dayjs(next).format(DATE_FORMAT) : '')}
        min={parseDate(minDate ?? '') ?? undefined}
        max={parseDate(maxDate ?? '') ?? undefined}
        placeholder={placeholder ?? 'Select date'}
        invalid={error}
        disabled={disabled}
        onBlur={onBlur}
      />
    </FieldControl>
  </FieldFrame>
);

/**
 * A date and a time, as two library selectors over one `YYYY-MM-DDTHH:mm` string.
 *
 * Picking the date first takes the time already chosen, or the current time of day, so a date alone
 * never leaves a half-formed value in the form.
 */
export const DateTimeField = ({
  value,
  onChange,
  label,
  required,
  error,
  helperText,
  onBlur,
  disabled,
  minDate,
  maxDate,
  className,
}: DateFieldProps) => {
  const current = value ? dayjs(value) : null;
  const valid = current?.isValid() ? current : null;

  return (
    <FieldFrame
      label={label}
      required={required}
      error={error}
      helperText={helperText}
      className={className}
      onBlur={onBlur}
      labelFor={false}
    >
      <div className="grid grid-cols-[1fr_auto] gap-2">
        <DateSelector
          value={valid ? valid.toDate() : null}
          onChange={(next) => {
            if (!next) {
              onChange('');
              return;
            }
            const base = dayjs(next);
            const time = valid ?? dayjs();
            onChange(base.hour(time.hour()).minute(time.minute()).format(DATE_TIME_FORMAT));
          }}
          min={parseDate(minDate ?? '') ?? undefined}
          max={parseDate(maxDate ?? '') ?? undefined}
          placeholder="Select date"
          invalid={error}
          disabled={disabled}
          onBlur={onBlur}
        />
        <TimeSelector
          hourCycle={24}
          value={valid ? { hours: valid.hour(), minutes: valid.minute() } : null}
          onChange={(next) => {
            if (!next) {
              return;
            }
            const base = valid ?? dayjs();
            onChange(base.hour(next.hours).minute(next.minutes).format(DATE_TIME_FORMAT));
          }}
          placeholder="Time"
          invalid={error}
          disabled={disabled}
          onBlur={onBlur}
        />
      </div>
    </FieldFrame>
  );
};

interface FileFieldProps {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  disabled?: boolean;
  onBlur?: () => void;
  /** The ceiling for this field. It has to match what the service enforces for that endpoint. */
  maxBytes?: number;
}

/**
 * File selection on the library's upload field.
 *
 * The size cap sits here rather than on the field's own `maxSize`, which answers an oversized file
 * with a dialog: a refused file is never handed to the form, because reporting it while leaving it
 * selected would let a submit go ahead with a file the service is certain to reject. The refusal is a
 * courtesy; `UploadedFileScanner` enforces the same number on the bytes it received.
 */
export const FileField = ({
  label,
  value,
  onChange,
  required,
  error,
  helperText,
  disabled,
  onBlur,
  maxBytes = MAX_UPLOAD_BYTES,
}: FileFieldProps) => {
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

/** What a screen says about an inline message: the library's `Banner` variants. */
export type CalloutTone = 'info' | 'success' | 'warning' | 'danger';

interface CalloutProps {
  tone: CalloutTone;
  title?: ReactNode;
  children?: ReactNode;
  /** Small print under the message - a correlation id, a code. */
  footnote?: ReactNode;
  action?: ReactNode;
  className?: string;
}

/**
 * An inline message block, for form failures, guidance and blocker summaries.
 *
 * A heading is required by the library's banner, so a message with no title uses its body as the
 * heading rather than leaving the banner empty.
 */
export const Callout = ({ tone, title, children, footnote, action, className }: CalloutProps) => {
  const body = footnote ? (
    <>
      {children}
      <span className="mt-1 block text-xs">{footnote}</span>
    </>
  ) : (
    children
  );
  return (
    <Banner
      variant={tone}
      heading={title ?? children}
      subtext={title ? body : footnote}
      action={action}
      className={className}
    />
  );
};

/** A form-level failure in the service's own wording, with the correlation id support will ask for. */
export const FormErrorBanner = ({ error }: { error: FleetApiError }) => (
  <Callout
    tone={error.isForbidden ? 'warning' : 'danger'}
    title={errorLabel(error)}
    footnote={errorDetail(error)}
  >
    {error.message}
  </Callout>
);

const modalSizes: Record<'sm' | 'md' | 'lg' | 'xl', ModalSize> = {
  sm: 'md',
  md: 'lg',
  lg: 'xl',
  xl: '2xl',
};

interface FormModalProps {
  open: boolean;
  title: string;
  description?: string;
  submitLabel: string;
  submitting: boolean;
  /** Blocks submission for reasons the form itself cannot fix (readiness, eligibility, state). */
  submitDisabled?: boolean;
  formError?: FleetApiError;
  maxWidth?: 'sm' | 'md' | 'lg' | 'xl';
  destructive?: boolean;
  /**
   * A one-line read-back of what is about to be submitted, pinned above the actions.
   *
   * Outside the body on purpose: the body scrolls, so anything inside it can be off screen at the
   * moment the operator reaches the submit button - which is the one moment a summary is for.
   */
  summary?: ReactNode;
  onClose: () => void;
  onSubmit: () => void;
  children: ReactNode;
}

/**
 * The shell every dispatch action dialog uses, on the library's modal.
 *
 * Two guarantees carry over. The submit button is disabled while a request is in flight, so a double
 * click cannot raise two records, and closing is blocked for the same reason - a stray click cannot
 * dismiss a form mid-submit and leave the operator unsure whether the write landed. And a form-level
 * failure is shown above the actions rather than swallowed.
 *
 * Enter in a single-line field submits, as the `<form>` this replaced did; the modal itself takes no
 * form element, so that is handled here.
 */
export const FormModal = ({
  open,
  title,
  description,
  submitLabel,
  submitting,
  submitDisabled,
  formError,
  maxWidth = 'md',
  destructive,
  summary,
  onClose,
  onSubmit,
  children,
}: FormModalProps) => {
  const submitOnEnter = (event: KeyboardEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (event.key === 'Enter' && target.tagName === 'INPUT' && !submitting && !submitDisabled) {
      event.preventDefault();
      onSubmit();
    }
  };

  return (
    <Modal
      open={open}
      onOpenChange={(next) => {
        if (!next && !submitting) {
          onClose();
        }
      }}
    >
      <ModalPortal>
        <ModalOverlay />
        <ModalContent
          showCloseButton={!submitting}
          size={modalSizes[maxWidth]}
          {...(description ? {} : { 'aria-describedby': undefined })}
        >
          <ModalHeader>
            <ModalTitle>{title}</ModalTitle>
            {description && <ModalDescription>{description}</ModalDescription>}
          </ModalHeader>

          <ModalBody className="space-y-5" onKeyDown={submitOnEnter}>
            {children}
            {formError && <FormErrorBanner error={formError} />}
          </ModalBody>

          {summary}

          <ModalFooter>
            <Button variant="ghost" onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
            <Button
              variant={destructive ? 'primary-destructive' : 'primary'}
              loading={submitting}
              disabled={submitDisabled}
              onClick={onSubmit}
            >
              {submitting ? 'Working…' : submitLabel}
            </Button>
          </ModalFooter>
        </ModalContent>
      </ModalPortal>
    </Modal>
  );
};

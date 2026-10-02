import { ReactNode, useId, useState } from 'react';
import {
  Checkbox as LibraryCheckbox,
  DateSelector,
  Dropdown,
  Field,
  FieldControl,
  FieldDescription,
  FieldError,
  FieldLabel,
  Input,
  TimeSelector,
  Textarea,
  UploadField,
  type TimeValue,
} from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import { MAX_UPLOAD_BYTES, sizeRejectionReason } from 'shared/evidence/evidenceFilesApi';
import { cn } from 'shared/components/cn';

/*
 * The form controls the fleet dialogs compose, on the library's `Field` family.
 *
 * They keep the `value` / `onChange(value)` / `error` / `helperText` / `onBlur` shape that
 * `useFleetForm().fieldProps` returns, so a dialog binds a field in one line and the validation
 * contract (a string value, an error flag, a hint line) is unchanged. A hint and an error share the
 * one line under the control: the error replaces the hint while it is in force.
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

const FieldLabelText = ({ label, required }: { label: string; required?: boolean }) => (
  <>
    {label}
    {required && (
      <>
        <span aria-hidden="true"> *</span>
        <span className="sr-only"> (required)</span>
      </>
    )}
  </>
);

interface FieldFrameProps extends CommonProps {
  children: ReactNode;
}

const FieldFrame = ({
  label,
  required,
  error,
  helperText,
  className,
  children,
}: FieldFrameProps) => (
  <Field invalid={error} className={cn('w-full min-w-0', className)}>
    {label && (
      <FieldLabel>
        <FieldLabelText label={label} required={required} />
      </FieldLabel>
    )}
    <FieldControl>{children}</FieldControl>
    {error ? (
      <FieldError>{helperText}</FieldError>
    ) : (
      helperText && <FieldDescription>{helperText}</FieldDescription>
    )}
  </Field>
);

export interface TextInputProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  maxLength?: number;
  type?: 'text' | 'email' | 'tel' | 'url' | 'password';
  autoComplete?: string;
  name?: string;
}

export const TextInput = ({
  value,
  onChange,
  onBlur,
  disabled,
  placeholder,
  maxLength,
  autoFocus,
  type = 'text',
  autoComplete,
  name,
  ...frame
}: TextInputProps) => (
  <FieldFrame {...frame}>
    <Input
      type={type}
      name={name}
      autoComplete={autoComplete}
      value={value}
      maxLength={maxLength}
      placeholder={placeholder}
      disabled={disabled}
      autoFocus={autoFocus}
      invalid={frame.error}
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
    />
  </FieldFrame>
);

export interface NumberInputProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
  suffix?: string;
}

/** The unit rides in the label, since the library input has no trailing slot. */
export const NumberInput = ({
  value,
  onChange,
  onBlur,
  disabled,
  placeholder,
  min = 0,
  max,
  step = 1,
  suffix,
  ...frame
}: NumberInputProps) => (
  <FieldFrame {...frame} label={frame.label && suffix ? `${frame.label} (${suffix})` : frame.label}>
    <Input
      type="number"
      inputMode="numeric"
      value={value}
      min={min}
      max={max}
      step={step}
      placeholder={placeholder}
      disabled={disabled}
      invalid={frame.error}
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
    />
  </FieldFrame>
);

export interface TextAreaInputProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  rows?: number;
  maxLength?: number;
}

export const TextAreaInput = ({
  value,
  onChange,
  onBlur,
  disabled,
  placeholder,
  rows = 3,
  maxLength,
  ...frame
}: TextAreaInputProps) => (
  <FieldFrame {...frame}>
    <Textarea
      rows={rows}
      value={value}
      maxLength={maxLength}
      placeholder={placeholder}
      disabled={disabled}
      invalid={frame.error}
      className="w-full"
      onChange={(event) => onChange(event.target.value)}
      onBlur={onBlur}
    />
  </FieldFrame>
);

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

interface SelectInputProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  allowEmpty?: boolean;
  emptyLabel?: string;
}

/**
 * The library's `Dropdown`. Its trigger is named by `aria-label`, which is the label text, so the
 * control is announced by purpose and not by the value it happens to hold.
 */
export const SelectInput = ({
  value,
  onChange,
  options,
  disabled,
  placeholder,
  allowEmpty,
  emptyLabel = 'Any',
  ...frame
}: SelectInputProps) => (
  <FieldFrame {...frame}>
    <Dropdown
      aria-label={frame.label ?? 'Select'}
      value={value || null}
      onValueChange={(next) => onChange(next ?? '')}
      options={options}
      placeholder={allowEmpty ? emptyLabel : (placeholder ?? 'Select…')}
      clearable={allowEmpty}
      disabled={disabled}
      invalid={frame.error}
    />
  </FieldFrame>
);

interface EnumSelectProps<T extends string> extends CommonProps {
  value: T | '';
  options: readonly T[];
  onChange: (value: T | '') => void;
  allowEmpty?: boolean;
  emptyLabel?: string;
  renderOptionLabel?: (option: T) => string;
}

export function EnumSelect<T extends string>({
  value,
  options,
  onChange,
  renderOptionLabel,
  ...rest
}: EnumSelectProps<T>) {
  return (
    <SelectInput
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

interface CheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: ReactNode;
  hint?: string;
  disabled?: boolean;
}

export const Checkbox = ({ checked, onChange, label, hint, disabled }: CheckboxProps) => (
  <div className="min-w-0">
    <LibraryCheckbox
      checked={checked}
      disabled={disabled}
      onCheckedChange={onChange}
      label={label}
    />
    {hint && <p className="mt-1 text-theme-xs opacity-70">{hint}</p>}
  </div>
);

/* ---------------------------------------------------------------------------------------------
 * Dates. The dialogs hold `YYYY-MM-DD` and `YYYY-MM-DDTHH:mm` strings, local time throughout, which
 * is what `fromLocalInputValue` and the service's date fields expect. The pickers speak `Date`, so
 * these convert at the edge and never through UTC - `toISOString` would shift a late-evening
 * booking onto the next day.
 * ------------------------------------------------------------------------------------------- */

const pad = (value: number) => String(value).padStart(2, '0');

const parseDate = (value?: string): Date | undefined => {
  if (!value) {
    return undefined;
  }
  const [year, month, day] = value.slice(0, 10).split('-').map(Number);
  return new Date(year, month - 1, day);
};

const formatDateValue = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

interface DateFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  minDate?: string;
  maxDate?: string;
}

export const DateField = ({
  value,
  onChange,
  onBlur,
  disabled,
  placeholder,
  minDate,
  maxDate,
  ...frame
}: DateFieldProps) => (
  <FieldFrame {...frame}>
    <div role="group" aria-label={frame.label ?? 'Date'}>
      <DateSelector
        value={parseDate(value) ?? null}
        onChange={(date) => onChange(date ? formatDateValue(date) : '')}
        onBlur={onBlur}
        placeholder={placeholder ?? 'Select date'}
        min={parseDate(minDate)}
        max={parseDate(maxDate)}
        invalid={frame.error}
        disabled={disabled}
      />
    </div>
  </FieldFrame>
);

const parseTime = (value: string): TimeValue | null => {
  const time = value.slice(11, 16);
  if (!time) {
    return null;
  }
  const [hours, minutes] = time.split(':').map(Number);
  return { hours, minutes };
};

/** A date and a time as two library pickers; the value is empty until a date is chosen. */
export const DateTimeField = ({
  value,
  onChange,
  onBlur,
  disabled,
  minDate,
  maxDate,
  ...frame
}: DateFieldProps) => {
  const date = value.slice(0, 10);
  const time = parseTime(value);

  const emit = (nextDate: string, nextTime: TimeValue | null) => {
    if (!nextDate) {
      onChange('');
      return;
    }
    const resolved = nextTime ?? { hours: 0, minutes: 0 };
    onChange(`${nextDate}T${pad(resolved.hours)}:${pad(resolved.minutes)}`);
  };

  return (
    <FieldFrame {...frame}>
      <div
        role="group"
        aria-label={frame.label ?? 'Date and time'}
        className="grid grid-cols-[minmax(0,1fr)_minmax(0,auto)] gap-2"
      >
        <DateSelector
          value={parseDate(date) ?? null}
          onChange={(next) => emit(next ? formatDateValue(next) : '', time)}
          onBlur={onBlur}
          placeholder="Select date"
          min={parseDate(minDate)}
          max={parseDate(maxDate)}
          invalid={frame.error}
          disabled={disabled}
        />
        <TimeSelector
          hourCycle={24}
          minuteStep={5}
          value={time}
          onChange={(next) => date && emit(date, next)}
          onBlur={onBlur}
          placeholder="Time"
          invalid={frame.error}
          disabled={disabled || !date}
        />
      </div>
    </FieldFrame>
  );
};

interface FileInputProps extends CommonProps {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  accept?: string;
  /**
   * The ceiling for this field, defaulting to the evidence cap. Must match what the service enforces
   * for the endpoint, or the field promises something the upload will not honour.
   */
  maxBytes?: number;
}

/**
 * File selection on the library's `UploadField`.
 *
 * The size cap is applied here and not on the upload field's own `maxSize`, which answers an
 * oversized file with a dialog: a refused file is not handed to the form at all, because reporting
 * it while leaving it selected would let a submit go ahead with a file the service will reject. The
 * refusal is a courtesy at the moment of choosing - the service enforces the same number on the
 * bytes it receives.
 */
export const FileInput = ({
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
}: FileInputProps) => {
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
    <Field invalid={invalid} className="w-full min-w-0">
      <FieldLabel id={labelId} htmlFor={undefined}>
        <FieldLabelText label={label} required={required} />
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

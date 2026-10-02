import { useState, type FocusEvent, type ReactNode } from 'react';
import {
  Checkbox,
  DateSelector,
  Dropdown,
  Field,
  FieldControl,
  FieldDescription,
  FieldError,
  FieldLabel,
  Input,
  Textarea,
  TimeSelector,
  type TimeValue,
} from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import { cn } from 'shared/components/cn';

/**
 * Form controls for the safety and security dialogs: the library's `Field` family around its own
 * `Input`, `Textarea`, `Dropdown`, `DateSelector` and `TimeSelector`.
 *
 * Every control keeps the props the dialogs were already written against (`error` and `helperText`
 * from `useFleetForm().fieldProps`, `onChange` handing back the value rather than an event), so a
 * dialog's validation and request mapping did not have to change to adopt the library. The error
 * wins over the hint, and a field never swaps its label for its error - the operator still has to
 * know which field is wrong.
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

const RequiredMark = () => (
  <>
    <span className="ml-0.5 text-[var(--clet-error-text)]" aria-hidden="true">
      *
    </span>
    <span className="sr-only"> (required)</span>
  </>
);

const Notes = ({ error, helperText }: Pick<CommonProps, 'error' | 'helperText'>) =>
  error ? <FieldError>{helperText}</FieldError> : <FieldDescription>{helperText}</FieldDescription>;

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
  <Field invalid={error} className={cn('min-w-0 w-full', className)}>
    {label && (
      <FieldLabel>
        {label}
        {required && <RequiredMark />}
      </FieldLabel>
    )}
    <FieldControl>
      <Input
        type={type}
        name={name}
        value={value}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={disabled}
        autoFocus={autoFocus}
        required={required}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
        className="w-full"
      />
    </FieldControl>
    <Notes error={error} helperText={helperText} />
  </Field>
);

export interface NumberFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
  /** Shown beside the control - "days", "s". */
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
  <Field invalid={error} className={cn('min-w-0 w-full', className)}>
    {label && (
      <FieldLabel>
        {label}
        {required && <RequiredMark />}
      </FieldLabel>
    )}
    <div className="flex items-center gap-2">
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
          required={required}
          onChange={(event) => onChange(event.target.value)}
          onBlur={onBlur}
          className="w-full"
        />
      </FieldControl>
      {suffix && (
        <span className="shrink-0 text-theme-xs font-medium text-[var(--clet-text-secondary)]">
          {suffix}
        </span>
      )}
    </div>
    <Notes error={error} helperText={helperText} />
  </Field>
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
  <Field invalid={error} className={cn('min-w-0 w-full', className)}>
    {label && (
      <FieldLabel>
        {label}
        {required && <RequiredMark />}
      </FieldLabel>
    )}
    <FieldControl>
      <Textarea
        rows={rows}
        value={value}
        maxLength={maxLength}
        placeholder={placeholder}
        disabled={disabled}
        required={required}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
        className="w-full"
      />
    </FieldControl>
    <Notes error={error} helperText={helperText} />
  </Field>
);

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  options: SelectOption[];
  /** Adds a blank choice - use for filters, never for a required request field. */
  allowEmpty?: boolean;
  emptyLabel?: string;
}

/**
 * The dropdown has no blur callback of its own, so the field reports leaving it - but not the focus
 * moving into its own open list, which would mark the field visited before a choice is made.
 */
const leaveField =
  (onBlur?: () => void) => (event: FocusEvent<HTMLDivElement>) => {
    if (event.relatedTarget instanceof Element && event.relatedTarget.closest('[role="listbox"]')) {
      return;
    }
    onBlur?.();
  };

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
}: SelectFieldProps) => (
  <Field invalid={error} className={cn('min-w-0 w-full', className)} onBlur={leaveField(onBlur)}>
    {label && (
      <FieldLabel htmlFor={undefined}>
        {label}
        {required && <RequiredMark />}
      </FieldLabel>
    )}
    <Dropdown
      aria-label={label ? (required ? `${label} (required)` : label) : emptyLabel}
      value={value || null}
      onValueChange={(next) => onChange(next ?? '')}
      options={options}
      placeholder={allowEmpty ? emptyLabel : 'Select…'}
      clearable={allowEmpty}
      disabled={disabled}
      invalid={error}
      required={required}
    />
    <Notes error={error} helperText={helperText} />
  </Field>
);

export interface EnumFieldProps<T extends string> extends CommonProps {
  value: T | '';
  options: readonly T[];
  onChange: (value: T | '') => void;
  /** Adds a blank choice - use for filters, never for a `@NotNull` request field. */
  allowEmpty?: boolean;
  emptyLabel?: string;
  renderOptionLabel?: (option: T) => string;
}

/** A select bound to a backend enum, so an operator can never submit a value the service rejects. */
export function EnumField<T extends string>({
  value,
  options,
  onChange,
  renderOptionLabel,
  ...rest
}: EnumFieldProps<T>) {
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
    disabled={disabled}
    onCheckedChange={onChange}
    label={
      hint ? (
        <span className="block">
          <span className="block">{label}</span>
          <span className="block text-theme-xs text-[var(--clet-text-secondary)]">{hint}</span>
        </span>
      ) : (
        label
      )
    }
  />
);

const pad = (value: number) => String(value).padStart(2, '0');

const parseDate = (value: string): Date | null => {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
};

const formatDate = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

export interface DateFieldProps extends CommonProps {
  /** `YYYY-MM-DD`, the wire format - empty when nothing is chosen. */
  value: string;
  onChange: (value: string) => void;
  minDate?: string;
  maxDate?: string;
}

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
  <Field invalid={error} className={cn('min-w-0 w-full', className)}>
    {label && (
      <FieldLabel htmlFor={undefined}>
        {label}
        {required && <RequiredMark />}
      </FieldLabel>
    )}
    <DateSelector
      value={parseDate(value)}
      onChange={(next) => onChange(next ? formatDate(next) : '')}
      min={minDate ? (parseDate(minDate) ?? undefined) : undefined}
      max={maxDate ? (parseDate(maxDate) ?? undefined) : undefined}
      placeholder={placeholder ?? 'Select date'}
      invalid={error}
      disabled={disabled}
      onBlur={onBlur}
    />
    <Notes error={error} helperText={helperText} />
  </Field>
);

const parseTime = (value: string): TimeValue | null => {
  const match = /T(\d{2}):(\d{2})/.exec(value);
  return match ? { hours: Number(match[1]), minutes: Number(match[2]) } : null;
};

export interface DateTimeFieldProps extends CommonProps {
  /** `YYYY-MM-DDTHH:mm`, the wire format - empty when nothing is chosen. */
  value: string;
  onChange: (value: string) => void;
}

/**
 * A date and a time as two library selectors holding one `YYYY-MM-DDTHH:mm` value.
 *
 * A time without a date, or a date without a time, is not yet a value, so the field reports an
 * empty string until both are chosen - the same thing the native control did - and holds the half
 * that has been chosen so far so it does not vanish while the other is picked.
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
  className,
}: DateTimeFieldProps) => {
  const [partial, setPartial] = useState({ date: '', time: '' });
  const whole = parseDate(value) && parseTime(value);
  const date = whole ? parseDate(value) : parseDate(partial.date);
  const time = whole ? parseTime(value) : parseTime(`T${partial.time}`);

  const emit = (nextDate: string, nextTime: string) => {
    setPartial({ date: nextDate, time: nextTime });
    onChange(nextDate && nextTime ? `${nextDate}T${nextTime}` : '');
  };
  const clock = (next: TimeValue) => `${pad(next.hours)}:${pad(next.minutes)}`;

  return (
    <Field invalid={error} className={cn('min-w-0 w-full', className)}>
      {label && (
        <FieldLabel htmlFor={undefined}>
          {label}
          {required && <RequiredMark />}
        </FieldLabel>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <DateSelector
          value={date}
          onChange={(next) => emit(next ? formatDate(next) : '', time ? clock(time) : '')}
          placeholder="Select date"
          invalid={error}
          disabled={disabled}
          onBlur={onBlur}
        />
        <TimeSelector
          variant="wheel"
          hourCycle={24}
          value={time}
          onChange={(next) => emit(date ? formatDate(date) : '', next ? clock(next) : '')}
          placeholder="Select time"
          invalid={error}
          disabled={disabled}
          onBlur={onBlur}
        />
      </div>
      <Notes error={error} helperText={helperText} />
    </Field>
  );
};

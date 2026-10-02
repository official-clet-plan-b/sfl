import { ReactNode } from 'react';
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
import { useFormMode } from 'modules/fuel/components/formMode';

/**
 * The fuel forms' field set, composed from the library's `Field` family.
 *
 * Every fuel dialog keeps its form state in React and validates in its own submit handler, so the
 * fields take a plain `value`/`onChange(value)` pair and an `error` flag rather than a
 * react-hook-form controller. What is shared is the shape: a label, the control, and one line under
 * it that is the hint until the field is wrong and the error from then on - never both, so a field
 * does not change height when validation fires.
 */

interface CommonProps {
  label?: string;
  required?: boolean;
  /** Marks the field invalid. `helperText` is then rendered as the error message. */
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  placeholder?: string;
  className?: string;
  autoFocus?: boolean;
}

interface ShellProps {
  label?: string;
  required?: boolean;
  disabled?: boolean;
  error?: boolean;
  helperText?: string;
  className?: string;
  children: ReactNode;
}

/** A label above, a hint or error below. `required` is announced as well as drawn. */
const FieldShell = ({
  label,
  required,
  disabled,
  error,
  helperText,
  className,
  children,
}: ShellProps) => {
  const { markOptional } = useFormMode();
  return (
    <Field invalid={Boolean(error)} className={className ?? 'w-full'}>
      {label && (
        <FieldLabel>
          {label}
          {required && (
            <>
              <span aria-hidden="true"> *</span>
              <span className="sr-only"> (required)</span>
            </>
          )}
          {/* A field the form fills in itself is not the operator's to complete, optional or not. */}
          {!required && !disabled && markOptional && (
            <span className="font-normal text-(--clet-text-secondary)"> (Optional)</span>
          )}
        </FieldLabel>
      )}
      {children}
      {error ? (
        <FieldError>{helperText}</FieldError>
      ) : (
        <FieldDescription>{helperText}</FieldDescription>
      )}
    </Field>
  );
};

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
  <FieldShell
    label={label}
    required={required}
    disabled={disabled}
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
  </FieldShell>
);

export interface NumberFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
  suffix?: string;
}

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
  <FieldShell
    label={label}
    required={required}
    disabled={disabled}
    error={error}
    helperText={helperText}
    className={className}
  >
    <div className="relative">
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
          className={suffix ? 'pr-12' : undefined}
          onChange={(event) => onChange(event.target.value)}
          onBlur={onBlur}
        />
      </FieldControl>
      {suffix && (
        <span className="pointer-events-none absolute top-1/2 right-3.5 -translate-y-1/2 text-xs font-medium text-(--clet-text-secondary)">
          {suffix}
        </span>
      )}
    </div>
  </FieldShell>
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
  autoFocus,
}: TextAreaFieldProps) => (
  <FieldShell
    label={label}
    required={required}
    disabled={disabled}
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
        autoFocus={autoFocus}
        invalid={error}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
      />
    </FieldControl>
  </FieldShell>
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
  emptyLabel?: string;
  allowEmpty?: boolean;
}

export const SelectField = ({
  value,
  onChange,
  options,
  label,
  required,
  error,
  helperText,
  disabled,
  className,
  allowEmpty,
  emptyLabel = 'Any',
  placeholder,
}: SelectFieldProps) => (
  <FieldShell
    label={label}
    required={required}
    disabled={disabled}
    error={error}
    helperText={helperText}
    className={className}
  >
    <Dropdown
      aria-label={label ?? emptyLabel}
      value={value || null}
      onValueChange={(next) => onChange(next ?? '')}
      options={options}
      placeholder={allowEmpty ? emptyLabel : (placeholder ?? 'Select…')}
      clearable={allowEmpty}
      disabled={disabled}
      invalid={error}
    />
  </FieldShell>
);

interface EnumFieldProps<T extends string> extends CommonProps {
  value: T | '';
  options: readonly T[];
  onChange: (value: T | '') => void;
  allowEmpty?: boolean;
  emptyLabel?: string;
  renderOptionLabel?: (option: T) => string;
}

export function EnumField<T extends string>({
  value,
  options,
  onChange,
  allowEmpty,
  emptyLabel = 'Any',
  renderOptionLabel,
  ...rest
}: EnumFieldProps<T>) {
  return (
    <SelectField
      {...rest}
      value={value}
      allowEmpty={allowEmpty}
      emptyLabel={emptyLabel}
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
  <div className="flex flex-col gap-1">
    <Checkbox checked={checked} onCheckedChange={onChange} label={label} disabled={disabled} />
    {hint && <p className="pl-7 text-xs text-(--clet-text-secondary)">{hint}</p>}
  </div>
);

/* Dates travel as `YYYY-MM-DD` and date-times as `YYYY-MM-DDTHH:mm`, local time, as the forms and
 * the services already expect - the library selectors speak `Date`, so these convert at the edge. */

const pad = (value: number) => String(value).padStart(2, '0');

const parseDate = (value: string): Date | null => {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
};

const parseTime = (value: string): TimeValue | null => {
  const match = /T(\d{2}):(\d{2})/.exec(value);
  return match ? { hours: Number(match[1]), minutes: Number(match[2]) } : null;
};

const isoDate = (date: Date) =>
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
  <FieldShell
    label={label}
    required={required}
    disabled={disabled}
    error={error}
    helperText={helperText}
    className={className}
  >
    <div role="group" aria-label={label}>
      <DateSelector
        value={parseDate(value)}
        onChange={(date) => {
          onChange(date ? isoDate(date) : '');
          onBlur?.();
        }}
        min={(minDate && parseDate(minDate)) || undefined}
        max={(maxDate && parseDate(maxDate)) || undefined}
        placeholder={placeholder ?? 'Select date'}
        invalid={error}
        disabled={disabled}
      />
    </div>
  </FieldShell>
);

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
  placeholder,
  className,
}: DateFieldProps) => {
  const date = parseDate(value);
  const time = parseTime(value);

  const emit = (nextDate: Date | null, nextTime: TimeValue | null) => {
    if (!nextDate) {
      onChange('');
      return;
    }
    const at = nextTime ?? { hours: 0, minutes: 0 };
    onChange(`${isoDate(nextDate)}T${pad(at.hours)}:${pad(at.minutes)}`);
  };

  return (
    <FieldShell
      label={label}
      required={required}
      disabled={disabled}
      error={error}
      helperText={helperText}
      className={className}
    >
      <div role="group" aria-label={label} className="grid grid-cols-[1fr_auto] gap-2">
        <DateSelector
          value={date}
          onChange={(next) => {
            emit(next, time);
            onBlur?.();
          }}
          min={(minDate && parseDate(minDate)) || undefined}
          max={(maxDate && parseDate(maxDate)) || undefined}
          placeholder={placeholder ?? 'Select date'}
          invalid={error}
          disabled={disabled}
        />
        <TimeSelector
          hourCycle={24}
          minuteStep={5}
          value={time}
          onChange={(next) => {
            emit(date, next);
            onBlur?.();
          }}
          placeholder="Time"
          invalid={error}
          disabled={disabled || !date}
        />
      </div>
    </FieldShell>
  );
};

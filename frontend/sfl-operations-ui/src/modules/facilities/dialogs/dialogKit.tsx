import { ReactNode } from 'react';
import {
  Button,
  Checkbox as LibraryCheckbox,
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
  Notice,
  Textarea,
  TimeSelector,
} from '@rfdtech/components';
import type { TimeValue } from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import { FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';

/**
 * Domain bindings over the library's form parts, shared by every estate, booking and IFIMP dialog.
 *
 * <p>The library composes a field as `Field > FieldLabel + FieldControl(control) + FieldError`. These
 * wrappers do that composition once with the dashboard's own contract: values are strings in form
 * state (an empty number box stays empty rather than collapsing to 0, a date is `YYYY-MM-DD`), and
 * `error` plus `helperText` mean "show this line, in the error colour". Keeping that contract is what
 * lets the validators in the dialogs stay exactly as they were.
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

/** The label carries the required marker once, and says so to a screen reader in words. */
const LabelText = ({ label, required }: { label: string; required?: boolean }) => (
  <>
    {label}
    {required && (
      <>
        <span className="ml-0.5 text-error" aria-hidden="true">
          *
        </span>
        <span className="sr-only"> (required)</span>
      </>
    )}
  </>
);

interface FieldFrameProps extends CommonProps {
  children: ReactNode;
  /** The caller places `FieldControl` itself, because something else sits beside the control. */
  customControl?: boolean;
}

/**
 * Label, control, then one line for the hint or - when the field is in error - the message.
 *
 * Exported so a bespoke control can sit in the same rhythm as the rest of the form.
 */
export const FieldFrame = ({
  label,
  required,
  error,
  helperText,
  className,
  customControl,
  children,
}: FieldFrameProps) => (
  <Field invalid={error} className={className ?? 'w-full'}>
    {label && (
      <FieldLabel>
        <LabelText label={label} required={required} />
      </FieldLabel>
    )}
    {customControl ? children : <FieldControl>{children}</FieldControl>}
    {error ? (
      <FieldError>{helperText}</FieldError>
    ) : (
      <FieldDescription>{helperText}</FieldDescription>
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
  /** Rendered inside the control on the right - "km", "L", "%". */
  suffix?: string;
}

/**
 * Numeric entry kept as a string in form state, so an empty field stays empty and the validator can
 * report "must be a whole number" instead of silently coercing.
 */
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
  <FieldFrame {...frame} customControl>
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
          invalid={frame.error}
          className={suffix ? 'pr-14' : undefined}
          onChange={(event) => onChange(event.target.value)}
          onBlur={onBlur}
        />
      </FieldControl>
      {suffix && (
        <span className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-xs font-medium text-muted-foreground">
          {suffix}
        </span>
      )}
    </div>
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
  emptyLabel?: string;
  allowEmpty?: boolean;
}

/** A named listbox bound to a closed set of choices. `allowEmpty` makes the blank choice clearable. */
export const SelectInput = ({
  value,
  onChange,
  options,
  onBlur,
  disabled,
  allowEmpty,
  emptyLabel = 'Any',
  ...frame
}: SelectInputProps) => (
  <FieldFrame {...frame}>
    <Dropdown
      aria-label={frame.label ?? 'Select'}
      value={value || null}
      onValueChange={(next) => {
        onChange(next ?? '');
        onBlur?.();
      }}
      options={options}
      placeholder={allowEmpty ? emptyLabel : 'Select…'}
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
  /** Adds a blank option - use for filters, never for a `@NotNull` request field. */
  allowEmpty?: boolean;
  emptyLabel?: string;
  renderOptionLabel?: (option: T) => string;
}

/** Select bound to a backend enum, so an operator can never submit a value the service rejects. */
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
  <LibraryCheckbox
    checked={checked}
    onCheckedChange={onChange}
    disabled={disabled}
    label={
      hint ? (
        <span className="block">
          <span className="block font-medium">{label}</span>
          <span className="block text-xs text-muted-foreground">{hint}</span>
        </span>
      ) : (
        label
      )
    }
  />
);

const DATE_FORMAT: Intl.DateTimeFormatOptions = { day: '2-digit', month: 'short', year: 'numeric' };

const pad = (n: number) => String(n).padStart(2, '0');

/** `YYYY-MM-DD` read as the operator's local calendar day, which `new Date(string)` would not do. */
const parseDay = (value: string): Date | null => {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
};

const formatDay = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

const parseTime = (value: string): TimeValue | null => {
  const match = /T(\d{2}):(\d{2})/.exec(value);
  return match ? { hours: Number(match[1]), minutes: Number(match[2]) } : null;
};

interface DateFieldProps extends CommonProps {
  value: string;
  onChange: (value: string) => void;
  minDate?: string;
  maxDate?: string;
}

/** Form state keeps `YYYY-MM-DD`; the calendar shows a human date. */
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
    <DateSelector
      value={parseDay(value)}
      onChange={(date) => {
        onChange(date ? formatDay(date) : '');
        onBlur?.();
      }}
      min={minDate ? (parseDay(minDate) ?? undefined) : undefined}
      max={maxDate ? (parseDay(maxDate) ?? undefined) : undefined}
      placeholder={placeholder ?? 'Select date'}
      formatOptions={DATE_FORMAT}
      disabled={disabled}
      invalid={frame.error}
    />
  </FieldFrame>
);

/**
 * Date and time, held as `YYYY-MM-DDTHH:mm` as before.
 *
 * Two library selectors rather than one control: the library has no combined picker. Choosing a day
 * with no time yet starts at noon, and choosing a time with no day yet starts today, so neither half
 * ever leaves the value unparseable.
 */
export const DateTimeField = ({
  value,
  onChange,
  onBlur,
  disabled,
  placeholder,
  minDate,
  maxDate,
  ...frame
}: DateFieldProps) => {
  const day = parseDay(value);
  const time = parseTime(value);
  const commit = (nextDay: Date | null, nextTime: TimeValue | null) => {
    if (!nextDay && !nextTime) {
      onChange('');
    } else {
      const base = nextDay ?? new Date();
      const at = nextTime ?? { hours: 12, minutes: 0 };
      onChange(`${formatDay(base)}T${pad(at.hours)}:${pad(at.minutes)}`);
    }
    onBlur?.();
  };

  return (
    <FieldFrame {...frame}>
      <div className="grid grid-cols-[3fr_2fr] gap-2">
        <DateSelector
          value={day}
          onChange={(date) => commit(date, time)}
          min={minDate ? (parseDay(minDate) ?? undefined) : undefined}
          max={maxDate ? (parseDay(maxDate) ?? undefined) : undefined}
          placeholder={placeholder ?? 'Select date'}
          formatOptions={DATE_FORMAT}
          disabled={disabled}
          invalid={frame.error}
        />
        <TimeSelector
          value={time}
          onChange={(next) => commit(day, next)}
          hourCycle={24}
          minuteStep={5}
          placeholder="Time"
          disabled={disabled}
          invalid={frame.error}
        />
      </div>
    </FieldFrame>
  );
};

interface FormDialogProps {
  open: boolean;
  title: string;
  description?: string;
  submitLabel: string;
  submitting: boolean;
  /** Blocks submission for reasons the form itself cannot fix (readiness, eligibility, state). */
  submitDisabled?: boolean;
  formError?: FleetApiError;
  maxWidth?: 'sm' | 'md' | 'lg';
  destructive?: boolean;
  /**
   * A one-line read-back of what is about to be submitted, pinned above the actions.
   *
   * <p>Outside `children` on purpose. The body scrolls, so anything inside it can be off screen at
   * the moment the operator reaches the submit button - which is the one moment a summary is for.
   */
  summary?: ReactNode;
  onClose: () => void;
  onSubmit: () => void;
  children: ReactNode;
}

const modalSizes = { sm: 'md', md: 'lg', lg: '2xl' } as const;

/**
 * The shell every action dialog uses.
 *
 * Two guarantees: the submit button is disabled while a request is in flight, so a double click
 * cannot raise two records; and a form-level failure is shown above the actions with the service's
 * own wording and correlation id rather than being swallowed.
 *
 * The `<form>` element stays even though the library's own example drives submit from a click:
 * pressing Enter in a text field submits, which every one of these dialogs has always done. It is
 * `display: contents` so the header, body and footer remain direct flex children of the modal.
 */
export const FormDialog = ({
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
}: FormDialogProps) => (
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
        onInteractOutside={(event) => {
          if (submitting) {
            event.preventDefault();
          }
        }}
        onEscapeKeyDown={(event) => {
          if (submitting) {
            event.preventDefault();
          }
        }}
      >
        <ModalHeader>
          <ModalTitle>{title}</ModalTitle>
          {description && <ModalDescription>{description}</ModalDescription>}
        </ModalHeader>
        <form
          className="contents"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
          noValidate
        >
          <ModalBody className="space-y-5">
            {children}

            {formError && (
              <Notice
                variant={formError.isForbidden ? 'warning' : 'error'}
                title={errorLabel(formError)}
              >
                <p>{formError.message}</p>
                <p className="mt-1 text-xs opacity-80">{errorDetail(formError)}</p>
              </Notice>
            )}
          </ModalBody>

          {summary}

          <ModalFooter>
            <Button variant="outline" type="button" onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant={destructive ? 'primary-destructive' : 'primary'}
              loading={submitting}
              loadingLabel="Working…"
              disabled={submitDisabled}
            >
              {submitLabel}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </ModalPortal>
  </Modal>
);

export default FormDialog;

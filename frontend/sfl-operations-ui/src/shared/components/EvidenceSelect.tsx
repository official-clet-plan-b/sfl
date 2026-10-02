import { FocusEvent, ReactNode, useMemo, useState } from 'react';
import {
  Button,
  Dropdown,
  Field,
  FieldControl,
  FieldDescription,
  FieldError,
  FieldLabel,
  Input,
  type DropdownOption,
} from '@rfdtech/components';
import { useApiQuery } from 'shared/hooks/useApiQuery';
import { cn } from './cn';

/**
 * Picks evidence already filed against a record.
 *
 * Every closure dialog in this dashboard used to ask an operator to paste an evidence reference id.
 * The identifier is a UUID that appears on no paperwork, so the real workflow was: open Evidence &
 * audit in another tab, find the record, copy the id, come back. The S166 gap register called that
 * the main usability cost in the whole dashboard, and it was worst exactly where it mattered most -
 * closing a workflow item, where evidence is mandatory and the service refuses the close without it.
 *
 * `GET /evidence?relatedRecordType=&relatedRecordId=` is the whole fix. The record is the only thing
 * an operator reliably knows, and it is what every one of these dialogs already has in hand.
 *
 * **The text field does not go away.** Evidence filed against a different record is a legitimate
 * reference - a site-wide certificate closes a dozen items and belongs to none of them - so the
 * picker offers what it found and gets out of the way when the answer is somewhere else. It also
 * falls back to the text field when the record has no evidence at all, which is the state an
 * operator is in the first time they close anything.
 *
 * <h2>Why it lives in `shared` and takes a `search` function</h2>
 *
 * It was written in `modules/fleet` and used by exactly one dialog, while eight others kept asking
 * for the paste. Moving it here is what let the rest adopt it: dispatch needs it too, and a dispatch
 * dialog reaching into `modules/fleet` for an API client is a module boundary crossed for no reason.
 * Injecting the search keeps this component ignorant of which service answers - the FTLMP evidence
 * store happens to serve fleet, fuel and dispatch alike, and a future module with its own store
 * passes its own function rather than forcing a change here.
 *
 * `search` is deliberately **not** a dependency of the query. Callers pass a module-level function;
 * an inline arrow would re-fetch on every render.
 */

/** The shape the picker needs. Any module's evidence response maps onto this. */
export interface EvidenceChoice {
  id: string;
  /** What the operator recognises. Falls back to the storage reference when a name is absent. */
  fileName: string;
  evidenceType: string;
  legalHold?: boolean;
}

export type EvidenceSearch = (
  relatedRecordType: string,
  relatedRecordId: string,
  signal?: AbortSignal,
) => Promise<EvidenceChoice[]>;

interface EvidenceSelectProps {
  /** Both are needed to query. Either being absent means the picker cannot run, not that it failed. */
  relatedRecordType: string | null;
  relatedRecordId: string | null;
  /** Must be stable across renders - a module-level function, not an inline arrow. */
  search: EvidenceSearch;
  value: string;
  onChange: (value: string) => void;
  label?: string;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  className?: string;
}

/** Label with the required marker the shared fields have always carried, for assistive tech too. */
const RequiredLabel = ({ label, required }: { label: string; required?: boolean }): ReactNode => (
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

export const EvidenceSelect = ({
  relatedRecordType,
  relatedRecordId,
  search,
  value,
  onChange,
  label = 'Evidence reference',
  required,
  error,
  helperText,
  onBlur,
  disabled,
  className,
}: EvidenceSelectProps) => {
  const [manual, setManual] = useState(false);

  const evidence = useApiQuery(
    (signal) =>
      relatedRecordType && relatedRecordId
        ? search(relatedRecordType, relatedRecordId, signal)
        : Promise.resolve(undefined),
    [relatedRecordType, relatedRecordId],
  );

  const options = useMemo<DropdownOption[]>(
    () =>
      (evidence.data ?? []).map((reference) => ({
        value: reference.id,
        // File name first: it is what the operator recognises. The type disambiguates two scans of
        // the same job, and the legal hold is worth seeing before it is cited in a closure.
        label: `${reference.fileName} · ${reference.evidenceType}${reference.legalHold ? ' · legal hold' : ''}`,
      })),
    [evidence.data],
  );

  /** No record to query, nothing filed against it, or the operator asked for the text field. */
  const typing = manual || !relatedRecordType || !relatedRecordId || options.length === 0;

  const typingHint =
    helperText ??
    (evidence.loading
      ? 'Looking for evidence filed against this record…'
      : options.length === 0 && relatedRecordType && relatedRecordId
        ? `Nothing is filed against ${relatedRecordType} ${relatedRecordId.slice(0, 8)} yet - register it under Evidence and audit, then paste the reference here.`
        : 'Paste the reference id from Evidence and audit.');

  // The dropdown has no blur callback of its own, so the field reports leaving it - but not the
  // focus moving into its own open list, which would mark the field visited before a choice is made.
  const leave = (event: FocusEvent<HTMLDivElement>) => {
    if (event.relatedTarget instanceof Element && event.relatedTarget.closest('[role="listbox"]')) {
      return;
    }
    onBlur?.();
  };

  if (typing) {
    return (
      <div className={cn('flex flex-col items-start gap-1.5', className)}>
        <Field invalid={error} className="w-full" onBlur={onBlur}>
          <FieldLabel>
            <RequiredLabel label={label} required={required} />
          </FieldLabel>
          <FieldControl>
            <Input value={value} onChange={(event) => onChange(event.target.value)} disabled={disabled} />
          </FieldControl>
          {error ? <FieldError>{typingHint}</FieldError> : <FieldDescription>{typingHint}</FieldDescription>}
        </Field>
        {options.length > 0 && (
          <Button size="sm" variant="ghost" onClick={() => setManual(false)}>
            Choose from this record instead
          </Button>
        )}
      </div>
    );
  }

  const chosenHint =
    helperText ?? `${options.length} filed against this ${relatedRecordType.toLowerCase()}.`;

  return (
    <div className={cn('flex flex-col items-start gap-1.5', className)}>
      <Field invalid={error} className="w-full" onBlur={leave}>
        <FieldLabel htmlFor={undefined}>
          <RequiredLabel label={label} required={required} />
        </FieldLabel>
        <Dropdown
          aria-label={required ? `${label} (required)` : label}
          value={value || null}
          onValueChange={(next) => onChange(next ?? '')}
          options={options}
          disabled={disabled}
          invalid={error}
        />
        {error ? <FieldError>{chosenHint}</FieldError> : <FieldDescription>{chosenHint}</FieldDescription>}
      </Field>
      <Button
        size="sm"
        variant="ghost"
        onClick={() => {
          setManual(true);
          // A selected id would otherwise sit in a field the operator is about to retype, and look
          // like something they chose.
          onChange('');
        }}
      >
        Use a reference from elsewhere
      </Button>
    </div>
  );
};

export default EvidenceSelect;

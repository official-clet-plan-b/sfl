import { FocusEvent } from 'react';
import { Dropdown, Field, FieldDescription, FieldError, FieldLabel } from '@rfdtech/components';
import { actorSiteFailure, actorSites, defaultSite, scopeIsEverySite } from 'shared/layout/actorSites';
import { cn } from './cn';

/**
 * The actor's site scope.
 *
 * <p>Both the list and the default now come from `shared/layout/actorSites`, which resolves the `*`
 * wildcard into real site codes rather than treating it as one. Re-exported here because forty call
 * sites import `defaultSite` from this module and the indirection is not worth a rename.
 */
export { defaultSite };

/**
 * Every site this actor may name.
 *
 * A function rather than a constant: for a wildcard scope the list arrives from the site register at
 * boot, after this module is evaluated but before the first paint, so a constant captured here would
 * be the unresolved one.
 */
export const sflSites = (): string[] => actorSites();

interface SiteSelectProps {
  label?: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  error?: boolean;
  helperText?: string;
  onBlur?: () => void;
  disabled?: boolean;
  className?: string;
  /** Adds an "all sites" choice - use for a filter, never for a request field. */
  allowEmpty?: boolean;
  emptyLabel?: string;
}

/**
 * Site scope as a choice rather than free text.
 *
 * Every write carries a site code, and the service refuses one outside the actor's scope with
 * `FLEET_UNAUTHORIZED_SCOPE` - a rule the operator only discovered by typing a neighbouring site
 * and having the submission bounced. The options are the actor's own sites, so the dashboard can no
 * longer offer a site it cannot write to. Its props mirror the text fields' so it drops into the same
 * grid without any other change.
 *
 * <p>Where the site list could not be resolved, the control says so on itself rather than rendering
 * an empty dropdown that reads as a screen with nothing in it. The caller's own `helperText` wins,
 * so a field with something more specific to say still says it.
 */
const SiteSelect = ({
  label = 'Site code',
  emptyLabel = 'All sites',
  helperText,
  value,
  onChange,
  required,
  error,
  onBlur,
  disabled,
  className,
  allowEmpty,
}: SiteSelectProps) => {
  const sites = actorSites();
  const failure = actorSiteFailure();
  const invalid = Boolean(error || (failure !== null && required));
  const hint = helperText ?? failure ?? undefined;

  // The dropdown has no blur callback of its own, so the field reports leaving it - but not the
  // focus moving into its own open list, which would mark the field visited before a choice is made.
  const leave = (event: FocusEvent<HTMLDivElement>) => {
    if (event.relatedTarget instanceof Element && event.relatedTarget.closest('[role="listbox"]')) {
      return;
    }
    onBlur?.();
  };

  return (
    <Field invalid={invalid} className={cn('min-w-0', className)} onBlur={leave}>
      <FieldLabel htmlFor={undefined}>
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
      <Dropdown
        aria-label={required ? `${label} (required)` : label}
        value={value || null}
        onValueChange={(next) => onChange(next ?? '')}
        options={sites.map((site) => ({ value: site, label: site }))}
        placeholder={allowEmpty ? emptyLabel : 'Select…'}
        clearable={allowEmpty}
        disabled={disabled}
        invalid={invalid}
      />
      {invalid ? <FieldError>{hint}</FieldError> : <FieldDescription>{hint}</FieldDescription>}
    </Field>
  );
};

export { scopeIsEverySite };
export default SiteSelect;

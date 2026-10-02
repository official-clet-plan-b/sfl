import { Notice } from '@rfdtech/components';
import { cn } from './cn';

/**
 * The record you are about to create, in one line.
 *
 * <p>A dialog body scrolls at `68vh`. On the longer forms that means the fields you filled first have
 * left the screen by the time you reach the submit button, so the last thing you see before
 * committing a record is whatever happened to be at the bottom of the form. This is pinned between
 * the scroll area and the footer for exactly that reason - it is the one part that must not scroll.
 *
 * <p>It reads back what was entered rather than repeating the labels: `Accra HQ → Kumasi Centre`,
 * `13,500 → 13,880 km · 380 km covered`. A derived value is the point. Nobody checks whether an
 * odometer reading is plausible in isolation; everybody notices a trip that covered 38,000 km.
 *
 * <p>Deliberately not a validation surface. Errors belong on the field that caused them, where they
 * can be fixed; this only says what the form currently amounts to.
 */

export interface SummaryItem {
  label: string;
  /** Blank, null or undefined renders as a dash - "not yet answered" is worth showing. */
  value: string | null | undefined;
  /**
   * Draws the eye to a derived figure - distance covered, a total, a computed window. Reserved for
   * values the operator did not type and would not otherwise check.
   */
  emphasis?: boolean;
}

interface FormSummaryProps {
  items: SummaryItem[];
  className?: string;
}

const FormSummary = ({ items, className }: FormSummaryProps) => {
  const shown = items.filter((item) => item.label);
  if (shown.length === 0) {
    return null;
  }

  return (
    // `group` rather than the Notice's own live-region role: this restates the form on every
    // keystroke, and announcing each of those would drown out the field being typed in.
    <Notice
      role="group"
      className={cn('rounded-none border-x-0 border-b-0 px-6 py-3', className)}
    >
      <dl className="flex flex-wrap items-baseline gap-x-5 gap-y-1.5">
        {shown.map((item) => {
          const value = item.value?.trim();
          return (
            <div key={item.label} className="flex min-w-0 items-baseline gap-1.5">
              <dt className="text-xs text-muted-foreground">{item.label}</dt>
              <dd
                className={cn(
                  'truncate text-sm',
                  value
                    ? item.emphasis
                      ? 'font-semibold text-primary'
                      : 'font-medium text-foreground'
                    : 'text-muted-foreground',
                )}
              >
                {value || '-'}
              </dd>
            </div>
          );
        })}
      </dl>
    </Notice>
  );
};

export default FormSummary;

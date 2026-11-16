import { clsx } from 'clsx';

/**
 * Loading indicators - one look everywhere something is being fetched.
 *
 * A ring in the interactive (teal) colour on a faint track. `role="status"` and a visually hidden
 * label let a screen reader announce what is loading; the animation honours reduced-motion through
 * Tailwind's `motion-safe`.
 */
export const Spinner = ({
  size = 26,
  className,
  label,
}: {
  size?: number;
  className?: string;
  /** Read out to assistive technology. Omit when a visible label sits beside the spinner. */
  label?: string;
}) => (
  <span className={clsx('inline-flex shrink-0', className)} role={label ? 'status' : undefined}>
    <svg
      className="motion-safe:animate-spin text-primary"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9.5" stroke="currentColor" strokeOpacity="0.18" strokeWidth="2.5" />
      <path d="M21.5 12A9.5 9.5 0 0 0 12 2.5" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
    {label && <span className="sr-only">{label}</span>}
  </span>
);

/** A centred spinner with a caption, for a panel or page whose content has not arrived yet. */
export const LoadingBlock = ({
  label = 'Loading…',
  minHeight = 220,
  className,
}: {
  label?: string;
  minHeight?: number;
  className?: string;
}) => (
  <div
    className={clsx('flex flex-col items-center justify-center gap-3', className)}
    style={{ minHeight }}
    role="status"
    aria-live="polite"
  >
    <Spinner size={30} />
    <p className="text-sm text-muted-foreground">{label}</p>
  </div>
);

/** A small spinner beside a line of text, for a field or list that is filling in. */
export const InlineLoading = ({ label, className }: { label: string; className?: string }) => (
  <span className={clsx('inline-flex items-center gap-2 text-sm text-muted-foreground', className)} role="status">
    <Spinner size={14} />
    {label}
  </span>
);

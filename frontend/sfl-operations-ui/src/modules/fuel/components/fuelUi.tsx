import { ReactNode } from 'react';
import {
  Badge,
  Banner,
  Button,
  Card,
  CardActions,
  CardHeader,
  CardTitle,
  EmptyState,
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetOverlay,
  SheetPortal,
  SheetTitle,
  Table,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  TableSearch,
  type BadgeVariant,
  type TableColumn,
} from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import { FormModeContext } from 'modules/fuel/components/formMode';
import { PAGE_SIZE_OPTIONS } from 'modules/fuel/components/useRegisterPaging';
import { FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';

export type Tone = 'ready' | 'caution' | 'blocked' | 'neutral' | 'active';

const toneVariants: Record<Tone, BadgeVariant> = {
  ready: 'success',
  caution: 'warning',
  blocked: 'error',
  neutral: 'default',
  active: 'primary',
};

/**
 * How each status the fuel screens show reads at a glance.
 *
 * EXCEPTION and ESCALATED are the states that put a case on somebody's queue, so they share the
 * alarm tone; RETURNED and the pre-decision anomaly states are amber because the record is waiting
 * on a person rather than because anything failed; RECONCILED, APPROVED and CLOSED are settled.
 */
const statusTones: Record<string, Tone> = {
  ACTIVE: 'ready',
  INACTIVE: 'neutral',
  ARCHIVED: 'neutral',
  SUSPENDED: 'blocked',
  CANCELLED: 'neutral',
  ACCEPTED: 'ready',
  REJECTED: 'blocked',
  PENDING: 'caution',
  MISSING: 'blocked',
  WARNING: 'caution',
  BLOCKING: 'blocked',

  RECEIVED: 'neutral',
  VALIDATING: 'active',
  MATCHED: 'ready',
  RECONCILED: 'ready',
  EXCEPTION: 'blocked',
  VOIDED: 'neutral',

  DRAFT: 'neutral',
  SUBMITTED: 'active',
  UNDER_REVIEW: 'active',
  RETURNED: 'caution',
  RESUBMITTED: 'active',
  APPROVED: 'ready',
  REOPENED: 'caution',

  DETECTED: 'caution',
  ASSIGNED: 'active',
  AWAITING_EXPLANATION: 'caution',
  EXPLANATION_RECEIVED: 'active',
  ESCALATED: 'blocked',
  CLOSED: 'ready',
  HELD: 'caution',

  LOW: 'neutral',
  MEDIUM: 'active',
  HIGH: 'caution',
  CRITICAL: 'blocked',
};

interface FuelBadgeProps {
  value: string | null | undefined;
  tone?: Tone;
  label?: string;
  size?: 'sm' | 'md';
}

/** A status as a library `Badge`, toned from the value unless the caller states a tone. */
export const FuelBadge = ({ value, tone, label, size = 'sm' }: FuelBadgeProps) => (
  <Badge
    size={size}
    variant={toneVariants[tone ?? (value ? (statusTones[value] ?? 'neutral') : 'neutral')]}
  >
    {label ?? humanise(value)}
  </Badge>
);

/**
 * A request failure, in the service's own words with the correlation id support will ask for.
 *
 * An authorisation refusal is a warning rather than an error: nothing broke, the actor is simply not
 * entitled, and the two should not look alike.
 */
export const ErrorBanner = ({
  error,
  onRetry,
  className,
}: {
  error: FleetApiError;
  onRetry?: () => void;
  className?: string;
}) => (
  <Banner
    variant={error.isForbidden ? 'warning' : 'danger'}
    heading={errorLabel(error)}
    subtext={
      <>
        {error.message}
        {errorDetail(error) && <span className="mt-1 block text-xs">{errorDetail(error)}</span>}
      </>
    }
    action={
      onRetry ? (
        <Button size="sm" variant="outline" onClick={onRetry}>
          Retry
        </Button>
      ) : undefined
    }
    className={className}
  />
);

type SheetWidth = 'sm' | 'md' | 'lg' | 'xl';

const sheetWidths: Record<SheetWidth, string> = {
  sm: 'w-[min(440px,100vw)]',
  md: 'w-[min(520px,100vw)]',
  lg: 'w-[min(640px,100vw)]',
  xl: 'w-[min(760px,100vw)]',
};

interface FuelFormSheetProps {
  open: boolean;
  title: string;
  description?: string;
  submitLabel: string;
  submitting: boolean;
  /** Blocks submission for reasons the form itself cannot fix (readiness, eligibility, state). */
  submitDisabled?: boolean;
  formError?: FleetApiError;
  maxWidth?: SheetWidth;
  destructive?: boolean;
  /**
   * A one-line read-back of what is about to be submitted, pinned above the actions.
   *
   * Outside the scrolling body on purpose: anything inside it can be off screen at the moment the
   * operator reaches the submit button, which is the one moment a summary is for.
   */
  summary?: ReactNode;
  onClose: () => void;
  onSubmit: () => void;
  children: ReactNode;
}

/**
 * The slide-out panel every fuel form uses, as the design draws them.
 *
 * Two guarantees: the submit button is disabled while a request is in flight, so a double click
 * cannot raise two writes, and a form-level failure is shown above the actions with the service's
 * own wording and correlation id rather than swallowed. The library sheet asks for no literal
 * `<form>`, so Enter in a text field submits through the key handler below instead.
 *
 * Fields inside it mark themselves "(Optional)" when they are not required - the design's
 * convention - which is what `FormModeContext` tells them to do.
 */
export const FuelFormSheet = ({
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
}: FuelFormSheetProps) => (
  <Sheet
    open={open}
    // A request in flight cannot be abandoned from the overlay, Escape or the close control.
    onOpenChange={(next) => {
      if (!next && !submitting) {
        onClose();
      }
    }}
  >
    <SheetPortal>
      <SheetOverlay />
      <SheetContent
        showCloseButton
        className={sheetWidths[maxWidth]}
        onKeyDown={(event) => {
          if (
            event.key === 'Enter' &&
            !event.shiftKey &&
            event.target instanceof HTMLInputElement &&
            event.target.type !== 'file' &&
            !submitting &&
            !submitDisabled
          ) {
            event.preventDefault();
            onSubmit();
          }
        }}
      >
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
          {description && <SheetDescription>{description}</SheetDescription>}
        </SheetHeader>

        <SheetBody>
          <FormModeContext.Provider value={{ markOptional: true }}>
            <div className="flex flex-col gap-5">
              {children}
              {formError && <ErrorBanner error={formError} />}
            </div>
          </FormModeContext.Provider>
        </SheetBody>

        {summary}

        <SheetFooter>
          <Button variant="outline" onClick={onClose} disabled={submitting}>
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
        </SheetFooter>
      </SheetContent>
    </SheetPortal>
  </Sheet>
);

/** A titled card for one section of a screen: title and description on the left, actions on the right. */
export const Panel = ({
  title,
  description,
  actions,
  className,
  children,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}) => (
  <Card bordered className={className}>
    <CardHeader>
      <div className="min-w-0">
        <CardTitle>{title}</CardTitle>
        {description && (
          <p className="mt-0.5 text-sm text-(--clet-text-secondary)">{description}</p>
        )}
      </div>
      {actions && <CardActions>{actions}</CardActions>}
    </CardHeader>
    {children}
  </Card>
);

/** Two-line cell: a strong primary value with quieter supporting detail underneath. */
export const CellStack = ({
  primary,
  secondary,
}: {
  primary: ReactNode;
  secondary?: ReactNode;
}) => (
  <div className="min-w-0">
    <div className="truncate font-semibold">{primary}</div>
    {secondary !== undefined && secondary !== null && (
      <div className="truncate text-xs text-(--clet-text-secondary)">{secondary}</div>
    )}
  </div>
);

interface RegisterTableProps<T> {
  /** Namespaces the table's URL state (`<prefix>.page`, `<prefix>.search`) from the other tables on screen. */
  paramPrefix: string;
  columns: TableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  loading: boolean;
  onRowClick?: (row: T) => void;
  /** What the register says when nothing is in it, and when the filters leave nothing. */
  empty: { title: string; description: string; filteredTitle: string };
  filtersApplied: boolean;
  totalPages: number;
  totalItems: number;
  pageSize: number;
  /** The filter fields. Omit for a register that is searched only. */
  filters?: ReactNode;
  /** Two fields or fewer sit inline; more are grouped behind the Filters trigger. */
  spreadFilters?: boolean;
  onResetFilters?: () => void;
  searchPlaceholder?: string;
}

/**
 * A server-paged register: filters and search above a soft table, pagination below.
 *
 * Every register screen composes the same five library parts in the same order, and the order
 * matters - `TableSearch` and `TableFilter` only work inside `Table`. The screen owns the data, its
 * filters and the paging state (see `useRegisterPaging`); this owns only how they are laid out.
 */
export function RegisterTable<T>({
  paramPrefix,
  columns,
  rows,
  rowKey,
  loading,
  onRowClick,
  empty,
  filtersApplied,
  totalPages,
  totalItems,
  pageSize,
  filters,
  spreadFilters,
  onResetFilters,
  searchPlaceholder,
}: RegisterTableProps<T>) {
  return (
    <Table paramPrefix={paramPrefix} variant="soft">
      {(filters || searchPlaceholder) && (
        <TableHeader>
          {filters && (
            <TableFilter variant={spreadFilters ? 'spread' : 'popover'} onReset={onResetFilters}>
              {filters}
            </TableFilter>
          )}
          {searchPlaceholder && <TableSearch placeholder={searchPlaceholder} />}
        </TableHeader>
      )}
      <TableContent
        variant="soft"
        columns={columns}
        data={rows}
        rowKey={rowKey}
        loading={loading}
        onRowClick={onRowClick}
        emptyContent={
          <EmptyState
            title={filtersApplied ? empty.filteredTitle : empty.title}
            description={
              filtersApplied ? 'Try removing a filter or adjusting your search terms.' : empty.description
            }
          />
        }
      />
      {totalItems > 0 && (
        <TableFooter noBorder>
          <TablePagination
            totalPages={Math.max(1, totalPages)}
            totalItems={totalItems}
            pageSizeOptions={PAGE_SIZE_OPTIONS}
            defaultPageSize={pageSize}
          />
        </TableFooter>
      )}
    </Table>
  );
}

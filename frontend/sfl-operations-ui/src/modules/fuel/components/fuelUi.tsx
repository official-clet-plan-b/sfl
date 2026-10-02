import { useState } from 'react';
import type { ComponentProps, ReactNode } from 'react';
import {
  Badge,
  Banner,
  Button as LibraryButton,
  Card,
  CardActions,
  CardHeader,
  CardTitle,
  EmptyState,
  Table,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  TableSearch,
  MetricCard,
  type MetricCardProps,
  type BadgeVariant,
  type TableColumn,
} from '@rfdtech/components';
import { humanise } from 'modules/fleet/api/enums';
import { FormModeContext } from 'modules/fuel/components/formMode';
import { PAGE_SIZE_OPTIONS } from 'modules/fuel/components/useRegisterPaging';
import { FleetApiError, errorDetail, errorLabel } from 'shared/errors/FleetApiError';
import { MAX_UPLOAD_BYTES, sizeRejectionReason } from 'shared/evidence/evidenceFilesApi';
import { CheckboxField, DateField, DateTimeField, EnumField, NumberField, SelectField, TextAreaField, TextField } from './fuelFields';

/** Fuel screens use these names while they are being kept readable by domain language. Each is
 * composed directly from @rfdtech/components; no legacy shared-kit component is involved. */
export { TextField, NumberField, TextAreaField, SelectField, EnumField, CheckboxField, DateField, DateTimeField };
export const Button = ({ startIcon: _startIcon, endIcon: _endIcon, variant, ...props }: Omit<ComponentProps<typeof LibraryButton>, 'variant'> & { startIcon?: string; endIcon?: string; variant?: ComponentProps<typeof LibraryButton>['variant'] | 'danger' | 'accent' }) => (
  <LibraryButton variant={variant === 'danger' ? 'primary-destructive' : variant === 'accent' ? 'primary' : variant} {...props} />
);
export { default as EvidenceFileField, EvidenceFileActions } from 'shared/components/EvidenceFileField';
export { default as EvidenceSelect } from 'shared/components/EvidenceSelect';
export const FileField = ({ label, value, onChange, accept, maxBytes = MAX_UPLOAD_BYTES, error, helperText, disabled, required, onBlur }: { label: string; value: File | null; onChange: (file: File | null) => void; accept?: string; maxBytes?: number; error?: boolean; helperText?: string; disabled?: boolean; required?: boolean; onBlur?: () => void }) => {
  const [refusal, setRefusal] = useState<string | null>(null);
  return (
    <label className="flex flex-col gap-1 text-sm font-medium text-gray-800">
      <span>{label}{required && <span aria-hidden="true"> *</span>}</span>
      <input
        type="file"
        accept={accept}
        disabled={disabled}
        required={required}
        aria-invalid={error || Boolean(refusal) || undefined}
        onBlur={onBlur}
        onChange={(event) => {
          const file = event.target.files?.[0] ?? null;
          const reason = file ? sizeRejectionReason(file, maxBytes) : null;
          setRefusal(reason);
          onChange(reason ? null : file);
        }}
        className="block w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm"
      />
      <span className="text-xs font-normal text-gray-500">{refusal ?? helperText ?? `Up to ${Math.round(maxBytes / (1024 * 1024))} MB.`}</span>
      {value && <span className="text-xs font-normal text-gray-700">Selected: {value.name}</span>}
    </label>
  );
};
export const TextInput = TextField;
export const NumberInput = NumberField;
export const TextAreaInput = TextAreaField;
export const SelectInput = SelectField;
export const EnumSelect = EnumField;
export type { TableColumn } from '@rfdtech/components';
export { default as PageHeader } from 'modules/emergency/components/PageHeading';

export const Alert = ({ variant, title, children, className }: { variant: 'info' | 'success' | 'warning' | 'error'; title?: string; children?: ReactNode; className?: string }) => (
  <Banner variant={variant === 'error' ? 'danger' : variant} heading={title} subtext={children} className={className} />
);

export const StatCard = ({ label, value, caption, tone: _tone, ...props }: { label: string; value: string | number; caption?: string; tone?: string } & Partial<MetricCardProps>) => (
  <MetricCard label={label} value={value} description={caption} variant="soft" {...props} />
);

export const StatusChip = ({ value, ...props }: ComponentProps<typeof FuelBadge>) => <FuelBadge value={value} {...props} />;

export const FilterBar = ({ children }: { children: ReactNode }) => (
  <div className="flex flex-wrap items-end gap-3 border-b border-gray-100 bg-gray-50/70 p-4">{children}</div>
);

export type FuelColumn<T> = {
  key: string;
  header: ReactNode;
  width?: number;
  align?: 'left' | 'center' | 'right';
  cell: (row: T) => ReactNode;
  hideBelowLg?: boolean;
};

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
  open ? (
    <div role="dialog" aria-modal="true" aria-label={title} className="fixed inset-0 z-50 flex justify-end bg-black/30">
      <div
        className={`${sheetWidths[maxWidth]} pointer-events-auto`}
        style={{ pointerEvents: 'auto' }}
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
        <div className="border-b border-gray-200 px-6 py-5">
          <h2 className="text-lg font-semibold text-gray-900">{title}</h2>
          {description && <p className="mt-1 text-sm text-gray-600">{description}</p>}
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-5">
          <FormModeContext.Provider value={{ markOptional: true }}>
            <div className="flex flex-col gap-5">
              {children}
              {formError && <ErrorBanner error={formError} />}
            </div>
          </FormModeContext.Provider>
        </div>

        {summary}

        <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
          <Button variant="outline" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant={destructive ? 'primary-destructive' : 'primary'}
            loading={submitting}
            disabled={submitDisabled}
            onClick={onSubmit}
          >
            {submitting ? 'Working…' : submitLabel}
          </Button>
        </div>
      </div>
    </div>
  ) : null
);

export const FormDialog = FuelFormSheet;

/** A titled card for one section of a screen: title and description on the left, actions on the right. */
export const Panel = ({
  title,
  description,
  subtitle,
  flush: _flush,
  actions,
  className,
  children,
}: {
  title: string;
  description?: ReactNode;
  subtitle?: ReactNode;
  flush?: boolean;
  actions?: ReactNode;
  className?: string;
  children: ReactNode;
}) => (
  <Card bordered className={className}>
    <CardHeader>
      <div className="min-w-0">
        <CardTitle>{title}</CardTitle>
        {(description ?? subtitle) && (
          <p className="mt-0.5 text-sm text-(--clet-text-secondary)">{description ?? subtitle}</p>
        )}
      </div>
      {actions && <CardActions>{actions}</CardActions>}
    </CardHeader>
    {children}
  </Card>
);

export const SectionCard = Panel;

export const DataTable = <T,>({
  rows,
  columns,
  getRowId,
  loading = false,
  onRowClick,
  emptyMessage = 'Nothing to show.',
  className,
}: {
  rows: T[];
  columns: FuelColumn<T>[];
  getRowId: (row: T) => string;
  loading?: boolean;
  onRowClick?: (row: T) => void;
  emptyMessage?: string;
  dense?: boolean;
  caption?: string;
  page?: number;
  pageSize?: number;
  totalElements?: number;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
  pageSizeOptions?: number[];
  className?: string;
}) => (
  <Table paramPrefix="fuel-table" variant="soft" className={className}>
    <TableContent
      variant="soft"
      columns={columns.map((column) => ({
        id: column.key,
        header: String(column.header ?? ''),
        width: column.width,
        align: column.align,
        cell: ({ row }: { row: T }) => column.cell(row),
      }))}
      data={rows}
      rowKey={getRowId}
      loading={loading}
      onRowClick={onRowClick}
      emptyContent={<EmptyState title={emptyMessage} description="There are no records to display." />}
    />
  </Table>
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

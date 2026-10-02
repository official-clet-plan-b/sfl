import { ReactNode, useId } from 'react';
import Button from './Button';
import Icon from './Icon';
import { Spinner } from './DataState';
import { cn } from './cn';
import type { TableColumn as LibraryTableColumn } from '@rfdtech/components';

export interface Column<T> {
  key: string;
  header: ReactNode;
  /** Minimum width in pixels. The table scrolls horizontally rather than crushing a column. */
  width?: number;
  align?: 'left' | 'center' | 'right';
  cell: (row: T) => ReactNode;
  /** Hides the column below `lg`, for detail that is not worth a horizontal scroll on a laptop. */
  hideBelowLg?: boolean;
}

type CompatibleColumn<T> = Column<T> | LibraryTableColumn<T>;

const renderColumnCell = <T,>(column: CompatibleColumn<T>, row: T) =>
  'key' in column
    ? column.cell(row)
    : column.cell?.({
        row,
        value: (column.accessorFn?.(row) ?? (column.accessorKey ? row[column.accessorKey] : undefined)) as ReactNode,
      });

interface DataTableProps<T> {
  rows: T[];
  columns: CompatibleColumn<T>[];
  getRowId: (row: T) => string;
  loading?: boolean;
  onRowClick?: (row: T) => void;
  emptyMessage?: string;
  dense?: boolean;
  /** Names the table for screen readers. Rendered visually hidden. */
  caption?: string;
  /** Server-side pagination. Omit `totalElements` to render the table without a footer. */
  page?: number;
  pageSize?: number;
  totalElements?: number;
  onPageChange?: (page: number) => void;
  onPageSizeChange?: (size: number) => void;
  pageSizeOptions?: number[];
  className?: string;
}

const alignment = {
  left: 'text-left',
  center: 'text-center',
  right: 'text-right',
} as const;

/**
 * The dashboard's table, styled after the design system's own table component: a grey header band in
 * sentence case, hairline row rules, a strong first column and quieter supporting columns.
 *
 * Server-paginated by default because every fleet collection endpoint is paged, and a table that
 * silently shows the first page as if it were the whole register is the kind of thing an operator
 * only discovers when a vehicle is missing. The footer therefore always states the real total.
 *
 * Accessibility notes. The horizontal scroller is focusable and labelled, so a keyboard user can
 * reach columns that are off-screen (SC 2.1.1). A clickable row is a real `<button>` inside the
 * first cell rather than an `onClick` on the `<tr>` - a table row has no role that accepts
 * activation, and a div-with-a-handler is invisible to assistive technology.
 */
function DataTable<T>({
  rows,
  columns,
  getRowId,
  loading = false,
  onRowClick,
  emptyMessage = 'Nothing to show.',
  dense = false,
  caption,
  page = 0,
  pageSize = 25,
  totalElements,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [10, 25, 50, 100],
  className,
}: DataTableProps<T>) {
  const captionId = useId();
  const paginated = totalElements !== undefined;
  const totalPages = paginated ? Math.max(1, Math.ceil(totalElements / pageSize)) : 1;
  const firstRow = totalElements === 0 ? 0 : page * pageSize + 1;
  const lastRow = Math.min((page + 1) * pageSize, totalElements ?? rows.length);

  return (
    <div className={cn('relative flex min-w-0 flex-col', className)}>
      <div
        className="custom-scrollbar min-w-0 overflow-x-auto"
        tabIndex={0}
        role="region"
        aria-labelledby={caption ? captionId : undefined}
        aria-label={caption ? undefined : 'Results table'}
      >
        <table className="w-full min-w-max border-collapse">
          {caption && (
            <caption id={captionId} className="sr-only">
              {caption}
            </caption>
          )}
          <thead>
            <tr className="border-y border-gray-200 bg-gray-50">
              {columns.map((column) => (
                <th
                  key={'key' in column ? column.key : column.id}
                  scope="col"
                  style={column.width ? { minWidth: column.width } : undefined}
                  className={cn(
                    'px-5 py-3 text-[11px] font-semibold tracking-wider whitespace-nowrap text-gray-500 uppercase',
                    alignment[column.align ?? 'left'],
                    'hideBelowLg' in column && column.hideBelowLg && 'hidden lg:table-cell',
                  )}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !loading && (
              <tr>
                <td
                  colSpan={columns.length}
                  className="px-5 py-12 text-center text-theme-sm text-gray-500"
                >
                  {emptyMessage}
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr
                key={getRowId(row)}
                className={cn(
                  'border-b border-gray-100 transition-colors last:border-b-0',
                  onRowClick && 'hover:bg-gray-50 focus-within:bg-gray-50',
                )}
              >
                {columns.map((column, columnIndex) => (
                  <td
                    key={'key' in column ? column.key : column.id}
                    className={cn(
                      'px-5 align-middle text-theme-sm text-gray-700',
                      dense ? 'py-3' : 'py-4',
                      alignment[column.align ?? 'left'],
                      'hideBelowLg' in column && column.hideBelowLg && 'hidden lg:table-cell',
                    )}
                  >
                    {onRowClick && columnIndex === 0 ? (
                      <button
                        type="button"
                        onClick={() => onRowClick(row)}
                        className="block w-full rounded-sm text-left"
                      >
                        {renderColumnCell(column, row)}
                      </button>
                    ) : (
                      renderColumnCell(column, row)
                    )}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {loading && (
        <div className="absolute inset-0 z-9 flex items-start justify-center bg-white/70 pt-16">
          <Spinner />
        </div>
      )}

      {paginated && (
        <div className="flex flex-col items-center justify-between gap-3 border-t border-gray-200 px-5 py-3 sm:flex-row">
          {/* Announced on change so a keyboard user paging through hears where they now are. */}
          <div className="flex items-center gap-4 text-theme-xs text-gray-500" aria-live="polite">
            <span>
              {totalElements === 0
                ? 'No records'
                : `${firstRow.toLocaleString()}–${lastRow.toLocaleString()} of ${totalElements.toLocaleString()}`}
            </span>
            {onPageSizeChange && (
              <label className="flex items-center gap-1.5">
                <span>Rows</span>
                <select
                  value={pageSize}
                  onChange={(event) => onPageSizeChange(Number(event.target.value))}
                  className="h-8 rounded-md border border-gray-500 bg-white px-2 text-theme-xs text-gray-800"
                >
                  {pageSizeOptions.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>

          <nav className="flex items-center gap-1.5" aria-label="Pagination">
            <Button
              size="sm"
              variant="outline"
              aria-label="First page"
              disabled={page === 0}
              onClick={() => onPageChange?.(0)}
              className="w-9 px-0"
            >
              <Icon name="chevrons-left" size={15} />
            </Button>
            <Button
              size="sm"
              variant="outline"
              aria-label="Previous page"
              disabled={page === 0}
              onClick={() => onPageChange?.(page - 1)}
              className="w-9 px-0"
            >
              <Icon name="chevron-left" size={15} />
            </Button>
            <span className="px-2 text-theme-xs font-medium text-gray-700">
              Page {page + 1} of {totalPages}
            </span>
            <Button
              size="sm"
              variant="outline"
              aria-label="Next page"
              disabled={page + 1 >= totalPages}
              onClick={() => onPageChange?.(page + 1)}
              className="w-9 px-0"
            >
              <Icon name="chevron-right" size={15} />
            </Button>
            <Button
              size="sm"
              variant="outline"
              aria-label="Last page"
              disabled={page + 1 >= totalPages}
              onClick={() => onPageChange?.(totalPages - 1)}
              className="w-9 px-0"
            >
              <Icon name="chevrons-right" size={15} />
            </Button>
          </nav>
        </div>
      )}
    </div>
  );
}

export default DataTable;

/** Two-line cell: a strong primary value with quieter supporting detail underneath. */
export const CellStack = ({
  primary,
  secondary,
}: {
  primary: ReactNode;
  secondary?: ReactNode;
}) => (
  <div className="min-w-0">
    <div className="truncate font-semibold text-gray-900">{primary}</div>
    {secondary !== undefined && secondary !== null && (
      <div className="truncate text-theme-xs text-gray-500">{secondary}</div>
    )}
  </div>
);

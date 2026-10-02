import { Fragment, type ReactNode } from 'react';
import {
  Card,
  EmptyState,
  Table,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  TableSearch,
  type TableColumn,
} from '@rfdtech/components';

/** The page sizes every register offers, and the one it opens on. */
export const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];
export const DEFAULT_PAGE_SIZE = 25;

interface RegisterTableProps<T> {
  /** Namespaces the table's URL state; unique on the page. */
  paramPrefix: string;
  columns: TableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  loading?: boolean;
  onRowClick?: (row: T) => void;
  /** Shown when the register has nothing in it. */
  emptyTitle: string;
  emptyDescription?: ReactNode;
  emptyAction?: ReactNode;
  /** Filter fields, each a named `Dropdown` seeded from the table's URL state. */
  filters?: ReactNode;
  /** How many fields `filters` holds: up to two spread inline, more group into a popover. */
  filterCount?: number;
  searchPlaceholder?: string;
  /** Omit for a short list that is not paged. */
  totalItems?: number;
  size?: number;
  /** Wraps the table in its own card; turn off when a `Panel` already supplies the surface. */
  framed?: boolean;
}

/**
 * A soft library `Table` composed the way every register reads: search and filters over the rows,
 * pagination under them, and an empty state in place of the rows when there are none.
 *
 * State lives in the URL (`useTableState` with the same `paramPrefix`) and the page feeds the
 * service from it, so a reload or a shared link reopens the same page of the same filter.
 */
function RegisterTable<T>({
  paramPrefix,
  columns,
  rows,
  rowKey,
  loading,
  onRowClick,
  emptyTitle,
  emptyDescription,
  emptyAction,
  filters,
  filterCount = 0,
  searchPlaceholder,
  totalItems,
  size = DEFAULT_PAGE_SIZE,
  framed = true,
}: RegisterTableProps<T>) {
  const paged = totalItems !== undefined;
  const showHeader = Boolean(filters) || Boolean(searchPlaceholder);

  const Surface = framed ? Card : Fragment;

  return (
    <Table paramPrefix={paramPrefix} variant="soft">
      <Surface>
        {showHeader && (
          <TableHeader>
            {searchPlaceholder && <TableSearch placeholder={searchPlaceholder} />}
            {filters && (
              <TableFilter variant={filterCount > 2 ? undefined : 'spread'}>{filters}</TableFilter>
            )}
          </TableHeader>
        )}
        <TableContent
          variant="soft"
          columns={columns}
          data={rows}
          rowKey={rowKey}
          loading={loading}
          onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
          emptyContent={
            <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
          }
        />
      </Surface>
      {paged && (
        <TableFooter noBorder>
          <TablePagination
            totalPages={Math.max(1, Math.ceil(totalItems / size))}
            totalItems={totalItems}
            pageSizeOptions={PAGE_SIZE_OPTIONS}
          />
        </TableFooter>
      )}
    </Table>
  );
}

export default RegisterTable;

/** Two-line cell: a strong primary value with quieter supporting detail underneath. */
export const CellStack = ({ primary, secondary }: { primary: ReactNode; secondary?: ReactNode }) => (
  <div className="min-w-0">
    <div className="truncate font-semibold">{primary}</div>
    {secondary !== undefined && secondary !== null && (
      <div className="truncate text-theme-xs text-[var(--clet-text-secondary)]">{secondary}</div>
    )}
  </div>
);

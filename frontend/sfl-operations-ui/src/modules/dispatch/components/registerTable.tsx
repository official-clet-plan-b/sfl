import { ReactNode, useEffect, useState } from 'react';
import {
  Dropdown,
  EmptyState,
  Table,
  TableActions,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  TableSearch,
  useTableState,
  type DateRangeValue,
  type TableColumn,
} from '@rfdtech/components';
import dayjs from 'dayjs';

/**
 * The register table every dispatch list is built from: the library's table in its soft variant, with
 * its search, filters and pagination, over a server-paged collection.
 *
 * <h2>State lives in the URL, and the service is asked from it</h2>
 *
 * The library's table keeps page, page size, search and filters in the query string under a prefix.
 * That replaces the page state each register held for itself, and it is better for the operator: a
 * filtered view survives a reload and can be sent to a colleague. The registers read it back with
 * {@link useRegisterQuery} and hand the service the same values they always did. The library counts
 * pages from one and the service from zero, so the conversion is made once, in the hook.
 */

export const REGISTER_PAGE_SIZES = [10, 25, 50, 100];

/** The key a table filter writes to the URL, for a link that should land on a filtered register. */
export const filterLink = (path: string, prefix: string, name: string, value: string): string =>
  `${path}?${prefix}.f_${name}=${encodeURIComponent(value)}`;

export interface RegisterQuery {
  /** Zero-based, as the service counts. */
  page: number;
  size: number;
  /** The search box, trimmed. Empty when nothing is typed. */
  search: string;
  filters: Partial<Record<string, string | null | undefined>>;
  /** One-based, as the table counts. */
  tablePage: number;
  setTablePage: (page: number) => void;
}

/**
 * The register's page, size, search and filters, read from the table's URL state.
 *
 * `totalPages` is optional because a register learns it from the query this state drives; a page that
 * has it in hand passes it, and one that does not calls {@link useClampRegisterPage} afterwards.
 */
export const useRegisterQuery = (paramPrefix: string, totalPages?: number): RegisterQuery => {
  const table = useTableState({
    paramPrefix,
    defaultPageSize: REGISTER_PAGE_SIZES[0],
    pageSizeOptions: REGISTER_PAGE_SIZES,
  });

  const query: RegisterQuery = {
    page: Math.max(0, table.page - 1),
    size: table.pageSize,
    search: table.search.trim(),
    filters: table.filters,
    tablePage: table.page,
    setTablePage: table.setPage,
  };
  useClampRegisterPage(query, totalPages);
  return query;
};

/**
 * Clamps a page that has fallen off the end of a shrinking result set.
 *
 * Voiding the last record on the last page would otherwise strand the operator on a page the service
 * no longer has, which renders as an empty table rather than as the end of the register.
 */
export function useClampRegisterPage(query: RegisterQuery, totalPages: number | undefined): void {
  const { tablePage, setTablePage } = query;
  useEffect(() => {
    if (totalPages !== undefined && totalPages > 0 && tablePage > totalPages) {
      setTablePage(totalPages);
    }
  }, [tablePage, totalPages, setTablePage]);
}

/** A date range as the service's `from` and `to` instants, whole days inclusive. */
export const rangeToInstants = (
  range: DateRangeValue,
): { from: string | undefined; to: string | undefined } => ({
  from: range.start ? dayjs(range.start).startOf('day').toISOString() : undefined,
  to: range.end ? dayjs(range.end).endOf('day').toISOString() : undefined,
});

export const emptyRange: DateRangeValue = { start: null, end: null };

interface FilterOption {
  value: string;
  label: string;
}

interface FilterDropdownProps {
  /** The table's `paramPrefix`, so the field is seeded from the URL it writes to. */
  paramPrefix: string;
  /** The filter key: `name="status"` writes `?<prefix>.f_status=`. */
  name: string;
  label: string;
  options: FilterOption[];
}

/**
 * One named filter field, read as "Direction: All" and "Direction: Inbound".
 *
 * Seeded from the URL when it mounts, as the library requires, so a reload or a shared link restores
 * the selection; the popover mounts it afresh each time it opens, which keeps it honest if the filter
 * was cleared elsewhere.
 */
export const FilterDropdown = ({ paramPrefix, name, label, options }: FilterDropdownProps) => {
  const { filters } = useTableState({ paramPrefix });
  const [value, setValue] = useState(filters[name] ?? '');

  return (
    <Dropdown
      name={name}
      aria-label={label}
      value={value || null}
      onValueChange={(next) => setValue(next ?? '')}
      options={options}
      placeholder={`${label}: All`}
      clearable
      formatOption={(option, state) =>
        state === 'empty' ? `${label}: All` : state === 'selected' ? `${label}: ${option?.label}` : option?.label
      }
    />
  );
};

interface RegisterTableProps<T> {
  paramPrefix: string;
  columns: TableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  loading: boolean;
  totalPages: number;
  totalItems: number;
  /** Names the table for assistive technology, and says what is in it. */
  caption: string;
  onRowClick?: (row: T) => void;
  searchPlaceholder?: string;
  /** Filter fields, each a named control seeded from the URL. */
  filters?: ReactNode;
  /** Lays one or two filters out in the toolbar; three or more always group into the popover. */
  spreadFilters?: boolean;
  /** Controls that sit with the filters but are not table state - a date range, say. */
  actions?: ReactNode;
  emptyTitle: string;
  emptyDescription?: string;
  /** Hides the search box for a register the service cannot search. */
  searchable?: boolean;
}

export function RegisterTable<T>({
  paramPrefix,
  columns,
  rows,
  rowKey,
  loading,
  totalPages,
  totalItems,
  caption,
  onRowClick,
  searchPlaceholder = 'Search',
  filters,
  spreadFilters,
  actions,
  emptyTitle,
  emptyDescription,
  searchable = true,
}: RegisterTableProps<T>) {
  /*
   * A row click is for the pointer; the first cell also carries a real button, so a keyboard user
   * can open the record too. Its click stops short of the row's, or one press would navigate twice.
   */
  const reachable: TableColumn<T>[] = onRowClick
    ? columns.map((column, index) =>
        index === 0
          ? {
              ...column,
              cell: (info) => (
                <button
                  type="button"
                  className="text-left"
                  onClick={(event) => {
                    event.stopPropagation();
                    onRowClick(info.row);
                  }}
                >
                  {column.cell ? column.cell(info) : info.value}
                </button>
              ),
            }
          : column,
      )
    : columns;

  return (
    <Table paramPrefix={paramPrefix} variant="soft" aria-label={caption}>
      {(searchable || filters || actions) && (
        <TableHeader>
          {searchable && <TableSearch placeholder={searchPlaceholder} />}
          {filters && <TableFilter variant={spreadFilters ? 'spread' : 'popover'}>{filters}</TableFilter>}
          {actions && <TableActions>{actions}</TableActions>}
        </TableHeader>
      )}
      <TableContent
        variant="soft"
        columns={reachable}
        data={rows}
        rowKey={rowKey}
        loading={loading}
        onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
        emptyContent={<EmptyState title={emptyTitle} description={emptyDescription} />}
      />
      <TableFooter noBorder>
        <TablePagination
          totalPages={totalPages}
          totalItems={totalItems}
          pageSizeOptions={REGISTER_PAGE_SIZES}
        />
      </TableFooter>
    </Table>
  );
}

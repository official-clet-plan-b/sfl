import {
  EmptyState,
  Table,
  TableContent,
  TableFooter,
  TablePagination,
  useTableState,
  type TableColumn,
} from '@rfdtech/components';
import DataState from 'shared/components/DataState';
import type { FleetApiError } from 'shared/errors/FleetApiError';

const PAGE_SIZES = [10, 25, 50];

interface LocalTableProps<T> {
  /** Unique on the page: every table keeps its own page and size in the URL under this prefix. */
  paramPrefix: string;
  columns: TableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  loading: boolean;
  error?: FleetApiError;
  onRetry?: () => void;
  caption: string;
  onRowClick?: (row: T) => void;
  emptyTitle: string;
  emptyHint?: string;
}

/**
 * A short list the service has already narrowed, paged here over what it returned.
 *
 * These screens ask for one bounded window and filter it by state, so the table's own page state
 * slices that window; nothing is searched or filtered in the browser beyond what the caller passes.
 * The first cell carries a real button when a row opens something, so the row is reachable by
 * keyboard as well as by pointer.
 */
export function LocalTable<T>({
  paramPrefix,
  columns,
  rows,
  rowKey,
  loading,
  error,
  onRetry,
  caption,
  onRowClick,
  emptyTitle,
  emptyHint,
}: LocalTableProps<T>) {
  const { page, pageSize } = useTableState({
    paramPrefix,
    defaultPageSize: PAGE_SIZES[0],
    pageSizeOptions: PAGE_SIZES,
  });
  const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
  const visible = rows.slice((Math.min(page, totalPages) - 1) * pageSize, Math.min(page, totalPages) * pageSize);

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
    <DataState loading={false} error={error} onRetry={onRetry}>
      <Table paramPrefix={paramPrefix} variant="soft" aria-label={caption}>
        <TableContent
          variant="soft"
          columns={reachable}
          data={visible}
          rowKey={rowKey}
          loading={loading}
          onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
          emptyContent={<EmptyState title={emptyTitle} description={emptyHint} />}
        />
        {rows.length > PAGE_SIZES[0] && (
          <TableFooter noBorder>
            <TablePagination
              totalPages={totalPages}
              totalItems={rows.length}
              pageSizeOptions={PAGE_SIZES}
            />
          </TableFooter>
        )}
      </Table>
    </DataState>
  );
}

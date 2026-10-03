import { ReactNode, useMemo } from 'react';
import {
  Card,
  SectionDescription,
  SectionHeader,
  SectionActions,
  SectionTitle,
  Tabs,
  TabsList,
  TabsTrigger,
  Dropdown,
  EmptyState,
  Table,
  TableContent,
  TableFilter,
  TableFooter,
  TableHeader,
  TablePagination,
  TableSearch,
  useTableState,
  type TableColumn,
} from '@rfdtech/components';
import { defaultPageSize } from 'shared/api/config';
import Icon from 'shared/components/Icon';
import { tabLabel } from 'modules/fleet/components/StatusBadge';
import DataState from 'shared/components/DataState';
import { defaultSite } from 'shared/components/SiteSelect';
import { FleetApiError } from 'shared/errors/FleetApiError';

export interface FleetColumn<T> {
  key: string;
  header: string;
  /** Minimum width in pixels. The table scrolls horizontally rather than crushing a column. */
  width?: number;
  align?: 'left' | 'center' | 'right';
  cell: (row: T) => ReactNode;
}

const PAGE_SIZES = [10, 25, 50, 100];

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
      <div className="truncate text-theme-xs opacity-70">{secondary}</div>
    )}
  </div>
);

/**
 * The register's query state, held in the URL by the library's `useTableState`.
 *
 * The URL is the state, so a filtered, paged register survives a reload and can be linked. Pages are
 * one-based in the URL and zero-based on the wire; `apiPage` is the wire's.
 *
 * Site is the one filter with a default - the actor's own site - and "all sites" has to be
 * expressible against that default, so it is stored as an explicit `ALL` rather than as an absent
 * key (which means "use the default"). `site` below is already decoded to what the API takes.
 */
export const ALL_SITES = 'ALL';

export const useRegisterState = (paramPrefix: string) => {
  const state = useTableState({
    paramPrefix,
    defaultPageSize,
    pageSizeOptions: PAGE_SIZES,
  });
  const { filters } = state;
  const rawSite = filters.site ?? undefined;
  const site = rawSite === undefined ? defaultSite : rawSite === ALL_SITES ? '' : rawSite;
  return {
    ...state,
    apiPage: state.page - 1,
    site,
    rawSite: rawSite ?? defaultSite,
    setSite: (value: string) => state.setFilter('site', value || ALL_SITES),
    flag: (key: string) => filters[key] === 'true',
  };
};

/**
 * A filter field the table collects from its form: the child, plus a hidden input carrying the value
 * under the filter's name. For controls (a site picker, a date and time) that hold their value in
 * React and so would otherwise be invisible to `TableFilter`'s form snapshot.
 */
export const FilterField = ({
  name,
  value,
  children,
}: {
  name: string;
  value: string;
  children: ReactNode;
}) => (
  <div className="min-w-0">
    {children}
    <input type="hidden" name={name} value={value} />
  </div>
);

interface FilterDropdownProps {
  name: string;
  label: string;
  value: string | undefined;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
}

/** A single-value filter. The search endpoints take one value per axis, so this is a `Dropdown`. */
export const FilterDropdown = ({ name, label, value, options, onChange }: FilterDropdownProps) => (
  <Dropdown
    name={name}
    aria-label={label}
    placeholder={label}
    clearable
    value={value || null}
    onValueChange={(next) => onChange(next ?? '')}
    options={options}
  />
);

interface FleetTableProps<T> {
  /** URL namespace for this table's page, size, search and filters. Unique per table on a page. */
  paramPrefix: string;
  rows: T[];
  columns: FleetColumn<T>[];
  getRowId: (row: T) => string;
  loading?: boolean;
  error?: FleetApiError;
  onRetry?: () => void;
  onRowClick?: (row: T) => void;
  /** Server-side pagination. Omit `totalElements` for a table that is the whole of its data. */
  totalElements?: number;
  pageSize?: number;
  /** Placeholder for the table's search box; omit and the table has none. */
  searchPlaceholder?: string;
  /** The filter fields, inside a `TableFilter`. */
  filters?: ReactNode;
  /** Lays the filters out inline. The library groups them back into a popover past two fields. */
  spreadFilters?: boolean;
  /** The card's own title and supporting line, above the tabs. */
  heading?: { title: string; description?: string; actions?: ReactNode };
  /** One-click views of this register, with how many records each holds. */
  tabs?: { value: string; label: string; count?: number }[];
  tab?: string;
  onTabChange?: (value: string) => void;
  emptyTitle: string;
  emptyDescription?: string;
  emptyAction?: ReactNode;
  /** Names the table for assistive technology. */
  caption?: string;
}

/**
 * A fleet register on the library's `Table`: toolbar, soft content, footer pagination.
 *
 * Server-paginated by default because every fleet collection endpoint is paged, and a table that
 * silently shows the first page as if it were the whole register is the kind of thing an operator
 * only discovers when a vehicle is missing. The footer therefore always states the real total.
 *
 * A clickable row is also a real button inside the first cell. The library's row handler is a click
 * on the `<tr>`, which a keyboard cannot reach, so the first cell carries the activation for
 * keyboard and assistive technology while the row handler keeps the whole row a pointer target.
 */
function FleetTable<T>({
  paramPrefix,
  rows,
  columns,
  getRowId,
  loading,
  error,
  onRetry,
  onRowClick,
  totalElements,
  pageSize = defaultPageSize,
  searchPlaceholder,
  filters,
  spreadFilters,
  heading,
  tabs,
  tab,
  onTabChange,
  emptyTitle,
  emptyDescription,
  emptyAction,
  caption,
}: FleetTableProps<T>) {
  const tableColumns = useMemo<TableColumn<T>[]>(() => {
    const mapped: TableColumn<T>[] = columns.map((column, index) => ({
      id: column.key,
      header: column.header,
      minWidth: column.width,
      align: column.align,
      cell: ({ row }) =>
        onRowClick && index === 0 ? (
          <button
            type="button"
            className="block w-full text-left"
            onClick={(event) => {
              event.stopPropagation();
              onRowClick(row);
            }}
          >
            {column.cell(row)}
          </button>
        ) : (
          column.cell(row)
        ),
    }));
    // The design ends every clickable row in a chevron, which says the row opens a record.
    return onRowClick
      ? [
          ...mapped,
          {
            id: 'open',
            header: '',
            width: 40,
            align: 'right',
            cell: () => <Icon name="chevron-right" size={16} aria-hidden="true" />,
          },
        ]
      : mapped;
  }, [columns, onRowClick]);

  const hasToolbar = Boolean(searchPlaceholder || filters);
  const totalPages = Math.max(1, Math.ceil((totalElements ?? rows.length) / pageSize));

  return (
    <DataState loading={false} error={error} onRetry={onRetry} minHeight={200}>
      <Table paramPrefix={paramPrefix} variant="soft" aria-label={caption}>
        <Card bordered>
          {heading && (
            <SectionHeader className="[--clet-section-header-margin-bottom:16px] [--clet-section-header-title-size:20px]">
              <SectionTitle>{heading.title}</SectionTitle>
              {heading.description && (
                <SectionDescription>{heading.description}</SectionDescription>
              )}
              {heading.actions && <SectionActions className="items-end [&_button]:whitespace-nowrap">{heading.actions}</SectionActions>}
            </SectionHeader>
          )}
          {tabs && tab !== undefined && (
            <Tabs variant="pill" value={tab} onValueChange={onTabChange} className="mb-4">
              <TabsList>
                {tabs.map((entry) => (
                  <TabsTrigger key={entry.value} value={entry.value}>
                    {tabLabel(entry.label, entry.count)}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          )}
          {hasToolbar && (
            <TableHeader>
              {searchPlaceholder && <TableSearch placeholder={searchPlaceholder} />}
              {filters && (
                <TableFilter variant={spreadFilters ? 'spread' : 'popover'}>{filters}</TableFilter>
              )}
            </TableHeader>
          )}
          <TableContent
            variant="soft"
            columns={tableColumns}
            data={rows}
            rowKey={getRowId}
            loading={loading}
            onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
            emptyContent={
              <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
            }
          />
          {totalElements !== undefined && (
            <TableFooter noBorder>
              <TablePagination
                totalPages={totalPages}
                totalItems={totalElements}
                pageSizeOptions={PAGE_SIZES}
                defaultPageSize={pageSize}
              />
            </TableFooter>
          )}
        </Card>
      </Table>
    </DataState>
  );
}

export default FleetTable;

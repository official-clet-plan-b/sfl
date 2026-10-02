import type { ReactNode } from 'react';
import RegisterTable from 'modules/emergency/components/RegisterTable';

export interface FacilitiesColumn<T> {
  key: string;
  header: ReactNode;
  width?: number;
  align?: 'left' | 'center' | 'right';
  cell: (row: T) => ReactNode;
  hideBelowLg?: boolean;
}

interface Props<T> {
  rows: T[];
  columns: FacilitiesColumn<T>[];
  getRowId: (row: T) => string;
  loading?: boolean;
  onRowClick?: (row: T) => void;
  emptyMessage?: string;
  dense?: boolean;
}

const FacilitiesDataTable = <T,>({ rows, columns, getRowId, loading, onRowClick, emptyMessage }: Props<T>) => (
  <RegisterTable
    paramPrefix="facilities-detail"
    columns={columns.map((column) => ({
      id: column.key,
      header: String(column.header),
      width: column.width,
      align: column.align,
      cell: ({ row }: { row: T }) => column.cell(row),
    }))}
    rows={rows}
    rowKey={getRowId}
    loading={loading}
    onRowClick={onRowClick}
    emptyTitle={emptyMessage ?? 'Nothing to show.'}
    framed={false}
  />
);

export default FacilitiesDataTable;

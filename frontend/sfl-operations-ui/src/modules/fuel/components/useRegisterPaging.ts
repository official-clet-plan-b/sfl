import { useEffect, useRef } from 'react';
import { useTableState } from '@rfdtech/components';
import { DEFAULT_PAGE_SIZE } from 'modules/fuel/api/fuelApi';

export { useClampPage } from 'shared/hooks/useServerPage';

/**
 * Page state for a server-paged register, held where the library's pagination keeps it: the URL.
 *
 * `TablePagination` reads and writes `<prefix>.page` (one-based) and `<prefix>.pageSize` itself, so
 * the register reads the same keys instead of mirroring them in component state - a reload or a
 * shared link then lands on the page the operator was on. Callers still see a zero-based page,
 * which is what the service's paged envelope speaks.
 *
 * A filter change resets to the first page, for the reason it always did: page four of the previous
 * result set is meaningless against the new one, and an empty table on a filter that did match
 * records reads as "nothing found".
 */
export interface RegisterPaging {
  page: number;
  size: number;
  setPage: (page: number) => void;
  /** The register's search box, which `TableSearch` writes to `<prefix>.search`. */
  search: string;
}

export function useRegisterPaging(
  paramPrefix: string,
  /** Any value other than the search box that changes the query. Paging resets when it changes. */
  filterKey: string,
  initialSize: number = DEFAULT_PAGE_SIZE,
): RegisterPaging {
  const state = useTableState({ paramPrefix, defaultPageSize: initialSize });
  const { setPage: setUrlPage } = state;

  const queryKey = `${filterKey}|${state.search}`;
  const lastQueryKey = useRef(queryKey);
  useEffect(() => {
    if (lastQueryKey.current === queryKey) {
      return;
    }
    lastQueryKey.current = queryKey;
    setUrlPage(1);
  }, [queryKey, setUrlPage]);

  return {
    page: Math.max(0, state.page - 1),
    size: state.pageSize,
    setPage: (page: number) => setUrlPage(page + 1),
    search: state.search.trim(),
  };
}

/** Page sizes the registers offer. */
export const PAGE_SIZE_OPTIONS = [10, 25, 50, 100];


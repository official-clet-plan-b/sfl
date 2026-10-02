import { apiClient } from 'shared/api/client';

export type IfimpRecord = Record<string, unknown>;

export interface IfimpDataset {
  rows: IfimpRecord[];
  summary?: IfimpRecord;
}

const asRecord = (value: unknown): IfimpRecord | undefined =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as IfimpRecord)
    : undefined;

/**
 * Phase 2 endpoints do not all use the same collection shape: registers return arrays, dashboards
 * return objects and a few Spring endpoints return a page. Normalise that at the boundary so the
 * operations screen never guesses while it renders.
 */
const normalise = (value: unknown): IfimpDataset => {
  if (Array.isArray(value)) {
    return { rows: value.filter((item): item is IfimpRecord => asRecord(item) !== undefined) };
  }
  const record = asRecord(value);
  if (!record) {
    return { rows: [] };
  }
  for (const key of ['content', 'items', 'records', 'results']) {
    const candidate = record[key];
    if (Array.isArray(candidate)) {
      return {
        rows: candidate.filter((item): item is IfimpRecord => asRecord(item) !== undefined),
        summary: record,
      };
    }
  }
  return { rows: [record], summary: record };
};

export const readIfimpDataset = async (
  path: string,
  siteCode: string,
  query?: Record<string, string>,
  signal?: AbortSignal,
): Promise<IfimpDataset> =>
  normalise(
    await apiClient.get<unknown>(
      path,
      { ...(siteCode ? { siteCode } : {}), ...query },
      signal,
      'facilities',
    ),
  );

export type IfimpWriteMethod = 'POST' | 'PATCH' | 'PUT' | 'DELETE';

export const writeIfimpRecord = (path: string, body: unknown, method: IfimpWriteMethod = 'POST'): Promise<IfimpRecord> => {
  if (method === 'PATCH') return apiClient.patch<IfimpRecord>(path, body, { service: 'facilities' });
  if (method === 'PUT') return apiClient.put<IfimpRecord>(path, body, { service: 'facilities' });
  if (method === 'DELETE') return apiClient.delete<IfimpRecord>(path, { service: 'facilities' });
  return apiClient.post<IfimpRecord>(path, body, { service: 'facilities' });
};

export const readIfimpRecord = (path: string, signal?: AbortSignal): Promise<IfimpRecord> =>
  apiClient.get<IfimpRecord>(path, undefined, signal, 'facilities');

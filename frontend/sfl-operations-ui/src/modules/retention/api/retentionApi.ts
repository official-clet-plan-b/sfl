import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export interface RetentionPolicy { systemCode: string; recordClass: string; retentionDays: number; action: 'REVIEW' | 'ANONYMISE'; basis: string; updatedBy: string; updatedAt: string }
export interface RetentionDue { systemCode: string; evidenceId: string; siteCode: string; reference: string; fileName: string; recordClass: string; submittedAt: string; dueSince: string }

const base = '/api/v1/facilities/retention';

export const retentionApi = {
  policies: (signal?: AbortSignal) => apiClient.get<RetentionPolicy[]>(`${base}/policies`, undefined, signal, 'facilities'),
  due: (system: string | undefined, signal?: AbortSignal) => apiClient.get<RetentionDue[]>(`${base}/due`, (system ? { system } : undefined) as QueryParams | undefined, signal, 'facilities'),
  set: (p: RetentionPolicy, retentionDays: number, basis: string) => apiClient.put<RetentionPolicy>(`${base}/policies/${p.systemCode}/${p.recordClass}`, { retentionDays, basis }, { service: 'facilities' }),
};

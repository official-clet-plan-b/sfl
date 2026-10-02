import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';
import type {
  AssessmentDetail,
  AssessmentPage,
  AssessmentSearchParams,
  AssessmentTemplate,
  CoverageReport,
  CreateAssessmentRequest,
  DraftRequest,
  HazardFrequency,
  LinkCheck,
  ReviewFlag,
  ReviewFlagPage,
  ReviewInterval,
  RiskDashboard,
  SignOffResult,
  TemplateRequest,
  VersionView,
} from './dto';
import type { ReviewFlagStatus, RiskLevel } from './enums';

/**
 * The typed client for S165, served by `sfl-safety-security-service`.
 *
 * Creating an assessment is the one state-creating POST, so it is the one that sends an
 * `Idempotency-Key` - which this service really honours: a retry returns the original. Every other
 * change carries the record's `expectedVersion` instead, and publish, sign-off, defer and complete are
 * POSTs that change an existing record, so they send no key.
 */

const service = 'safetySecurity' as const;
const BASE = '/api/v1/risk-assessments';
const asQuery = (params: object): QueryParams => params as QueryParams;

export const riskAssessmentApi = {
  search: (params: AssessmentSearchParams, signal?: AbortSignal) =>
    apiClient.get<AssessmentPage>(BASE, asQuery(params), signal, service),
  get: (id: string, signal?: AbortSignal) => apiClient.get<AssessmentDetail>(`${BASE}/${id}`, undefined, signal, service),
  version: (id: string, versionNumber: number, signal?: AbortSignal) =>
    apiClient.get<VersionView>(`${BASE}/${id}/versions/${versionNumber}`, undefined, signal, service),
  create: (body: CreateAssessmentRequest) => apiClient.post<AssessmentDetail>(BASE, body, { service }),
  editDraft: (id: string, body: DraftRequest) => apiClient.put<AssessmentDetail>(`${BASE}/${id}/draft`, body, { service }),
  openRevision: (id: string) => apiClient.post<AssessmentDetail>(`${BASE}/${id}/revisions`, undefined, { service, idempotent: false }),
  publish: (id: string, expectedVersion: number) =>
    apiClient.post<AssessmentDetail>(`${BASE}/${id}/publish`, { expectedVersion }, { service, idempotent: false }),
  signOff: (id: string, notes: string | undefined, expectedVersion: number) =>
    apiClient.post<SignOffResult>(`${BASE}/${id}/sign-off`, { notes, expectedVersion }, { service, idempotent: false }),
  linkCheck: (id: string, siteCode: string, signal?: AbortSignal) =>
    apiClient.get<LinkCheck>(`${BASE}/${id}/link-check`, { siteCode }, signal, service),

  dashboard: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<RiskDashboard>(`${BASE}/dashboard`, { siteCode }, signal, service),
  hazards: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<HazardFrequency[]>(`${BASE}/analytics/hazards`, { siteCode }, signal, service),
  coverage: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<CoverageReport>(`${BASE}/analytics/coverage`, { siteCode }, signal, service),
  activityTypes: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<string[]>(`${BASE}/activity-types`, { siteCode }, signal, service),

  reviewFlags: (params: { siteCode: string; status?: ReviewFlagStatus; page: number; size: number }, signal?: AbortSignal) =>
    apiClient.get<ReviewFlagPage>(`${BASE}/review-flags`, asQuery(params), signal, service),
  deferFlag: (flagId: string, body: { reason: string; until: string; expectedVersion: number }) =>
    apiClient.post<ReviewFlag>(`${BASE}/review-flags/${flagId}/defer`, body, { service, idempotent: false }),
  completeFlag: (flagId: string, body: { findings: string; expectedVersion: number }) =>
    apiClient.post<ReviewFlag>(`${BASE}/review-flags/${flagId}/complete`, body, { service, idempotent: false }),

  templates: (activeOnly: boolean, signal?: AbortSignal) =>
    apiClient.get<AssessmentTemplate[]>(`${BASE}/templates`, { activeOnly }, signal, service),
  createTemplate: (body: TemplateRequest) => apiClient.post<AssessmentTemplate>(`${BASE}/templates`, body, { service }),
  updateTemplate: (id: string, body: TemplateRequest) =>
    apiClient.put<AssessmentTemplate>(`${BASE}/templates/${id}`, body, { service }),
  reviewIntervals: (signal?: AbortSignal) =>
    apiClient.get<ReviewInterval[]>(`${BASE}/configuration/review-intervals`, undefined, signal, service),
  updateReviewIntervals: (intervals: { riskLevel: RiskLevel; intervalDays: number; reminderLeadDays: number }[]) =>
    apiClient.put<ReviewInterval[]>(`${BASE}/configuration/review-intervals`, { intervals }, { service }),
};

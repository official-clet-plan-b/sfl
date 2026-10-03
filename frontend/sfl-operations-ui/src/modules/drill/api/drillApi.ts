import { apiClient } from 'shared/api/client';
import type {
  CapaCounts,
  CheckIn,
  ComplianceRow,
  DrillCorrectiveAction,
  DrillDashboard,
  DrillDetail,
  DrillFinding,
  DrillPage,
  DrillSearchParams,
  DrillTemplate,
  Judgement,
  PlanRequest,
  RollCallGap,
  RollCallView,
} from './dto';
import type { DrillType, GapFollowUp } from './enums';

/**
 * The typed client for S175, served by `sfl-safety-security-service`.
 *
 * Planning a drill is the one state-creating POST and the one that sends an `Idempotency-Key`, which the
 * service honours. Every other change is a POST or PUT on an existing drill, guarded by `expectedVersion`
 * and the drill's lifecycle, so it sends no key.
 */

const service = 'safetySecurity' as const;
const BASE = '/api/v1/drills';
const change = { service, idempotent: false } as const;

export const drillApi = {
  search: (params: DrillSearchParams, signal?: AbortSignal) => apiClient.get<DrillPage>(BASE, params, signal, service),
  get: (id: string, signal?: AbortSignal) => apiClient.get<DrillDetail>(`${BASE}/${id}`, undefined, signal, service),
  templates: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<DrillTemplate[]>(`${BASE}/notification-templates`, { siteCode }, signal, service),

  create: (siteCode: string, plan: PlanRequest) => apiClient.post<DrillDetail>(BASE, { siteCode, plan }, { service }),
  revise: (id: string, plan: PlanRequest, expectedVersion: number) =>
    apiClient.put<DrillDetail>(`${BASE}/${id}/plan`, { plan, expectedVersion }, { service }),
  schedule: (id: string, expectedVersion: number, scheduledFor?: string) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/schedule`, { scheduledFor, expectedVersion }, change),
  postpone: (id: string, reason: string, expectedVersion: number) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/postpone`, { reason, expectedVersion }, change),
  cancel: (id: string, reason: string, expectedVersion: number) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/cancel`, { reason, expectedVersion }, change),

  start: (id: string, expectedVersion: number) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/start`, { expectedVersion }, change),
  checkIn: (id: string, personRef: string) => apiClient.post<CheckIn>(`${BASE}/${id}/check-ins`, { personRef }, change),
  rollCall: (id: string, signal?: AbortSignal) =>
    apiClient.get<RollCallView>(`${BASE}/${id}/roll-call`, undefined, signal, service),
  closeRollCall: (id: string, expectedVersion: number) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/roll-call/close`, { expectedVersion }, change),
  followUpGap: (id: string, gapId: string, followUp: GapFollowUp, notes?: string) =>
    apiClient.post<RollCallGap>(`${BASE}/${id}/gaps/${gapId}/follow-up`, { followUp, notes }, change),

  review: (id: string, summary: string, timingNotes?: string) =>
    apiClient.put<DrillDetail>(`${BASE}/${id}/review`, { summary, timingNotes }, { service }),
  addFinding: (id: string, description: string) =>
    apiClient.post<DrillFinding>(`${BASE}/${id}/findings`, { description }, change),
  noAction: (id: string, findingId: string, justification: string) =>
    apiClient.post<DrillFinding>(`${BASE}/${id}/findings/${findingId}/no-action`, { justification }, change),
  judge: (id: string, judgements: Judgement[], expectedVersion: number) =>
    apiClient.put<DrillDetail>(`${BASE}/${id}/expectations`, { judgements, expectedVersion }, { service }),
  submitReview: (id: string, expectedVersion: number) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/review/submit`, { expectedVersion }, change),
  close: (id: string, expectedVersion: number, deferralReason?: string) =>
    apiClient.post<DrillDetail>(`${BASE}/${id}/close`, { deferralReason, expectedVersion }, change),

  openAction: (id: string, body: { findingId: string; description: string; ownerId: string; dueDate: string }) =>
    apiClient.post<DrillCorrectiveAction>(`${BASE}/${id}/corrective-actions`, body, change),
  startAction: (id: string, actionId: string) =>
    apiClient.post<DrillCorrectiveAction>(`${BASE}/${id}/corrective-actions/${actionId}/start`, undefined, change),
  verifyAction: (id: string, actionId: string, notes: string) =>
    apiClient.post<DrillCorrectiveAction>(`${BASE}/${id}/corrective-actions/${actionId}/verify`, { notes }, change),
  cancelAction: (id: string, actionId: string, reason: string) =>
    apiClient.post<DrillCorrectiveAction>(`${BASE}/${id}/corrective-actions/${actionId}/cancel`, { reason }, change),

  dashboard: (siteCode: string | undefined, days: number, signal?: AbortSignal) =>
    apiClient.get<DrillDashboard>(`${BASE}/dashboard`, { siteCode, days }, signal, service),
  compliance: (siteCode: string | undefined, signal?: AbortSignal) =>
    apiClient.get<ComplianceRow[]>(`${BASE}/compliance`, { siteCode }, signal, service),
  setRequirement: (body: { siteCode: string; drillType: DrillType; intervalDays: number; warningDays: number; effectiveFrom?: string }) =>
    apiClient.put<ComplianceRow>(`${BASE}/compliance/requirements`, body, { service }),
  correctiveActionSummary: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<CapaCounts>(`${BASE}/corrective-actions/summary`, { siteCode }, signal, service),
};

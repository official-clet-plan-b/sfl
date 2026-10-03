import { apiClient, downloadFile } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

/**
 * The typed client for S164, served by `sfl-safety-security-service`. Every change is a POST on an existing permit, guarded by the
 * permit's version where one is sent and by its lifecycle always.
 */

export type PermitStatus = 'DRAFT' | 'SUBMITTED' | 'ISOLATION_VERIFIED' | 'STAGE1_APPROVED' | 'ACTIVE' | 'SUSPENDED' | 'RESUMPTION_PENDING' | 'WORK_COMPLETE' | 'CLOSED' | 'REJECTED' | 'CANCELLED';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type WorkRole = 'SUPERVISOR' | 'OPERATIVE' | 'FIRE_WATCH' | 'STANDBY';
export type IsolationKind = 'ELECTRICAL' | 'MECHANICAL' | 'GAS_FUEL' | 'PRESSURE' | 'ZONE_ACCESS' | 'OTHER';
export type IsolationStatus = 'REQUIRED' | 'VERIFIED' | 'REMOVED';
export type OriginSystem = 'S153' | 'S176' | 'NONE';
export type EvidenceKind = 'PHOTO' | 'CHECKLIST' | 'OTHER';
export type ApprovalStage = 'ISSUING_AUTHORITY' | 'SAFETY_SIGN_OFF';

export interface PermitType {
  id: string; code: string; name: string; description: string | null; riskLevel: RiskLevel; activityType: string | null; riskAssessmentRequired: boolean;
  twoStage: boolean; requiresIsolation: boolean; maxValidityHours: number; requiredCompetencies: string[]; active: boolean; version: number;
}
export interface Permit {
  id: string; siteCode: string; reference: string; permitTypeId: string; workType: string; title: string; workDescription: string; locationCode: string; zoneId: string | null;
  zoneCode: string | null; startsAt: string; endsAt: string; status: PermitStatus; statusReason: string | null; riskAssessmentId: string | null; riskAssessmentReference: string | null;
  riskAssessmentVersion: number | null; riskLevel: RiskLevel | null; riskReviewDueAt: string | null; contractorReference: string | null; supervisorReference: string;
  supervisorContact: string | null; originSystem: OriginSystem; originReference: string | null; approvalRound: number; requestedBy: string; submittedAt: string | null;
  issuedAt: string | null; completionStatement: string | null; workCompletedAt: string | null; workCompletedBy: string | null; closedAt: string | null; closedBy: string | null; version: number;
}
export interface Worker { id: string; personReference: string; displayName: string; workRole: WorkRole }
export interface CompetencyCheck { id: string; workerId: string; competencyCode: string; competent: boolean; evidenceReference: string | null; validUntil: string | null; checkedBy: string; checkedAt: string }
export interface CompetencyException { workerId: string; workerName: string; competency: string; reason: 'MISSING' | 'NOT_COMPETENT' | 'EXPIRED' }
export interface Isolation { id: string; kind: IsolationKind; description: string; tagReference: string | null; status: IsolationStatus; verifiedBy: string | null; verifiedAt: string | null; removedBy: string | null; removedAt: string | null }
export interface Approval { id: string; purpose: 'ISSUE' | 'RESUME' | 'EXTEND'; approvalRound: number; stage: ApprovalStage; decision: 'APPROVED' | 'REJECTED'; decidedBy: string; decidedAt: string; conditions: string | null; comment: string | null }
export interface Extension { id: string; requestedBy: string; requestedAt: string; previousEndsAt: string; newEndsAt: string; reason: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; decidedBy: string | null; decisionNote: string | null }
export interface Suspension { id: string; suspendedBy: string; suspendedAt: string; reason: string; resumedAt: string | null }
export interface Notification { id: string; recipientReference: string; recipientName: string | null; recipientRole: string; state: 'QUEUED' | 'DELIVERED' | 'FAILED' }
export interface Evidence { id: string; kind: EvidenceKind; reference: string; fileName: string; submittedBy: string; submittedAt: string }
export interface Flag { id: string; flagType: 'INCIDENT' | 'EMERGENCY_ZONE'; reference: string; detail: string | null; status: 'OPEN' | 'REVIEWED'; raisedAt: string; reviewedBy: string | null; reviewNote: string | null; permitId?: string }
export interface Escalation { id: string; level: 'NEARING_EXPIRY' | 'OVERDUE'; raisedAt: string }
export interface HistoryEntry { id: string; fromStatus: string | null; toStatus: string; action: string; actor: string; reason: string | null; occurredAt: string }
export interface RiskStanding { assessmentId: string | null; found: boolean; reference: string | null; version: number | null; activityType: string | null; riskLevel: RiskLevel | null; reviewDueAt: string | null; current: boolean; reason: string | null }
export interface PermitDetail {
  permit: Permit; type: PermitType; workers: Worker[]; competencyChecks: CompetencyCheck[]; competencyExceptions: CompetencyException[]; isolations: Isolation[]; approvals: Approval[];
  nextStage: ApprovalStage | null; extensions: Extension[]; suspensions: Suspension[]; notifications: Notification[]; evidence: Evidence[]; flags: Flag[]; escalations: Escalation[];
  history: HistoryEntry[]; blockers: string[]; riskAssessment: RiskStanding; overdue: boolean; unverified: string[];
}
export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface TypeRiskCell { typeCode: string; typeName: string; riskLevel: RiskLevel; open: number; active: number; suspended: number }
export interface ZoneIsolation { zone: string; permits: number; required: number; verified: number; removed: number }
export interface CompetencyExceptionRow { permitReference: string; permitId: string; contractor: string | null; worker: string; competency: string; reason: string }
export interface Dashboard {
  siteCode: string | null; asOf: string; open: number; awaitingVerification: number; awaitingApproval: number; openByTypeAndRisk: TypeRiskCell[]; nearingExpiry: Permit[];
  overdueCloseOuts: Permit[]; isolationByZone: ZoneIsolation[]; competencyExceptions: CompetencyExceptionRow[]; openFlags: Array<Flag & { permitId: string }>; warnMinutes: number;
}
export interface CountRow { key: string; count: number }
export interface Analytics { from: string | null; to: string | null; permits: number; byType: CountRow[]; byContractor: CountRow[]; byOutcome: CountRow[]; meanOpenHours: number | null; flaggedForIncident: number; incidentCorrelationPercent: number }

export interface WorkerInput { personReference: string; displayName: string; workRole: WorkRole }
export interface IsolationInput { kind: IsolationKind; description: string; tagReference?: string }
export interface PermitInput {
  permitTypeId: string; title: string; workDescription: string; locationCode: string; zoneId?: string; startsAt: string; endsAt: string; riskAssessmentId?: string;
  contractorReference?: string; supervisorReference: string; supervisorContact?: string; originSystem?: OriginSystem; originReference?: string;
}
export interface TypeInput {
  code: string; name: string; description?: string; riskLevel: RiskLevel; activityType?: string; riskAssessmentRequired: boolean; twoStage: boolean; requiresIsolation: boolean;
  maxValidityHours: number; requiredCompetencies: string[]; active: boolean; version?: number;
}
export interface SearchParams { siteCode?: string; permitTypeId?: string; status?: PermitStatus; contractor?: string; openOnly?: boolean; overdueOnly?: boolean; q?: string; page?: number; size?: number; sort?: string }

const service = 'safetySecurity' as const;
const BASE = '/api/v1/permits';
const post = <T,>(path: string, body?: unknown) => apiClient.post<T>(`${BASE}${path}`, body ?? {}, { service, idempotent: false });

export const permitApi = {
  search: (params: SearchParams, signal?: AbortSignal) => apiClient.get<Page<Permit>>(BASE, params as QueryParams, signal, service),
  get: (id: string, signal?: AbortSignal) => apiClient.get<PermitDetail>(`${BASE}/${id}`, undefined, signal, service),
  dashboard: (siteCode: string | undefined, signal?: AbortSignal) => apiClient.get<Dashboard>(`${BASE}/dashboard`, siteCode ? { siteCode } : undefined, signal, service),
  analytics: (siteCode: string | undefined, signal?: AbortSignal) => apiClient.get<Analytics>(`${BASE}/analytics`, siteCode ? { siteCode } : undefined, signal, service),
  types: (activeOnly: boolean, signal?: AbortSignal) => apiClient.get<PermitType[]>('/api/v1/permit-types', { activeOnly }, signal, service),
  createType: (body: TypeInput) => apiClient.post<PermitType>('/api/v1/permit-types', body, { service, idempotent: false }),
  updateType: (id: string, body: TypeInput) => apiClient.put<PermitType>(`/api/v1/permit-types/${id}`, body, { service, idempotent: false }),

  create: (siteCode: string, permit: PermitInput, workers: WorkerInput[], isolations: IsolationInput[]) => post<PermitDetail>('', { siteCode, permit, workers, isolations }),
  addWorker: (id: string, body: WorkerInput) => post<PermitDetail>(`/${id}/workers`, body),
  addIsolation: (id: string, body: IsolationInput) => post<PermitDetail>(`/${id}/isolations`, body),
  submit: (p: Permit) => post<PermitDetail>(`/${p.id}/submit`, { version: p.version }),
  cancel: (p: Permit, reason: string) => post<PermitDetail>(`/${p.id}/cancel`, { reason, version: p.version }),
  verifyIsolation: (id: string, isolationId: string, note?: string) => post<PermitDetail>(`/${id}/isolations/${isolationId}/verify`, { note }),
  completeVerification: (p: Permit, note?: string) => post<PermitDetail>(`/${p.id}/verification/complete`, { note, version: p.version }),
  competency: (id: string, workerId: string, body: { competencyCode: string; competent: boolean; evidenceReference?: string; validUntil?: string; note?: string }) =>
    post<PermitDetail>(`/${id}/workers/${workerId}/competency`, body),
  approve: (p: Permit, conditions?: string, comment?: string) => post<PermitDetail>(`/${p.id}/approve`, { conditions, comment, version: p.version }),
  reject: (p: Permit, reason: string) => post<PermitDetail>(`/${p.id}/reject`, { reason, version: p.version }),
  suspend: (p: Permit, reason: string) => post<PermitDetail>(`/${p.id}/suspend`, { reason, version: p.version }),
  requestResumption: (p: Permit) => post<PermitDetail>(`/${p.id}/resume-request`, { version: p.version }),
  requestExtension: (p: Permit, newEndsAt: string, reason: string) => post<PermitDetail>(`/${p.id}/extensions`, { newEndsAt, reason, version: p.version }),
  decideExtension: (p: Permit, extensionId: string, approve: boolean, note?: string) => post<PermitDetail>(`/${p.id}/extensions/${extensionId}/decide`, { approve, note, version: p.version }),
  evidence: (id: string, body: { kind: EvidenceKind; reference: string; fileName: string; mediaType: string; sizeBytes: number; contentHash: string }) => post<PermitDetail>(`/${id}/evidence`, body),
  complete: (p: Permit, statement: string) => post<PermitDetail>(`/${p.id}/complete`, { statement, version: p.version }),
  removeIsolation: (id: string, isolationId: string, note?: string) => post<PermitDetail>(`/${id}/isolations/${isolationId}/remove`, { note }),
  close: (p: Permit) => post<PermitDetail>(`/${p.id}/close`, { version: p.version }),
  reviewFlag: (id: string, flagId: string, reason: string) => post<PermitDetail>(`/${id}/flags/${flagId}/review`, { reason }),
  linkIncident: (id: string, incidentReference: string, detail?: string) => post<PermitDetail>(`/${id}/incident-links`, { incidentReference, detail }),
  exportRegister: (siteCode: string, reason: string) => downloadFile(`${BASE}/export`, { siteCode, reason }, 'permits.csv', 'text/csv, application/json', service),
};

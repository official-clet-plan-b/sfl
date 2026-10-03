import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export type ControlType = 'AUDIT' | 'PEST_VISIT' | 'STATUTORY_CHECK';
export type RiskCategory = 'FOOD_SAFETY' | 'PEST' | 'WASTE' | 'SANITATION' | 'WATER' | 'GENERAL';
export type Frequency = 'ONE_OFF' | 'WEEKLY' | 'FORTNIGHTLY' | 'MONTHLY' | 'QUARTERLY' | 'BIANNUAL' | 'ANNUAL';
export type ControlStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'MISSED' | 'CANCELLED';
/** What a reader sees: the stored status, or DUE / OVERDUE worked out for today. */
export type EffectiveControlStatus = ControlStatus | 'DUE' | 'OVERDUE';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type FindingStatus = 'OPEN' | 'IN_PROGRESS' | 'AWAITING_VERIFICATION' | 'CLOSED';
export type ActionStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'VERIFIED' | 'REJECTED';
export type EvidenceStatus = 'SUBMITTED' | 'ACCEPTED' | 'REJECTED';
export type LinkState = 'NOT_REQUIRED' | 'PENDING_MANUAL' | 'LINKED' | 'RAISED';
export type EscalationLevel = 'NONE' | 'OWNER' | 'HSE' | 'LEADERSHIP';
export type ClosureMode = 'EVIDENCE' | 'EXCEPTION';

export interface HygieneControl {
  id: string;
  reference: string;
  siteCode: string;
  roomId: string | null;
  locationLabel: string | null;
  controlType: ControlType;
  riskCategory: RiskCategory;
  title: string;
  ownerReference: string;
  frequency: Frequency;
  dueOn: string;
  status: ControlStatus;
  completedOn: string | null;
  providerReference: string | null;
  providerConfirmed: boolean;
  previousControlId: string | null;
  notes: string | null;
  version: number;
}

export interface ControlRow {
  control: HygieneControl;
  effectiveStatus: EffectiveControlStatus;
}

export interface HistoryEntry {
  id: string;
  subjectType: string;
  fromStatus: string | null;
  toStatus: string;
  actor: string;
  reason: string | null;
  occurredAt: string;
}

export interface HygieneFinding {
  id: string;
  reference: string;
  controlId: string;
  siteCode: string;
  category: RiskCategory;
  title: string;
  description: string | null;
  severity: Severity;
  status: FindingStatus;
  ownerReference: string | null;
  targetDate: string | null;
  requiresIncident: boolean;
  incidentState: LinkState;
  incidentReference: string | null;
  workOrderState: LinkState;
  workOrderNumber: string | null;
  repeatOfId: string | null;
  escalationLevel: EscalationLevel;
  closureMode: ClosureMode | null;
  closureReason: string | null;
  closureApprovedBy: string | null;
  closedAt: string | null;
  version: number;
}

export interface HygieneAction {
  id: string;
  findingId: string;
  description: string;
  ownerReference: string;
  dueOn: string;
  status: ActionStatus;
  completedBy: string | null;
  verifiedBy: string | null;
  rejectionReason: string | null;
  version: number;
}

export interface HygieneEvidence {
  id: string;
  findingId: string;
  actionId: string | null;
  reference: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  contentHash: string;
  retentionClass: string;
  notes: string | null;
  status: EvidenceStatus;
  submittedBy: string;
  submittedAt: string;
  reviewedBy: string | null;
  reviewReason: string | null;
}

export interface HygieneEscalation {
  id: string;
  subjectType: 'CONTROL' | 'FINDING' | 'ACTION';
  subjectId: string;
  subjectReference: string;
  level: EscalationLevel;
  reason: string;
  detail: string | null;
  raisedAt: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
}

export interface FindingDetail {
  finding: HygieneFinding;
  overdue: boolean;
  actions: HygieneAction[];
  evidenceCount: number;
  history: HistoryEntry[];
}

export interface ControlDetail {
  control: HygieneControl;
  effectiveStatus: EffectiveControlStatus;
  history: HistoryEntry[];
}

export interface Completion {
  control: HygieneControl;
  next: HygieneControl | null;
}

export interface Page<T> {
  items: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface Dashboard {
  siteCode: string;
  periodDays: number;
  completionRatePercent: number | null;
  kpis: {
    controlsDue: number;
    controlsCompleted: number;
    overdueControls: number;
    openFindings: number;
    openCriticalFindings: number;
    overdueFindings: number;
    overdueActions: number;
    openEscalations: number;
    meanHoursToCloseCritical: number | null;
  };
}

const base = '/api/v1/facilities/hygiene';
const get = <T,>(path: string, query?: QueryParams, signal?: AbortSignal) => apiClient.get<T>(`${base}${path}`, query, signal, 'facilities');
const post = <T,>(path: string, body?: unknown) => apiClient.post<T>(`${base}${path}`, body ?? {}, { service: 'facilities' });

export interface ControlFilters {
  siteCode: string;
  status?: string;
  controlType?: string;
  overdueOnly?: boolean;
  page: number;
  size: number;
}

export interface FindingFilters {
  siteCode: string;
  status?: string;
  severity?: string;
  overdueOnly?: boolean;
  page: number;
  size: number;
}

export interface NewControl {
  siteCode: string;
  controlType: ControlType;
  riskCategory: RiskCategory;
  title: string;
  ownerReference: string;
  frequency: Frequency;
  dueOn: string;
  locationLabel?: string;
  providerReference?: string;
  notes?: string;
}

export interface NewFinding {
  title: string;
  description?: string;
  severity: Severity;
  category?: RiskCategory;
  ownerReference?: string;
  targetDate?: string;
  requiresIncident?: boolean;
}

export interface NewEvidence {
  actionId?: string;
  reference: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  contentHash: string;
  retentionClass?: string;
  notes?: string;
}

export const hygieneApi = {
  dashboard: (siteCode: string, signal?: AbortSignal) => get<Dashboard>('/dashboard', { siteCode }, signal),
  controls: (filters: ControlFilters, signal?: AbortSignal) => get<Page<ControlRow>>('/controls', { ...filters }, signal),
  control: (id: string, signal?: AbortSignal) => get<ControlDetail>(`/controls/${id}`, undefined, signal),
  createControl: (body: NewControl) => post<ControlRow>('/controls', body),
  startControl: (control: HygieneControl) => post<HygieneControl>(`/controls/${control.id}/start`, { version: control.version }),
  completeControl: (control: HygieneControl, notes?: string) => post<Completion>(`/controls/${control.id}/complete`, { notes, version: control.version }),
  missControl: (control: HygieneControl, reason: string) => post<Completion>(`/controls/${control.id}/missed`, { reason, version: control.version }),
  cancelControl: (control: HygieneControl, reason: string) => post<HygieneControl>(`/controls/${control.id}/cancel`, { reason, version: control.version }),
  confirmProvider: (control: HygieneControl) => post<HygieneControl>(`/controls/${control.id}/confirm-provider`, { version: control.version }),
  findings: (filters: FindingFilters, signal?: AbortSignal) => get<Page<HygieneFinding>>('/findings', { ...filters }, signal),
  finding: (id: string, signal?: AbortSignal) => get<FindingDetail>(`/findings/${id}`, undefined, signal),
  createFinding: (controlId: string, body: NewFinding) => post<HygieneFinding>(`/controls/${controlId}/findings`, body),
  startFinding: (finding: HygieneFinding) => post<HygieneFinding>(`/findings/${finding.id}/start`, { version: finding.version }),
  linkIncident: (id: string, incidentReference: string) => post<HygieneFinding>(`/findings/${id}/link-incident`, { incidentReference }),
  retryWorkOrder: (id: string) => post<HygieneFinding>(`/findings/${id}/retry-work-order`),
  closeFinding: (finding: HygieneFinding, mode: ClosureMode, reason?: string) => post<HygieneFinding>(`/findings/${finding.id}/close`, { mode, reason, version: finding.version }),
  reopenFinding: (id: string, reason: string) => post<HygieneFinding>(`/findings/${id}/reopen`, { reason }),
  addAction: (findingId: string, body: { description: string; ownerReference: string; dueOn: string }) => post<HygieneAction>(`/findings/${findingId}/actions`, body),
  moveAction: (action: HygieneAction, status: ActionStatus, reason?: string) => post<HygieneAction>(`/actions/${action.id}/transition`, { status, reason, version: action.version }),
  evidence: (findingId: string, signal?: AbortSignal) => get<HygieneEvidence[]>(`/findings/${findingId}/evidence`, undefined, signal),
  submitEvidence: (findingId: string, body: NewEvidence) => post<HygieneEvidence>(`/findings/${findingId}/evidence`, body),
  reviewEvidence: (id: string, accept: boolean, reason?: string) => post<HygieneEvidence>(`/evidence/${id}/review`, { accept, reason }),
  escalations: (siteCode: string, openOnly: boolean, page: number, size: number, signal?: AbortSignal) =>
    get<Page<HygieneEscalation>>('/escalations', { siteCode, openOnly, page, size }, signal),
  acknowledge: (id: string) => post<HygieneEscalation>(`/escalations/${id}/acknowledge`),
};

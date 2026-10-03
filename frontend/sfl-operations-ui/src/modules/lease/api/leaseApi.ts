import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export type AgreementKind = 'LEASE' | 'TENANCY';
export type Direction = 'INBOUND' | 'OUTBOUND';
export type AgreementStatus = 'DRAFT' | 'IN_REVIEW' | 'ACTIVE' | 'EXPIRED' | 'TERMINATED' | 'ARCHIVED';
export type RenewalType = 'NONE' | 'OPTION' | 'AUTO';
export type ObligationKind = 'RENEWAL' | 'NOTICE' | 'RENT_REVIEW' | 'INSURANCE' | 'COMPLIANCE' | 'DOCUMENT_EXPIRY' | 'PAYMENT' | 'OTHER';
export type AmendmentKind = 'RENT_CHANGE' | 'TERM_CHANGE' | 'RENEWAL' | 'TERMINATION';
export type AmendmentStatus = 'PROPOSED' | 'LEGAL_REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
export type DocumentKind = 'SIGNED_AGREEMENT' | 'APPROVAL_EVIDENCE' | 'INSURANCE_CERTIFICATE' | 'COMPLIANCE_CERTIFICATE' | 'NOTICE' | 'TERMINATION_NOTICE' | 'OTHER';

export interface Agreement {
  id: string; reference: string; siteCode: string; propertyReference: string; kind: AgreementKind; direction: Direction; title: string;
  counterpartyReference: string | null; counterpartyState: 'UNRESOLVED' | 'VERIFIED'; contractReference: string | null; financeReference: string | null;
  ownerReference: string | null; startDate: string; endDate: string; renewalType: RenewalType; renewalTermMonths: number | null; noticeDays: number | null;
  noticeDate: string | null; rentReviewDate: string | null;
  /** Null unless the caller holds the financial grant. */
  annualRent: number | null; depositAmount: number | null; currency: string | null;
  status: AgreementStatus; versionNumber: number; requestedBy: string | null; approvedBy: string | null; version: number;
}
export interface AgreementVersion { id: string; versionNumber: number; endDate: string; annualRent: number | null; depositAmount: number | null; noticeDays: number | null; ownerReference: string | null; status: AgreementStatus; amendmentId: string | null; approvedBy: string | null; approvedAt: string | null; recordedAt: string }
export interface Amendment {
  id: string; reference: string; agreementId: string; siteCode: string; kind: AmendmentKind; status: AmendmentStatus; reason: string; newEndDate: string | null;
  newAnnualRent: number | null; newDepositAmount: number | null; newNoticeDays: number | null; newRentReviewDate: string | null; newRenewalTermMonths: number | null;
  effectiveOn: string | null; priorVersion: number; proposedBy: string; proposedAt: string; decidedBy: string | null; decisionReason: string | null; legalReviewNote: string | null; version: number;
}
export interface Obligation { id: string; agreementId: string; kind: ObligationKind; title: string; dueOn: string; ownerReference: string | null; status: 'OPEN' | 'DONE' | 'WAIVED'; completedOn: string | null; completionNote: string | null; generated: boolean; version: number }
export interface LeaseDocument { id: string; kind: DocumentKind; reference: string; fileName: string; contentHash: string; expiresOn: string | null; submittedBy: string; submittedAt: string }
export interface Alert { id: string; agreementId: string; obligationId: string | null; level: 'OWNER' | 'MANAGER' | 'DIRECTOR' | 'LEGAL'; reason: string; detail: string | null; raisedAt: string; acknowledgedBy: string | null; acknowledgedAt: string | null }
export interface HistoryEntry { id: string; subjectType: string; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; occurredAt: string }
export interface WorkOrderRequest { id: string; agreementId: string; obligationId: string | null; trigger: 'EXPIRED_REVIEW' | 'MANUAL'; description: string; state: 'RAISED' | 'PENDING_MANUAL'; workOrderNumber: string | null; requestedBy: string; createdAt: string; version: number }
export interface Detail {
  agreement: Agreement; blockers: string[]; warnings: string[]; documents: Array<{ document: LeaseDocument; expired: boolean }>; obligations: Obligation[];
  amendments: Amendment[]; versions: AgreementVersion[]; alerts: Alert[]; history: HistoryEntry[]; financialView: boolean; pastEndDate: boolean; workOrders: WorkOrderRequest[]; ownerVerified: boolean;
}
export interface Page<T> { items: T[]; totalElements: number; totalPages: number; page: number; size: number }
export interface Calendar { timezone: string; weekend: string[]; holidays: Array<{ date: string; name: string }> }
export interface Exposure { payableRemaining: number; receivableRemaining: number; depositsHeldByLandlords: number; depositsHeld: number }
export interface Portfolio {
  siteCode: string; asOf: string; timezone: string; agreementsByStatus: Record<string, number>; expiringWithin: Record<string, number>; obligationsDueWithin: Record<string, number>;
  overdueObligations: number; incompleteAgreements: number; unresolvedCounterparties: number; expiredAgreements: number; renewalsOnTimePercent: number | null; renewalsDueOrDone: number;
  meanAmendmentCycleHours: number | null; byOwner: Array<{ owner: string; agreements: number; openObligations: number; overdueObligations: number }>;
  financialExposure: Record<string, Exposure> | null;
}

const base = '/api/v1/facilities/leases';
const get = <T,>(path: string, query?: QueryParams, signal?: AbortSignal) => apiClient.get<T>(`${base}${path}`, query, signal, 'facilities');
const post = <T,>(path: string, body?: unknown) => apiClient.post<T>(`${base}${path}`, body ?? {}, { service: 'facilities' });

export interface NewAgreement {
  siteCode: string; propertyReference: string; kind: AgreementKind; direction: Direction; title: string; counterpartyReference?: string; contractReference?: string;
  financeReference?: string; ownerReference?: string; startDate: string; endDate: string; renewalType: RenewalType; renewalTermMonths?: number; noticeDays?: number;
  rentReviewDate?: string; annualRent?: number; depositAmount?: number; currency?: string;
}

export const leaseApi = {
  portfolio: (siteCode: string, signal?: AbortSignal) => get<Portfolio>('/portfolio', { siteCode }, signal),
  calendar: (signal?: AbortSignal) => get<Calendar>('/calendar', undefined, signal),
  saveSettings: (timezone: string, weekend: string[]) => post<Calendar>('/calendar/settings', { timezone, weekend }),
  addHoliday: (date: string, name: string) => post<Calendar>('/calendar/holidays', { date, name }),
  agreements: (query: { siteCode: string; status?: string; owner?: string; endsWithinDays?: number; page: number; size: number }, signal?: AbortSignal) => get<Page<Agreement>>('/agreements', { ...query }, signal),
  agreement: (id: string, signal?: AbortSignal) => get<Detail>(`/agreements/${id}`, undefined, signal),
  register: (body: NewAgreement) => post<Agreement>('/agreements', body),
  submit: (a: Agreement) => post<Agreement>(`/agreements/${a.id}/submit`, { version: a.version }),
  returnToDraft: (a: Agreement, reason: string) => post<Agreement>(`/agreements/${a.id}/return-to-draft`, { reason, version: a.version }),
  approve: (a: Agreement) => post<Agreement>(`/agreements/${a.id}/approve`, { version: a.version }),
  reassign: (a: Agreement, ownerReference: string) => post<Agreement>(`/agreements/${a.id}/reassign`, { ownerReference, version: a.version }),
  fileDocument: (id: string, body: { kind: DocumentKind; reference: string; fileName: string; mediaType: string; sizeBytes: number; contentHash: string; expiresOn?: string }) => post<LeaseDocument>(`/agreements/${id}/documents`, body),
  amendments: (query: { siteCode: string; status?: string; page: number; size: number }, signal?: AbortSignal) => get<Page<Amendment>>('/amendments', { ...query }, signal),
  propose: (id: string, body: { kind: AmendmentKind; newEndDate?: string; newAnnualRent?: number; newDepositAmount?: number; newNoticeDays?: number; newRentReviewDate?: string; newRenewalTermMonths?: number; effectiveOn?: string; reason: string }) => post<Amendment>(`/agreements/${id}/amendments`, body),
  decide: (m: Amendment, approve: boolean, reason?: string) => post<Amendment>(`/amendments/${m.id}/decide`, { approve, reason, version: m.version }),
  clearLegal: (id: string, note: string) => post<Amendment>(`/amendments/${id}/clear-legal-review`, { note }),
  withdraw: (id: string) => post<Amendment>(`/amendments/${id}/withdraw`),
  obligations: (query: { siteCode: string; status?: string; dueWithinDays?: number; page: number; size: number }, signal?: AbortSignal) => get<Page<Obligation>>('/obligations', { ...query }, signal),
  addObligation: (id: string, body: { kind: ObligationKind; title: string; dueOn: string; ownerReference?: string }) => post<Obligation>(`/agreements/${id}/obligations`, body),
  complete: (o: Obligation, note?: string) => post<Obligation>(`/obligations/${o.id}/complete`, { note, version: o.version }),
  waive: (o: Obligation, reason: string) => post<Obligation>(`/obligations/${o.id}/waive`, { reason, version: o.version }),
  alerts: (query: { siteCode: string; openOnly: boolean; page: number; size: number }, signal?: AbortSignal) => get<Page<Alert>>('/alerts', { ...query }, signal),
  raiseWorkOrder: (id: string, description: string, obligationId?: string) => post<WorkOrderRequest>(`/agreements/${id}/work-orders`, { description, obligationId }),
  retryWorkOrder: (id: string) => post<WorkOrderRequest>(`/work-orders/${id}/retry`),
  acknowledge: (id: string) => post<Alert>(`/alerts/${id}/acknowledge`),
};

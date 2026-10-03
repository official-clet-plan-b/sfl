import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export type ItemCategory = 'DOCUMENT' | 'ELECTRONICS' | 'JEWELLERY' | 'CASH_VALUABLES' | 'CLOTHING' | 'BAG' | 'KEYS' | 'MEDICAL' | 'OTHER';
export type ItemStatus = 'REGISTERED' | 'STORED' | 'ISOLATED' | 'RELEASED' | 'DISPOSED' | 'HANDED_TO_AUTHORITIES';
export type ClaimStatus = 'RECEIVED' | 'VERIFIED' | 'APPROVED' | 'REFUSED' | 'RELEASED' | 'WITHDRAWN';
export type VerificationMethod = 'ID_DOCUMENT' | 'STAFF_ID' | 'VISITOR_RECORD' | 'KNOWN_TO_STAFF';
export type EvidenceKind = 'PHOTO' | 'VERIFICATION' | 'RELEASE_RECEIPT' | 'DISPOSAL_AUTHORISATION' | 'AUTHORITY_RECEIPT';

export interface FoundItem {
  id: string; reference: string; claimReference: string; siteCode: string; category: ItemCategory; publicDescription: string;
  /** Null unless the caller holds the private grant. */
  privateDescription: string | null; foundLocation: string; foundAt: string; finderReference: string | null; initialCondition: string;
  status: ItemStatus; unsafe: boolean; unsafeReason: string | null; storageLocationId: string | null; retentionUntil: string; closedAt: string | null; version: number;
}
export interface StorageLocation { id: string; siteCode: string; code: string; name: string; secure: boolean; active: boolean; version: number }
export interface RetentionPolicy { category: ItemCategory; unclaimedDays: number; personalDataDays: number }
export interface CustodyEvent { id: string; fromParty: string; toParty: string; location: string; occurredAt: string; reason: string | null; recordedBy: string }
export interface Claim {
  id: string; reference: string; itemId: string; siteCode: string; claimantName: string | null; claimantContact: string | null; claimantDescription: string | null;
  status: ClaimStatus; identityVerified: boolean; verificationMethod: VerificationMethod | null; verifiedBy: string | null; decisionReason: string | null;
  decidedBy: string | null; personalDataPurgedAt: string | null; createdAt: string; version: number;
}
export interface LfEvidence { id: string; claimId: string | null; kind: EvidenceKind; reference: string; fileName: string; contentHash: string; retentionClass: string; submittedBy: string; submittedAt: string }
export interface HistoryEntry { id: string; subjectType: string; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; occurredAt: string }
export interface Escalation { id: string; itemId: string; reason: 'UNSAFE_ITEM' | 'COMPETING_CLAIMS' | 'RETENTION_EXPIRED'; escalatedTo: string; detail: string | null; incidentState: string; incidentReference: string | null; raisedAt: string; acknowledgedBy: string | null; acknowledgedAt: string | null }

export interface ItemDetail {
  item: FoundItem; storageLocation: StorageLocation | null; claims: Claim[]; custody: CustodyEvent[]; custodyComplete: boolean;
  evidenceCount: number; history: HistoryEntry[]; privateView: boolean;
}
export interface ClaimantView { claimRef: string; itemClaimReference: string; category: ItemCategory; publicDescription: string; status: ClaimStatus; identityVerified: boolean; privateDescription: string | null }
export interface Page<T> { items: T[]; totalElements: number; totalPages: number; page: number; size: number }
export interface Configuration { locations: StorageLocation[]; policies: RetentionPolicy[] }
export interface Dashboard {
  siteCode: string; openByAge: { upTo7Days: number; from8To30Days: number; from31To90Days: number; over90Days: number };
  openByLocation: Array<{ location: string; items: number }>; meanHoursToVerifiedRelease: number | null; custodyCompletenessPercent: number | null;
  itemsWithChain: number; withinPolicyPercent: number | null; itemsClosed: number; retentionExpired: number; openEscalations: number; isolatedItems: number;
}

const base = '/api/v1/facilities/lost-found';
const get = <T,>(path: string, query?: QueryParams, signal?: AbortSignal) => apiClient.get<T>(`${base}${path}`, query, signal, 'facilities');
const post = <T,>(path: string, body?: unknown) => apiClient.post<T>(`${base}${path}`, body ?? {}, { service: 'facilities' });

export interface NewItem {
  siteCode: string; category: ItemCategory; publicDescription: string; privateDescription?: string; foundLocation: string; finderReference: string;
  initialCondition: string; unsafe: boolean; unsafeReason?: string; storageLocationId?: string;
}

export const lostFoundApi = {
  dashboard: (siteCode: string, signal?: AbortSignal) => get<Dashboard>('/dashboard', { siteCode }, signal),
  configuration: (siteCode: string, signal?: AbortSignal) => get<Configuration>('/configuration', { siteCode }, signal),
  createLocation: (body: { siteCode: string; code: string; name: string; secure: boolean }) => post<StorageLocation>('/locations', body),
  setPolicy: (body: { siteCode: string; category: ItemCategory; unclaimedDays: number; personalDataDays: number }) => post<RetentionPolicy>('/retention', body),
  items: (query: { siteCode: string; status?: string; category?: string; openOnly?: boolean; page: number; size: number }, signal?: AbortSignal) => get<Page<FoundItem>>('/items', { ...query }, signal),
  item: (id: string, signal?: AbortSignal) => get<ItemDetail>(`/items/${id}`, undefined, signal),
  register: (body: NewItem) => post<FoundItem>('/items', body),
  store: (item: FoundItem, storageLocationId: string) => post<FoundItem>(`/items/${item.id}/store`, { storageLocationId, version: item.version }),
  transfer: (id: string, body: { toParty: string; location: string; reason?: string }) => post<FoundItem>(`/items/${id}/transfer`, body),
  unsafe: (id: string, reason: string) => post<FoundItem>(`/items/${id}/unsafe`, { reason }),
  dispose: (item: FoundItem) => post<FoundItem>(`/items/${item.id}/dispose`, { version: item.version }),
  handToAuthorities: (item: FoundItem, authority: string) => post<FoundItem>(`/items/${item.id}/hand-to-authorities`, { authority, version: item.version }),
  evidence: (id: string, signal?: AbortSignal) => get<LfEvidence[]>(`/items/${id}/evidence`, undefined, signal),
  submitEvidence: (id: string, body: { claimId?: string; kind: EvidenceKind; reference: string; fileName: string; mediaType: string; sizeBytes: number; contentHash: string }) => post<LfEvidence>(`/items/${id}/evidence`, body),
  claims: (query: { siteCode: string; status?: string; page: number; size: number }, signal?: AbortSignal) => get<Page<Claim>>('/claims', { ...query }, signal),
  receiveClaim: (itemId: string, body: { claimantName: string; claimantContact: string; claimantDescription?: string }) => post<Claim>(`/items/${itemId}/claims`, body),
  claimantView: (id: string, signal?: AbortSignal) => get<ClaimantView>(`/claims/${id}/claimant-view`, undefined, signal),
  verify: (c: Claim, method: VerificationMethod, verificationReference: string) => post<Claim>(`/claims/${c.id}/verify`, { method, verificationReference, version: c.version }),
  approve: (c: Claim, reason?: string) => post<Claim>(`/claims/${c.id}/approve`, { reason, version: c.version }),
  refuse: (c: Claim, reason: string) => post<Claim>(`/claims/${c.id}/refuse`, { reason, version: c.version }),
  withdraw: (c: Claim, reason: string) => post<Claim>(`/claims/${c.id}/withdraw`, { reason, version: c.version }),
  release: (c: Claim, accepted: boolean, note?: string) => post<Claim>(`/claims/${c.id}/release`, { accepted, note, version: c.version }),
  escalations: (query: { siteCode: string; openOnly: boolean; page: number; size: number }, signal?: AbortSignal) => get<Page<Escalation>>('/escalations', { ...query }, signal),
  acknowledge: (id: string) => post<Escalation>(`/escalations/${id}/acknowledge`),
  linkIncident: (id: string, text: string) => post<Escalation>(`/escalations/${id}/link-incident`, { text }),
};

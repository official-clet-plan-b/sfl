import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export type WasteCategory = 'GENERAL' | 'RECYCLABLE' | 'ORGANIC' | 'ELECTRONIC' | 'CHEMICAL' | 'MEDICAL' | 'CONSTRUCTION' | 'OTHER';
export type DestinationType = 'RECYCLER' | 'REUSE' | 'COMPOSTING' | 'TREATMENT' | 'LANDFILL';
export type ApprovalStatus = 'APPROVED' | 'SUSPENDED';
export type QuantityBasis = 'MEASURED' | 'ESTIMATED';
export type CollectionStatus = 'SCHEDULED' | 'COLLECTED' | 'HANDED_OVER' | 'DESTINATION_CONFIRMED' | 'CLOSED' | 'MISSED' | 'CANCELLED';
export type EvidenceKind = 'MANIFEST' | 'RECEIVING' | 'CERTIFICATE' | 'PHOTO';
export type EvidenceStatus = 'SUBMITTED' | 'ACCEPTED' | 'REJECTED';
export type ExceptionType = 'MISSED_COLLECTION' | 'CONTAMINATION' | 'MISSING_CERTIFICATE' | 'MISSING_RECEIVING_EVIDENCE' | 'SPILL' | 'UNAPPROVED_CARRIER' | 'UNAPPROVED_DESTINATION';
export type ExceptionStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED';

export interface WasteStream { id: string; code: string; name: string; category: WasteCategory; hazardous: boolean; diverted: boolean; handlingRules: string | null; active: boolean; version: number }
export interface WastePoint { id: string; siteCode: string; code: string; name: string; containerDescription: string | null; active: boolean; version: number }
export interface WasteCarrier { id: string; code: string; name: string; licenceReference: string; licenceExpiresOn: string; hazardousApproved: boolean; status: ApprovalStatus; version: number }
export interface WasteDestination { id: string; code: string; name: string; destinationType: DestinationType; permitReference: string; permitExpiresOn: string; acceptsHazardous: boolean; status: ApprovalStatus; version: number }
export interface WasteUnit { code: string; name: string; kilogramsPerUnit: number }

export interface WasteCollection {
  id: string; reference: string; siteCode: string; streamId: string; pointId: string; carrierId: string; destinationId: string | null;
  hazardous: boolean; scheduledFor: string; collectedOn: string | null; quantity: number | null; unit: string | null; quantityKg: number | null;
  quantityBasis: QuantityBasis | null; manifestReference: string | null; certificateReference: string | null; contaminated: boolean;
  quantityReconciled: boolean; status: CollectionStatus; version: number;
}

export interface CustodyEvent { id: string; step: string; fromParty: string; toParty: string | null; location: string | null; evidenceReference: string | null; recordedBy: string; occurredAt: string }
export interface WasteEvidence { id: string; kind: EvidenceKind; reference: string; fileName: string; contentHash: string; retentionClass: string; status: EvidenceStatus; submittedBy: string; reviewedBy: string | null; reviewReason: string | null }
export interface WasteException {
  id: string; reference: string; siteCode: string; collectionId: string | null; exceptionType: ExceptionType; description: string; ownerReference: string | null;
  dueOn: string; status: ExceptionStatus; escalatedTo: string; incidentState: string; incidentReference: string | null; workOrderState: string; workOrderNumber: string | null;
  resolution: string | null; version: number;
}
export interface HistoryEntry { id: string; subjectType: string; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; occurredAt: string }

export interface CollectionDetail {
  collection: WasteCollection; stream: WasteStream; point: WastePoint; carrier: WasteCarrier; destination: WasteDestination | null;
  custody: CustodyEvent[]; evidence: WasteEvidence[]; openExceptions: WasteException[]; history: HistoryEntry[]; estimated: boolean;
}

export interface Configuration { streams: WasteStream[]; points: WastePoint[]; carriers: WasteCarrier[]; destinations: WasteDestination[]; units: WasteUnit[] }
export interface Page<T> { items: T[]; totalElements: number; totalPages: number; page: number; size: number }

export interface Dashboard {
  siteCode: string; periodDays: number; measuredKg: number; estimatedKg: number; diversionRatePercent: number | null;
  certificateCompletionPercent: number | null; handedOverCollections: number; certifiedCollections: number;
  missedCollections: { missedOpen: number; upTo7Days: number; from8To14Days: number; over14Days: number; oldestDays: number | null };
  openHazardousChainExceptions: number; openExceptions: number; overdueExceptions: number;
}

export interface ReportLine { streamCode: string; streamName: string; hazardous: boolean; diverted: boolean; measuredKg: number; estimatedKg: number; divertedMeasuredKg: number; collections: number; estimatedCollections: number; sourceReferences: string[] }
export interface Report { siteCode: string; from: string; to: string; lines: ReportLine[]; measuredKg: number; estimatedKg: number; diversionRatePercent: number | null; estimationRule: string; diversionRule: string; collections: number; estimatedCollections: number }

const base = '/api/v1/facilities/waste';
const get = <T,>(path: string, query?: QueryParams, signal?: AbortSignal) => apiClient.get<T>(`${base}${path}`, query, signal, 'facilities');
const post = <T,>(path: string, body?: unknown) => apiClient.post<T>(`${base}${path}`, body ?? {}, { service: 'facilities' });

export const wasteApi = {
  dashboard: (siteCode: string, signal?: AbortSignal) => get<Dashboard>('/dashboard', { siteCode }, signal),
  report: (siteCode: string, from: string, to: string, signal?: AbortSignal) => get<Report>('/report', { siteCode, from, to }, signal),
  configuration: (siteCode: string, signal?: AbortSignal) => get<Configuration>('/configuration', { siteCode }, signal),
  createStream: (body: { code: string; name: string; category: WasteCategory; hazardous: boolean; diverted: boolean; handlingRules?: string }) => post<WasteStream>('/streams', body),
  createPoint: (body: { siteCode: string; code: string; name: string; containerDescription?: string }) => post<WastePoint>('/points', body),
  createCarrier: (body: { code: string; name: string; licenceReference: string; licenceExpiresOn: string; hazardousApproved: boolean }) => post<WasteCarrier>('/carriers', body),
  setCarrierStatus: (carrier: WasteCarrier, status: ApprovalStatus) => post<WasteCarrier>(`/carriers/${carrier.id}/update`, { status, version: carrier.version }),
  createDestination: (body: { code: string; name: string; destinationType: DestinationType; permitReference: string; permitExpiresOn: string; acceptsHazardous: boolean }) => post<WasteDestination>('/destinations', body),
  setDestinationStatus: (destination: WasteDestination, status: ApprovalStatus) => post<WasteDestination>(`/destinations/${destination.id}/update`, { status, version: destination.version }),
  collections: (query: { siteCode: string; status?: string; hazardousOnly?: boolean; page: number; size: number }, signal?: AbortSignal) => get<Page<WasteCollection>>('/collections', { ...query }, signal),
  collection: (id: string, signal?: AbortSignal) => get<CollectionDetail>(`/collections/${id}`, undefined, signal),
  schedule: (body: { siteCode: string; streamId: string; pointId: string; carrierId: string; scheduledFor: string }) => post<WasteCollection>('/collections', body),
  record: (c: WasteCollection, body: { collectedOn?: string; quantity: number; unit: string; basis: QuantityBasis; manifestReference?: string }) => post<WasteCollection>(`/collections/${c.id}/record`, { ...body, version: c.version }),
  handOver: (c: WasteCollection, destinationId: string, location?: string) => post<WasteCollection>(`/collections/${c.id}/hand-over`, { destinationId, location, version: c.version }),
  confirmDestination: (c: WasteCollection) => post<WasteCollection>(`/collections/${c.id}/confirm-destination`, { version: c.version }),
  certificate: (c: WasteCollection, certificateReference: string) => post<WasteCollection>(`/collections/${c.id}/certificate`, { certificateReference, version: c.version }),
  contaminated: (id: string, text: string) => post<WasteCollection>(`/collections/${id}/contaminated`, { text }),
  reconcile: (c: WasteCollection, body: { quantity: number; unit: string; basis: QuantityBasis }) => post<WasteCollection>(`/collections/${c.id}/reconcile`, { ...body, version: c.version }),
  missed: (c: WasteCollection, reason: string) => post<WasteCollection>(`/collections/${c.id}/missed`, { reason, version: c.version }),
  cancel: (c: WasteCollection, reason: string) => post<WasteCollection>(`/collections/${c.id}/cancel`, { reason, version: c.version }),
  close: (c: WasteCollection) => post<WasteCollection>(`/collections/${c.id}/close`, { version: c.version }),
  submitEvidence: (collectionId: string, body: { kind: EvidenceKind; reference: string; fileName: string; mediaType: string; sizeBytes: number; contentHash: string }) => post<WasteEvidence>(`/collections/${collectionId}/evidence`, body),
  reviewEvidence: (id: string, accept: boolean, reason?: string) => post<WasteEvidence>(`/evidence/${id}/review`, { accept, reason }),
  exceptions: (query: { siteCode: string; status?: string; page: number; size: number }, signal?: AbortSignal) => get<Page<WasteException>>('/exceptions', { ...query }, signal),
  reportException: (body: { siteCode: string; collectionId?: string; exceptionType: ExceptionType; description: string; ownerReference?: string }) => post<WasteException>('/exceptions', body),
  startException: (e: WasteException) => post<WasteException>(`/exceptions/${e.id}/start`, { version: e.version }),
  assignException: (id: string, text: string) => post<WasteException>(`/exceptions/${id}/assign`, { text }),
  linkIncident: (id: string, text: string) => post<WasteException>(`/exceptions/${id}/link-incident`, { text }),
  retryWorkOrder: (id: string) => post<WasteException>(`/exceptions/${id}/retry-work-order`),
  resolveException: (e: WasteException, resolution: string) => post<WasteException>(`/exceptions/${e.id}/resolve`, { resolution, version: e.version }),
};

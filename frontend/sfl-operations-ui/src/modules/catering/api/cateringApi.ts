import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

export type Allergen = 'CELERY' | 'CEREALS_GLUTEN' | 'CRUSTACEANS' | 'EGGS' | 'FISH' | 'LUPIN' | 'MILK' | 'MOLLUSCS' | 'MUSTARD' | 'NUTS' | 'PEANUTS' | 'SESAME' | 'SOYA' | 'SULPHITES';
export type DietaryTag = 'VEGETARIAN' | 'VEGAN' | 'HALAL' | 'KOSHER' | 'GLUTEN_FREE' | 'DAIRY_FREE' | 'NUT_FREE';
export type ContextType = 'EVENT' | 'BOOKING' | 'EXAMINATION' | 'ROUTINE';
export type ServiceStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'CONFIRMED' | 'DELIVERED' | 'RECONCILED' | 'CLOSED' | 'CANCELLED';
export type NeedType = 'ALLERGY' | 'DIETARY';
export type CheckType = 'SUPPLIER' | 'TEMPERATURE' | 'FOOD_SAFETY';
export type HoldType = 'HOT' | 'COLD';
export type ExceptionType = 'SHORTAGE' | 'SUBSTITUTION' | 'FOOD_SAFETY_INCIDENT' | 'SERVICE_EXCEPTION';
export type VarianceKind = 'QUANTITY' | 'COST' | 'SUBSTITUTION';
export type EvidenceKind = 'DELIVERY_NOTE' | 'INVOICE' | 'CERTIFICATE' | 'TEMPERATURE_LOG' | 'PHOTO';

export interface Supplier { id: string; code: string; name: string; certificateReference: string; certificateExpiresOn: string; status: 'APPROVED' | 'SUSPENDED'; version: number }
export interface Venue { id: string; siteCode: string; code: string; name: string; capacity: number; active: boolean; version: number }
export interface Menu { id: string; siteCode: string; code: string; name: string; description: string | null; status: 'DRAFT' | 'APPROVED' | 'RETIRED'; approvedBy: string | null; version: number }
export interface MenuItem { id: string; menuId: string; name: string; allergens: Allergen[]; allergensDeclared: boolean; dietaryTags: DietaryTag[] }
export interface CateringService {
  id: string; reference: string; siteCode: string; venueId: string; menuId: string; supplierId: string; contextType: ContextType; contextReference: string | null;
  title: string; serviceDate: string; startsAt: string; cancellationCutoff: string; expectedGuests: number; plannedPortions: number; deliveredPortions: number | null;
  status: ServiceStatus; requestedBy: string | null; approvedBy: string | null; capacityExceptionReason: string | null; supplierExceptionReason: string | null;
  purchaseReference: string | null; invoiceReference: string | null; financeState: 'NOT_STARTED' | 'PENDING_FINANCE' | 'RECORDED'; version: number;
}
export interface DietaryRequest { id: string; serviceId: string; personReference: string; needType: NeedType; needCode: string; authorisedBy: string; status: 'OPEN' | 'SUBSTITUTED' | 'WAIVED'; substituteItemId: string | null; waiverReason: string | null; version: number }
export interface Blocker { code: string; message: string; exceptionable: boolean }
export interface NeedView { request: DietaryRequest; flaggedItems: string[]; blocked: boolean; message: string | null }
export interface Check { id: string; checkType: CheckType; holdType: HoldType | null; temperatureC: number | null; result: 'PASS' | 'FAIL'; notes: string | null; checkedBy: string; checkedAt: string }
export interface CateringException { id: string; reference: string; siteCode: string; serviceId: string | null; exceptionType: ExceptionType; description: string; ownerReference: string; status: 'OPEN' | 'RESOLVED'; incidentState: string; incidentReference: string | null; resolution: string | null; version: number }
export interface Variance { id: string; kind: VarianceKind; planned: number; actual: number; difference: number; ownerReference: string; reason: string; status: 'OPEN' | 'APPROVED'; approvedBy: string | null }
export interface HistoryEntry { id: string; subjectType: string; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; occurredAt: string }
export interface ServiceDetail {
  service: CateringService; venue: Venue; menu: Menu; items: MenuItem[]; supplier: Supplier; readiness: Blocker[]; needs: NeedView[]; dietaryCount: number;
  checks: Check[]; exceptions: CateringException[]; variances: Variance[]; evidenceCount: number; history: HistoryEntry[]; dietaryView: boolean;
}
export interface Page<T> { items: T[]; totalElements: number; totalPages: number; page: number; size: number }
export interface Configuration { suppliers: Supplier[]; venues: Venue[]; menus: Array<{ menu: Menu; items: MenuItem[] }> }
export interface Dashboard {
  siteCode: string; periodDays: number; confirmedServices: number; deliveredServices: number; deliveredPercent: number | null; servicesDelivered: number;
  servicesWithPassingCheck: number; checkedPercent: number | null; dietaryRequests: number; dietaryExceptions: number; dietaryExceptionPercent: number | null;
  openVariances: number; variancesUpTo7Days: number; variances8To14Days: number; variancesOver14Days: number; netQuantityVariance: number; openExceptions: number; awaitingFinance: number;
}
export interface ExceptionWorkOrder { exceptionId: string; siteCode: string; state: 'RAISED' | 'PENDING_MANUAL'; workOrderId: string | null; workOrderNumber: string | null }
export interface Pack { service: CateringService; variances: Variance[]; checks: Check[]; exceptions: CateringException[]; evidence: Array<{ id: string; kind: EvidenceKind; fileName: string; reference: string }>; finance: { provider: string; available: boolean; matched: boolean }; portionDifference: number | null }

const base = '/api/v1/facilities/catering';
const get = <T,>(path: string, query?: QueryParams, signal?: AbortSignal) => apiClient.get<T>(`${base}${path}`, query, signal, 'facilities');
const post = <T,>(path: string, body?: unknown) => apiClient.post<T>(`${base}${path}`, body ?? {}, { service: 'facilities' });

export const cateringApi = {
  dashboard: (siteCode: string, signal?: AbortSignal) => get<Dashboard>('/dashboard', { siteCode }, signal),
  configuration: (siteCode: string, signal?: AbortSignal) => get<Configuration>('/configuration', { siteCode }, signal),
  createSupplier: (body: { code: string; name: string; certificateReference: string; certificateExpiresOn: string }) => post<Supplier>('/suppliers', body),
  setSupplierStatus: (s: Supplier, status: 'APPROVED' | 'SUSPENDED') => post<Supplier>(`/suppliers/${s.id}/update`, { status, version: s.version }),
  createVenue: (body: { siteCode: string; code: string; name: string; capacity: number }) => post<Venue>('/venues', body),
  createMenu: (body: { siteCode: string; code: string; name: string }) => post<Menu>('/menus', body),
  addItem: (menuId: string, body: { name: string; allergens: Allergen[]; allergensDeclared: boolean; dietaryTags: DietaryTag[] }) => post<MenuItem>(`/menus/${menuId}/items`, body),
  updateItem: (id: string, body: { allergens: Allergen[]; allergensDeclared: boolean; dietaryTags: DietaryTag[] }) => post<MenuItem>(`/items/${id}/update`, body),
  approveMenu: (m: Menu) => post<Menu>(`/menus/${m.id}/approve`, { version: m.version }),
  services: (query: { siteCode: string; status?: string; page: number; size: number }, signal?: AbortSignal) => get<Page<CateringService>>('/services', { ...query }, signal),
  service: (id: string, signal?: AbortSignal) => get<ServiceDetail>(`/services/${id}`, undefined, signal),
  createService: (body: { siteCode: string; venueId: string; menuId: string; supplierId: string; contextType: ContextType; contextReference?: string; title: string; startsAt: string; expectedGuests: number; plannedPortions: number }) => post<CateringService>('/services', body),
  change: (s: CateringService, body: { expectedGuests?: number; plannedPortions?: number; reason?: string }) => post<CateringService>(`/services/${s.id}/change`, { ...body, version: s.version }),
  submit: (s: CateringService) => post<CateringService>(`/services/${s.id}/submit`, { version: s.version }),
  approve: (s: CateringService, capacityReason?: string, supplierReason?: string) => post<CateringService>(`/services/${s.id}/approve`, { capacityReason, supplierReason, version: s.version }),
  confirm: (s: CateringService) => post<CateringService>(`/services/${s.id}/confirm`, { version: s.version }),
  deliver: (s: CateringService, deliveredPortions: number) => post<CateringService>(`/services/${s.id}/deliver`, { deliveredPortions, version: s.version }),
  cancel: (s: CateringService, reason: string) => post<CateringService>(`/services/${s.id}/cancel`, { reason, version: s.version }),
  addDietary: (serviceId: string, body: { personReference: string; needType: NeedType; needCode: string; authorisedBy: string }) => post<DietaryRequest>(`/services/${serviceId}/dietary`, body),
  substitute: (r: DietaryRequest, itemId: string) => post<DietaryRequest>(`/dietary/${r.id}/substitute`, { itemId, version: r.version }),
  waive: (r: DietaryRequest, reason: string) => post<DietaryRequest>(`/dietary/${r.id}/waive`, { reason, version: r.version }),
  recordCheck: (body: { siteCode?: string; serviceId?: string; supplierId?: string; checkType: CheckType; holdType?: HoldType; temperatureC?: number; passed?: boolean; notes?: string }) => post<Check>('/checks', body),
  exceptions: (query: { siteCode: string; status?: string; page: number; size: number }, signal?: AbortSignal) => get<Page<CateringException>>('/exceptions', { ...query }, signal),
  raiseException: (body: { siteCode: string; serviceId?: string; exceptionType: ExceptionType; description: string; ownerReference: string }) => post<CateringException>('/exceptions', body),
  resolveException: (e: CateringException, resolution: string) => post<CateringException>(`/exceptions/${e.id}/resolve`, { resolution, version: e.version }),
  exceptionWorkOrders: (siteCode: string, signal?: AbortSignal) => get<ExceptionWorkOrder[]>('/exceptions/work-orders', { siteCode }, signal),
  retryWorkOrder: (id: string) => post<ExceptionWorkOrder>(`/exceptions/${id}/work-order/retry`),
  linkIncident: (id: string, text: string) => post<CateringException>(`/exceptions/${id}/link-incident`, { text }),
  recordVariance: (serviceId: string, body: { kind: VarianceKind; planned: number; actual: number; ownerReference: string; reason: string }) => post<Variance>(`/services/${serviceId}/variances`, body),
  approveVariance: (id: string) => post<Variance>(`/variances/${id}/approve`),
  submitEvidence: (serviceId: string, body: { kind: EvidenceKind; reference: string; fileName: string; mediaType: string; sizeBytes: number; contentHash: string }) => post(`/services/${serviceId}/evidence`, body),
  reconcile: (s: CateringService, purchaseReference: string, invoiceReference?: string) => post<{ service: CateringService; note: string | null }>(`/services/${s.id}/reconcile`, { purchaseReference, invoiceReference, version: s.version }),
  close: (s: CateringService) => post<CateringService>(`/services/${s.id}/close`, { version: s.version }),
  pack: (id: string, signal?: AbortSignal) => get<Pack>(`/services/${id}/pack`, undefined, signal),
};

import { apiClient } from 'shared/api/client';
import type { QueryParams } from 'shared/api/types';

/** Whether a vehicle can be located right now - decided by the service, not by this screen. */
export type TrackingStatus = 'TRACKED' | 'STALE' | 'UNTRACKED';

export interface TrackedVehicle {
  vehicleId: string;
  registrationNumber: string;
  make: string;
  model: string;
  siteCode: string;
  trackingStatus: TrackingStatus;
  latitude: number | null;
  longitude: number | null;
  recordedAt: string | null;
  sourceSystem: string | null;
}

export interface TrackingPage {
  content: TrackedVehicle[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  /** How old a report may be before the service calls the vehicle stale. */
  staleAfterSeconds: number;
}

export const trackingApi = {
  overview: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<TrackingPage>(
      '/api/v1/fleet/vehicles/tracking',
      { siteCode, size: 200 } as QueryParams,
      signal,
    ),
};

export const ASSET_CATEGORIES = [
  'EQUIPMENT',
  'ROOM_DEVICE',
  'CCTV_CAMERA',
  'ACCESS_READER',
  'FIRE_PANEL',
  'VEHICLE',
  'DISPATCH_ITEM',
  'DOCUMENT_CONTAINER',
  'OTHER',
] as const;
export type AssetCategory = (typeof ASSET_CATEGORIES)[number];

export const LOCATION_TYPES = ['SITE', 'BUILDING', 'FLOOR', 'ROOM', 'ZONE', 'VEHICLE', 'EXTERNAL'] as const;
export type LocationType = (typeof LOCATION_TYPES)[number];

export interface AssetReference {
  id: string;
  assetCode: string;
  name: string;
  category: AssetCategory;
  status: string;
  siteCode: string;
  locationType: LocationType;
  locationReference: string;
  custodianReference: string | null;
  /** The physical tag - an RFID, barcode or QR value. One tag identifies one asset. */
  externalReference: string | null;
  evidenceReference: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RegisterAssetReferenceRequest {
  assetCode: string;
  name: string;
  category: AssetCategory;
  siteCode: string;
  locationType: LocationType;
  locationReference: string;
  custodianReference?: string | null;
  externalReference?: string | null;
}

export type AssetChangeType = 'REGISTERED' | 'TAG_ASSIGNED' | 'MOVED' | 'CUSTODY_CHANGED' | 'EVIDENCE_LINKED';

export interface AssetHistoryEntry {
  id: string;
  assetId: string;
  changeType: AssetChangeType;
  fromValue: string | null;
  toValue: string | null;
  source: 'MANUAL' | 'READER';
  sourceReference: string | null;
  actorId: string;
  occurredAt: string;
}

const BASE = '/api/v1/assets';

export const assetVisibilityApi = {
  search: (siteCode: string, signal?: AbortSignal) =>
    apiClient.get<AssetReference[]>(BASE, { siteCode } as QueryParams, signal),
  register: (body: RegisterAssetReferenceRequest) => apiClient.post<AssetReference>(BASE, body),
  move: (assetId: string, locationType: LocationType, locationReference: string) =>
    apiClient.patch<AssetReference>(`${BASE}/${assetId}/location`, { locationType, locationReference }),
  custody: (assetId: string, custodianReference: string | null) =>
    apiClient.patch<AssetReference>(`${BASE}/${assetId}/custody`, { custodianReference }),
  tag: (assetId: string, tagId: string) =>
    apiClient.patch<AssetReference>(`${BASE}/${assetId}/tag`, { tagId }),
  history: (assetId: string, signal?: AbortSignal) =>
    apiClient.get<AssetHistoryEntry[]>(`${BASE}/${assetId}/history`, undefined, signal),
};

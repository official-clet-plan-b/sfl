import { apiClient } from 'shared/api/client';
import { PageResponse, QueryParams } from 'shared/api/types';
import {
  AcknowledgeTripRequest,
  AssignTripRequest,
  AssignmentPreviewParams,
  BindDriverPrincipalRequest,
  AuditChainVerificationResponse,
  AuditEventResponse,
  AuditSearchParams,
  CancelTripRequest,
  ChangeVehicleLifecycleRequest,
  CloseTripRequest,
  CommentResponse,
  ComplianceDocumentResponse,
  ComplianceSearchParams,
  CorrectOdometerRequest,
  CreateTripRequest,
  DashboardDrilldownRow,
  DashboardParams,
  DriverResponse,
  DriverSearchParams,
  EligibilityResponse,
  InboxSearchParams,
  EvidenceResponse,
  EvidenceSearchParams,
  ExportRequestResponse,
  GoLiveReadinessReport,
  HoldTripRequest,
  InboxMessageResponse,
  InspectionResponse,
  IntegrationHealthResponse,
  OperationsDashboardSnapshot,
  RaiseWorkflowItemRequest,
  ReadinessResponse,
  ReportLocationRequest,
  RecordInspectionRequest,
  RecordStandaloneInspectionRequest,
  RecordVehicleServiceRequest,
  RegisterComplianceDocumentRequest,
  RegisterDriverRequest,
  RegisterEvidenceRequest,
  RegisterVehicleRequest,
  ServiceHistoryResponse,
  ServiceRecordResponse,
  StartTripRequest,
  TripResponse,
  TripSearchParams,
  UpdateDriverRequest,
  UpdateVehicleRequest,
  VehicleLocationResponse,
  VehicleResponse,
  VehicleSearchParams,
  WorkflowHistoryResponse,
  WorkflowItemResponse,
  WorkflowSearchParams,
} from './dto';
import type { EvidenceSearch } from 'shared/components/EvidenceSelect';

/**
 * Typed client for the S166 Fleet & Vehicle Management API.
 *
 * Paths were taken from the controllers, not from the API inventory document - several inventory
 * entries do not match the implementation. The mismatches are listed in
 * `docs/fleet/S166_UI_Gap_Report.md`; nothing here calls an endpoint that does not exist.
 */

const BASE = '/api/v1/fleet';

const asQuery = (params: object | undefined): QueryParams | undefined =>
  params as QueryParams | undefined;

export const vehiclesApi = {
  search: (params: VehicleSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<PageResponse<VehicleResponse>>(`${BASE}/vehicles`, asQuery(params), signal),

  findById: (vehicleId: string, signal?: AbortSignal) =>
    apiClient.get<VehicleResponse>(`${BASE}/vehicles/${vehicleId}`, undefined, signal),

  register: (body: RegisterVehicleRequest) =>
    apiClient.post<VehicleResponse>(`${BASE}/vehicles`, body),

  update: (vehicleId: string, body: UpdateVehicleRequest) =>
    apiClient.patch<VehicleResponse>(`${BASE}/vehicles/${vehicleId}`, body),

  changeLifecycle: (vehicleId: string, body: ChangeVehicleLifecycleRequest) =>
    apiClient.patch<VehicleResponse>(`${BASE}/vehicles/${vehicleId}/lifecycle`, body),

  /**
   * The vehicle's readiness, on its own terms.
   *
   * The vehicle detail screen used to answer this by calling `trips/assignment-preview` with only a
   * `vehicleId` - the same policy, reached through a trip-shaped endpoint because nothing else
   * existed. This is that policy with a vehicle-shaped door.
   */
  readiness: (vehicleId: string, signal?: AbortSignal) =>
    apiClient.get<ReadinessResponse>(`${BASE}/vehicles/${vehicleId}/readiness`, undefined, signal),

  /**
   * The vehicle's movement history, newest first.
   *
   * A vendor projection: freshness is the reader's judgement to make from `recordedAt`, because how
   * stale is too stale depends on what is being asked.
   */
  movement: (vehicleId: string, size = 25, signal?: AbortSignal) =>
    apiClient.get<VehicleLocationResponse[]>(
      `${BASE}/vehicles/${vehicleId}/movement`,
      { size },
      signal,
    ),

  /**
   * Records a standalone periodic inspection - no trip involved.
   *
   * Before this endpoint existed, a vehicle with no open trip could not be inspected at all, which
   * blocked the periodic-inspection half of SRS-SFL-S166-01.
   */
  recordInspection: (vehicleId: string, body: RecordStandaloneInspectionRequest) =>
    apiClient.post<InspectionResponse>(`${BASE}/vehicles/${vehicleId}/inspections`, body),

  /**
   * Cross-fleet compliance search.
   *
   * The compliance screen used to fan out over the first fifty active vehicles in scope and say so
   * on the page. One query now, and correct for a fleet of any size.
   */
  searchComplianceDocuments: (params: ComplianceSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<ComplianceDocumentResponse[]>(
      `${BASE}/vehicles/compliance-documents`,
      asQuery(params),
      signal,
    ),

  complianceDocuments: (vehicleId: string, signal?: AbortSignal) =>
    apiClient.get<ComplianceDocumentResponse[]>(
      `${BASE}/vehicles/${vehicleId}/compliance-documents`,
      undefined,
      signal,
    ),

  registerComplianceDocument: (vehicleId: string, body: RegisterComplianceDocumentRequest) =>
    apiClient.post<ComplianceDocumentResponse>(
      `${BASE}/vehicles/${vehicleId}/compliance-documents`,
      body,
    ),

  serviceHistory: (vehicleId: string, signal?: AbortSignal) =>
    apiClient.get<ServiceHistoryResponse>(
      `${BASE}/vehicles/${vehicleId}/service-history`,
      undefined,
      signal,
    ),

  recordService: (vehicleId: string, body: RecordVehicleServiceRequest) =>
    apiClient.post<ServiceRecordResponse>(`${BASE}/vehicles/${vehicleId}/service-records`, body),

  correctOdometer: (vehicleId: string, body: CorrectOdometerRequest) =>
    apiClient.post<VehicleResponse>(`${BASE}/vehicles/${vehicleId}/odometer-corrections`, body, {
      idempotent: false,
    }),
};

export const driversApi = {
  search: (params: DriverSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<PageResponse<DriverResponse>>(`${BASE}/drivers`, asQuery(params), signal),

  findById: (driverId: string, signal?: AbortSignal) =>
    apiClient.get<DriverResponse>(`${BASE}/drivers/${driverId}`, undefined, signal),

  register: (body: RegisterDriverRequest) =>
    apiClient.post<DriverResponse>(`${BASE}/drivers`, body),

  update: (driverId: string, body: UpdateDriverRequest) =>
    apiClient.patch<DriverResponse>(`${BASE}/drivers/${driverId}`, body),

  bindPrincipal: (driverId: string, body: BindDriverPrincipalRequest) =>
    apiClient.patch<DriverResponse>(`${BASE}/drivers/${driverId}/principal`, body),

  /**
   * Eligibility, optionally against a vehicle category and a period end.
   *
   * Passing `until` is what catches a licence that is valid today but lapses mid-trip.
   */
  eligibility: (
    driverId: string,
    params: { vehicleCategory?: string; until?: string } = {},
    signal?: AbortSignal,
  ) =>
    apiClient.get<EligibilityResponse>(
      `${BASE}/drivers/${driverId}/eligibility`,
      asQuery(params),
      signal,
    ),
};

export const tripsApi = {
  search: (params: TripSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<PageResponse<TripResponse>>(`${BASE}/trips`, asQuery(params), signal),

  findById: (tripId: string, signal?: AbortSignal) =>
    apiClient.get<TripResponse>(`${BASE}/trips/${tripId}`, undefined, signal),

  create: (body: CreateTripRequest) => apiClient.post<TripResponse>(`${BASE}/trips`, body),

  assign: (tripId: string, body: AssignTripRequest) =>
    apiClient.patch<TripResponse>(`${BASE}/trips/${tripId}/assignment`, body),

  start: (tripId: string, body: StartTripRequest) =>
    apiClient.patch<TripResponse>(`${BASE}/trips/${tripId}/start`, body),

  /**
   * The assigned driver confirms or defers (SRS-SFL-S166-02).
   *
   * Refused unless the signed-in identity is bound to the driver on the trip - a driver may answer
   * for their own assignment and nobody else's, and a supervisor may not answer on their behalf.
   */
  acknowledge: (tripId: string, body: AcknowledgeTripRequest) =>
    apiClient.patch<TripResponse>(`${BASE}/trips/${tripId}/acknowledgement`, body),

  holdOrResume: (tripId: string, body: HoldTripRequest) =>
    apiClient.patch<TripResponse>(`${BASE}/trips/${tripId}/hold`, body),

  cancel: (tripId: string, body: CancelTripRequest) =>
    apiClient.patch<TripResponse>(`${BASE}/trips/${tripId}/cancel`, body),

  close: (tripId: string, body: CloseTripRequest) =>
    apiClient.patch<TripResponse>(`${BASE}/trips/${tripId}/closure`, body),

  inspections: (tripId: string, signal?: AbortSignal) =>
    apiClient.get<InspectionResponse[]>(`${BASE}/trips/${tripId}/inspections`, undefined, signal),

  recordInspection: (tripId: string, body: RecordInspectionRequest) =>
    apiClient.post<InspectionResponse>(`${BASE}/trips/${tripId}/inspections`, body),

  /**
   * The assigned driver reports the vehicle's current position, while the trip is in progress.
   *
   * There is no connected telematics vendor (S167, Phase 2) - this is how a live position reaches
   * the fleet office at all, through the same mobile-friendly web workflow every other field role
   * already uses. Refused outside `IN_PROGRESS`, and unless the signed-in identity is bound to the
   * driver on this trip.
   */
  reportLocation: (tripId: string, body: ReportLocationRequest) =>
    apiClient.post<VehicleLocationResponse>(`${BASE}/trips/${tripId}/location`, body),

  /**
   * The trip's vehicle's latest known position, if anything has reported one yet.
   *
   * `null` is the honest answer before the first report - not an error, and not distinguishable
   * from "the trip has no vehicle assigned" on this response alone.
   */
  latestLocation: (tripId: string, signal?: AbortSignal) =>
    apiClient.get<VehicleLocationResponse | null>(`${BASE}/trips/${tripId}/location`, undefined, signal),

  /**
   * Readiness preview before committing to an assignment.
   *
   * Uses the same policy and inputs the assignment itself will use, so the blockers shown here
   * cannot disagree with the ones that would reject the submission.
   */
  assignmentPreview: (params: AssignmentPreviewParams, signal?: AbortSignal) =>
    apiClient.get<ReadinessResponse>(`${BASE}/trips/assignment-preview`, asQuery(params), signal),
};

export const workflowApi = {
  search: (params: WorkflowSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<PageResponse<WorkflowItemResponse>>(
      `${BASE}/workflow-items`,
      asQuery(params),
      signal,
    ),

  findById: (itemId: string, signal?: AbortSignal) =>
    apiClient.get<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}`, undefined, signal),

  raise: (body: RaiseWorkflowItemRequest) =>
    apiClient.post<WorkflowItemResponse>(`${BASE}/workflow-items`, body),

  assign: (itemId: string, body: { assignee: string; reason?: string; expectedVersion?: number }) =>
    apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/assignment`, body),

  start: (itemId: string, body: { expectedVersion?: number } = {}) =>
    apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/progress`, body),

  holdOrResume: (
    itemId: string,
    body: { resume: boolean; reason?: string; expectedVersion?: number },
  ) => apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/hold`, body),

  escalate: (itemId: string, body: { reason: string; expectedVersion?: number }) =>
    apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/escalation`, body),

  cancel: (itemId: string, body: { reason: string; expectedVersion?: number }) =>
    apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/cancel`, body),

  close: (
    itemId: string,
    body: { closureReason: string; closureEvidenceId: string; expectedVersion?: number },
  ) => apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/closure`, body),

  reopen: (itemId: string, body: { reason: string; expectedVersion?: number }) =>
    apiClient.patch<WorkflowItemResponse>(`${BASE}/workflow-items/${itemId}/reopen`, body),

  comment: (itemId: string, body: { body: string }) =>
    apiClient.post<CommentResponse>(`${BASE}/workflow-items/${itemId}/comments`, body),

  history: (itemId: string, signal?: AbortSignal) =>
    apiClient.get<WorkflowHistoryResponse>(
      `${BASE}/workflow-items/${itemId}/transitions`,
      undefined,
      signal,
    ),
};

/**
 * The FTLMP evidence store, in the shape {@link EvidenceSelect} wants.
 *
 * <p>One store serves fleet, fuel and dispatch - a dispatch exception's closure evidence is written
 * into the same `EvidenceReference` table as a trip's, by `RecordedDispatchEvidenceAdapter`. So this
 * is the search function every FTLMP dialog passes to the picker, and dispatch importing it is the
 * same cross-module reuse it already does for `EVIDENCE_RETENTION_CLASSES` and `humanise`.
 *
 * <p>Declared at module level rather than inline at each call site because the picker treats it as a
 * stable reference; an arrow created during render would re-fetch on every keystroke.
 */
export const searchEvidenceChoices: EvidenceSearch = (
  relatedRecordType,
  relatedRecordId,
  signal,
) =>
  evidenceApi
    .search({ relatedRecordType, relatedRecordId }, signal)
    .then((found) =>
      (found ?? []).map((reference) => ({
        id: reference.id,
        fileName: reference.fileName,
        evidenceType: reference.evidenceType,
        legalHold: reference.legalHold,
      })),
    );

export const evidenceApi = {
  /**
   * Evidence filed against one record.
   *
   * Closes the gap the S166 register called the main usability cost in the whole dashboard: with no
   * search, every closure dialog asked an operator to paste a reference id copied from another tab.
   */
  search: (params: EvidenceSearchParams, signal?: AbortSignal) =>
    apiClient.get<EvidenceResponse[]>(`${BASE}/evidence`, asQuery(params), signal),

  findById: (evidenceId: string, signal?: AbortSignal) =>
    apiClient.get<EvidenceResponse>(`${BASE}/evidence/${evidenceId}`, undefined, signal),

  register: (body: RegisterEvidenceRequest) =>
    apiClient.post<EvidenceResponse>(`${BASE}/evidence`, body, { idempotent: false }),

  /** Records an access entry against the evidence record (SRS-SFL-S166-03 access audit). */
  recordAccess: (evidenceId: string) =>
    apiClient.post<EvidenceResponse>(`${BASE}/evidence/${evidenceId}/access`, undefined, {
      idempotent: false,
    }),

  requestExport: (evidenceId: string, body: { reason: string }) =>
    apiClient.post<ExportRequestResponse>(`${BASE}/evidence/${evidenceId}/export-requests`, body, {
      idempotent: false,
    }),

  decideExport: (exportRequestId: string, body: { approved: boolean; decisionReason: string }) =>
    apiClient.patch<ExportRequestResponse>(
      `${BASE}/evidence/export-requests/${exportRequestId}/decision`,
      body,
    ),

  export: (exportRequestId: string) =>
    apiClient.post<ExportRequestResponse>(
      `${BASE}/evidence/export-requests/${exportRequestId}/export`,
      undefined,
      { idempotent: false },
    ),
};

export const auditApi = {
  search: (params: AuditSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<AuditEventResponse[]>(`${BASE}/audit/records`, asQuery(params), signal),

  verifyChain: (signal?: AbortSignal) =>
    apiClient.get<AuditChainVerificationResponse>(
      `${BASE}/audit/chain/verification`,
      undefined,
      signal,
    ),
};

export const integrationsApi = {
  health: (signal?: AbortSignal) =>
    apiClient.get<IntegrationHealthResponse>(`${BASE}/integrations/health`, undefined, signal),

  /**
   * Searches the inbound inbox.
   *
   * Replay takes a message identifier, and the health projection only ever carried a handful of
   * recent messages - so dead-letter replay was a documented capability that could not be reached
   * from this dashboard at all.
   */
  messages: (params: InboxSearchParams = {}, signal?: AbortSignal) =>
    apiClient.get<InboxMessageResponse[]>(`${BASE}/integrations/messages`, asQuery(params), signal),

  replay: (messageId: string) =>
    apiClient.post<InboxMessageResponse>(
      `${BASE}/integrations/messages/${messageId}/replay`,
      undefined,
      { idempotent: false },
    ),
};

export const dashboardApi = {
  operations: (params: DashboardParams = {}, signal?: AbortSignal) =>
    apiClient.get<OperationsDashboardSnapshot>(
      `${BASE}/dashboards/operations`,
      asQuery(params),
      signal,
    ),

  drilldown: (indicator: string, params: DashboardParams = {}, signal?: AbortSignal) =>
    apiClient.get<DashboardDrilldownRow[]>(
      `${BASE}/dashboards/operations/drilldowns/${indicator}`,
      asQuery(params),
      signal,
    ),

  goLiveReadiness: (params: { siteCode?: string } = {}, signal?: AbortSignal) =>
    apiClient.get<GoLiveReadinessReport>(
      `${BASE}/reports/go-live-readiness`,
      asQuery(params),
      signal,
    ),
};

/** Indicator keys the drilldown endpoint recognises (service-side `switch`). */
export const DRILLDOWN_INDICATORS = {
  EXPIRED_COMPLIANCE: 'EXPIRED_COMPLIANCE',
  SERVICE_DUE: 'SERVICE_DUE',
  READINESS_BLOCKERS: 'READINESS_BLOCKERS',
  ASSIGNMENT_CONFLICTS: 'ASSIGNMENT_CONFLICTS',
} as const;

export type DrilldownIndicator = keyof typeof DRILLDOWN_INDICATORS;

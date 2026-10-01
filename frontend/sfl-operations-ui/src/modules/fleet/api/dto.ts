import {
  BlockerSeverity,
  ComplianceDocumentStatus,
  ComplianceDocumentType,
  DefectSeverity,
  DriverEligibilityStatus,
  DriverLifecycleStatus,
  EvidenceExportStatus,
  EvidenceRetentionClass,
  FleetWorkflowStatus,
  FleetWorkflowType,
  InspectionResult,
  InspectionStatus,
  InspectionType,
  IntegrationMessageStatus,
  LicenceClass,
  OperatingMode,
  ReadinessBlockerCode,
  ReadinessStatus,
  ServiceOutcome,
  ServiceType,
  VehicleAvailabilityStatus,
  VehicleCategory,
  VehicleLifecycleStatus,
  VehicleServiceStatus,
  WorkflowAction,
  WorkflowPriority,
  WorkflowSeverity,
} from './enums';

/**
 * Request and response shapes for the Fleet & Logistics service.
 *
 * Each interface mirrors a Java record in `fleet.api.request` / `fleet.api.response` field for
 * field. Optional members here mean the Java field is nullable, not "the UI may omit it".
 */

// --- Vehicles -----------------------------------------------------------------------------

export interface VehicleResponse {
  id: string;
  registrationNumber: string;
  vin: string | null;
  /** `true` when the caller lacks `FLEET_VEHICLE_SENSITIVE_READ` - never present a mask as the VIN. */
  vinMasked: boolean;
  make: string;
  model: string;
  manufactureYear: number;
  category: VehicleCategory;
  capacity: number;
  siteCode: string;
  responsibleUnit: string;
  operationalOwner: string;
  acquisitionReference: string | null;
  lifecycleStatus: VehicleLifecycleStatus;
  serviceStatus: VehicleServiceStatus;
  availabilityStatus: VehicleAvailabilityStatus;
  odometerValue: number;
  odometerUnit: string;
  odometerSource: string;
  odometerRecordedAt: string | null;
  emergencyOnly: boolean;
  allowedOperatingModes: OperatingMode[] | null;
  currentTripId: string | null;
  createdBy: string | null;
  createdAt: string;
  lastModifiedBy: string | null;
  lastModifiedAt: string | null;
  version: number;
  sourceChannel: string | null;
  auditCorrelationId: string | null;
}

export interface RegisterVehicleRequest {
  registrationNumber: string;
  vin?: string | null;
  make: string;
  model: string;
  manufactureYear: number;
  category: VehicleCategory;
  capacity: number;
  siteCode: string;
  responsibleUnit: string;
  operationalOwner: string;
  acquisitionReference?: string | null;
  initialOdometer: number;
  emergencyOnly?: boolean;
  allowedOperatingModes?: OperatingMode[];
}

export interface UpdateVehicleRequest {
  vin?: string | null;
  make: string;
  model: string;
  manufactureYear: number;
  category: VehicleCategory;
  capacity: number;
  responsibleUnit: string;
  operationalOwner: string;
  acquisitionReference?: string | null;
  emergencyOnly?: boolean;
  allowedOperatingModes?: OperatingMode[];
  expectedVersion?: number | null;
}

export interface ChangeVehicleLifecycleRequest {
  targetStatus: VehicleLifecycleStatus;
  reason: string;
  expectedVersion?: number | null;
}

export interface CorrectOdometerRequest {
  correctedReading: number;
  reason: string;
  evidenceId: string;
  expectedVersion?: number | null;
}

export interface VehicleSearchParams {
  siteCode?: string;
  status?: VehicleLifecycleStatus;
  serviceStatus?: VehicleServiceStatus;
  availability?: VehicleAvailabilityStatus;
  category?: VehicleCategory;
  responsibleUnit?: string;
  registrationNumber?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// --- Compliance and service ---------------------------------------------------------------

export interface ComplianceDocumentResponse {
  id: string;
  vehicleId: string;
  siteCode: string;
  documentType: ComplianceDocumentType;
  mandatory: boolean;
  documentReference: string;
  issuingAuthority: string;
  issuedOn: string;
  expiresOn: string;
  daysUntilExpiry: number;
  status: ComplianceDocumentStatus;
  evidenceId: string | null;
  retentionClass: EvidenceRetentionClass;
  createdAt: string;
  createdBy: string | null;
  version: number;
}

export interface RegisterComplianceDocumentRequest {
  documentType: ComplianceDocumentType;
  documentReference: string;
  issuingAuthority: string;
  issuedOn: string;
  expiresOn: string;
  evidenceId?: string | null;
  retentionClass: EvidenceRetentionClass;
}

export interface ServiceRecordResponse {
  id: string;
  vehicleId: string;
  siteCode: string;
  serviceType: ServiceType;
  performedOn: string;
  odometerAtService: number;
  nextDueOn: string | null;
  nextDueOdometer: number | null;
  providerReference: string | null;
  workSummary: string;
  outcome: ServiceOutcome;
  evidenceId: string | null;
  createdAt: string;
  createdBy: string | null;
  version: number;
}

export interface ServiceHistoryResponse {
  vehicleId: string;
  currentServiceStatus: VehicleServiceStatus;
  nextDueOn: string | null;
  nextDueOdometer: number | null;
  currentOdometer: number;
  history: ServiceRecordResponse[];
}

export interface RecordVehicleServiceRequest {
  serviceType: ServiceType;
  performedOn: string;
  odometerAtService: number;
  nextDueOn?: string | null;
  nextDueOdometer?: number | null;
  providerReference?: string | null;
  workSummary: string;
  outcome: ServiceOutcome;
  evidenceId?: string | null;
}

// --- Drivers ------------------------------------------------------------------------------

export interface DriverResponse {
  id: string;
  staffReference: string;
  displayName: string;
  licenceNumber: string | null;
  /** `true` when the caller lacks `FLEET_DRIVER_SENSITIVE_READ`. */
  licenceNumberMasked: boolean;
  licenceClass: LicenceClass;
  licenceExpiresOn: string;
  daysUntilLicenceExpiry: number;
  medicalClearanceExpiresOn: string | null;
  siteCode: string;
  responsibleUnit: string;
  lifecycleStatus: DriverLifecycleStatus;
  eligibilityStatus: DriverEligibilityStatus;
  suspensionReason: string | null;
  principalLinked: boolean;
  createdBy: string | null;
  createdAt: string;
  lastModifiedBy: string | null;
  lastModifiedAt: string | null;
  version: number;
}

export interface RegisterDriverRequest {
  staffReference: string;
  displayName: string;
  licenceNumber: string;
  licenceClass: LicenceClass;
  licenceExpiresOn: string;
  medicalClearanceExpiresOn?: string | null;
  siteCode: string;
  responsibleUnit: string;
  principalSubject?: string | null;
}

export interface UpdateDriverRequest {
  displayName: string;
  licenceNumber: string;
  licenceClass: LicenceClass;
  licenceExpiresOn: string;
  medicalClearanceExpiresOn?: string | null;
  responsibleUnit: string;
  targetLifecycleStatus?: DriverLifecycleStatus | null;
  lifecycleReason?: string | null;
  expectedVersion?: number | null;
}

export interface BindDriverPrincipalRequest {
  principalSubject?: string | null;
  expectedVersion?: number | null;
}

export interface DriverSearchParams {
  siteCode?: string;
  status?: DriverLifecycleStatus;
  eligibility?: DriverEligibilityStatus;
  responsibleUnit?: string;
  licenceExpiringBefore?: string;
  search?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// --- Readiness and eligibility --------------------------------------------------------------

export interface BlockerResponse {
  code: ReadinessBlockerCode;
  message: string;
  severity: BlockerSeverity;
  context: Record<string, unknown> | null;
}

export interface ReadinessResponse {
  vehicleId: string;
  driverId: string | null;
  status: ReadinessStatus;
  permitsAssignment: boolean;
  blockers: BlockerResponse[];
  assessedAt: string;
  periodStart: string | null;
  periodEnd: string | null;
  operatingMode: OperatingMode | null;
}

export interface EligibilityResponse {
  driverId: string;
  status: DriverEligibilityStatus;
  permitsAssignment: boolean;
  blockers: BlockerResponse[];
  assessedAt: string;
  assessedForCategory: VehicleCategory | null;
}

// --- Trips and inspections -------------------------------------------------------------------

export interface TripResponse {
  id: string;
  tripNumber: string;
  vehicleId: string | null;
  driverId: string | null;
  siteCode: string;
  purpose: string;
  origin: string;
  destination: string;
  operatingMode: OperatingMode;
  plannedStart: string;
  plannedEnd: string;
  actualStart: string | null;
  actualEnd: string | null;
  status: TripStatusValue;
  holdReason: string | null;
  cancellationReason: string | null;
  closureReason: string | null;
  closureEvidenceId: string | null;
  /**
   * Who ended the journey. Null while it is open, and for trips closed before it was recorded.
   *
   * Compared against the assigned driver so a screen can tell a driver's own closure from one an
   * officer made on their behalf - the two used to be indistinguishable on the record, and they are
   * different facts about where the end odometer came from.
   */
  closedBy: string | null;
  startOdometer: number | null;
  endOdometer: number | null;
  distanceCovered: number | null;
  acknowledgementState: TripAcknowledgementState;
  acknowledgementReason: string | null;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
  createdBy: string | null;
  createdAt: string;
  lastModifiedBy: string | null;
  lastModifiedAt: string | null;
  version: number;
}

export type TripStatusValue =
  | 'PLANNED'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'ON_HOLD'
  | 'COMPLETED'
  | 'CANCELLED';

/**
 * The assigned driver's answer, which is a separate axis from `status`.
 *
 * A confirmed trip and an unanswered one are both `ASSIGNED` - the lifecycle has not moved. Reading
 * this off `status` is why it is not in that union: a dispatcher needs to see both facts at once.
 */
export type TripAcknowledgementState = 'PENDING' | 'CONFIRMED' | 'DEFERRED';

export interface AcknowledgeTripRequest {
  /** `PENDING` is rejected by the service: it is the starting state, not an answer. */
  answer: Exclude<TripAcknowledgementState, 'PENDING'>;
  /** Required when deferring, ignored when confirming. */
  reason?: string | null;
  expectedVersion?: number | null;
}

export interface CreateTripRequest {
  vehicleId?: string | null;
  driverId?: string | null;
  siteCode: string;
  purpose: string;
  origin: string;
  destination: string;
  operatingMode: OperatingMode;
  plannedStart: string;
  plannedEnd: string;
}

export interface AssignTripRequest {
  vehicleId: string;
  driverId: string;
  reason?: string | null;
  expectedVersion?: number | null;
}

export interface StartTripRequest {
  startOdometer: number;
  expectedVersion?: number | null;
}

export interface ReportLocationRequest {
  latitude: number;
  longitude: number;
}

export interface HoldTripRequest {
  action: 'HOLD' | 'RESUME';
  reason?: string | null;
  expectedVersion?: number | null;
}

export interface CancelTripRequest {
  reason: string;
  expectedVersion?: number | null;
}

/** Closure reason *and* evidence are both mandatory (`FLEET_CLOSURE_EVIDENCE_MISSING`). */
export interface CloseTripRequest {
  closureReason: string;
  closureEvidenceId: string;
  endOdometer: number;
  expectedVersion?: number | null;
}

export interface FindingRequest {
  checkCode: string;
  description: string;
  severity: DefectSeverity;
}

export interface RecordInspectionRequest {
  inspectionType: InspectionType;
  odometerReading: number;
  evidenceId?: string | null;
  findings?: FindingRequest[];
  notes?: string | null;
}

export interface FindingResponse {
  checkCode: string;
  description: string;
  severity: string;
  resolved: boolean;
  resolutionReference: string | null;
}

export interface InspectionResponse {
  id: string;
  vehicleId: string;
  tripId: string | null;
  siteCode: string;
  inspectionType: InspectionType;
  status: InspectionStatus;
  result: InspectionResult;
  /** `false` blocks the vehicle from use until the defect is cleared. */
  permitsUse: boolean;
  hasOpenCriticalDefect: boolean;
  performedBy: string | null;
  performedAt: string;
  odometerReading: number;
  evidenceId: string | null;
  findings: FindingResponse[];
  notes: string | null;
  version: number;
}

export interface TripSearchParams {
  siteCode?: string;
  status?: TripStatusValue;
  vehicleId?: string;
  driverId?: string;
  operatingMode?: OperatingMode;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AssignmentPreviewParams {
  vehicleId: string;
  driverId?: string;
  from?: string;
  to?: string;
  operatingMode?: OperatingMode;
}

// --- Workflow ---------------------------------------------------------------------------------

export interface WorkflowItemResponse {
  id: string;
  workflowNumber: string;
  workflowType: FleetWorkflowType;
  relatedRecordType: string | null;
  relatedRecordId: string | null;
  siteCode: string;
  title: string;
  description: string;
  priority: WorkflowPriority;
  severity: WorkflowSeverity;
  operatingMode: OperatingMode;
  status: FleetWorkflowStatus;
  assignee: string | null;
  slaDueAt: string | null;
  responseDueAt: string | null;
  slaBreached: boolean;
  escalationLevel: number;
  firstResponseAt: string | null;
  holdReason: string | null;
  closureReason: string | null;
  closureEvidenceId: string | null;
  closedAt: string | null;
  closedBy: string | null;
  createdBy: string | null;
  createdAt: string;
  version: number;
}

export interface RaiseWorkflowItemRequest {
  workflowType: FleetWorkflowType;
  relatedRecordType?: string | null;
  relatedRecordId?: string | null;
  siteCode: string;
  title: string;
  description: string;
  priority: WorkflowPriority;
  severity: WorkflowSeverity;
  operatingMode?: OperatingMode | null;
  assignee?: string | null;
}

export interface TransitionResponse {
  id: string;
  sequence: number;
  fromStatus: FleetWorkflowStatus | null;
  toStatus: FleetWorkflowStatus;
  action: WorkflowAction;
  actorId: string | null;
  occurredAt: string;
  reason: string | null;
  correlationId: string | null;
}

export interface CommentResponse {
  id: string;
  author: string | null;
  body: string;
  occurredAt: string;
  correlationId: string | null;
}

export interface WorkflowHistoryResponse {
  workflowItemId: string;
  transitions: TransitionResponse[];
  comments: CommentResponse[];
}

export interface WorkflowSearchParams {
  siteCode?: string;
  status?: FleetWorkflowStatus;
  type?: FleetWorkflowType;
  priority?: WorkflowPriority;
  severity?: WorkflowSeverity;
  operatingMode?: OperatingMode;
  assignee?: string;
  overdueOnly?: boolean;
  escalatedOnly?: boolean;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// --- Evidence and audit ------------------------------------------------------------------------

export interface EvidenceResponse {
  id: string;
  siteCode: string;
  relatedRecordType: string;
  relatedRecordId: string;
  evidenceType: string;
  fileName: string;
  contentType: string;
  storageReference: string;
  sha256Hash: string;
  retentionClass: EvidenceRetentionClass;
  retentionExpiresAt: string | null;
  legalHold: boolean;
  createdBy: string | null;
  createdAt: string;
  lastModifiedBy: string | null;
  lastModifiedAt: string | null;
  version: number;
  sourceChannel: string | null;
  auditCorrelationId: string | null;
}

/**
 * Both fields are required by the service - there is no "all evidence" read.
 *
 * That is a deliberate constraint rather than a missing feature: evidence is only ever meaningful
 * against the thing it evidences, and a dashboard-wide evidence list would be a browsable index of
 * every incident at every site.
 */
export interface EvidenceSearchParams {
  relatedRecordType: string;
  relatedRecordId: string;
}

export interface RegisterEvidenceRequest {
  siteCode: string;
  relatedRecordType: string;
  relatedRecordId: string;
  evidenceType: string;
  fileName: string;
  contentType: string;
  storageReference: string;
  sha256Hash: string;
  retentionClass: EvidenceRetentionClass;
  retentionExpiresAt?: string | null;
}

export interface ExportRequestResponse {
  id: string;
  evidenceId: string;
  siteCode: string;
  reason: string;
  status: EvidenceExportStatus;
  requestedBy: string | null;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  exportedBy: string | null;
  exportedAt: string | null;
  version: number;
}

export interface AuditChainVerificationResponse {
  intact: boolean;
  recordsChecked: number;
  firstDivergentSequence: number | null;
  expectedValue: string | null;
  actualValue: string | null;
  reason: string | null;
  headHash: string | null;
}

export interface AuditEventResponse {
  id?: string;
  sequence?: number;
  actorId?: string | null;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  siteCode?: string;
  occurredAt?: string;
  correlationId?: string | null;
  [key: string]: unknown;
}

export interface AuditSearchParams {
  resourceType?: string;
  resourceId?: string;
  actorId?: string;
  action?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

// --- Integrations -------------------------------------------------------------------------------

export interface InboxMessageResponse {
  id: string;
  sourceSystem: string;
  idempotencyKey: string | null;
  eventType: string | null;
  siteCode: string | null;
  correlationId: string | null;
  occurredAt: string | null;
  payloadHash: string | null;
  status: IntegrationMessageStatus;
  attempts: number;
  failureReason: string | null;
  receivedAt: string;
  processedAt: string | null;
}

export interface IntegrationMessageSummary {
  [key: string]: unknown;
}

export interface IntegrationHealthResponse {
  checkedAt: string;
  processedMessages: number;
  rejectedMessages: number;
  deadLetterMessages: number;
  recentMessages: IntegrationMessageSummary[];
}

// --- Dashboard -----------------------------------------------------------------------------------

export interface DashboardIndicators {
  vehiclesAvailable: number;
  expiredCompliance: number;
  serviceDue: number;
  assignmentConflicts: number;
  readinessBlockers: number;
  openWorkflowItems: number;
  escalatedWorkflowItems: number;
  integrationDeadLetters: number;
}

export interface DashboardReconciliation {
  vehicles: number;
  complianceDocuments: number;
  trips: number;
  workflowItems: number;
  latestServiceRecords: number;
  recentVehicleLocations: number;
}

export interface OperationsDashboardSnapshot {
  id: string;
  generatedAt: string;
  scopeKey: string;
  siteCode: string | null;
  stale: boolean;
  warnings: string[];
  indicators: DashboardIndicators;
  reconciliation: DashboardReconciliation;
  snapshotAsOf: string;
  freshestSourceAt: string;
}

export interface DashboardDrilldownRow {
  resourceType: string;
  resourceId: string;
  siteCode: string;
  summary: string;
}

export interface GoLiveReadinessReport {
  id: string;
  generatedAt: string;
  ready: boolean;
  snapshot: OperationsDashboardSnapshot;
  blockers: string[];
}

export interface DashboardParams {
  siteCode?: string;
  status?: FleetWorkflowStatus;
  priority?: WorkflowPriority;
  owner?: string;
  operatingMode?: OperatingMode;
  from?: string;
  to?: string;
  requireFresh?: boolean;
}

/* ------------------------------------------------- endpoints added in the gap-closure round */

/**
 * `VehicleLocationResponse` - one movement snapshot from a telematics provider.
 *
 * A projection, not a source of truth: SFL records what a vendor reported and when. `recordedAt` is
 * what freshness is judged from, and the screen shows it rather than deciding on the reader's behalf
 * how stale is too stale.
 */
export interface VehicleLocationResponse {
  id: string;
  vehicleId: string;
  siteCode: string;
  latitude: number | null;
  longitude: number | null;
  odometerValue: number | null;
  recordedAt: string;
  sourceSystem: string | null;
  integrationMessageId: string | null;
  correlationId: string | null;
}

/**
 * A standalone periodic inspection - the same shape as the trip one, minus the trip.
 *
 * `findings` is required and non-empty on the service side: an inspection with nothing recorded is
 * an assertion nobody can audit.
 */
export interface RecordStandaloneInspectionRequest {
  inspectionType: InspectionType;
  odometerReading: number;
  evidenceId?: string | null;
  findings: FindingRequest[];
  notes?: string | null;
}

/** Filters the cross-fleet compliance search accepts. */
export interface ComplianceSearchParams {
  documentType?: ComplianceDocumentType | '';
  status?: ComplianceDocumentStatus | '';
  /** Documents expiring on or before this date. An ISO date, not an instant. */
  expiringBefore?: string;
  size?: number;
}

/** Filters the inbound inbox search accepts. */
export interface InboxSearchParams {
  sourceSystem?: string;
  status?: IntegrationMessageStatus | '';
  eventType?: string;
  size?: number;
}

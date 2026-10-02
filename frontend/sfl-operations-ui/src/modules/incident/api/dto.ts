import type { PageResponse, QueryParams } from 'shared/api/types';

export type IncidentStatus = 'TRIAGE' | 'INVESTIGATING' | 'CLOSED';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | 'EMERGENCY';
export type Likelihood = 'RARE' | 'UNLIKELY' | 'POSSIBLE' | 'LIKELY' | 'ALMOST_CERTAIN';
export type Impact = 'NEGLIGIBLE' | 'MINOR' | 'MODERATE' | 'MAJOR' | 'CATASTROPHIC';
export type IncidentSource = 'REPORTED' | 'HSE' | 'CCTV_SEED' | 'ACCESS_SEED' | 'INTRUSION_SEED' | 'FIRE_SEED';
export type RetentionClass = 'STANDARD' | 'EXTENDED' | 'PERMANENT';
export type CapaStatus = 'OPEN' | 'IN_PROGRESS' | 'VERIFIED' | 'CANCELLED';

export interface RecordMetadata {
  createdBy: string;
  createdAt: string;
  lastModifiedBy: string;
  lastModifiedAt: string;
  version: number;
  sourceChannel: string;
  correlationId: string | null;
}

export interface RiskRating { likelihood: Likelihood; impact: Impact; score?: number; band?: string }

export interface SecurityIncident {
  id: string;
  siteCode: string;
  source: IncidentSource;
  reference: string;
  anonymous: boolean;
  reporterId: string | null;
  reporterContact: string | null;
  description: string;
  nearMiss: boolean;
  status: IncidentStatus;
  severity: Severity | null;
  riskRating: RiskRating | null;
  emergencyEscalated: boolean;
  investigatorId: string | null;
  investigationNotes: string | null;
  reportable: boolean;
  reportabilityNotes: string | null;
  closureNotes: string | null;
  closedAt: string | null;
  /** Phase 2 S165-04: the risk assessment the incident happened under, if recorded. */
  riskAssessmentId: string | null;
  /** The activity under way, as the reporter or investigator recorded it. */
  activityType: string | null;
  metadata: RecordMetadata;
}

export interface CorrectiveAction {
  id: string;
  incidentId: string;
  siteCode: string;
  description: string;
  ownerId: string;
  dueDate: string;
  mandatory: boolean;
  status: CapaStatus;
  verificationNotes: string | null;
  createdBy: string;
  createdAt: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
}

export interface IncidentEvidence {
  id: string;
  incidentId: string;
  siteCode: string;
  fileReference: string;
  fileName: string | null;
  mediaType: string | null;
  sizeBytes: number | null;
  contentHash: string;
  retentionClass: RetentionClass;
  notes: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** Corrective-action counts; `ageing` is open actions by days since raised: 0-30, 31-60, 61-90, over 90. */
export interface CapaCounts { open: number; overdue: number; verified: number; cancelled: number; ageing: number[] }
export interface IncidentDashboard { byStatus: Partial<Record<IncidentStatus, number>>; bySeverity: Partial<Record<Severity, number>> }
export interface IncidentSearchParams extends QueryParams { siteCode?: string; status?: IncidentStatus; severity?: Severity; page?: number; size?: number; sort?: string }
export type IncidentPage = PageResponse<SecurityIncident>;

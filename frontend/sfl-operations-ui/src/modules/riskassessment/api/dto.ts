import type { PageResponse } from 'shared/api/types';
import type {
  ControlType,
  CurrencyReason,
  HazardType,
  Likelihood,
  ReviewFlagStatus,
  RiskLevel,
  Severity,
  Standing,
  StandingFilter,
  VersionStatus,
} from './enums';

/**
 * S165 wire types, read off the running service (`/v3/api-docs` and live responses), not inferred.
 * Instants arrive as ISO-8601 text. Fields the service computes - levels, scores, standing - are
 * typed here so a screen never has to work them out.
 */

export interface RecordMetadata {
  createdBy: string;
  createdAt: string;
  lastModifiedBy: string;
  lastModifiedAt: string;
  version: number;
  sourceChannel: string;
  correlationId: string | null;
}

export interface ControlMeasure {
  controlType: ControlType;
  description: string;
}

/** A hazard as stored - what an edit sends back. */
export interface Hazard {
  hazardType: HazardType;
  description: string;
  whoAtRisk: string | null;
  inherentRisk: { likelihood: Likelihood; severity: Severity };
  residualRisk: { likelihood: Likelihood; severity: Severity };
  controls: ControlMeasure[];
}

/** A hazard as the service scores it - `VersionView.HazardView`. */
export interface HazardView {
  number: number;
  hazardType: HazardType;
  description: string;
  whoAtRisk: string | null;
  inherentLikelihood: Likelihood;
  inherentSeverity: Severity;
  inherentScore: number;
  inherentLevel: RiskLevel;
  residualLikelihood: Likelihood;
  residualSeverity: Severity;
  residualScore: number;
  residualLevel: RiskLevel;
  controls: ControlMeasure[];
}

export interface AssessmentVersion {
  id: string;
  assessmentId: string;
  siteCode: string;
  versionNumber: number;
  status: VersionStatus;
  content: { title: string; summary: string | null; hazards: Hazard[] };
  authorId: string;
  authorName: string | null;
  reviewIntervalDays: number | null;
  reviewDueAt: string | null;
  reviewReminderSentAt: string | null;
  reviewLapsedAt: string | null;
  publishedAt: string | null;
  publishedBy: string | null;
  signedOffBy: string | null;
  signedOffByName: string | null;
  signedOffAt: string | null;
  supersededAt: string | null;
  metadata: RecordMetadata;
}

export interface VersionView {
  version: AssessmentVersion;
  riskLevel: RiskLevel | null;
  residualScore: number;
  hazards: HazardView[];
  current: boolean;
  currencyReason: CurrencyReason | null;
}

export interface AssessmentSummary {
  id: string;
  siteCode: string;
  reference: string;
  title: string;
  activityType: string | null;
  locationCode: string | null;
  currentVersion: number | null;
  draftVersion: number | null;
  latestVersion: number;
  riskLevel: RiskLevel | null;
  reviewDueAt: string | null;
  authorId: string | null;
  signedOffBy: string | null;
  standing: Standing;
  currencyReason: CurrencyReason | null;
  recordVersion: number;
  lastModifiedAt: string;
}

export interface SignOff {
  id: string;
  assessmentId: string;
  versionId: string;
  versionNumber: number;
  siteCode: string;
  reviewerId: string;
  reviewerName: string | null;
  signedOffAt: string;
  notes: string | null;
  previousReviewDueAt: string | null;
  reviewDueAt: string;
  independent: boolean;
}

export interface ReviewFlag {
  id: string;
  assessmentId: string;
  assessmentReference: string;
  versionNumber: number;
  siteCode: string;
  trigger: 'INCIDENT';
  sourceId: string;
  sourceReference: string | null;
  reason: string | null;
  status: ReviewFlagStatus;
  raisedAt: string;
  deferredUntil: string | null;
  deferralReason: string | null;
  deferredBy: string | null;
  deferralCount: number;
  clearedAt: string | null;
  clearedBy: string | null;
  findings: string | null;
  metadata: RecordMetadata;
}

export interface AssessmentDetail {
  assessment: AssessmentSummary;
  /** Newest first, superseded versions included. */
  versions: VersionView[];
  signOffs: SignOff[];
  reviewFlags: ReviewFlag[];
}

export interface SignOffResult {
  assessment: AssessmentSummary;
  signOff: SignOff;
}

export interface LinkCheck {
  assessmentId: string;
  found: boolean;
  reference: string | null;
  version: number | null;
  activityType: string | null;
  locationCode: string | null;
  riskLevel: RiskLevel | null;
  reviewDueAt: string | null;
  verdict: { current: boolean; reason: CurrencyReason | null };
  linkable: boolean;
}

export interface HazardFrequency {
  hazardType: HazardType;
  occurrences: number;
  assessments: number;
  highestResidual: RiskLevel;
}

export interface ObservationSource {
  sourceSystem: string;
  occurrences: number;
  firstSeenAt: string;
  lastSeenAt: string;
  lastSourceReference: string | null;
}

export interface CoverageLine {
  activityType: string;
  covered: boolean;
  occurrences: number;
  sources: ObservationSource[];
  assessments: AssessmentSummary[];
}

export interface CoverageReport {
  siteCode: string;
  observedActivityTypes: number;
  covered: number;
  gaps: CoverageLine[];
  coveredLines: CoverageLine[];
}

export interface RiskDashboard {
  siteCode: string;
  totalAssessments: number;
  byStanding: Record<Standing, number>;
  currentByRiskLevel: Record<RiskLevel, number>;
  dueSoon: number;
  openDrafts: number;
  openReviewFlags: number;
  deferredReviewFlags: number;
  coverageGaps: number;
  topHazards: HazardFrequency[];
}

export interface AssessmentTemplate {
  id: string;
  name: string;
  activityType: string | null;
  description: string | null;
  active: boolean;
  hazards: Hazard[];
  metadata: RecordMetadata;
}

export interface ReviewInterval {
  riskLevel: RiskLevel;
  intervalDays: number;
  reminderLeadDays: number;
  metadata: RecordMetadata;
}

export type AssessmentPage = PageResponse<AssessmentSummary>;
export type ReviewFlagPage = PageResponse<ReviewFlag>;

// ---- requests ------------------------------------------------------------------------------------

export interface HazardInput {
  hazardType: HazardType;
  description: string;
  whoAtRisk?: string;
  inherentLikelihood: Likelihood;
  inherentSeverity: Severity;
  residualLikelihood: Likelihood;
  residualSeverity: Severity;
  controls: ControlMeasure[];
}

export interface CreateAssessmentRequest {
  siteCode: string;
  activityType?: string;
  locationCode?: string;
  title?: string;
  summary?: string;
  hazards?: HazardInput[];
  templateId?: string;
}

export interface DraftRequest {
  title: string;
  summary?: string;
  hazards: HazardInput[];
  expectedVersion: number;
}

export interface AssessmentSearchParams {
  siteCode: string;
  activityType?: string;
  riskLevel?: RiskLevel;
  standing?: StandingFilter;
  q?: string;
  page: number;
  size: number;
  sort?: string;
}

export interface TemplateRequest {
  name: string;
  activityType?: string;
  description?: string;
  active?: boolean;
  hazards: HazardInput[];
  expectedVersion?: number;
}

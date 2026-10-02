import type { PageResponse, QueryParams } from 'shared/api/types';
import type {
  CapaStatus,
  ComplianceStanding,
  DrillModule,
  DrillStatus,
  DrillType,
  ExpectationOutcome,
  GapFollowUp,
} from './enums';

/** S175 wire shapes - the service's records as Jackson writes them. Instants are ISO-8601 strings. */

export interface RecordMetadata {
  createdBy: string;
  createdAt: string;
  lastModifiedBy: string;
  lastModifiedAt: string;
  version: number;
  sourceChannel: string;
  correlationId: string | null;
}

export interface ModuleExpectation {
  module: DrillModule;
  expectation: string;
  successCriterion: string | null;
  outcome: ExpectationOutcome | null;
  outcomeNotes: string | null;
}

export interface DrillPlan {
  drillType: DrillType;
  title: string;
  scenario: string | null;
  expectedParticipants: string | null;
  assemblyZone: string | null;
  scheduledFor: string | null;
  notificationTemplateId: string | null;
  audienceGroupIds: string[];
  recipientZoneIds: string[];
  expectations: ModuleExpectation[];
}

export interface Drill {
  id: string;
  siteCode: string;
  reference: string;
  plan: DrillPlan;
  status: DrillStatus;
  statusReason: string | null;
  metadata: RecordMetadata;
}

export interface DrillExecution {
  drillId: string;
  siteCode: string;
  startedAt: string;
  startedBy: string;
  notificationActivationId: string | null;
  notificationNumber: string | null;
  notificationSentAt: string | null;
  musterSessionId: string;
  baselineTakenAt: string;
  baselineAccessDataAsOf: string | null;
  baselineStale: boolean;
  baselineCount: number;
  rollCallClosedAt: string | null;
  rollCallClosedBy: string | null;
  checkedInCount: number | null;
  gapCount: number | null;
  notificationToMusterSeconds: number | null;
}

export interface RollCallGap {
  id: string;
  drillId: string;
  siteCode: string;
  personRef: string;
  displayName: string | null;
  source: string;
  followUp: GapFollowUp | null;
  followUpNotes: string | null;
  followedUpBy: string | null;
  followedUpAt: string | null;
}

export interface DrillReview {
  drillId: string;
  siteCode: string;
  summary: string | null;
  timingNotes: string | null;
  recordedBy: string | null;
  recordedAt: string | null;
  submittedBy: string | null;
  submittedAt: string | null;
}

export interface DrillFinding {
  id: string;
  drillId: string;
  siteCode: string;
  sequenceNo: number;
  description: string;
  noActionJustification: string | null;
  recordedBy: string;
  recordedAt: string;
}

export interface DrillCorrectiveAction {
  id: string;
  drillId: string;
  findingId: string;
  siteCode: string;
  description: string;
  ownerId: string;
  dueDate: string;
  status: CapaStatus;
  verificationNotes: string | null;
  createdBy: string;
  createdAt: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
}

export interface DrillDetail {
  drill: Drill;
  execution: DrillExecution | null;
  gaps: RollCallGap[];
  review: DrillReview | null;
  findings: DrillFinding[];
  correctiveActions: DrillCorrectiveAction[];
  unactionedFindingIds: string[];
  overdueActionIds: string[];
}

export interface BaselinePerson {
  personRef: string;
  displayName: string | null;
  source: string;
}

export interface CheckIn {
  personRef: string;
  checkedInAt: string;
}

export interface NotificationStatus {
  activationId: string;
  activationNumber: string;
  status: string;
  sentAt: string | null;
  targetCount: number;
  sentCount: number;
  deliveredCount: number;
  failedCount: number;
  acknowledgedCount: number;
}

export interface RollCallView {
  execution: DrillExecution;
  baseline: BaselinePerson[];
  checkIns: CheckIn[];
  outstanding: BaselinePerson[];
  unexpected: string[];
  notification: NotificationStatus | null;
}

export interface DrillTemplate {
  templateId: string;
  templateCode: string;
  title: string;
  channels: string[];
}

export interface FrequencyRequirement {
  id: string;
  siteCode: string;
  drillType: DrillType;
  intervalDays: number;
  warningDays: number;
  effectiveFrom: string;
  gapFlaggedForDueAt: string | null;
  metadata: RecordMetadata;
}

export interface ComplianceRow {
  requirement: FrequencyRequirement;
  lastCountedDrillAt: string | null;
  dueAt: string;
  standing: ComplianceStanding;
}

/** @property ageing open actions by days since raised: 0-30, 31-60, 61-90, over 90 */
export interface CapaCounts {
  open: number;
  overdue: number;
  verified: number;
  cancelled: number;
  ageing: number[];
}

export interface SiteDrillStats {
  siteCode: string;
  planned: number;
  executed: number;
  reviewed: number;
  cancelled: number;
  baselineTotal: number;
  checkedInTotal: number;
  gapsTotal: number;
  findingsOpen: number;
  findingsClosed: number;
}

export interface DrillDashboard {
  since: string;
  sites: SiteDrillStats[];
  correctiveActions: CapaCounts;
  compliance: ComplianceRow[];
}

export type DrillPage = PageResponse<Drill>;

export interface DrillSearchParams extends QueryParams {
  siteCode?: string;
  drillType?: DrillType;
  status?: DrillStatus;
  from?: string;
  to?: string;
  q?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// ---- requests ------------------------------------------------------------------------------------

export interface ExpectationInput {
  module: DrillModule;
  expectation: string;
  successCriterion: string;
}

export interface PlanRequest {
  drillType: DrillType;
  title: string;
  scenario?: string;
  expectedParticipants?: string;
  assemblyZone?: string;
  scheduledFor?: string;
  notificationTemplateId?: string;
  audienceGroupIds: string[];
  recipientZoneIds: string[];
  expectations: ExpectationInput[];
}

export interface Judgement {
  index: number;
  outcome: ExpectationOutcome;
  notes?: string;
}

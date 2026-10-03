/**
 * S175 vocabularies, transcribed from the service's enums (`DrillType`, `DrillStatus`, `DrillModule`,
 * `ExpectationOutcome`, `GapFollowUp`, `CapaStatus`, `ComplianceStanding`), in the service's own order.
 */

export type DrillType = 'FIRE' | 'SECURITY_LOCKDOWN' | 'MEDICAL' | 'COMBINED';
export type DrillStatus = 'PLANNED' | 'SCHEDULED' | 'POSTPONED' | 'IN_PROGRESS' | 'COMPLETED' | 'REVIEWED' | 'CLOSED' | 'CANCELLED';
export type DrillModule =
  | 'S160_VISITOR_MANAGEMENT'
  | 'S160A_ACCESS_CONTROL'
  | 'S162A_LIFE_SAFETY'
  | 'S166_FLEET'
  | 'S171_DISPATCH'
  | 'S174_MASS_NOTIFICATION'
  | 'OTHER';
export type ExpectationOutcome = 'MET' | 'PARTIALLY_MET' | 'NOT_MET';
export type GapFollowUp = 'ACCOUNTED_FOR' | 'NOT_ON_SITE' | 'UNACCOUNTED';
export type CapaStatus = 'OPEN' | 'IN_PROGRESS' | 'VERIFIED' | 'CANCELLED';
export type ComplianceStanding = 'COMPLIANT' | 'DUE_SOON' | 'COMPLIANCE_GAP';

export const drillTypes: DrillType[] = ['FIRE', 'SECURITY_LOCKDOWN', 'MEDICAL', 'COMBINED'];
export const drillStatuses: DrillStatus[] = ['PLANNED', 'SCHEDULED', 'POSTPONED', 'IN_PROGRESS', 'COMPLETED', 'REVIEWED', 'CLOSED', 'CANCELLED'];
export const drillModules: DrillModule[] = [
  'S160_VISITOR_MANAGEMENT',
  'S160A_ACCESS_CONTROL',
  'S162A_LIFE_SAFETY',
  'S166_FLEET',
  'S171_DISPATCH',
  'S174_MASS_NOTIFICATION',
  'OTHER',
];
export const expectationOutcomes: ExpectationOutcome[] = ['MET', 'PARTIALLY_MET', 'NOT_MET'];
export const gapFollowUps: GapFollowUp[] = ['ACCOUNTED_FOR', 'NOT_ON_SITE', 'UNACCOUNTED'];

export const drillTypeLabel: Record<DrillType, string> = {
  FIRE: 'Fire evacuation',
  SECURITY_LOCKDOWN: 'Security lockdown',
  MEDICAL: 'Medical emergency',
  COMBINED: 'Combined',
};

export const drillStatusLabel: Record<DrillStatus, string> = {
  PLANNED: 'Planned',
  SCHEDULED: 'Scheduled',
  POSTPONED: 'Postponed',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Awaiting review',
  REVIEWED: 'Reviewed',
  CLOSED: 'Closed',
  CANCELLED: 'Cancelled',
};

export const drillModuleLabel: Record<DrillModule, string> = {
  S160_VISITOR_MANAGEMENT: 'S160 Visitor management',
  S160A_ACCESS_CONTROL: 'S160a Access control',
  S162A_LIFE_SAFETY: 'S162a Fire & life safety',
  S166_FLEET: 'S166 Fleet',
  S171_DISPATCH: 'S171 Dispatch',
  S174_MASS_NOTIFICATION: 'S174 Mass notification',
  OTHER: 'Other',
};

export const outcomeLabel: Record<ExpectationOutcome, string> = { MET: 'Met', PARTIALLY_MET: 'Partially met', NOT_MET: 'Not met' };

export const gapFollowUpLabel: Record<GapFollowUp, string> = {
  ACCOUNTED_FOR: 'Accounted for',
  NOT_ON_SITE: 'Not actually on site',
  UNACCOUNTED: 'Never accounted for',
};

export const complianceLabel: Record<ComplianceStanding, string> = {
  COMPLIANT: 'Compliant',
  DUE_SOON: 'Due soon',
  COMPLIANCE_GAP: 'Compliance gap',
};

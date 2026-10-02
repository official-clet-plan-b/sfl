/**
 * S165 vocabularies, transcribed from the service's enums (`HazardType`, `ControlType`, `Likelihood`,
 * `Severity`, the shared `RiskAssessmentCurrency`), in the service's own order.
 */

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED';
export type Standing = 'NO_PUBLISHED_VERSION' | 'CURRENT' | 'AWAITING_INDEPENDENT_SIGN_OFF' | 'LAPSED';
export type CurrencyReason = 'NONE_LINKED' | 'SUPERSEDED' | 'NOT_PUBLISHED' | 'REVIEW_LAPSED' | 'NOT_INDEPENDENTLY_SIGNED_OFF';
export type StandingFilter = 'DRAFT_ONLY' | 'PUBLISHED' | 'LAPSED' | 'AWAITING_INDEPENDENT_SIGN_OFF';
export type ReviewFlagStatus = 'OPEN' | 'DEFERRED' | 'CLEARED';
export type Likelihood = 'RARE' | 'UNLIKELY' | 'POSSIBLE' | 'LIKELY' | 'ALMOST_CERTAIN';
export type Severity = 'NEGLIGIBLE' | 'MINOR' | 'MODERATE' | 'MAJOR' | 'CATASTROPHIC';
export type ControlType = 'ELIMINATION' | 'SUBSTITUTION' | 'ENGINEERING' | 'ADMINISTRATIVE' | 'PPE';
export type HazardType =
  | 'WORKING_AT_HEIGHT'
  | 'HOT_WORK_FIRE'
  | 'ELECTRICAL'
  | 'CONFINED_SPACE'
  | 'MANUAL_HANDLING'
  | 'SLIPS_TRIPS_FALLS'
  | 'CROWD_OCCUPANCY'
  | 'HAZARDOUS_SUBSTANCES'
  | 'MACHINERY_EQUIPMENT'
  | 'STRUCTURAL'
  | 'VEHICLE_TRAFFIC'
  | 'NOISE_VIBRATION'
  | 'BIOLOGICAL'
  | 'ENVIRONMENTAL'
  | 'SECURITY_VIOLENCE'
  | 'OTHER';

export const riskLevels: RiskLevel[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
export const likelihoods: Likelihood[] = ['RARE', 'UNLIKELY', 'POSSIBLE', 'LIKELY', 'ALMOST_CERTAIN'];
export const severities: Severity[] = ['NEGLIGIBLE', 'MINOR', 'MODERATE', 'MAJOR', 'CATASTROPHIC'];
export const controlTypes: ControlType[] = ['ELIMINATION', 'SUBSTITUTION', 'ENGINEERING', 'ADMINISTRATIVE', 'PPE'];
export const reviewFlagStatuses: ReviewFlagStatus[] = ['OPEN', 'DEFERRED', 'CLEARED'];
export const standingFilters: StandingFilter[] = ['PUBLISHED', 'DRAFT_ONLY', 'AWAITING_INDEPENDENT_SIGN_OFF', 'LAPSED'];
export const hazardTypes: HazardType[] = [
  'WORKING_AT_HEIGHT',
  'HOT_WORK_FIRE',
  'ELECTRICAL',
  'CONFINED_SPACE',
  'MANUAL_HANDLING',
  'SLIPS_TRIPS_FALLS',
  'CROWD_OCCUPANCY',
  'HAZARDOUS_SUBSTANCES',
  'MACHINERY_EQUIPMENT',
  'STRUCTURAL',
  'VEHICLE_TRAFFIC',
  'NOISE_VIBRATION',
  'BIOLOGICAL',
  'ENVIRONMENTAL',
  'SECURITY_VIOLENCE',
  'OTHER',
];

/** Plain-English labels where the constant reads badly - everything else uses the shared `humanise`. */
export const standingLabel: Record<Standing, string> = {
  NO_PUBLISHED_VERSION: 'Draft only',
  CURRENT: 'Current',
  AWAITING_INDEPENDENT_SIGN_OFF: 'Awaiting independent sign-off',
  LAPSED: 'Lapsed',
};

export const hazardTypeLabel: Record<HazardType, string> = {
  WORKING_AT_HEIGHT: 'Working at height',
  HOT_WORK_FIRE: 'Hot work / fire',
  ELECTRICAL: 'Electrical',
  CONFINED_SPACE: 'Confined space',
  MANUAL_HANDLING: 'Manual handling',
  SLIPS_TRIPS_FALLS: 'Slips, trips and falls',
  CROWD_OCCUPANCY: 'Crowd / occupancy',
  HAZARDOUS_SUBSTANCES: 'Hazardous substances',
  MACHINERY_EQUIPMENT: 'Machinery and equipment',
  STRUCTURAL: 'Structural',
  VEHICLE_TRAFFIC: 'Vehicle traffic',
  NOISE_VIBRATION: 'Noise and vibration',
  BIOLOGICAL: 'Biological',
  ENVIRONMENTAL: 'Environmental',
  SECURITY_VIOLENCE: 'Security / violence',
  OTHER: 'Other',
};

/** Why a version is not current, in the words a refusal would use. */
export const currencyReasonLabel: Record<CurrencyReason, string> = {
  NONE_LINKED: 'Nothing published',
  SUPERSEDED: 'Superseded by a later version',
  NOT_PUBLISHED: 'Not published',
  REVIEW_LAPSED: 'Review date passed without sign-off',
  NOT_INDEPENDENTLY_SIGNED_OFF: 'Needs sign-off by someone other than the author',
};

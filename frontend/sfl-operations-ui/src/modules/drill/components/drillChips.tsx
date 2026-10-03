import type { CapaStatus, ComplianceStanding, DrillStatus, ExpectationOutcome, GapFollowUp } from '../api/enums';
import { complianceLabel, drillStatusLabel, gapFollowUpLabel, outcomeLabel } from '../api/enums';
import StatusBadge, { type Tone } from 'modules/emergency/components/StatusBadge';

/**
 * S175's readings of its own statuses, stated locally (playbook §10). A completed drill is not done - it owes
 * its after-action review - so it reads as caution, not ready.
 */

const statusTone: Record<DrillStatus, Tone> = {
  PLANNED: 'neutral',
  SCHEDULED: 'accent',
  POSTPONED: 'caution',
  IN_PROGRESS: 'active',
  COMPLETED: 'caution',
  REVIEWED: 'ready',
  CLOSED: 'ready',
  CANCELLED: 'neutral',
};

const complianceTone: Record<ComplianceStanding, Tone> = { COMPLIANT: 'ready', DUE_SOON: 'caution', COMPLIANCE_GAP: 'blocked' };
const outcomeTone: Record<ExpectationOutcome, Tone> = { MET: 'ready', PARTIALLY_MET: 'caution', NOT_MET: 'blocked' };
const followUpTone: Record<GapFollowUp, Tone> = { ACCOUNTED_FOR: 'ready', NOT_ON_SITE: 'neutral', UNACCOUNTED: 'blocked' };
const capaTone: Record<CapaStatus, Tone> = { OPEN: 'caution', IN_PROGRESS: 'active', VERIFIED: 'ready', CANCELLED: 'neutral' };

export const DrillStatusChip = ({ status, size }: { status: DrillStatus; size?: 'sm' | 'md' }) => (
  <StatusBadge value={status} tone={statusTone[status]} label={drillStatusLabel[status]} size={size} />
);

export const ComplianceChip = ({ standing }: { standing: ComplianceStanding }) => (
  <StatusBadge value={standing} tone={complianceTone[standing]} label={complianceLabel[standing]} />
);

export const OutcomeChip = ({ outcome }: { outcome: ExpectationOutcome | null }) =>
  outcome ? <StatusBadge value={outcome} tone={outcomeTone[outcome]} label={outcomeLabel[outcome]} /> : <StatusBadge value="PENDING" tone="neutral" label="Not judged" />;

export const FollowUpChip = ({ followUp }: { followUp: GapFollowUp | null }) =>
  followUp ? <StatusBadge value={followUp} tone={followUpTone[followUp]} label={gapFollowUpLabel[followUp]} /> : <StatusBadge value="OPEN" tone="caution" label="Needs follow-up" />;

export const CapaChip = ({ status, overdue }: { status: CapaStatus; overdue?: boolean }) =>
  overdue ? <StatusBadge value="OVERDUE" tone="blocked" label="Overdue" /> : <StatusBadge value={status} tone={capaTone[status]} />;

/** The banner-grade marker every drill screen carries, so nobody reads a drill record as a real event. */
export const DrillMarker = () => <StatusBadge value="DRILL" tone="accent" label="Drill - exercise only" />;

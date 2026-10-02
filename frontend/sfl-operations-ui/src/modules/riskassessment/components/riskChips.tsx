import type { ReviewFlagStatus, RiskLevel, Standing, VersionStatus } from '../api/enums';
import { standingLabel } from '../api/enums';
import StatusBadge, { type Tone } from 'modules/emergency/components/StatusBadge';

/**
 * S165's readings of its own statuses, stated locally (playbook §10): the shared table reads `OPEN` as
 * active and `DRAFT` as neutral, which is right for a work order and wrong for a review flag, where open
 * means somebody owes a review.
 */

const standingTone: Record<Standing, Tone> = {
  CURRENT: 'ready',
  AWAITING_INDEPENDENT_SIGN_OFF: 'caution',
  LAPSED: 'blocked',
  NO_PUBLISHED_VERSION: 'neutral',
};

const levelTone: Record<RiskLevel, Tone> = { LOW: 'neutral', MEDIUM: 'active', HIGH: 'caution', CRITICAL: 'blocked' };

const flagTone: Record<ReviewFlagStatus, Tone> = { OPEN: 'caution', DEFERRED: 'neutral', CLEARED: 'ready' };

const versionTone: Record<VersionStatus, Tone> = { DRAFT: 'active', PUBLISHED: 'ready', SUPERSEDED: 'neutral' };

export const StandingChip = ({ standing, size }: { standing: Standing; size?: 'sm' | 'md' }) => (
  <StatusBadge value={standing} tone={standingTone[standing]} label={standingLabel[standing]} size={size} />
);

export const RiskLevelChip = ({ level }: { level: RiskLevel | null }) =>
  level ? <StatusBadge value={level} tone={levelTone[level]} /> : <span className="text-gray-500">-</span>;

export const ReviewFlagChip = ({ status }: { status: ReviewFlagStatus }) => (
  <StatusBadge value={status} tone={flagTone[status]} />
);

export const VersionStatusBadge = ({ status }: { status: VersionStatus }) => (
  <StatusBadge value={status} tone={versionTone[status]} label={status === 'SUPERSEDED' ? 'Superseded - not current' : undefined} />
);

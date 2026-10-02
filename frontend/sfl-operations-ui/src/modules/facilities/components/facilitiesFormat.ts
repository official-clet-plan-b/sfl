import type {
  AssetOperationalStatus,
  BlockerSeverity,
  FacilityFaultStatus,
  FaultPriority,
  LocationReadinessStatus,
  WorkOrderStatus,
} from '../api/enums';

/**
 * The meaning a status badge carries, independent of the colour a badge renders it in.
 *
 * `StatusBadge` maps each tone to a library `Badge` variant, so a value is judged here - once, in
 * the domain's own vocabulary - and styled there.
 */
export type Tone = 'ready' | 'caution' | 'blocked' | 'neutral' | 'active' | 'accent';

/**
 * How S152 values are shown.
 *
 * The tones matter more than they look. Readiness values are given their tone explicitly here rather
 * than left to a generic word lookup: `DEGRADED` is a fleet activation mode and `CRITICAL` is not a
 * readiness word at all, so a lookup written for a different vocabulary would colour them wrongly. A
 * blocked examination hall rendered in a neutral grey would be a genuinely dangerous piece of
 * styling.
 */

/** Readiness status → chip tone. */
export const readinessTone = (status: LocationReadinessStatus): Tone => {
  switch (status) {
    case 'READY':
      return 'ready';
    case 'DEGRADED':
      return 'caution';
    case 'BLOCKED':
      return 'blocked';
    default:
      // UNKNOWN is neutral, not good. An unassessed hall is not a passed one.
      return 'neutral';
  }
};

/** Blocker severity → chip tone. Advisory is neutral: it is noted, it changes nothing. */
export const severityTone = (severity: BlockerSeverity): Tone => {
  switch (severity) {
    case 'CRITICAL':
      return 'blocked';
    case 'MAJOR':
      return 'caution';
    case 'MINOR':
      return 'caution';
    default:
      return 'neutral';
  }
};

/** Asset operational status → chip tone. */
export const assetStatusTone = (status: AssetOperationalStatus): Tone => {
  switch (status) {
    case 'OPERATIONAL':
      return 'ready';
    case 'DEGRADED':
    case 'UNDER_MAINTENANCE':
      return 'caution';
    case 'OUT_OF_SERVICE':
      return 'blocked';
    default:
      return 'neutral';
  }
};

/**
 * How a headline figure is judged, as the colour of the icon beside its caption.
 *
 * The value itself stays in the card's own type colour. A KPI row where every number is a different
 * colour is the loudest thing a dashboard can do, and it makes the two figures that need attention
 * indistinguishable from the six that do not - so the caption is where a measure says it is in
 * trouble, in words as well as colour.
 */
export type FigureTone = 'good' | 'caution' | 'critical' | 'neutral';

export const figureToneClass: Record<FigureTone, string> = {
  good: 'text-success-text',
  caution: 'text-warning-text',
  critical: 'text-error-text',
  neutral: 'text-muted-foreground',
};

/** A status tone, as the figure tone that reads the same way. */
export const figureTone = (tone: Tone): FigureTone =>
  tone === 'ready' ? 'good' : tone === 'caution' ? 'caution' : tone === 'blocked' ? 'critical' : 'neutral';

/** A readiness score's tone, on the same thresholds the dashboard uses for its site score. */
export const scoreTone = (score: number): Tone => {
  if (score >= 90) return 'ready';
  if (score >= 60) return 'caution';
  return 'blocked';
};

/**
 * The acronyms in these enumerations, which sentence case would otherwise mangle.
 *
 * `HVAC` became "Hvac" and `CCTV_CAMERA` became "Cctv camera" in every dropdown, register cell and
 * chip that renders an enum - which is most of them. An acronym is not a word, and lower-casing one
 * reads as a typo rather than as a style. Kept as an explicit list rather than a "three or more
 * capitals" rule, because that rule would also catch a legitimately capitalised word and there is no
 * way to tell them apart from the string alone.
 */
const ACRONYMS = new Set([
  'HVAC',
  'UPS',
  'IT',
  'CCTV',
  'RFID',
  'IOT',
  'SLA',
  'AV',
]);

/** `EXAMINATION_HALL` → `Examination hall`; `CCTV_CAMERA` → `CCTV camera`. */
export const humaniseCode = (value: string | null | undefined): string => {
  if (!value) return '-';
  const words = value.split('_').map((word, index) => {
    if (ACRONYMS.has(word)) {
      return word;
    }
    const lower = word.toLowerCase();
    return index === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
  });
  return words.join(' ');
};

/**
 * How long ago something happened, in the coarse terms an operator reads.
 *
 * Deliberately imprecise: "3 days ago" is what matters about a stale assessment, not the minute.
 */
export const relativeTime = (iso: string | null | undefined, now = new Date()): string => {
  if (!iso) return 'never';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '-';

  const seconds = Math.round((now.getTime() - then) / 1000);
  if (seconds < 60) return 'just now';

  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;

  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? '' : 's'} ago`;

  const days = Math.round(hours / 24);
  if (days < 31) return `${days} day${days === 1 ? '' : 's'} ago`;

  const months = Math.round(days / 30);
  return `${months} month${months === 1 ? '' : 's'} ago`;
};

/** An ISO date or timestamp as a readable date. */
export const formatDate = (iso: string | null | undefined): string => {
  if (!iso) return '-';
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? '-'
    : date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
};

/** An ISO timestamp as a readable date and time. */
export const formatDateTime = (iso: string | null | undefined): string => {
  if (!iso) return '-';
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? '-'
    : date.toLocaleString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
};

/** `null` and `undefined` render as an em dash rather than as an empty cell. */
export const orDash = (value: string | number | null | undefined): string =>
  value === null || value === undefined || value === '' ? '-' : String(value);

// ---- S153 -----------------------------------------------------------------------------------

/** How urgent a fault is. Critical is the one that stops a space being used. */
export const priorityTone = (priority: FaultPriority): Tone => {
  switch (priority) {
    case 'CRITICAL':
      return 'blocked';
    case 'HIGH':
      return 'caution';
    case 'MEDIUM':
      return 'active';
    default:
      return 'neutral';
  }
};

/**
 * The tone a fault status carries.
 *
 * `RESOLVED` is the only success. The three dismissals are neutral rather than negative - a fault
 * correctly rejected is not a failure, and colouring it red would train people to ignore red.
 */
export const faultStatusTone = (status: FacilityFaultStatus): Tone => {
  switch (status) {
    case 'RESOLVED':
      return 'ready';
    case 'WORK_ORDER_CREATED':
      return 'active';
    case 'REPORTED':
    case 'TRIAGED':
      return 'caution';
    default:
      return 'neutral';
  }
};

/** The tone a work-order status carries. Held is warned about; cancelled is merely over. */
export const workOrderStatusTone = (status: WorkOrderStatus): Tone => {
  switch (status) {
    case 'CLOSED':
      return 'ready';
    case 'COMPLETED':
      return 'accent';
    case 'ON_HOLD':
      return 'caution';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'active';
  }
};

/**
 * How an SLA reads at a glance.
 *
 * Overdue is an error whatever the escalation level, because level zero overdue is still a breached
 * deadline. The level is shown separately rather than folded into the tone - "overdue" and "nobody
 * has picked it up three times" are different facts and a single colour cannot carry both.
 */
export const slaTone = (overdue: boolean): Tone => (overdue ? 'blocked' : 'ready');

/**
 * How late something is, in words.
 *
 * Takes `minutesOverdue` from the service rather than comparing the deadline to the browser clock: a
 * workstation whose clock is ten minutes fast would otherwise disagree with the escalation sweep
 * about what is late, and the sweep is the one that notifies people.
 */
export const overdueBy = (minutes: number | null | undefined): string => {
  if (minutes === null || minutes === undefined || minutes <= 0) {
    return '';
  }
  if (minutes < 60) {
    return `${minutes} min overdue`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h overdue`;
  }
  const days = Math.floor(hours / 24);
  return `${days}d overdue`;
};

/** Accumulated hold time, shown beside the deadline and never subtracted from it. */
export const heldFor = (seconds: number): string => {
  if (!seconds) {
    return '';
  }
  const hours = Math.floor(seconds / 3600);
  if (hours < 1) {
    return `${Math.max(1, Math.round(seconds / 60))} min on hold`;
  }
  if (hours < 24) {
    return `${hours}h on hold`;
  }
  return `${Math.floor(hours / 24)}d on hold`;
};

/**
 * The evidence shortfall, as the sentence a user needs.
 *
 * Empty when the requirement is met, so a caller can render it or not without asking twice.
 */
export const evidenceGap = (attached: number, required: number): string =>
  attached >= required ? '' : `${attached} of ${required} required`;

/** An escalation level, or empty at zero. Level is not a status; it is how many times it has been raised. */
export const escalationLabel = (level: number): string =>
  level > 0 ? `Escalated · level ${level}` : '';

/**
 * How a floor names itself in a list.
 *
 * `B1 · Basement`, `GF · Ground`, `L2 · Second`. The code leads because it is what appears on signage
 * and on a work order, and the level follows because it is what the list is sorted by - a reader
 * scanning for "two floors up" is looking for the number, and a reader who has been told to go to GF
 * is looking for the code.
 *
 * A null level is a **mezzanine** and says so rather than showing nothing. The column is nullable
 * precisely because a mezzanine sits between two floors and has no honest number, and a blank cell
 * reads as missing data instead of as the answer.
 */
export const floorLabel = (levelNumber: number | null, floorCode: string): string => {
  if (levelNumber === null) {
    return `${floorCode} · no level`;
  }
  if (levelNumber === 0) {
    return `${floorCode} · ground`;
  }
  return levelNumber < 0
    ? `${floorCode} · basement ${Math.abs(levelNumber)}`
    : `${floorCode} · level ${levelNumber}`;
};

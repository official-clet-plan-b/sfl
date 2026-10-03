import { apiClient } from 'shared/api/client';
import type { SflPermission } from './permissions';
import { ServingPlatform, servingPlatform, servingPlatformName } from 'shared/platform';

/** Long enough for a local service, short enough that a dead one does not hold up the first paint. */
const TIMEOUT_MS = 2500;

let granted: Set<string> | null = null;
let failure: string | null = null;

interface Source {
  path: string;
  service?: 'safetySecurity' | 'facilities';
}

/**
 * One source per platform. An origin asks for its own and nothing else.
 *
 * <p>This used to be a fixed list of three, asked by every origin. That is what made a dashboard
 * served by facilities call fleet and safety-security for permissions it had no screens for, and it
 * is half of why starting one service produced "Could not reach the Fleet & Logistics service".
 */
const SOURCE_FOR: Record<'IFIMP' | 'SSEMP' | 'FTLMP', Source> = {
  // FTLMP - fleet, fuel, dispatch and asset visibility. Four matrices, one deployable, one answer.
  FTLMP: { path: '/api/v1/fleet/actor/permissions' },
  // SSEMP - one union of every SSEMP module's matrix: S174, S160-S163, and Phase 2's S164, S165 and S175.
  SSEMP: { path: '/api/v1/emergency/actor/permissions', service: 'safetySecurity' },
  // IFIMP - S152, S153 and S159, one matrix in `shared` answering for all three.
  IFIMP: { path: '/api/v1/facilities/actor/permissions', service: 'facilities' },
};

const sourcesFor = (platform: ServingPlatform): Source[] => {
  if (platform === 'ALL') {
    // The portal is the only origin that aggregates. It is also the only one that can be partly
    // answered, which is why the caller shows which service did not reply.
    return Object.values(SOURCE_FOR);
  }
  const source = SOURCE_FOR[platform as 'IFIMP' | 'SSEMP' | 'FTLMP'];
  return source ? [source] : [];
};

const fetchOne = async (source: Source): Promise<string[] | null> => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const result = await apiClient.get<string[]>(
      source.path,
      undefined,
      controller.signal,
      source.service,
    );
    return Array.isArray(result) ? result : [];
  } catch {
    // null distinguishes "did not answer" from "answered with nothing", which the old code could
    // not tell apart - and that ambiguity is exactly what the fail-open was built on.
    return null;
  } finally {
    clearTimeout(timer);
  }
};

/**
 * Resolves the actor's permissions. Never throws, never rejects.
 *
 * <h2>This fails closed, and the previous version failing open is why nothing worked</h2>
 *
 * <p>`permits()` used to return `true` for everything when no source answered. The reasoning was that
 * a dashboard hiding screens because a request failed reads as a broken build. What it produced was
 * worse and much harder to see: with no service running, every account saw every screen, including
 * a driver looking at the whole fleet office. With one service running, everything belonging to the
 * others silently disappeared. Same cause, opposite symptoms, and neither says "a service is down".
 *
 * <p>So: no answer means no permissions, and {@link permissionFailure} carries a sentence naming the
 * service so the operator is told rather than left to infer. An empty sidebar with an explanation is
 * a smaller failure than a full one that lies.
 */
export const loadActorPermissions = async (): Promise<void> => {
  const platform = servingPlatform();
  const sources = sourcesFor(platform);
  if (sources.length === 0) {
    granted = new Set();
    failure = `Could not determine which platform ${servingPlatformName()} serves, so no screens can be offered.`;
    return;
  }

  const results = await Promise.all(sources.map(fetchOne));
  const answered = results.filter((r): r is string[] => r !== null);
  granted = new Set(answered.flat());
  failure =
    answered.length === sources.length
      ? null
      : `${servingPlatformName()} did not answer for your permissions, so nothing is being offered. Check that the service is running.`;
};

/**
 * Whether a control may be offered.
 *
 * Takes `SflPermission` rather than `string` deliberately: a mistyped permission returns false and
 * hides the control **permanently and silently**, which presents as a missing feature rather than as
 * an error. The union turns that into a compile error.
 */
export const permits = (permission?: SflPermission): boolean => {
  if (!permission) {
    return true;
  }
  if (granted === null) {
    // Before the load resolves nothing is known, and nothing known means nothing offered.
    return false;
  }
  return granted.has(permission);
};

/** The sentence to show the operator when permissions could not be loaded, or `null`. */
export const permissionFailure = (): string | null => failure;

/** For the account panel, so the actor can see what the dashboard was told. */
export const resolvedPermissionCount = (): number | null => (granted === null ? null : granted.size);

/**
 * The permissions whose holder's job *is* reading.
 *
 * Capability gating asks "can you do something here", and for most roles that is the right question.
 * For an auditor it is the wrong one: reading and proving is the whole role, and a sidebar that
 * offered them nothing because they change nothing would be a worse answer than the read-gated
 * version it replaced. Holding any of these is itself a capability.
 */
const REVIEWER_PERMISSIONS: SflPermission[] = [
  'AUDIT_READ',
  'FACILITIES_AUDIT_INTEGRITY_CHECK',
  'FACILITIES_EVIDENCE_EXPORT',
  'FLEET_AUDIT_INTEGRITY_CHECK',
  'FLEET_EVIDENCE_EXPORT_APPROVE',
  'FLEET_EVIDENCE_EXPORT_REQUEST',
  'FLEET_REPORT_EXPORT',
  'FUEL_REPORT_EXPORT',
  'DISPATCH_REPORT_EXPORT',
  'EMERGENCY_EVIDENCE_EXPORT',
  'EMERGENCY_REPORT_EXPORT',
  'VISITOR_REPORT_READ',
  'INCIDENT_REPORT_EXPORT',
];

/** Whether the actor holds at least one of these. */
export const permitsAny = (permissions: SflPermission | SflPermission[]): boolean => {
  const list = Array.isArray(permissions) ? permissions : [permissions];
  return list.some((permission) => permits(permission));
};

/** Whether reading is this actor's job rather than a lesser version of somebody else's. */
export const actorIsReviewer = (): boolean => permitsAny(REVIEWER_PERMISSIONS);
